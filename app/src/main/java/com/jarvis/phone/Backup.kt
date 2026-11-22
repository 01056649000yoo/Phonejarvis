package com.jarvis.phone

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 💾 백업·복원: 앱을 지웠다 다시 깔아도 내 설정이 그대로.
 *
 * - 넣는 것: 학생·학부모 명부, 학부모 방, 앱·방 끄기, 키워드, 광고 거르기, 요약 시간·쉬는 날, 수업·퇴근 시간표
 * - 넣지 않는 것: 메시지 내용, 학생별 기록, AI 모델 (개인정보·용량)
 * - 자동 백업: 하루 한 번 다운로드/쌤톡스/쌤톡스_백업.json ('모든 파일 접근' 권한이 있을 때). 앱을 지워도 남음
 * - 수동: 원하는 곳으로 내보내기 / 파일에서 불러오기 (권한 없이도 됨)
 * 인터넷 권한이 없어서 백업 파일도 폰 밖으로 나가지 않음.
 */
object Backup {
    private const val FORMAT = "ssamtox-backup"

    /** 백업할 설정 묶음과, 그 안에서 넣지 않을 값(그때그때 바뀌는 상태) */
    private val FILES = listOf("apps", "briefing", "quiet", "app", "storage", "people")
    private val SKIP = mapOf(
        "apps" to setOf("handled", "ad_day", "ad_count"),
        "quiet" to setOf("last", "since", "we_set_dnd", "zen_rule", "zen_rule_v"),   // 방해 금지 모드 번호는 폰마다 다름
        "storage" to setOf("last_clean", "last_vacuum"),
    )
    /** "app" 묶음은 이것만 (설치 안내 진행·모델 정보는 기기마다 다름) */
    private val APP_ONLY = setOf("apps_setup_done")

    private fun meta(ctx: Context) = ctx.getSharedPreferences("backup_meta", Context.MODE_PRIVATE)
    fun lastBackupTime(ctx: Context) = meta(ctx).getLong("last", 0L)


