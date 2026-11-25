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
 * - 넣는 것: 학생·학부모 명부, 학부모 방, 앱·방 끄기, 키워드, 광고 거르기, 요약 시간·쉬는 날, 수업·퇴근 시간표, 직접 넣은 할 일
 * - 넣지 않는 것: 메시지 내용, 학생별 기록, AI 모델 (개인정보·용량)
 * - 자동 백업: 하루 한 번(매일 새벽 알람 + 앱 열 때·요약 끝날 때) 다운로드/쌤톡스/쌤톡스_백업.json, 비밀번호로 암호화. 앱을 지워도 남음
 * - 수동: 원하는 곳으로 내보내기 / 파일에서 불러오기 (권한 없이도 됨)
 * 인터넷 권한이 없어서 백업 파일도 폰 밖으로 나가지 않음.
 */
object Backup {
    private const val FORMAT = "ssamtox-backup"

    /** 백업할 설정 묶음과, 그 안에서 넣지 않을 값(그때그때 바뀌는 상태) */
    // my_names(🙋 나를 부르는 말)·room_tier(방마다 정한 거리)·reply_log(내가 말한 방 — 방 이름과 시각만, 내용 X) — 폰을 바꿔도 '나와의 거리'가 이어지게(2026-11-25)
    private val FILES = listOf("apps", "briefing", "quiet", "app", "storage", "people", "my_names", "room_tier", "reply_log")
    private val SKIP = mapOf(
        "apps" to setOf("handled", "ad_day", "ad_count"),
        "quiet" to setOf("last", "since", "we_set_dnd", "zen_rule", "zen_rule_v"),   // 방해 금지 모드 번호는 폰마다 다름
        "storage" to setOf("last_clean", "last_vacuum"),
    )
    /** 쓰면서 저절로 쌓이는 묶음: 불러올 때 지금 값을 지우지 않고 합침(시각은 더 최근 것) */
    private val GROWING = setOf("reply_log")
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
                if (!isBackedUp(name, k)) return@forEach
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
            .put("tasks", tasksJson(ctx))
            .toString(2)
    }

    /** 📌 직접 넣은 할 일 — 설정 묶음이 아니라 DB 에 있어 예전 백업에서 빠져 있었다(다시 설치하면 할 일이 모두 사라짐) */
    private fun tasksJson(ctx: Context): JSONArray = JSONArray().apply {
        runCatching { NotificationDb.get(ctx).manualTasks() }.getOrDefault(emptyList()).forEach { t ->
            put(JSONObject().put("title", t.title).put("room", t.room).put("due", t.due).put("hasTime", t.hasTime)
                .put("sourceTime", t.sourceTime).put("done", t.done).put("dedupe", t.dedupe))
        }
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
    @Synchronized   // 비밀번호를 정한 직후의 백업과 '지금 백업'·자동 백업이 겹치면 파일이 두 개 생김
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
            // auto_last: 다운로드 폴더에 저장한 시각 — '파일로 내보내기'(last 만 고침)가 자동 백업을 하루 미루지 않게 따로 둠
            val now = System.currentTimeMillis()
            meta(ctx).edit().putString("ms_uri", uri.toString()).putLong("last", now).putLong("auto_last", now).apply()
        }.isSuccess
    }

    private const val NAME = "쌤톡스_백업.json"

    /** 이 설치에서 만든 백업 파일(MediaStore). 지웠거나 없으면 null */
    private fun ownUri(ctx: Context): Uri? = meta(ctx).getString("ms_uri", null)?.let(Uri::parse)?.takeIf { u ->
        runCatching { ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true }.getOrDefault(false)
    }

    /**
     * 하루 한 번 자동 백업 (매일 새벽 알람 · 앱 열 때 · 요약이 끝날 때 — [minGapHours] 안에 했으면 건너뜀). 비밀번호가 없으면 안 함.
     * 새벽 알람은 간격을 짧게(6시간) 부름 — 20시간이면 전날 아침에 한 백업 때문에 새벽 알람이 건너뛰어, 앱을 안 여는 날에는 이틀에 한 번만 됐다.
     */
    fun autoBackup(ctx: Context, minGapHours: Int = 20) {
        if (System.currentTimeMillis() - meta(ctx).getLong("auto_last", lastBackupTime(ctx)) < minGapHours * 3600 * 1000L) return
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
        val j = JSONObject(plain)
        require(j.optString("format") == FORMAT) { "쌤톡스 백업 파일이 아니에요." }
        // ① 먼저 모두 읽어 봄 — 예전엔 읽으면서 바로 써서, 중간에 깨진 값이 있으면 앞의 묶음만 바뀐 채 '불러오지 못했어요'가 됐다
        val prefs = j.getJSONObject("prefs")
        val plan = prefs.keys().asSequence().filter { it in FILES }.associateWith { name -> readEntries(prefs.getJSONObject(name)) }
        // 백업 파일에 이름이 있는 값(이 판이 모르는 형식 포함) — 모르는 형식이라 못 읽은 값을 '백업에 없는 값'으로 보고 지우지 않게
        val named = plan.keys.associateWith { name -> prefs.getJSONObject(name).keys().asSequence().toSet() }
        // 새 폰에서 처음 불러올 때 쓴 비밀번호를 이 폰의 백업 비밀번호로도 씀
        if (pin != null && !BackupCrypto.hasPin(ctx)) BackupCrypto.setPin(ctx, pin)
        // ② 그다음 한 번에 씀. 백업에 든 묶음은 백업 그대로 — 백업 뒤에 생긴 값(백업 때는 없던 설정)은 지움.
        //    예전엔 덮어쓰기만 해서, 백업 때 '저절로'(값 없음)였던 업무·일정 앱 지정·방 거리·사람 묶음 구성원 등이 지금 값으로 남았다.
        plan.forEach { (name, entries) ->
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val ed = sp.edit()
            val now = sp.all
            // 저절로 쌓이는 기록(내가 말한 방)은 지우지 않고 합침 — 며칠 전 백업을 불러와도 그 뒤에 말한 방이 '그 밖의 방'으로 내려가지 않게
            val grows = name in GROWING
            if (!grows) now.keys.forEach { k -> if (isBackedUp(name, k) && k !in named[name].orEmpty()) ed.remove(k) }
            entries.forEach { (k, v) ->
                if (!isBackedUp(name, k)) return@forEach
                when (v) {
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, if (grows) maxOf(v, now[k] as? Long ?: 0L) else v)
                    is Float -> ed.putFloat(k, v)
                    is String -> ed.putString(k, v)
                    is Set<*> -> ed.putStringSet(k, v.map { it.toString() }.toSet())
                }
            }
            ed.commit()
        }
        // 📌 직접 넣은 할 일(이미 있는 것은 그대로 — dedupe 로 한 번만)
        j.optJSONArray("tasks")?.let { a ->
            runCatching {
                val db = NotificationDb.get(ctx)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val dedupe = o.optString("dedupe")
                    if (!dedupe.startsWith("manual|") || o.optString("title").isBlank()) continue
                    db.insertTask(Task(title = o.getString("title").take(60), room = o.optString("room", "직접 추가"), due = o.getLong("due"),
                        hasTime = o.optBoolean("hasTime"), sourceTime = o.optLong("sourceTime"), done = o.optBoolean("done"), dedupe = dedupe))
                }
            }
        }
        // 브리핑·수업 알람 다시 예약
        BriefingScheduler.scheduleAll(ctx)
        QuietMode.tick(ctx)
        return j.optString("summary").ifEmpty { summary(ctx) }
    }

    /** 이 묶음의 이 값이 백업 대상인가(만들 때·불러올 때 같은 기준) */
    private fun isBackedUp(name: String, key: String) = key !in SKIP[name].orEmpty() && (name != "app" || key in APP_ONLY)

    /** 백업의 설정 묶음 하나를 (이름 → 값)으로. 형식이 틀리면 예외(아무것도 쓰기 전) */
    private fun readEntries(obj: JSONObject): Map<String, Any> = buildMap {
        obj.keys().forEach { k ->
            val e = obj.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> put(k, e.getBoolean("v"))
                "i" -> put(k, e.getInt("v"))
                "l" -> put(k, e.getLong("v"))
                "f" -> put(k, e.getDouble("v").toFloat())
                "s" -> put(k, e.getString("v"))
                "set" -> e.getJSONArray("v").let { a -> put(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
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
