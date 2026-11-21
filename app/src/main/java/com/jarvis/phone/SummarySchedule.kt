package com.jarvis.phone

import android.content.Context
import java.util.Calendar

/**
 * 🔕 모아 두기·요약 시간 (v1.7.0, 2026-11-21 선생님 결정)
 *  - 요약 때: 정한 시각 여러 개 · N시간마다(1시간 단위, 시간대) · 수업이 끝날 때마다 · 퇴근 모드가 끝날 때(출근)
 *  - 쉬는 날(토·일·공휴일·직접 넣은 날): 평일처럼 / 하루 종일 모아 두기 / 정한 시각 요약만 / 모두 쉬기
 * 예약은 모든 규칙 중 가장 가까운 '다음 한 번'만 걸고, 요약이 끝나면 그다음을 건다(BriefingScheduler).
 * 계산(next)은 안드로이드에 기대지 않는 순수 코드라 맥미니에서 시험한다(SummaryScheduleTest).
 */
object SummarySchedule {

    enum class Kind { FIXED, INTERVAL, CLASS_END, QUIET_END }

    /** 쉬는 날에 할 일 */
    enum class RestMode(val title: String, val desc: String) {
        LIKE_WEEKDAY("평일처럼", "퇴근 시간 밖은 모아 두고, 요약도 평일처럼 해요(수업 시간표는 쉬어요)."),
        ALL_DAY("하루 종일 모아 두기", "하루 종일 모아 두었다가 요약 시간에 정리해요."),
        FIXED_ONLY("정한 시각 요약만", "모아 두지 않고 알림은 평소처럼 와요. 정한 시각에만 요약해요."),
        OFF("모두 쉬기", "모아 두지도, 요약하지도 않아요. 다음 평일에 한 번에 정리돼요."),
    }

    data class Config(
        val times: List<Int>,              // 정한 시각(자정부터의 분)
        val intervalHours: Int,            // 0 = 끔, 1~4
        val intervalFrom: Int,
        val intervalTo: Int,
        val afterClass: Boolean,
        val afterQuiet: Boolean,
        val restMode: RestMode,
    )

    /** 하루의 정보(시간표·출근 시각은 QuietMode 설정) */
    data class Day(val classEnds: List<Int>, val workStart: Int?)

    data class Run(val at: Long, val kind: Kind, val minute: Int)

    /** now 다음의 요약 한 번. isRest(그날 0시) 로 쉬는 날을 판단 */
    fun next(now: Long, cfg: Config, day: Day, isRest: (Long) -> Boolean): Run? {
        val start = startOfDay(now)
        for (d in 0..8) {
            val dayStart = addDays(start, d)
            val rest = isRest(dayStart)
            val runs = runsOfDay(cfg, day, rest)
            runs.forEach { (m, kind) ->
                val at = dayStart + m * 60_000L
                if (at > now + 5_000) return Run(at, kind, m)
            }
        }
        return null
    }

    /** 그날의 요약 시각들(분, 종류) — 같은 분이면 정한 시각을 남김 */
    fun runsOfDay(cfg: Config, day: Day, rest: Boolean): List<Pair<Int, Kind>> {
        if (rest && cfg.restMode == RestMode.OFF) return emptyList()
        val list = mutableListOf<Pair<Int, Kind>>()
        cfg.times.forEach { list += it to Kind.FIXED }
        if (!rest || cfg.restMode == RestMode.LIKE_WEEKDAY || cfg.restMode == RestMode.ALL_DAY) {
            if (cfg.intervalHours in 1..12 && cfg.intervalTo > cfg.intervalFrom) {
                var m = cfg.intervalFrom + cfg.intervalHours * 60
                while (m <= cfg.intervalTo) { list += m to Kind.INTERVAL; m += cfg.intervalHours * 60 }
            }
            if (!rest && cfg.afterClass) day.classEnds.forEach { list += it to Kind.CLASS_END }
            if (cfg.afterQuiet && day.workStart != null && !(rest && cfg.restMode == RestMode.ALL_DAY)) list += day.workStart to Kind.QUIET_END
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
            times = p.getString("times", "")!!.split(",").mapNotNull { it.toIntOrNull() }.distinct().sorted(),
            intervalHours = p.getInt("interval_h", 0),
            intervalFrom = p.getInt("interval_from", 8 * 60),
            intervalTo = p.getInt("interval_to", 18 * 60),
            afterClass = p.getBoolean("after_class", false),
            afterQuiet = p.getBoolean("after_quiet", false),
            restMode = runCatching { RestMode.valueOf(p.getString("rest_mode", null)!!) }.getOrDefault(RestMode.ALL_DAY),
        )
    }

    fun update(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(ctx).edit().apply(block).apply()
        BriefingScheduler.scheduleAll(ctx)
        QuietMode.tick(ctx)
    }
    fun setTimes(ctx: Context, times: List<Int>) = update(ctx) { putString("times", times.distinct().sorted().joinToString(",")) }

    /** v1.6 까지의 아침·저녁 두 칸·주말 설정을 새 설정으로 한 번 옮김 */
    private fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("v17_migrated", false)) return
        val times = buildList {
            if (p.getBoolean("morning_on", false)) add(p.getInt("morning_min", 8 * 60))
            if (p.getBoolean("evening_on", false)) add(p.getInt("evening_min", 21 * 60))
        }
        val weekendQuiet = ctx.getSharedPreferences("quiet", Context.MODE_PRIVATE).getBoolean("weekend", true)
        val mode = when {
            p.getBoolean("skip_weekend", false) -> RestMode.OFF
            weekendQuiet -> RestMode.ALL_DAY
            else -> RestMode.LIKE_WEEKDAY
        }
        p.edit().putString("times", times.joinToString(",")).putString("rest_mode", mode.name).putBoolean("v17_migrated", true).apply()
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
