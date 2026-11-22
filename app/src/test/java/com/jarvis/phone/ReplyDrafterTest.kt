package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ✉️ 문자 답장 초안 (2026-11-22): 모델 답 정리·질문 모양 */
class ReplyDrafterTest {
    @Test fun 머리말과_따옴표를_떼고_답장만() {
        assertEquals("어머님, 안녕하세요.\n[시간]에 출발합니다.", ReplyDrafter.clean("답장 초안:\n\"어머님, 안녕하세요.\n[시간]에 출발합니다.\""))
    }
    @Test fun 질문에_보낸사람과_문자와_규칙() {
        val p = ReplyDrafter.prompt("01055551234", listOf(NotiItem(0, "com.google.android.apps.messaging", "메시지", "01055551234", "도시락 싸야 하나요?", 1_700_000_000_000L)))
        assertTrue(p.contains("보낸 사람: 01055551234") && p.contains("도시락 싸야 하나요?") && p.contains("[ ]로 비워"))
    }
    @Test fun 문자_앱만() {
        assertTrue(Sms.isSms("com.samsung.android.messaging") && !Sms.isSms("com.kakao.talk"))
    }
}
