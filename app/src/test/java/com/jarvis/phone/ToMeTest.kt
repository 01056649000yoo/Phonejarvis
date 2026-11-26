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

    private val room = "k|연구회"

    @Test fun 처음에는_예전_규칙과_같음() {
        assertTrue(ToMe.isToMe(setOf(ToMe.NAME), "김", room, w0))                       // 이름
        assertTrue(ToMe.isToMe(setOf(ToMe.MENTION), "김", room, w0))                    // 멘션
        assertTrue(ToMe.isToMe(setOf(ToMe.THREAD), "김", room, w0))                     // 내 말 바로 뒤
        assertTrue(ToMe.isToMe(setOf(ToMe.STUDENT), "김", room, w0))                    // 학생 이름
        assertFalse(ToMe.isToMe(setOf(ToMe.ASK, ToMe.SMALL), "김", room, w0))           // 작은 방의 물음만으로는 아직 아님
        assertFalse(ToMe.isToMe(emptySet(), "김", room, w0))
    }

    @Test fun 내가_자주_답하는_사람의_물음을_배움() {
        var wt = w0
        val f = setOf(ToMe.ASK, ToMe.SMALL)
        repeat(3) { wt = ToMe.adjust(wt, f, "김", room, toMe = true, answered = true) }
        assertFalse(ToMe.isToMe(f, "김", room, wt))                                     // 세 번까지는 아직
        wt = ToMe.adjust(wt, f, "김", room, toMe = true, answered = true)
        assertTrue(ToMe.isToMe(f, "김", room, wt))                                      // 네 번 답하면 그 사람의 물음을 잡음
        assertFalse(ToMe.isToMe(f, "이", room, wt))                                     // 다른 사람의 물음은 그대로 — 사람마다 배움
        assertFalse(ToMe.isToMe(setOf(ToMe.SMALL), "김", room, wt))                     // 그 사람의 잡담은 아님
        assertFalse(ToMe.isToMe(setOf(ToMe.ASK), "김", "k|큰 방", wt))                   // 큰 방의 물음도 아님
        // 더 답하면 여유가 쌓여, AI 가 한두 번 틀리게 '아니오'라고 해도 그대로 잡음
        repeat(2) { wt = ToMe.adjust(wt, f, "김", room, toMe = true, answered = true) }
        var noisy = wt
        repeat(4) { noisy = ToMe.adjust(noisy, f, "김", room, toMe = false) }
        assertTrue(ToMe.isToMe(f, "김", room, noisy))
        repeat(6) { noisy = ToMe.adjust(noisy, f, "김", room, toMe = false) }
        assertFalse(ToMe.isToMe(f, "김", room, noisy))                                  // 계속 '아니오'면 결국 내려감
        // AI 의 '예'만으로는 천천히(네 번으로는 모자람)
        var ai = w0
        repeat(30) { ai = ToMe.adjust(ai, f, "박", room, toMe = true) }
        assertFalse(ToMe.isToMe(f, "박", room, ai))                                     // AI 의 '예'만으로는 끝내 넘지 않음 — 내가 답해야
    }

    @Test fun 규칙이_자꾸_틀리는_방은_그_방에서만_끔() {
        var wt = w0
        val f = setOf(ToMe.STUDENT)                                                     // 다른 학교의 같은 이름 아이 이야기
        repeat(6) { wt = ToMe.adjust(wt, f, "닉", "k|잡담방", toMe = false) }
        assertTrue(ToMe.isToMe(f, "닉", "k|잡담방", wt))                                 // 여섯 번까지는 그대로(AI 가 몇 번 틀려도 안 흔들림)
        wt = ToMe.adjust(wt, f, "닉", "k|잡담방", toMe = false)
        assertFalse(ToMe.isToMe(f, "닉", "k|잡담방", wt))                                // 일곱 번째에 그 방에서 꺼짐
        assertTrue(ToMe.isToMe(f, "닉", "k|학부모방", wt))                               // 다른 방에서는 그대로
        assertTrue(ToMe.isToMe(setOf(ToMe.NAME), "닉", "k|잡담방", wt))                  // 이름을 부르면 그 방에서도 잡음
        assertEquals(wt, ToMe.adjust(wt, setOf(ToMe.NAME, ToMe.THREAD), "닉", "k|잡담방", toMe = false))   // 이름으로 잡은 것은 고치지 않음
        // 놓친 것이 '예'였으면 되돌아옴
        val back = ToMe.adjust(wt, f, "닉", "k|잡담방", toMe = true)
        assertTrue(ToMe.isToMe(f, "닉", "k|잡담방", back))
        // 범위를 넘지 않음
        repeat(50) { wt = ToMe.adjust(wt, f, "닉", "k|잡담방", toMe = false) }
        assertTrue((wt.rooms["k|잡담방"] ?: 0.0) >= ToMe.B_MIN)
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

    @Test fun 하루_정리_추천_고르기() {
        val now = 100L * 24 * 3600_000L
        val stats = mapOf(
            "k|6학년 동학년방" to ToMe.RoomStat(hits = 9, days = 4, yes = 2, no = 0, last = now, ans = 4),   // 여러 날, 내가 답도 함 → 놓치기 싫은 방
            "k|오픈채팅" to ToMe.RoomStat(hits = 5, days = 4, yes = 1, no = 5, last = now),                  // AI 가 거의 아니오 → 건수만
            "k|잡히기만 한 방" to ToMe.RoomStat(hits = 30, days = 9, last = now),                             // 잡히기만 하고 근거 없음 → 권하지 않음
            "k|가끔방" to ToMe.RoomStat(hits = 1, days = 1, last = now, ans = 1),
            "k|이미 정한 방" to ToMe.RoomStat(hits = 20, days = 9, last = now, ans = 9))
        val senders = mapOf("김민수 선생님" to 0.5, "박" to 0.1, "엄마" to 0.6)
        val r = ToMe.pickRecs(stats, senders, tiers = mapOf("k|이미 정한 방" to Relevance.ME), known = listOf("엄마"), asked = emptyMap(), now = now)
        assertEquals(listOf("6학년 동학년방" to 9), r.pin)
        assertEquals(listOf("오픈채팅" to 5), r.mute)
        assertEquals(listOf("김민수 선생님"), r.people)                               // 이미 묶음에 있는 사람·점수 낮은 사람은 빼고
        // 한 번 권한 것은 2주 동안 다시 권하지 않음
        val again = ToMe.pickRecs(stats, senders, emptyMap(), listOf("엄마"),
            asked = mapOf("pin|k|6학년 동학년방" to now - 24 * 3600_000L, "person|김민수 선생님" to now - 20L * 24 * 3600_000L), now = now)
        assertFalse(again.pin.any { it.first == "6학년 동학년방" })
        assertEquals(listOf("김민수 선생님"), again.people)                           // 20일 전에 권한 것은 다시
        assertEquals(ToMe.RoomStat(3, 2, 1, 0, 77L, 5), ToMe.roomStat(ToMe.RoomStat(3, 2, 1, 0, 77L, 5).pack()))
        assertEquals(ToMe.RoomStat(3, 2, 1, 0, 77L, 0), ToMe.roomStat("3|2|1|0|77"))   // 예전 기록(답한 수 없음)도 읽음
    }
}
