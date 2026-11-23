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
        assertTrue(t, "💬 대화 요약 (3개 방 · 🙋 답장 2)" in t && "- 🙋 김지우맘 · 08:30" in t)
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

    // 2026-11-23 22:00 하루 정리 화면에서 나온 것들
    @Test fun 하루정리_겹친_사람_지난_일정_시각순() {
        val ai = """
            ✅ 오늘 꼭 챙길 일:
            - 핑퐁로봇 · 09:00: 4학년 3시간
            🙋 답장할 연락:
            - 권지혜 선생님: 내일 아침 프로그램 pr코드 요청
            - 김진수: 출발 시간 문의
            📝 오늘 있었던 일:
            - 김진수 · 11:25: 완주 기념 모임 제안
            - 최두형 · 11:01: 링크 공유
            - 류성하 · 09:31: 일정 안내
            📅 내일 챙길 것:
            - 권지혜 선생님: 내일 아침 pr코드 카톡 전송
            - 윤하율 학생: 내일 접종 후 등교
        """.trimIndent()
        val out = DailyDigest.parseAi(ai, nowMinute = 22 * 60)!!
        assertFalse(out, "✅" in out)                                        // 지난 09:00 일정은 ✅ 에서 빠지고
        assertTrue(out, "📝 오늘 있었던 일\n- 핑퐁로봇 · 09:00" in out)        // 📝 맨 앞(시각 순)
        assertTrue(out, Regex("09:00.*\\n.*09:31.*\\n.*11:01").containsMatchIn(out))
        assertEquals(out, 1, Regex("권지혜").findAll(out).count())          // 🙋 에만
        assertEquals(out, 1, Regex("김진수").findAll(out).count())
        assertTrue(out, "윤하율" in out)
    }

    @Test fun 업체_이름은_사람이_아님_앱_건수는_모두() {
        listOf("토스", "IBK기업은행", "뱅크샐러드", "Google Gemini", "토스뱅크", "국민카드").forEach { assertTrue(it, AdFilter.isBusinessName(it)) }
        listOf("김진수", "권지혜 선생님", "토스트", "6학년 동학년방", "류성하").forEach { assertFalse(it, AdFilter.isBusinessName(it)) }
        val b = Briefing(3, 0, "📋 12:00 요약", 30, """
💬 사람에게 온 메시지 (3개 방)
- 토스 (1건): 대출금 변동 및 신용점수 확인
- IBK기업은행 (1건): 신용카드 이용한도 상향 안내
- 김진수 (2건): 완주 기념 모임 제안

📦 앱 알림 (27건 · 요약 안 함)
- 메시지 7건 · 안내
- Telegram 5건 · 봇
- 시계 3건 · 알람
- 카카오톡 3건 · 채널
- 삼성 월렛 3건 · 결제
- 날씨 2건 · 비
- 지도 2건 · 경로
- 캘린더 2건 · 일정""".trim())
        val m = DailyDigest.material(listOf(b), "", emptyList())
        assertEquals(m.lines.toString(), listOf("김진수 (2건): 완주 기념 모임 제안"), m.lines.map { it.text })
        assertEquals(27, m.apps.values.sum())                               // 📦 줄의 앞 6개 앱만 더해 '23건'이던 것
    }
}
