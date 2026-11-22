package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestTest {
    private val b1 = Briefing(1, 0, "🌅 아침 요약", 5, """
🕘 11/22 08:00 ~ 09:00에 온 알림

🙋 답장할 연락 (1)
- 김지우맘 · 08:30: 내일 도시락 챙겨야 하나요?

💬 사람에게 온 메시지 (2개 방)
- 6학년 동학년방 (3건): 학예회 순서 확정
- 박선생님 (1건): 협의회 자료 보냄

📦 앱 알림 (4건 · 요약 안 함)
- 쿠팡 3건 · 배송 출발
- 은행 1건 · 결제

(⏱ 10초 · AI 1번 9초)""".trim())
    private val b2 = Briefing(2, 0, "🌙 저녁 요약", 4, """
🙋 답장할 연락 (1)
- 6학년 동학년방 · 18:00: 명단 금요일까지 → 각 반 명단 내기

💬 사람에게 온 메시지 (1개 방)
- 박선생님 (2건): 내일 회의 3시로

📦 앱 알림 (2건 · 요약 안 함)
- 쿠팡 2건 · 배송 완료""".trim())

    @Test fun 하루_요약을_방마다_하나로_합침() {
        val t = DailyDigest.compose(listOf(b1, b2), "📌 오늘·내일 마감 (1건)\n- [내일] 명단 내기 (6학년 동학년방)\n")
        assertTrue(t, t.startsWith("요약 2번 · 알림 9건을 합쳤어요."))
        assertTrue(t, "📌 오늘·내일 마감" in t)
        assertTrue(t, "🙋 답장할 연락 (2)" in t)
        assertTrue(t, "- 박선생님 (3건): 내일 회의 3시로" in t)          // 최근 줄, 건수 합
        assertFalse(t, "학예회 순서 확정" in t)                          // 답장할 방은 💬 에서 뺌
        assertTrue(t, "📦 앱 알림 6건 · 쿠팡 5, 은행 1" in t)
        assertFalse(t, "⏱" in t); assertFalse(t, "🕘" in t)
    }

    @Test fun 다음_시각_쉬는날_건너뜀() {
        val cfg = SummarySchedule.Config(digest = true, times = emptyList(), intervalHours = 0, intervalFrom = 0, intervalTo = 0,
            restSkip = true, dailyOn = true, dailyMinute = 22 * 60)
        val day = SummarySchedule.startOfDay(System.currentTimeMillis())
        val now = day + 23 * 3600_000L          // 오늘 23시 → 내일은 쉬는 날 → 모레 22시
        val tomorrow = SummarySchedule.addDays(day, 1)
        assertEquals(SummarySchedule.addDays(day, 2) + 22 * 3600_000L, DailyDigest.next(now, cfg) { it == tomorrow })
        assertEquals(null, DailyDigest.next(now, cfg.copy(dailyOn = false)) { false })
    }

    @Test fun 순위대로_고르고_AI_답은_칸마다_줄수를_지킴() {
        val lines = List(12) { DailyDigest.Line(3, "💬 대화", "방$it", it) } + List(10) { DailyDigest.Line(1, "⭐ 키워드", "키$it") } +
            listOf(DailyDigest.Line(2, "🙋 답장할 연락", "민준 어머님 물음"))
        val p = DailyDigest.pick(lines)
        assertEquals(20, p.size)
        assertEquals(8, p.count { it.rank == 1 })                  // 1순위는 8줄까지 — 나머지 자리는 대화방에
        assertEquals(11, p.count { it.rank == 3 })
        assertTrue(p.any { it.rank == 2 })
        assertEquals("방11", p.first { it.rank == 3 }.text)          // 3순위는 건수 많은 방부터
        val out = DailyDigest.parseAi("**정리**\n**✅ 오늘 꼭 챙길 일:**\n- 가\n- 나\n- 다\n- 라\n🙋 답장할 연락:\n- 없음\n📅 내일 챙길 것: 회의 준비")!!
        assertEquals("✅ 오늘 꼭 챙길 일\n- 가\n- 나\n- 다\n\n📅 내일 챙길 것\n- 회의 준비", out)
        assertEquals(null, DailyDigest.parseAi("그냥 아무 말"))
    }
}
