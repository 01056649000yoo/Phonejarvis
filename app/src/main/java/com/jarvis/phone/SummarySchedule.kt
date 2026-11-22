package com.jarvis.phone

import android.content.Context
import java.util.Calendar

/**
 * 🔕 모아서 알림 (v1.8.0 단순화, 2026-11-21 선생님 결정)
 *  - 켜면 모으는 앱 알림은 늘 바로 숨기고 모아 두었다가 정한 시각(여러 개)·N시간마다(1시간 단위, 시간대) 요약해서 알림
 *  - 끄면 알림은 평소처럼, 요약은 직접 눌러서. 주말·공휴일에 쉴지 스위치 하나
 * 예약은 모든 규칙 중 가장 가까운 '다음 한 번'만 걸고, 요약이 끝나면 그다음을 건다(BriefingScheduler).
 * 계산(next)은 안드로이드에 기대지 않는 순수 코드라 맥미니에서 시험한다(SummaryScheduleTest).
 */
object SummarySchedule {

    enum class Kind { FIXED, INTERVAL }

    /**
     * v1.8.0 (2026-11-21 선생님: "기능이 너무 많으면 불편 — 단순하게"):
     *  digest 켜짐 = 모으는 앱 알림은 늘 바로 숨기고 모아 두었다가, 정한 시각·N시간마다 요약해서 알려 줌
     *  digest 꺼짐 = 알림은 평소처럼, 요약은 직접 눌러서
     *  restSkip = 주말·공휴일에는 모으지도 알리지도 않음
     */
    data class Config(
        val digest: Boolean,
        val times: List<Int>,              // 정한 시각(자정부터의 분)
        val intervalHours: Int,            // 0 = 끔, 1~4
        val intervalFrom: Int,
        val intervalTo: Int,
        val restSkip: Boolean,
        val dailyOn: Boolean = false,      // 🗓 하루 정리 (DailyDigest)
        val dailyMinute: Int = DailyDigest.DEFAULT_MINUTE,
    )

    data class Run(val at: Long, val kind: Kind, val minute: Int)

    /** now 다음의 요약 한 번. isRest(그날 0시) 로 쉬는 날을 판단 */
    fun next(now: Long, cfg: Config, isRest: (Long) -> Boolean): Run? {
        if (!cfg.digest) return null
        val start = startOfDay(now)
        for (d in 0..10) {
            val dayStart = addDays(start, d)
            if (cfg.restSkip && isRest(dayStart)) continue
            runsOfDay(cfg).forEach { (m, kind) ->
                val at = dayStart + m * 60_000L
                if (at > now + 5_000) return Run(at, kind, m)
            }
        }
        return null
    }

    /** 그날의 요약 시각들(분, 종류) — 같은 분이면 정한 시각을 남김 */
    fun runsOfDay(cfg: Config): List<Pair<Int, Kind>> {
        val list = mutableListOf<Pair<Int, Kind>>()
        cfg.times.forEach { list += it to Kind.FIXED }
        if (cfg.intervalHours in 1..12 && cfg.intervalTo > cfg.intervalFrom) {
            var m = cfg.intervalFrom + cfg.intervalHours * 60
            while (m <= cfg.intervalTo) { list += m to Kind.INTERVAL; m += cfg.intervalHours * 60 }
        }
        return list.filter { it.first in 0 until 24 * 60 }
            .sortedWith(compareBy<Pair<Int, Kind>> { it.first }.thenBy { it.second.ordinal })
            .distinctBy { it.first }
    }

    // ── 설정 저장 ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("briefing", Context.MODE_PRIVATE)

    fun config(ctx: Context): Config {
        val p = prefs(ctx)
        migrate(ctx)
        return Config(
            digest = p.getBoolean("digest", false),
            times = p.getString("times", "")!!.split(",").mapNotNull { it.toIntOrNull() }.distinct().sorted(),
            intervalHours = p.getInt("interval_h", 0),
            intervalFrom = p.getInt("interval_from", 8 * 60),
            intervalTo = p.getInt("interval_to", 18 * 60),
            restSkip = p.getBoolean("rest_skip", false),
            dailyOn = p.getBoolean("daily_on", true),
            dailyMinute = p.getInt("daily_min", DailyDigest.DEFAULT_MINUTE),
        )
    }

    fun update(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(ctx).edit().apply(block).apply()
        BriefingScheduler.scheduleAll(ctx)
        QuietMode.tick(ctx)
    }
    fun setTimes(ctx: Context, times: List<Int>) = update(ctx) { putString("times", times.distinct().sorted().joinToString(",")) }

