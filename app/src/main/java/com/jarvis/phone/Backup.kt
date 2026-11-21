package com.jarvis.phone

import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 💾 백업·복원: 앱을 지웠다 다시 깔아도 내 설정이 그대로.
 *
 * - 넣는 것: 학생·학부모 명부, 학부모 방, 앱·방 끄기, 키워드, 광고 거르기, 브리핑 시각·주말 쉬기, 수업·퇴근 시간표
 * - 넣지 않는 것: 메시지 내용, 학생별 기록, AI 모델 (개인정보·용량)
 * - 자동 백업: 하루 한 번 다운로드/쌤톡스/쌤톡스_백업.json ('모든 파일 접근' 권한이 있을 때). 앱을 지워도 남음
 * - 수동: 원하는 곳으로 내보내기 / 파일에서 불러오기 (권한 없이도 됨)
 * 인터넷 권한이 없어서 백업 파일도 폰 밖으로 나가지 않음.
 */
object Backup {
    private const val FORMAT = "ssamtox-backup"

    /** 백업할 설정 묶음과, 그 안에서 넣지 않을 값(그때그때 바뀌는 상태) */
    private val FILES = listOf("apps", "briefing", "quiet", "app")
    private val SKIP = mapOf(
        "apps" to setOf("handled", "ad_day", "ad_count"),
        "quiet" to setOf("last", "since", "we_set_dnd"),
    )
    /** "app" 묶음은 이것만 (설치 안내 진행·모델 정보는 기기마다 다름) */
    private val APP_ONLY = setOf("apps_setup_done")

    private fun meta(ctx: Context) = ctx.getSharedPreferences("backup_meta", Context.MODE_PRIVATE)
    fun lastBackupTime(ctx: Context) = meta(ctx).getLong("last", 0L)

    fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "쌤톡스")
    fun file(): File = File(dir(), "쌤톡스_백업.json")

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
        BriefingSlot.entries.count { it.isEnabled(ctx) }.takeIf { it > 0 }?.let { "자동 브리핑 ${it}개" },
    ).joinToString(" · ").ifEmpty { "기본 설정" }

    // ── 저장 ──
    /** 다운로드/쌤톡스 폴더에 저장. 권한이 없으면 false */
    fun saveToDownloads(ctx: Context): Boolean {
        if (!ModelFinder.hasAccess()) return false
        return runCatching {
            dir().mkdirs()
            val tmp = File(dir(), "쌤톡스_백업.tmp")
            tmp.writeText(toJson(ctx))
            if (!tmp.renameTo(file())) { file().delete(); tmp.renameTo(file()) }
            meta(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
        }.isSuccess
    }

    /** 하루 한 번 자동 백업 (앱 열 때·자동 브리핑 끝날 때) */
    fun autoBackup(ctx: Context) {
        if (System.currentTimeMillis() - lastBackupTime(ctx) < 20 * 3600 * 1000L) return
        saveToDownloads(ctx)
    }

    /** 선생님이 고른 위치로 내보내기 (권한 필요 없음) */
    fun exportTo(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(toJson(ctx).toByteArray()) }
        meta(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
    }.isSuccess

    // ── 찾기·불러오기 ──
    /** 다운로드/쌤톡스에 백업이 있으면 (저장 시각, 요약). 권한이 없거나 없으면 null */
    fun found(): Pair<Long, String>? {
        if (!ModelFinder.hasAccess() || !file().exists()) return null
        return runCatching {
            val j = JSONObject(file().readText())
            if (j.optString("format") != FORMAT) null else j.optLong("created") to j.optString("summary")
        }.getOrNull()
    }

    fun restoreFromDownloads(ctx: Context): Result<String> = runCatching { restore(ctx, file().readText()) }

    fun restoreFrom(ctx: Context, uri: Uri): Result<String> = runCatching {
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        restore(ctx, text)
    }

    /** 백업 내용을 설정에 덮어쓰고 알람을 다시 예약. 돌려주는 값은 요약 한 줄. */
    private fun restore(ctx: Context, text: String): String {
        val j = JSONObject(text)
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
        val f = File(dir, "쌤톡스_백업.json").apply { writeText(Backup.toJson(ctx)) }
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
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val j = JSONObject(text)
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
