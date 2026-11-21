package com.jarvis.phone

import com.jarvis.phone.SummarySchedule.Config
import com.jarvis.phone.SummarySchedule.Day
import com.jarvis.phone.SummarySchedule.Kind
import com.jarvis.phone.SummarySchedule.RestMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** 다음 요약 시각 계산 — 평일·쉬는 날 네 가지·수업 끝·간격·출근·자정 넘김·공휴일 */
class SummaryScheduleTest {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
    private fun t(s: String) = fmt.parse(s)!!.time
    private fun show(r: SummarySchedule.Run?) = r?.let { fmt.format(it.at) + " " + it.kind } ?: "없음"

    private val day = Day(classEnds = listOf(9 * 60 + 40, 10 * 60 + 30), workStart = 8 * 60)
    private val base = Config(times = listOf(8 * 60, 17 * 60), intervalHours = 0, intervalFrom = 8 * 60, intervalTo = 18 * 60,
        afterClass = false, afterQuiet = false, restMode = RestMode.ALL_DAY)
    /** 2026-11-23(월)~ 평일, 11-28(토)·11-29(일) 쉬는 날 */
    private val restIsWeekend: (Long) -> Boolean = { RestDays.isWeekendYmd(SummarySchedule.ymd(it)) || SummarySchedule.ymd(it) in RestDays.HOLIDAYS }

    @Test fun 정한_시각_다음_한_번() {
        assertEquals("2026-11-23 17:00 FIXED", show(SummarySchedule.next(t("2026-11-23 09:00"), base, day, restIsWeekend)))
        // 저녁이 지나면 다음 날 아침
        assertEquals("2026-11-24 08:00 FIXED", show(SummarySchedule.next(t("2026-11-23 17:30"), base, day, restIsWeekend)))
    }

    @Test fun 간격은_시작_다음부터_끝까지_1시간_단위() {
        val c = base.copy(times = emptyList(), intervalHours = 2)
        assertEquals(listOf(10 * 60, 12 * 60, 14 * 60, 16 * 60, 18 * 60),
            SummarySchedule.runsOfDay(c, day, rest = false).map { it.first })
        assertEquals("2026-11-23 12:00 INTERVAL", show(SummarySchedule.next(t("2026-11-23 10:00"), c, day, restIsWeekend)))
    }

    @Test fun 같은_분이면_정한_시각이_남는다() {
        val c = base.copy(intervalHours = 1, intervalFrom = 7 * 60)   // 8:00 이 정한 시각과 겹침
        val runs = SummarySchedule.runsOfDay(c, day, rest = false)
        assertEquals(Kind.FIXED, runs.first { it.first == 8 * 60 }.second)
        assertEquals(runs.size, runs.map { it.first }.distinct().size)
    }

    @Test fun 수업_끝날_때마다는_평일만() {
        val c = base.copy(afterClass = true)
        assertEquals("2026-11-23 09:40 CLASS_END", show(SummarySchedule.next(t("2026-11-23 09:00"), c, day, restIsWeekend)))
        assertFalse(SummarySchedule.runsOfDay(c, day, rest = true).any { it.second == Kind.CLASS_END })
    }

    @Test fun 출근할_때_밤사이_요약() {
        val c = base.copy(times = listOf(17 * 60), afterQuiet = true)
        assertEquals("2026-11-24 08:00 QUIET_END", show(SummarySchedule.next(t("2026-11-23 20:00"), c, day, restIsWeekend)))
        // 하루 종일 모아 두는 쉬는 날에는 '출근'이 없다
        assertFalse(SummarySchedule.runsOfDay(c, day, rest = true).any { it.second == Kind.QUIET_END })
    }

    @Test fun 쉬는_날_네_가지() {
        val c = base.copy(intervalHours = 2, afterClass = true)
        val sat = t("2026-11-28 07:00")
        // 하루 종일 모아 두기: 정한 시각 + 간격, 수업 끝은 없음
        assertEquals("2026-11-28 08:00 FIXED", show(SummarySchedule.next(sat, c, day, restIsWeekend)))
        assertTrue(SummarySchedule.runsOfDay(c, day, rest = true).any { it.second == Kind.INTERVAL })
        // 정한 시각만
        val fixedOnly = c.copy(restMode = RestMode.FIXED_ONLY)
        assertTrue(SummarySchedule.runsOfDay(fixedOnly, day, rest = true).all { it.second == Kind.FIXED })
        // 모두 쉬기: 토·일을 건너뛰고 월요일 아침
        val off = c.copy(restMode = RestMode.OFF)
        assertEquals("2026-11-30 08:00 FIXED", show(SummarySchedule.next(sat, off, day, restIsWeekend)))
    }

    @Test fun 공휴일도_쉬는_날() {
        val off = base.copy(restMode = RestMode.OFF)
        // 2026-12-24(목) 저녁 뒤 → 12-25(금) 성탄절 쉼 → 26·27 주말 → 12-28(월)
        assertEquals("2026-12-28 08:00 FIXED", show(SummarySchedule.next(t("2026-12-24 18:00"), off, day, restIsWeekend)))
        assertTrue(RestDays.isRest(20261225, emptySet(), emptySet()))
        assertTrue(RestDays.isRest(20261123, setOf(20261123), emptySet()))        // 직접 넣은 날(재량휴업일)
        assertFalse(RestDays.isRest(20261225, emptySet(), setOf(20261225)))       // 뺀 공휴일(학교 가는 날)
        assertFalse(RestDays.isRest(20261123, emptySet(), emptySet()))
    }

    @Test fun 요약_시간이_하나도_없으면_예약하지_않는다() {
        assertNull(SummarySchedule.next(t("2026-11-23 09:00"), base.copy(times = emptyList()), day, restIsWeekend))
    }
}
