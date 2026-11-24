package com.jarvis.phone

import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-11-24 이어 보기: 앞 요약과 뒤 요약 사이의 이어짐 —
 * 꼬리 겹치기 · 단톡방 고른 줄 이어받기 · 방별 이어가기 메모 · 조용할 때 자르기. AI 를 더 부르지 않음
 */
class ContinuityTest {
    @Before fun on() { BriefingComposer.pickLines = true }

    private val base = 1_700_000_000_000L
    private fun k(room: String, who: String, text: String, min: Int) = NotiItem(0, "com.kakao.talk", "카카오톡", who, text, base + min * 60_000L, room)

    // ── 🤫 조용할 때 자르기 ──
    @Test fun 덩어리는_조용해진_자리에서_자르되_AI_횟수는_늘지_않음() {
        // 24개 → 15분 조용 → 16개: 30·10 이 아니라 24·16
        val gap = (0 until 24).map { k("방", "가", "말 $it", it) } + (0 until 16).map { k("방", "나", "뒷말 $it", 40 + it) }
        assertEquals(listOf(24, 16), BriefingComposer.quietChunks(gap).map { it.size })
        // 쉼 없이 오면 예전처럼 30개씩
        val flat = (0 until 40).map { k("방", "가", "말 $it", it) }
        assertEquals(listOf(30, 10), BriefingComposer.quietChunks(flat).map { it.size })
        // 일찍 자르면 덩어리가 하나 더 생길 때는 자르지 않음(60개는 두 번)
        val two = (0 until 23).map { k("방", "가", "말 $it", it) } + (0 until 37).map { k("방", "나", "뒷말 $it", 60 + it) }
        assertEquals(2, BriefingComposer.quietChunks(two).size)
        assertEquals(60, BriefingComposer.quietChunks(two).sumOf { it.size })
    }

    // ── 🧵 꼬리 겹치기 ──
    @Test fun 이어서_정리할_때_바로_앞_말을_참고로_준다() = runBlocking {
        val old = listOf(k("", "민준 어머님", "선생님 안녕하세요 이번 주 상담을 목요일 4시로 옮겨도 될까요? 수요일에는 병원 예약이 있어서 가기 어려울 것 같아요", 0))
        val saved = mutableMapOf<String, BriefingComposer.Cached>()
        BriefingComposer.compose(old, ask = { "요점: 상담을 목요일 4시로 옮겨도 되는지 물음\n요청: 상담 시간 변경 가능한지 알려 달라고 함" }, since = 100L, onCached = { saved[it.key] = it })
        assertEquals(1, saved.size)
        var prompt = ""
        BriefingComposer.compose(old + k("", "민준 어머님", "아 그리고 그날 동생도 같이 가도 괜찮을까요", 50),
            ask = { prompt = it; "요점: 목요일 4시 상담에 동생도 같이 가도 되는지 물음\n요청: 동생 동행 가능한지 알려 달라고 함" }, cache = saved, since = 100L)
        assertTrue(prompt, "[바로 앞 대화" in prompt && "목요일 4시로 옮겨도" in prompt.substringBefore("---"))
        assertTrue(prompt, "동생도 같이" in prompt.substringAfter("---") && "병원 예약" !in prompt.substringAfter("---"))
    }

    // ── 🧵 단톡방: 새 말에서만 고르고 앞서 고른 줄은 이어받기 ──
    @Test fun 단톡방은_새_말에서만_고르고_기한이_든_앞_줄은_남긴다() = runBlocking {
        val old = listOf(k("동학년", "김부장", "학예회 명단 금요일까지 올려 주세요", 0), k("동학년", "이선생", "리허설은 목요일 5교시예요", 5))
        val saved = mutableMapOf<String, BriefingComposer.Cached>()
        BriefingComposer.compose(old, ask = { "핵심: 1, 2\n부탁: 1" }, since = 100L, onCached = { saved[it.key] = it })
        assertEquals(2, saved.values.single().lines.size)
        val more = old + k("동학년", "박선생", "간식은 제가 준비할게요", 90) + k("동학년", "최선생", "장소는 강당으로 정했습니다", 95)
        var prompt = ""; var calls = 0
        val r = BriefingComposer.compose(more, ask = { prompt = it; calls++; "핵심: 2\n부탁: 없음" }, cache = saved, since = 100L, onCached = { saved[it.key] = it })
        assertEquals(1, calls)
        // 앞 대화는 참고 칸에만, 번호는 새 말에만
        assertTrue(prompt, "[앞 대화" in prompt && "금요일까지" in prompt.substringBefore("[이번 대화]") && "금요일까지" !in prompt.substringAfter("[이번 대화]"))
        assertTrue(prompt, "1. " in prompt.substringAfter("[이번 대화]") && "박선생: 간식은" in prompt.substringAfter("[이번 대화]"))
        // 앞서 고른 기한 줄 + 새로 고른 줄, 부탁도 그대로
        assertTrue(r.people, "금요일까지" in r.people && "목요일 5교시" in r.people && "강당으로 정했습니다" in r.people)
        assertTrue(r.people, "🙋 동학년" in r.people && "올려 달라고 함" in r.people)
        assertEquals(4, saved.values.single().count)
    }

