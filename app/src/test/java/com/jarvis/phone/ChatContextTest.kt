package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 2026-11-24 카톡 대책: 말 차례 묶기 · 단톡방은 고르기만 · 앞 대화 참고 · ↪ 이어서 · 답한 🙋 내리기 */
class ChatContextTest {
    @Before fun on() { BriefingComposer.pickLines = true }

    private val base = 1_700_000_000_000L
    private fun k(room: String, who: String, text: String, min: Int) = NotiItem(0, "com.kakao.talk", "카카오톡", who, text, base + min * 60_000L, room)

    // '그룹채팅 4' 실제 대화(김진수의 세 마디는 3분 안이라 한 차례)
    private val chat = listOf(
        k("그룹채팅 4", "최성우", "요트가 따뜻해요", 0), k("그룹채팅 4", "최성우", "안주도 조음", 0),
        k("그룹채팅 4", "김진수", "대박ㅋㅋ요트도 크네", 2),
        k("그룹채팅 4", "김진수", "교감샘도드시냐", 3), k("그룹채팅 4", "김진수", "성우 유예를위해 오늘 달려야겠네", 3),
        k("그룹채팅 4", "최성우", "안드시는거같아요", 3), k("그룹채팅 4", "최성우", "일단 내팽개쳤어요", 3),
    )

    @Test fun 같은_사람의_잇따른_말은_한_차례로() {
        val conv = BriefingComposer.conversations(chat)[0]
        val ts = BriefingComposer.turns(conv, conv.items)
        assertEquals(3, ts.size)
        assertEquals("대박ㅋㅋ요트도 크네 / 교감샘도드시냐 / 성우 유예를위해 오늘 달려야겠네", ts[1].text)
    }

    @Test fun 단톡방은_AI_가_고른_줄의_원문() = runBlocking {
        var prompt = ""
        val r = BriefingComposer.compose(chat, ask = { p -> prompt = p; "핵심: 1, 3\n부탁: 없음" })
        assertTrue(prompt, "3. " in prompt && "교감샘도드시냐 / 성우 유예를위해" in prompt)
        // 1·3 은 둘 다 최성우지만 사이 줄이 빠졌으니 이름을 다시 적음
        assertTrue(r.people, "최성우: 요트가 따뜻해요 / 안주도 조음 / 최성우: 안드시는거같아요 / 일단 내팽개쳤어요" in r.people)
        // 번호를 못 읽으면 마지막 두 차례
        val loose = BriefingComposer.compose(chat, ask = { "요트 이야기를 함" })
        assertTrue(loose.people, "김진수: 대박ㅋㅋ요트도 크네" in loose.people && "최성우: 안드시는거같아요 / 일단 내팽개쳤어요" in loose.people)
    }

    @Test fun 혼잣말은_부탁으로_고르지_않음() {
        val conv = BriefingComposer.conversations(chat + k("그룹채팅 4", "김진수", "내일 회의 자료 올려 주실 수 있나요?", 9))[0]
        val ts = BriefingComposer.pickTurns(conv)
        val r = BriefingComposer.parsePicks(conv, ts, "핵심: 1\n부탁: 1, ${ts.size}")   // 1 은 '요트가 따뜻해요'(부탁·물음 아님)
        assertEquals("김진수: 내일 회의 자료 올려 달라고 함", r.ask)
    }

    @Test fun 앞_대화는_참고로_주고_이어진_줄에_표시() = runBlocking {
        val now = listOf(k("", "민준이 아빠", "ㅇㅇ 7시에 보자 주차는 지하에 하면 돼 길 막히면 연락할게 늦으면 먼저 들어가 있어 그리고 아까 말한 책도 챙겨 갈게", 0))
        var prompt = ""
        val r = BriefingComposer.compose(now, ask = { p -> prompt = p; "요점: 7시에 보자고 함\n요청: 없음" },
            before = { listOf(k("", "민준이 아빠", "오늘 볼테르 갈래?", -10)) })
        assertTrue(prompt, "[앞 대화" in prompt && "오늘 볼테르 갈래?" in prompt)
        assertTrue(r.people, "↪ 앞: “민준이 아빠: 오늘 볼테르 갈래?”" in r.people)
        // 30분보다 오래전이어도 앞이 물음이고 새 말이 답처럼 시작하면 이어진 대화(v1.14 이어 보기)
        val answer = BriefingComposer.compose(now, ask = { "요점: 7시에 보자고 함\n요청: 없음" },
            before = { listOf(k("", "민준이 아빠", "오늘 볼테르 갈래?", -45)) })
        assertTrue(answer.people, "↪ 앞: “민준이 아빠: 오늘 볼테르 갈래?”" in answer.people)
        // 30분보다 오래전이고 물음도 답도 같은 일도 아니면 이어진 대화가 아님
        val other = listOf(k("", "민준이 아빠", "내일 7시에 보자 주차는 지하에 하면 돼 길 막히면 연락할게 늦으면 먼저 들어가 있어 그리고 아까 말한 책도 챙겨 갈게", 0))
        val old = BriefingComposer.compose(other, ask = { "요점: 7시에 보자고 함\n요청: 없음" },
            before = { listOf(k("", "민준이 아빠", "사진 잘 받았어", -45)) })
        assertFalse(old.people, "↪" in old.people)
    }

    @Test fun 답한_물음은_하루_정리에서_답할_연락으로_남지_않음() {
        val b = Briefing(time = base, fromTime = base - 3_600_000L, label = "지금 정리", count = 2,
            text = "💬 대화 요약 (2개 방 · 🙋 답장 2)\n- 🙋 민준 어머님 · 13:00: 내일 도시락 싸야 하는지 물음\n- 🙋 김진수 · 13:05: \"차 놔두고 왔어요?\"라고 물음")
        val text = DailyDigest.compose(listOf(b), "", replied = mapOf("김진수" to base + 60_000L, "민준 어머님" to base - 60_000L))
        assertTrue(text, "🙋 민준 어머님" in text)                 // 요약보다 먼저 답한 것은 그대로 남음
        assertFalse(text, "🙋 김진수" in text)
        assertTrue(text, "김진수 · 13:05" in text && "✔ 답함" in text)
    }
}
