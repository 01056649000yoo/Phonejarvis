package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** 정각 요약에 '준비 뒤 온 알림'을 이어 붙일 때 구간 줄·마감 칸은 한 번만 (2026-11-22) */
class TopUpPartTest {
    @Test fun 구간줄과_마감칸은_빼고_나머지만() {
        val body = "🕘 12:40 뒤 ~ 12:45에 온 알림\n\n📌 오늘·내일 마감 (1건)\n- [오늘] 회의\n\n💬 사람에게 온 메시지 (1개 방)\n- 학부모: 등교 문의\n\n📦 앱 알림 (1건 · 요약 안 함)\n- 쿠팡 1건"
        assertEquals("💬 사람에게 온 메시지 (1개 방)\n- 학부모: 등교 문의\n\n📦 앱 알림 (1건 · 요약 안 함)\n- 쿠팡 1건", Briefings.topUpPart(body))
    }
    @Test fun 마감만_있으면_안내() {
        assertEquals("(새로 정리할 것이 없어요)", Briefings.topUpPart("🕘 a\n\n📌 마감\n- x"))
    }
}