    @Test fun 같은_말은_한_번_기한_없는_앞_줄은_새_줄에_자리를_내준다() {
        val conv = BriefingComposer.Conversation("카카오톡", "동학년", listOf(k("동학년", "김부장", "x", 0)))
        fun s(lines: List<String>, ask: String = "") = BriefingComposer.RoomSummary(conv, lines.joinToString(" / "), ask, ask.isNotEmpty(), byModel = true, lines = lines)
        val m = BriefingComposer.mergePicks(s(listOf("김부장: 회의는 내일 3시 교무실", "이선생: 요트가 따뜻해요")), s(listOf("김부장: 회의는 내일 3시 교무실에서", "박선생: 자료는 제가 뽑을게요")))
        assertEquals(listOf("김부장: 회의는 내일 3시 교무실에서", "박선생: 자료는 제가 뽑을게요"), m.lines)
    }

    // ── 🧵 30분 넘게 떨어진 대화 ──
    @Test fun 오래_떨어져도_답이거나_같은_일이면_이어진_대화() {
        val askOld = k("", "김부장", "학예회 명단 금요일까지 올려 주세요", -20 * 60)
        fun conv(text: String) = BriefingComposer.conversations(listOf(k("", "김부장", text, 0)))[0]
        // 어제 저녁 부탁 → 오늘 아침 답처럼 시작하는 말
        assertEquals(listOf(askOld), BriefingComposer.linked(conv("네 그럼 그렇게 할게요"), listOf(askOld)))
        // 같은 일 이야기
        assertEquals(listOf(askOld), BriefingComposer.linked(conv("학예회 명단 금요일까지 못 올릴 것 같아요 월요일에 드려도 될까요"), listOf(askOld)))
        // 답도 같은 일도 아니면 남남
        assertTrue(BriefingComposer.linked(conv("오늘 급식 맛있었어요 사진 보내 드릴게요"), listOf(askOld)).isEmpty())
        // 30분 안이면 늘
        val near = k("", "김부장", "사진 잘 받았어요", -10)
        assertEquals(listOf(near), BriefingComposer.linked(conv("오늘 급식 맛있었어요 사진 보내 드릴게요"), listOf(near)))
    }

    // ── 📝 방별 이어가기 메모 ──
    @Test fun 메모는_기한이_든_부탁과_정해진_것만_답했거나_사흘_지나면_지움() {
        val conv = BriefingComposer.conversations(listOf(
            k("동학년", "김부장", "학예회 명단 금요일까지 올려 주세요", 0),
            k("동학년", "이선생", "점심 뭐 먹을까요?", 5),
            k("동학년", "박선생", "리허설은 목요일 5교시로 변경됐어요", 10),
        ))[0]
        val memo = BriefingComposer.memoOf(conv)
        assertEquals(listOf("학예회 명단 금요일까지 올려 주세요", "리허설은 목요일 5교시로 변경됐어요"), memo.map { it.text })
        assertEquals("김부장", memo[0].who)
        val now = base + 3_600_000L
        assertEquals(2, BriefingComposer.memoCarry(emptyList(), memo, repliedAt = 0L, now = now).size)
        // 같은 말을 다시 뽑아도 한 번
        assertEquals(2, BriefingComposer.memoCarry(memo, memo, repliedAt = 0L, now = now).size)
        // 내가 답한 뒤에는 부탁은 지우고 정해진 것은 남김
        assertEquals(listOf("리허설은 목요일 5교시로 변경됐어요"), BriefingComposer.memoCarry(memo, emptyList(), repliedAt = base + 60_000L, now = now).map { it.text })
        // 사흘 지나면 모두
        assertTrue(BriefingComposer.memoCarry(memo, emptyList(), repliedAt = 0L, now = base + 4 * 24 * 3_600_000L).isEmpty())
    }

    @Test fun 메모는_다음_요약에서_참고로_들어가고_줄_끝에_표시() = runBlocking {
        // 어제 저녁(20시간 전) 부탁이 메모로 남아 있음 — beforeOf 가 메모를 NotiItem 꼴로 넘김
        val memo = k("", "김부장", "학예회 명단 금요일까지 올려 주세요", -20 * 60)
        val now = listOf(k("", "김부장", "그럼 명단은 월요일 아침까지로 미룰게요 양식은 메일로 다시 보냈으니 확인해 보시고 모르는 것 있으면 말씀 주세요", 0))
        var prompt = ""
        val r = BriefingComposer.compose(now, ask = { prompt = it; "요점: 명단 마감을 월요일 아침까지로 미룸\n요청: 양식 확인해 달라고 함" }, before = { listOf(memo) })
        assertTrue(prompt, "[앞 대화" in prompt && "금요일까지 올려 주세요" in prompt)
        assertTrue(r.people, "↪ 앞: “김부장: 학예회 명단 금요일까지 올려 주세요”" in r.people)
    }

    // ── 🤫 미리 요약은 조용해진 방만 ──
    @Test fun 말이_오가는_방뿐이면_미리_요약을_미룸() {
        val now = base + 60 * 60_000L
        val items = listOf(k("가방", "김", "말", 59), k("나방", "이", "말", 50), k("", "박", "말", 30))
        assertEquals(setOf("com.kakao.talk" to "가방"), BriefingComposer.talkingRooms(items, now, PreSummary.QUIET_MS))
        val c = PreSummary.Check(PreSummary.MIN_CHARS, PhoneLoad.State(PowerManager.THERMAL_STATUS_NONE, 80, false, false), screenOn = false, busy = false, hasModel = true, talking = true)
        assertEquals(PreSummary.TALKING, PreSummary.blockReason(c))
        assertEquals(PreSummary.READY_DELAY_MIN, PreSummary.delayMin(c))   // 예약은 함 — 울릴 때 조용해졌는지 다시 봄
        assertFalse(PreSummary.blockReason(c.copy(talking = false)) == PreSummary.TALKING)
    }
}
