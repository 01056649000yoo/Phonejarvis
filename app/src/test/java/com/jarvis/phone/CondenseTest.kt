package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-23 선생님: 1건이면 원문 그대로 나옴 → AI 없이 물음·부탁을 짧은 말로, 숫자 검사에 걸리면 AI 에 한 번 더 */
class CondenseTest {
    private var t = 1_700_000_000_000L
    private fun kakao(room: String, who: String, text: String) = NotiItem(0, "com.kakao.talk", "카카오톡", who, text, t++, room)

    @Test fun 물음과_부탁을_짧은_말로() {
        assertEquals("내일 도시락 싸야 하는지 물음", BriefingComposer.condense("선생님 안녕하세요 내일 도시락 싸야 하나요?"))
        assertEquals("2교시 후에 등교해도 되는지 물음", BriefingComposer.condense("2교시 후에 등교해도 될까요?"))
        assertEquals("택배 문 앞에 둘지 물음", BriefingComposer.condense("택배 문 앞에 둘까요?"))
        assertEquals("회신서 한 장 더 보내 달라고 함", BriefingComposer.condense("회신서 한 장 더 보내 주실 수 있나요?"))
        assertEquals("체육은 쉬게 해 달라고 함", BriefingComposer.condense("체육은 쉬게 해 주시면 감사하겠습니다."))
        assertEquals("자료 확인 부탁함", BriefingComposer.condense("자료 확인 부탁드려요~"))
        assertEquals("상담이 언제인지 물음", BriefingComposer.condense("상담이 언제예요?"))
        assertEquals("내일 비 온대요.", BriefingComposer.condense("내일 비 온대요."))   // 물음·부탁이 아니면 그대로
        assertEquals("체험학습 신청서 다시 내야 하는지 물음",
            BriefingComposer.askSentence("선생님 지호 엄마예요. 지호가 내일 체험학습에 못 가요. 체험학습 신청서 다시 내야 하나요?"))
    }

    @Test fun 한_마디_물음도_원문_대신_짧은_말() = runBlocking {
        val r = BriefingComposer.compose(listOf(kakao("", "민준 어머님", "선생님 내일 도시락 챙겨야 하나요?")), ask = { error("부르면 안 됨") })
        assertTrue(r.people, r.people.contains("민준 어머님") && r.people.contains("내일 도시락 챙겨야 하는지 물음") && "하나요?" !in r.people)
    }

    @Test fun 원문에_없는_숫자를_쓰면_한_번_더_묻는다() = runBlocking {
        val items = listOf(kakao("", "지호 어머니", "선생님 죄송한데 제가 그날 일이 생겨서요 수요일 상담 4시로 바꿔도 될까요?"), kakao("", "지호 어머니", "안 되면 목요일도 괜찮아요 편하신 시간 알려 주시면 맞출게요 번거롭게 해 드려서 정말 죄송합니다"))
        val prompts = mutableListOf<String>()
        val r = BriefingComposer.compose(items, ask = { p -> prompts += p
            if (prompts.size == 1) "요점: 수요일 상담을 3시로 바꾸고 싶다고 함\n요청: 상담 시간 확인해 달라고 함" else "요점: 수요일 상담을 4시로 바꾸고 싶다고 함\n요청: 바꿔도 되는지 알려 달라고 함" })
        assertEquals(2, prompts.size); assertTrue(prompts[1].contains("메시지에 없는 숫자는 쓰지 마"))
        assertTrue(r.people, r.people.contains("4시로 바꾸고 싶다고 함") && "3시" !in r.people)
    }
}
