package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🧭 나에게 한 말 — 점수로 판정하고 쓸수록 다듬기(2026-11-26 선생님) */
class ToMeTest {
    private val w0 = ToMe.Weights()
    private val t0 = 10_000_000L
    private fun m(id: Long, who: String, text: String, min: Int) = NotiItem(id, "com.kakao.talk", "카카오톡", who, text, t0 + min * 60_000L, "연구회")
    private fun conv(vararg items: NotiItem) = BriefingComposer.Conversation("카카오톡", "연구회", items.toList())
    private fun scorer(wt: ToMe.Weights = w0, saidAt: Long = 0L, saidText: String = "") =
        ToMe.Scorer(wt, listOf("유쌤", "유승현"), listOf("김민준"), { saidAt }, { saidText })

    @Test fun 처음_가중치는_예전_규칙과_같음() {
        assertTrue(ToMe.isToMe(setOf(ToMe.NAME), "김", w0))                       // 이름
        assertTrue(ToMe.isToMe(setOf(ToMe.MENTION), "김", w0))                    // 멘션
        assertTrue(ToMe.isToMe(setOf(ToMe.THREAD), "김", w0))                     // 내 말 바로 뒤
        assertTrue(ToMe.isToMe(setOf(ToMe.STUDENT), "김", w0))                    // 학생 이름
        assertFalse(ToMe.isToMe(setOf(ToMe.ASK, ToMe.SMALL), "김", w0))           // 작은 방의 물음만으로는 아직 아님
        assertFalse(ToMe.isToMe(emptySet(), "김", w0))
    }

    @Test fun 놓친_말을_배우면_그_사람의_다음_물음을_잡음() {
        var wt = w0
        val f = setOf(ToMe.ASK, ToMe.SMALL)
        // 내가 '김'의 물음에 여러 번 답함 → 조금씩 올라가 문턱을 넘음
        repeat(4) { wt = ToMe.adjust(wt, f, "김", toMe = true) }
        assertTrue(ToMe.isToMe(f, "김", wt))
        assertFalse(ToMe.isToMe(setOf(ToMe.ASK), "처음 보는 사람", wt))           // 다른 사람의 물음은 그대로(작은 방도 아님)
        // 이미 맞게 잡는 말은 더 올리지 않음
        assertEquals(wt, ToMe.adjust(wt, f, "김", toMe = true))
    }

    @Test fun 잘못_잡은_단서는_내리되_이름은_내리지_않음() {
        var wt = w0
        repeat(5) { wt = ToMe.adjust(wt, setOf(ToMe.THREAD), "수다쟁이", toMe = false) }
        assertFalse(ToMe.isToMe(setOf(ToMe.THREAD), "수다쟁이", wt))              // 내 말 뒤라는 것만으로는 이제 아님
        assertTrue(ToMe.isToMe(setOf(ToMe.THREAD, ToMe.ASK), "김", wt))            // 묻는 말이면 여전히
        val named = ToMe.adjust(wt, setOf(ToMe.NAME, ToMe.THREAD), "수다쟁이", toMe = false)
        assertEquals(wt, named)                                                    // 이름으로 잡은 것은 고치지 않음
        assertTrue(ToMe.isToMe(setOf(ToMe.NAME), "수다쟁이", named))
        // 가중치는 범위를 넘지 않음
        repeat(50) { wt = ToMe.adjust(wt, setOf(ToMe.THREAD), "수다쟁이", toMe = false) }
        assertTrue(wt.of(ToMe.THREAD) >= ToMe.W_MIN && (wt.senders["수다쟁이"] ?: 0.0) >= ToMe.S_MIN)
    }

    @Test fun 말마다_단서_뽑기() {
        val c = conv(m(1, "김", "유쌤 혹시 시간 되세요?", 0), m(2, "박", "점심 뭐 먹죠", 1), m(3, "김", "내일 연수 자료 때문에요", 2),
            m(4, "이", "김민준 오늘 조퇴했나요?", 3))
        val sc = scorer()
        assertTrue(ToMe.NAME in sc.features(c, c.items[0]) && ToMe.ASK in sc.features(c, c.items[0]))
        assertTrue(ToMe.FOLLOW in sc.features(c, c.items[2]))                      // 나를 부른 사람이 이어서 한 말
        assertTrue(ToMe.STUDENT in sc.features(c, c.items[3]))
        assertTrue(sc.toMe(c, c.items[0]) && sc.toMe(c, c.items[2]) && sc.toMe(c, c.items[3]))
        assertFalse(sc.toMe(c, c.items[1]))                                         // 점심 이야기는 아님
        assertTrue(ToMe.SMALL in sc.features(c, c.items[1]))                        // 세 사람뿐인 작은 방
    }

    @Test fun 내가_답한_말은_정답_예() {
        val c = conv(m(1, "박", "점심 뭐 먹죠", 0), m(2, "김", "발표 자료 금요일까지 될까요?", 5), m(3, "박", "ㅋㅋ", 6))
        val ex = ToMe.answered(c, t0 + 7 * 60_000L, scorer())                       // 7분에 내가 답함
        assertTrue(ex.isNotEmpty() && ex.all { it.toMe })
        assertTrue(ex.any { it.sender == "김" && ToMe.ASK in it.features })
        assertTrue(ToMe.answered(c, 0L, scorer()).isEmpty())                        // 내가 말한 적 없으면 없음
        assertTrue(ToMe.answered(c, t0 + 60 * 60_000L, scorer()).isEmpty())         // 한참 뒤에 한 말은 답이 아님
    }

    @Test fun AI_답_읽기() {
        assertEquals(true, ToMe.parseYesNo("예"))
        assertEquals(true, ToMe.parseYesNo("네, 맞습니다"))
        assertEquals(false, ToMe.parseYesNo("아니오."))
        assertEquals(false, ToMe.parseYesNo("- 아니요"))
        assertNull(ToMe.parseYesNo("잘 모르겠습니다"))
        val p = ToMe.askPrompt("연구회", "유쌤", listOf("박: 점심 뭐 먹죠"), "김", "발표 자료 금요일까지 될까요?")
        assertTrue(p, "'유쌤'에게 한 말이야" in p && "▶ 김: 발표 자료" in p)
    }

    @Test fun 나를_부르는_꼴_추천() {
        val texts = listOf("승현쌤 발표 자료 금요일까지 될까요?", "승현쌤 내일 회의 몇 시죠", "김쌤은 어떻게 생각하세요?", "유 부장님 결재 부탁드려요", "유 부장님 확인 부탁드립니다")
        val rec = ToMe.nicknames(texts, listOf("유승현"))
        assertTrue(rec.toString(), "승현쌤" in rec && "유부장님" in rec)
        assertFalse(rec.toString(), "김쌤" in rec)                                   // 한 번뿐인 것은 추천하지 않음
        assertTrue(ToMe.nicknames(texts, listOf("승현쌤", "유부장님")).isEmpty())     // 이미 넣은 것은 뺌
    }
}