    // ── 만들기 ──
    fun toJson(ctx: Context): String {
        val prefs = JSONObject()
        FILES.forEach { name ->
            val obj = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (k in SKIP[name].orEmpty()) return@forEach
                if (name == "app" && k !in APP_ONLY) return@forEach
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                }
                if (e != null) obj.put(k, e)
            }
            prefs.put(name, obj)
        }
        return JSONObject()
            .put("format", FORMAT).put("version", 1)
            .put("created", System.currentTimeMillis())
            .put("summary", summary(ctx))
            .put("prefs", prefs)
            .toString(2)
    }

    /** "학생 25명 · 학부모 연결 12 · 키워드 6" 같은 한 줄 */
    fun summary(ctx: Context): String = listOfNotNull(
        Students.names(ctx).size.takeIf { it > 0 }?.let { "학생 ${it}명" },
        ParentBook.links(ctx).size.takeIf { it > 0 }?.let { "학부모 연결 $it" },
        AppFilter.keywords(ctx).size.takeIf { it > 0 }?.let { "키워드 $it" },
        AppFilter.excluded(ctx).size.takeIf { it > 0 }?.let { "뺀 앱 $it" },
        SummarySchedule.config(ctx).times.size.takeIf { it > 0 }?.let { "요약 시각 ${it}개" },
    ).joinToString(" · ").ifEmpty { "기본 설정" }

    // ── 저장 ──
    /**
     * 다운로드/쌤톡스 폴더에 저장 — 안드로이드 기본 저장 통로(MediaStore)로, 권한 없이(2026-11-22 Play 스토어 준비: '모든 파일 접근' 뺌).
     * 이 앱이 만든 파일만 다시 열 수 있으므로 같은 파일을 덮어씀. 다시 설치하면 새 파일이 생기고(이름 뒤에 (1)), 불러오기는 파일 고르기로.
     */
    fun saveToDownloads(ctx: Context): Boolean {
        if (!BackupCrypto.hasPin(ctx)) return false   // 🔒 비밀번호를 정해야 백업(암호화)
        return runCatching {
            val text = fileText(ctx)
            val cr = ctx.contentResolver
            val uri = ownUri(ctx) ?: cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/쌤톡스/")
            })!!
            cr.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            meta(ctx).edit().putString("ms_uri", uri.toString()).putLong("last", System.currentTimeMillis()).apply()
        }.isSuccess
    }

    private const val NAME = "쌤톡스_백업.json"

    /** 이 설치에서 만든 백업 파일(MediaStore). 지웠거나 없으면 null */
    private fun ownUri(ctx: Context): Uri? = meta(ctx).getString("ms_uri", null)?.let(Uri::parse)?.takeIf { u ->
        runCatching { ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true }.getOrDefault(false)
    }

    /** 하루 한 번 자동 백업 (앱 열 때·자동 브리핑 끝날 때) */
    fun autoBackup(ctx: Context) {
        if (System.currentTimeMillis() - lastBackupTime(ctx) < 20 * 3600 * 1000L) return
        saveToDownloads(ctx)
    }

    /** 선생님이 고른 위치로 내보내기 (권한 필요 없음) */
    fun exportTo(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(fileText(ctx).toByteArray()) }
        meta(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
    }.isSuccess

    // ── 찾기·불러오기 ──
    /** 이 설치에서 다운로드/쌤톡스에 저장한 백업이 있으면 (저장 시각, 요약). 없으면 null(다시 설치한 뒤엔 파일 고르기로) */
    fun found(ctx: Context): Pair<Long, String>? {
        val u = ownUri(ctx) ?: return null
        return runCatching {
            val j = JSONObject(ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            if (j.optString("format") != FORMAT) null else j.optLong("created") to j.optString("summary")
        }.getOrNull()
    }

    fun restoreFromDownloads(ctx: Context, pin: String? = null): Result<String> = runCatching {
        restoreFrom(ctx, ownUri(ctx) ?: error("백업 파일이 없어요."), pin).getOrThrow()
    }

    fun restoreFrom(ctx: Context, uri: Uri, pin: String? = null): Result<String> = runCatching {
        restore(ctx, readSmall(ctx, uri), pin)
    }

    /** 받은 파일 읽기: 백업은 수십 KB — 2MB 넘는 파일은 열지 않음(다른 앱이 보낸 큰 파일로 멈추지 않게) */
    fun readSmall(ctx: Context, uri: Uri): String {
        ctx.contentResolver.openInputStream(uri)!!.use { inp ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
            while (true) {
                val n = inp.read(buf); if (n < 0) break
                total += n; require(total <= MAX_BYTES) { "백업 파일이 너무 커요. 쌤톡스 백업 파일이 맞는지 확인해 주세요." }
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
    private const val MAX_BYTES = 2_000_000

    /** 백업 내용을 설정에 덮어쓰고 알람을 다시 예약. 돌려주는 값은 요약 한 줄. */
    /** 암호화된 백업인데 비밀번호를 아직 안 받았을 때 */
    class NeedPin : Exception("백업 비밀번호를 입력해 주세요.")

    /** 파일에 쓸 내용: 백업 비밀번호로 암호화 (비밀번호가 없으면 만들지 않음) */
    fun fileText(ctx: Context): String {
        val pin = BackupCrypto.pin(ctx)
        require(!pin.isNullOrEmpty()) { "백업 비밀번호를 먼저 정해 주세요." }
        return BackupCrypto.encrypt(toJson(ctx), pin)
    }

    private fun restore(ctx: Context, text: String, pin: String?): String {
        val plain = if (BackupCrypto.isEncrypted(text)) BackupCrypto.decrypt(text, pin ?: throw NeedPin()) else text
        // 새 폰에서 처음 불러올 때 쓴 비밀번호를 이 폰의 백업 비밀번호로도 씀
        if (pin != null && !BackupCrypto.hasPin(ctx)) BackupCrypto.setPin(ctx, pin)
        val j = JSONObject(plain)
        require(j.optString("format") == FORMAT) { "쌤톡스 백업 파일이 아니에요." }
        val prefs = j.getJSONObject("prefs")
        prefs.keys().forEach { name ->
            if (name !in FILES) return@forEach
            val obj = prefs.getJSONObject(name)
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            obj.keys().forEach { k ->
                val e = obj.getJSONObject(k)
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "set" -> e.getJSONArray("v").let { a -> ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            ed.apply()
        }
        // 브리핑·수업 알람 다시 예약
        BriefingScheduler.scheduleAll(ctx)
        QuietMode.tick(ctx)
        return j.optString("summary").ifEmpty { summary(ctx) }
    }
}

/** 📤 다른 폰으로 보내기 / 받은 백업 파일 열기 */
object BackupShare {
    /** 백업 파일을 만들어 공유 창(카톡 나에게·드라이브·메일 등)으로 보내는 Intent */
    fun shareIntent(ctx: Context): android.content.Intent {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "쌤톡스_백업.json").apply { writeText(Backup.fileText(ctx)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "쌤톡스 설정 백업")
            .putExtra(android.content.Intent.EXTRA_TEXT, "쌤톡스 설정 백업 파일이에요. 새 폰에서 이 파일을 열고 '쌤톡스'를 고르면 설정을 불러와요.")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return android.content.Intent.createChooser(send, "다른 폰으로 보내기")
    }

    /** 받은 파일이 쌤톡스 백업인지 보고 (저장 시각, 요약)을 돌려줌 */
    fun peek(ctx: Context, uri: Uri): Result<Pair<Long, String>> = runCatching {
        val j = JSONObject(Backup.readSmall(ctx, uri))
        require(j.optString("format") == "ssamtox-backup") { "쌤톡스 백업 파일이 아니에요." }
        j.optLong("created") to j.optString("summary")
    }

    /** 다른 앱에서 연 파일 Intent에서 uri 꺼내기 (열기·공유받기) */
    fun uriFrom(intent: android.content.Intent?): Uri? = when (intent?.action) {
        android.content.Intent.ACTION_VIEW -> intent.data
        android.content.Intent.ACTION_SEND ->
            if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
        else -> null
    }
}

/**
 * 🔒 백업 암호화: 선생님이 정한 백업 비밀번호(PIN)로 AES-256-GCM 암호화.
 * 비밀번호는 이 폰 안에만 저장(백업 파일에는 안 들어감). 새 폰에서 불러올 때 한 번 입력.
 * 파일 겉에는 저장 시각과 요약(학생 수 등 숫자)만 보임 — 이름·번호는 암호 안에.
 */
object BackupCrypto {
    /** 새로 만드는 백업의 반복 횟수(2026-11-22 120,000 → 600,000). 파일에 적어 두어 옛 백업도 그대로 열림 */
    private const val ITER = 600_000
    private const val OLD_ITER = 120_000
    /** 새로 정하는 비밀번호는 6자 이상(글자·숫자 섞어도 됨) — 숫자 4자리는 백업 파일만 있으면 금방 풀림 */
    const val MIN_LEN = 6
    private fun meta(ctx: Context) = ctx.getSharedPreferences("backup_meta", Context.MODE_PRIVATE)

    fun pin(ctx: Context): String? = meta(ctx).getString("pin", null)
    fun hasPin(ctx: Context) = !pin(ctx).isNullOrEmpty()
    fun setPin(ctx: Context, pin: String) = meta(ctx).edit().putString("pin", pin).apply()

    private fun key(pin: String, salt: ByteArray, iter: Int = ITER): javax.crypto.spec.SecretKeySpec {
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iter, 256)).encoded
        return javax.crypto.spec.SecretKeySpec(k, "AES")
    }
    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun unb64(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    /** 평문 백업 JSON → 암호화된 백업 JSON */
    fun encrypt(plain: String, pin: String): String {
        val p = JSONObject(plain)
        val rnd = java.security.SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key(pin, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", "ssamtox-backup").put("version", 2).put("enc", true)
            .put("created", p.optLong("created")).put("summary", p.optString("summary"))
            .put("salt", b64(salt)).put("iv", b64(iv)).put("iter", ITER).put("data", b64(data))
            .toString(2)
    }

    fun isEncrypted(text: String) = runCatching { JSONObject(text).optBoolean("enc") }.getOrDefault(false)

    /** 암호화된 백업 → 평문 JSON. 비밀번호가 틀리면 예외 */
    fun decrypt(text: String, pin: String): String {
        val j = JSONObject(text)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iter = j.optInt("iter", OLD_ITER).coerceIn(OLD_ITER, 5_000_000)
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(pin, unb64(j.getString("salt")), iter),
            javax.crypto.spec.GCMParameterSpec(128, unb64(j.getString("iv"))))
        return try { String(c.doFinal(unb64(j.getString("data"))), Charsets.UTF_8) }
        catch (e: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("백업 비밀번호가 달라요.") }
    }
}
