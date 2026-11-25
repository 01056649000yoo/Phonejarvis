package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🎯 나와의 거리(2026-11-25 선생님: 하루 정리에 잡담이 너무 많음 — 나와 관련된 것만). 사례는 그날 하루 정리에서 */
class RelevanceTest {
    private val names = listOf("친절한 유쌤", "유쌤", "유승현")
    private val noRoster = { _: BriefingComposer.Conversation -> false }
    private var id = 0L
    private fun msg(room: String, who: String, text: String, pkg: String = "com.kakao.talk") =
        NotiItem(++id, pkg, "카카오톡", who, text, 1_000_000L + id * 60_000L, room)
    private fun conv(room: String, vararg m: NotiItem) = BriefingComposer.Conversation("카카오톡", room.ifEmpty { m.first().title }, m.toList())

    @Test fun 나를_부르거나_멘션한_말() {
        assertTrue(Relevance.addressesMe("[나를 멘션] @친절한유쌤(끄적끄적아지트) 응답바랍니다.", names))
        assertTrue(Relevance.addressesMe("유쌤이 지금 저한테 13주 걸리는 작업을 시켰습니다", names))
        assertTrue(Relevance.addressesMe("@친절한유쌤 이거 봐 주세요", names))       // 띄어 쓴 별명을 붙여 불러도
        assertFalse(Relevance.addressesMe("유승현님 오늘만 특가 혜택", names))         // 부르는 말뿐인 광고는 아님
        assertFalse(Relevance.addressesMe("주택 경매 증가와 세수 급감", names))
    }

    @Test fun 여러_사람에게_돌린_말() {
        assertTrue(Relevance.isBroadcast("AI평가 관련해서 관심있으신 선생님들 참여 부탁드립니다 감사합니다!!!"))
        assertTrue(Relevance.isBroadcast("혹시 마이크로비트, 마퀸, 허스키렌즈 사용해보신 선생님 계실지요?"))
        assertTrue(Relevance.isBroadcast("지금 두 가지 행동으로 현장의 뜻을 국회에 전달해 주세요. 서명 참여 부탁드립니다"))
        assertFalse(Relevance.isBroadcast("공문 발송 부탁드려요. 창평고 공문도 함께 보내 주세요"))
        assertFalse(Relevance.isBroadcast("흑백으로 인쇄 누르고 pdf 저장하면 될걸?"))
    }

    @Test fun 방마다_거리() {
        val joined = setOf("6학년 동학년방", "사랑하는 마누라")
        fun lv(c: BriefingComposer.Conversation, override: Int = Relevance.AUTO, sms: Boolean = false) =
            Relevance.level(c, names, noRoster, { it in joined }, override, sms)
        // 단톡방: 나를 부르면 1, 내가 말한 방이면 2, 그 밖은 3
        assertEquals(Relevance.ME, lv(conv("끄적끄적아지트", msg("끄적끄적아지트", "햅삐", "[나를 멘션] @친절한유쌤 응답바랍니다."))))
        assertEquals(Relevance.JOINED, lv(conv("6학년 동학년방", msg("6학년 동학년방", "김", "학예회 금요일"), msg("6학년 동학년방", "박", "네"))))
        assertEquals(Relevance.OTHER, lv(conv("라이프오브파이", msg("라이프오브파이", "A", "INK REALM 오픈소스 공개"), msg("라이프오브파이", "B", "대박"))))
        // 1:1: 문자·내가 답한 사람·나를 부름이면 1, 처음 보는 카톡 1:1(오픈채팅일 수 있음)은 2
        assertEquals(Relevance.ME, lv(conv("", msg("", "사랑하는 마누라", "학원비는 12월에"))))
        assertEquals(Relevance.JOINED, lv(conv("", msg("", "크림치즈컵케이크", "계정 이관 방법이 있을지요?"))))
        assertEquals(Relevance.ME, lv(conv("", msg("", "01012345678", "내일 상담 가능할까요?", "com.samsung.android.messaging")), sms = true))
        // 방 고르기에서 직접 정한 값이 먼저
        assertEquals(Relevance.OTHER, lv(conv("", msg("", "사랑하는 마누라", "학원비")), override = Relevance.OTHER))
        assertEquals(Relevance.ME, lv(conv("라이프오브파이", msg("라이프오브파이", "A", "공개")), override = Relevance.ME))
    }

    @Test fun 답할_것은_나에게_한_물음만() {
        val c = conv("끄적끄적아지트", msg("끄적끄적아지트", "A", "다들 내일 몇 시에 와요?"), msg("끄적끄적아지트", "햅삐", "@친절한유쌤 PRD 검토해 주실 수 있나요?"))
        assertTrue(Relevance.replyOk(c, Relevance.ME, names, noRoster))
        val other = conv("끄적끄적아지트", msg("끄적끄적아지트", "A", "다들 내일 몇 시에 와요?"), msg("끄적끄적아지트", "B", "유쌤 오늘 강의 좋았어요"))
        assertFalse(Relevance.replyOk(other, Relevance.ME, names, noRoster))      // 나를 부른 말은 물음이 아님
        val oneToOne = conv("", msg("", "사랑하는 마누라", "흑백으로 인쇄 누르고 pdf 저장하면 될걸?"))
        assertTrue(Relevance.replyOk(oneToOne, Relevance.ME, names, noRoster))
        val broadcast = conv("", msg("", "시무룩한 튜브", "AI평가 관련해서 관심있으신 선생님들 참여 부탁드립니다"))
        assertFalse(Relevance.replyOk(broadcast, Relevance.ME, names, noRoster))
        assertFalse(Relevance.replyOk(oneToOne, Relevance.JOINED, names, noRoster))   // 1단계가 아니면 🙋 아님
    }

    @Test fun 그_밖의_방은_AI_없이_이름과_건수만() = runBlocking {
        val items = listOf(
            msg("라이프오브파이", "A", "INK REALM 오픈소스 공개했어요 다들 써 보세요"), msg("라이프오브파이", "B", "저작권 문제는 없나요?"),
            msg("끄적끄적아지트", "햅삐", "[나를 멘션] @친절한유쌤 내일까지 PRD 검토해 주실 수 있나요?"), msg("끄적끄적아지트", "C", "저도 볼게요 금요일까지"),
        )
        var asked = 0
        val levels = { c: BriefingComposer.Conversation -> if (c.room == "끄적끄적아지트") Relevance.ME else Relevance.OTHER }
        val r = BriefingComposer.compose(items, ask = { asked++; "핵심: 1\n부탁: 1" }, focus = true, levels = levels,
            replyAllowed = { c -> Relevance.replyOk(c, levels(c), names, noRoster) })
        assertEquals(1, asked)                                                     // 그 밖의 방에는 AI 를 쓰지 않음
        assertTrue(r.people, "그 밖의 방: 라이프오브파이 2건" in r.people)
        assertTrue(r.people, "🙋 끄적끄적아지트" in r.people)
        assertEquals(Relevance.OTHER, r.rooms.first { it.conv.room == "라이프오브파이" }.level)
    }
}
