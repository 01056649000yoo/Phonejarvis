package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** 위젯은 요약만: 🙋·💬 칸은 그대로, 나머지 칸(마감·업무·앱 알림 등)은 제목만 한 줄 */
class WidgetSummaryTest {
    @Test fun 요약_칸만_보이고_나머지는_제목_한_줄() {
        val text = """
            📌 오늘·내일 마감 (2건)
            - [오늘] 학예회 명단 제출 (동학년방)
            - [내일] 연수 결과

            🙋 답장할 연락 (1)
            - 서윤 어머니 · 07:42: 2교시 후 등교 가능한지

            💬 사람에게 온 메시지 (2개 방)
            - 동학년방 (5건): 3시 협의회 2층

            📦 앱 알림 (4건 · 요약 안 함)
            - 쿠팡 2건 · 배송 출발
        """.trimIndent()
        assertEquals(listOf(
            "🙋 답장할 연락 (1)", "- 서윤 어머니 · 07:42: 2교시 후 등교 가능한지",
            "💬 사람에게 온 메시지 (2개 방)", "- 동학년방 (5건): 3시 협의회 2층",
            "(📌 오늘·내일 마감 (2건) · 📦 앱 알림 (4건 · 요약 안 함) — 눌러서 보기)",
        ), SummaryWidget.summaryOnly(text))
    }
}
