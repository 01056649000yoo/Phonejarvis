package com.jarvis.phone

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/**
 * 🔁 정기·자동 알림 (2026-11-23 선생님: "서버 알림이나 시간되면 오는 알림도 계속 들어 있음").
 * 메시지 앱이 아닌 앱에서 최근 7일 중 3일 이상, 같은 앱에서 같은 제목 꼴(숫자는 #)로 비슷한 시각(±30분)에 온 알림 →
 * 날씨·걸음 수·일정 미리 알림·서버 점검 결과 같은 것. 요약·⭐·하루 정리에서 빼고 건수 한 줄로만 남긴다.
 * 선생님이 알림 탭에서 길게 눌러 '요약에서 빼기'한 꼴([muted])도 같이 뺀다. 원래 알림은 알림 탭·검색에 그대로 남는다.
 */
object Routine {
    const val DAYS = 7
    const val MIN_DAYS = 3
    const val WINDOW_MIN = 30
    private val NUM = Regex("\\d+")

    /** 제목 꼴: 숫자는 #, 앞 30자 */
    fun shape(title: String) = NUM.replace(title.trim(), "#").take(30)
    fun key(n: NotiItem) = "${n.pkg}|${shape(n.title)}"
    /** 화면에 보일 이름: '앱 · 제목 꼴' */
    fun label(key: String, app: String = key.substringBefore('|')) = "$app · ${key.substringAfter('|').ifBlank { "(제목 없음)" }}"

    /** 최근 알림들에서 정기 알림 꼴 찾기(순수 규칙 — 시험) */
    fun detect(items: List<NotiItem>, zone: TimeZone = TimeZone.getDefault()): Set<String> {
        val cal = Calendar.getInstance(zone)
        fun dayMin(t: Long): Pair<Long, Int> {
            cal.timeInMillis = t
            val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis to m
        }
        // 메시지 앱(카톡·문자·알리미 등)은 넣지 않음 — 학교 알리미처럼 매일 같은 시각에 오는 중요한 안내가 있어서. 봇은 isQuiet 에서 따로
        return items.filterNot { AppAdvisor.isMessaging(it.app, it.pkg) }
            .groupBy(::key)
            .filter { (_, list) ->
                val dm = list.map { dayMin(it.time) }
                if (dm.map { it.first }.distinct().size < MIN_DAYS) return@filter false
                // 어느 한 시각(그 꼴이 온 시각들 중 하나) ±30분 안에 3일 이상 오면 정기
                dm.map { it.second }.distinct().any { c ->
                    dm.filter { (_, m) -> minOf(Math.floorMod(m - c, 1440), Math.floorMod(c - m, 1440)) <= WINDOW_MIN }
                        .map { it.first }.distinct().size >= MIN_DAYS
                }
            }.keys
    }

    // 7일치를 매번 세지 않게 30분 동안 기억
    @Volatile private var cache: Pair<Long, Set<String>>? = null
    fun keys(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        cache?.let { (at, k) -> if (now - at < 30 * 60_000L) return k }
        val k = runCatching { detect(NotificationDb.get(ctx).since(now - DAYS * 24L * 3600 * 1000)) }.getOrDefault(emptySet())
        cache = now to k
        return k
    }

    // ── 직접 뺀 꼴 ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)
    fun muted(ctx: Context): Set<String> = prefs(ctx).getStringSet("muted_shapes", emptySet()) ?: emptySet()
    fun mute(ctx: Context, key: String) = prefs(ctx).edit().putStringSet("muted_shapes", muted(ctx) + key).apply()
    fun unmute(ctx: Context, key: String) = prefs(ctx).edit().putStringSet("muted_shapes", muted(ctx) - key).apply()

    /** 요약에서 뺄 알림인가: 직접 뺀 꼴은 언제나, 정기 꼴·메시지 앱의 봇·서버 알림은 키워드가 없을 때만 */
    fun isQuiet(n: NotiItem, routine: Set<String>, muted: Set<String>, keywords: Collection<String> = emptyList()): Boolean {
        val k = key(n)
        if (k in muted) return true
        if (AppFilter.hasRealKeyword(keywords, n.title, n.text, n.room)) return false
        return k in routine || (AppAdvisor.isMessaging(n.app, n.pkg) && BriefingComposer.isBot(n))
    }

    /** 요약 끝의 한 줄: 🔁 정기·자동 알림 12건 (날씨 7, 텔레그램 5) — 요약에서 뺌 */
    fun line(quiet: List<NotiItem>): String {
        if (quiet.isEmpty()) return ""
        val byApp = quiet.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }
        return "🔁 정기·자동 알림 ${quiet.size}건 (" + byApp.take(4).joinToString(", ") { "${it.key} ${it.value}" } +
            (if (byApp.size > 4) " 외" else "") + ") — 요약에서 뺐어요\n"
    }
}
