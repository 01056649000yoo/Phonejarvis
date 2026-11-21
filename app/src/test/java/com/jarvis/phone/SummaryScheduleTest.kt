package com.jarvis.phone

import com.jarvis.phone.SummarySchedule.Config
import com.jarvis.phone.SummarySchedule.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** 모아서 알림(v1.8.0): 다음 요약 시각 — 정한 시각·간격·겹침·꺼짐·주말·공휴일 쉬기 */
class SummaryScheduleTest {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
    private fun t(s: String) = fmt.parse(s)!!.time
    private fun show(r: SummarySchedule.Run?) = r?.let { fmt.format(it.at) + " " + it.kind } ?: "없음"
    private val base = Config(digest = true, times = listOf(8 * 60, 17 * 60), intervalHours = 0, intervalFrom = 8 * 60, intervalTo = 18 * 60, restSkip = false)
    private val isRest: (Long) -> Boolean = { RestDays.isRest(SummarySchedule.ymd(it), emptySet(), emptySet()) }

    @Test fun 정한_시각_다음_한_번() {
        assertEquals("2026-11-23 17:00 FIXED", show(SummarySchedule.next(t("2026-11-23 09:00"), base, isRest)))
        assertEquals("2026-11-24 08:00 FIXED", show(SummarySchedule.next(t("2026-11-23 17:30"), base, isRest)))
    }

    @Test fun 간격은_시작_다음부터_끝까지_1시간_단위() {
        val c = base.copy(times = emptyList(), intervalHours = 2)
        assertEquals(listOf(10 * 60, 12 * 60, 14 * 60, 16 * 60, 18 * 60), SummarySchedule.runsOfDay(c).map { it.first })
        assertEquals("2026-11-23 12:00 INTERVAL", show(SummarySchedule.next(t("2026-11-23 10:00"), c, isRest)))
    }

    @Test fun 같은_분이면_정한_시각이_남는다() {
        val runs = SummarySchedule.runsOfDay(base.copy(intervalHours = 1, intervalFrom = 7 * 60))
        assertEquals(Kind.FIXED, runs.first { it.first == 8 * 60 }.second)
        assertEquals(runs.size, runs.map { it.first }.distinct().size)
    }

    @Test fun 모아서_알림을_끄면_예약하지_않는다() {
        assertNull(SummarySchedule.next(t("2026-11-23 09:00"), base.copy(digest = false), isRest))
        assertNull(SummarySchedule.next(t("2026-11-23 09:00"), base.copy(times = emptyList()), isRest))
    }

    @Test fun 주말_공휴일_쉬기() {
        val off = base.copy(restSkip = true)
        // 토요일 아침 → 월요일 아침
        assertEquals("2026-11-30 08:00 FIXED", show(SummarySchedule.next(t("2026-11-28 07:00"), off, isRest)))
        // 성탄절(금)·주말 건너뛰고 12-28(월)
        assertEquals("2026-12-28 08:00 FIXED", show(SummarySchedule.next(t("2026-12-24 18:00"), off, isRest)))
        // 쉬기를 끄면 토요일도
        assertEquals("2026-11-28 08:00 FIXED", show(SummarySchedule.next(t("2026-11-28 07:00"), base, isRest)))
        assertTrue(RestDays.isRest(20261225, emptySet(), emptySet()))
        assertFalse(RestDays.isRest(20261123, emptySet(), emptySet()))
    }
}