    /** 예전 설정(아침·저녁 두 칸 → v1.7 규칙들)을 v1.8 로 한 번 옮김: 요약 시각이 있었으면 모아서 알림 켬 */
    private fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("v17_migrated", false)) {
            val times = buildList {
                if (p.getBoolean("morning_on", false)) add(p.getInt("morning_min", 8 * 60))
                if (p.getBoolean("evening_on", false)) add(p.getInt("evening_min", 21 * 60))
            }
            p.edit().putString("times", times.joinToString(",")).putBoolean("v17_migrated", true)
                .putBoolean("rest_skip", p.getBoolean("skip_weekend", false)).apply()
        }
        if (!p.getBoolean("v18_migrated", false)) {
            val hasTimes = !p.getString("times", "").isNullOrBlank() || p.getInt("interval_h", 0) > 0
            p.edit().putBoolean("digest", hasTimes)
                .putBoolean("rest_skip", p.getBoolean("rest_skip", false) || p.getString("rest_mode", "") == "OFF")
                .putBoolean("v18_migrated", true).apply()
        }
    }

    // ── 날짜 계산 ──
    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun addDays(dayStart: Long, d: Int): Long = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, d) }.timeInMillis
    fun ymd(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.let {
        it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH)
    }
}

/**
 * 쉬는 날: 토·일 + 공휴일(대체공휴일 포함, 2026~2027) + 선생님이 넣은 날(재량휴업일 등) − 뺀 날.
 * 공휴일은 해마다 바뀌어 앱에 적어 둔다 — 틀리거나 새로 생긴 날은 설정에서 넣고 뺄 수 있다.
 */
object RestDays {
    val HOLIDAYS: Map<Int, String> = mapOf(
        20260101 to "신정", 20260216 to "설날", 20260217 to "설날", 20260218 to "설날",
        20260302 to "삼일절 대체", 20260505 to "어린이날", 20260525 to "부처님오신날 대체", 20260603 to "지방선거",
        20260606 to "현충일", 20260817 to "광복절 대체", 20260924 to "추석", 20260925 to "추석", 20260926 to "추석",
        20261005 to "개천절 대체", 20261009 to "한글날", 20261225 to "성탄절",
        20270101 to "신정", 20270206 to "설날", 20270207 to "설날", 20270208 to "설날", 20270209 to "설날 대체",
        20270301 to "삼일절", 20270505 to "어린이날", 20270513 to "부처님오신날", 20270606 to "현충일",
        20270816 to "광복절 대체", 20270914 to "추석", 20270915 to "추석", 20270916 to "추석",
        20271004 to "개천절 대체", 20271011 to "한글날 대체", 20271227 to "성탄절 대체",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("briefing", Context.MODE_PRIVATE)
    fun added(ctx: Context): Set<Int> = prefs(ctx).getStringSet("rest_added", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    fun removed(ctx: Context): Set<Int> = prefs(ctx).getStringSet("rest_removed", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    fun add(ctx: Context, ymd: Int) = SummarySchedule.update(ctx) {
        putStringSet("rest_added", (added(ctx) + ymd).map { it.toString() }.toSet())
        putStringSet("rest_removed", (removed(ctx) - ymd).map { it.toString() }.toSet())
    }
    fun remove(ctx: Context, ymd: Int) = SummarySchedule.update(ctx) {
        putStringSet("rest_added", (added(ctx) - ymd).map { it.toString() }.toSet())
        if (ymd in HOLIDAYS || isWeekendYmd(ymd)) putStringSet("rest_removed", (removed(ctx) + ymd).map { it.toString() }.toSet())
    }

    fun isRest(ymd: Int, added: Set<Int>, removed: Set<Int>): Boolean =
        ymd !in removed && (ymd in added || ymd in HOLIDAYS || isWeekendYmd(ymd))
    fun isRest(ctx: Context, t: Long): Boolean = isRest(SummarySchedule.ymd(t), added(ctx), removed(ctx))

    fun isWeekendYmd(ymd: Int): Boolean = Calendar.getInstance().apply { set(ymd / 10000, ymd / 100 % 100 - 1, ymd % 100) }
        .get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }

    /** 오늘부터 앞으로 60일 안의 쉬는 날(주말 빼고) — 설정 화면 목록 */
    fun upcoming(ctx: Context, now: Long = System.currentTimeMillis()): List<Pair<Int, String>> {
        val a = added(ctx); val r = removed(ctx); val start = SummarySchedule.startOfDay(now)
        return (0..60).map { SummarySchedule.ymd(SummarySchedule.addDays(start, it)) }
            .filter { !isWeekendYmd(it) && isRest(it, a, r) }
            .map { it to (HOLIDAYS[it] ?: "직접 넣은 날") }
    }
}
