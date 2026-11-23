package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-24 요약 화면에서 나온 것들 */
class SummaryPolishTest {
    private fun n(title: String, text: String, t: Long, room: String = "", app: String = "카카오톡", pkg: String = "com.kakao.talk") =
        NotiItem(t, pkg, app, title, text, t, room)

    @Test fun 이름을_두_번_말하지_않음() {
        assertEquals("2주 스윙 후 0.5% 수익률을 아쉬워함", BriefingComposer.dropName("햅삐가 2주 스윙 후 0.5% 수익률을 아쉬워함", "햅삐"))
        assertEquals("비트코인 손실 이야기를 함", BriefingComposer.dropName("서울_개복치(캔디)가 비트코인 손실 이야기를 함", "서울_개복치(캔디)"))
        assertEquals("회의 4시로", BriefingComposer.dropName("김민수 선생님께서 회의 4시로", "김민수 선생님"))
        assertEquals("햅삐네 가게 이야기", BriefingComposer.dropName("햅삐네 가게 이야기", "햅삐"))      // 조사가 아니면 그대로
    }

    @Test fun 언급함은_부드럽게() {
        assertEquals("과거 상승률을 말함", BriefingComposer.soften("과거 상승률을 언급함"))
        assertEquals("손실에 대해 이야기함", BriefingComposer.soften("손실에 대해 언급함"))
    }

    @Test fun 글자_없는_물음은_요청이_아님() {
        val conv = BriefingComposer.Conversation("카카오톡", "울산 혁명", listOf(n("울산 혁명", "부산교대 아웃풋 영상이에요", 1), n("울산 혁명", "??", 2)))
        val r = BriefingComposer.parse(conv, "요점: 부산교대 아웃풋 영상을 공유함\n요청: 없음")
        assertEquals("", r.ask)
        assertFalse(r.needReply)
    }

    @Test fun 부르는_말이_붙은_인사도_맞장구() {
        assertTrue(BriefingComposer.isFiller("감사합니다 선배님 !!"))
        assertTrue(BriefingComposer.isFiller("네 선생님"))
        assertFalse(BriefingComposer.isFiller("선배님 내일 몇 시에 가요?"))
    }

    @Test fun 인사뿐인_방은_그_밖의_방에_캘린더는_일정_그대로() = runBlocking {
        val ack = listOf(n("경북 5학년 3반", "감사합니다 선배님 !!", 1), n("경북 5학년 3반", "네 선배님", 2), n("경북 5학년 3반", "ㅎㅎ", 3))
        val cal = listOf(n("모두모임", "내일 오전 9:00", 4, app = "캘린더", pkg = "com.samsung.android.calendar"),
            n("진남초 모두모임", "내일 오후 3:00", 5, app = "캘린더", pkg = "com.samsung.android.calendar"))
        var asked = 0
        val r = BriefingComposer.compose(ack, ask = { asked++; "요점: 엉뚱함\n요청: 없음" },
            groups = listOf(BriefingComposer.Group(BriefingComposer.WORK_TITLE, cal.map { it.app to it })))
        assertEquals(0, asked)                                                      // AI 를 부르지 않음
        assertTrue(r.people, "- 그 밖의 방: 경북 5학년 3반 3건" in r.people)
        assertTrue(r.work, "모두모임 (내일 오전 9:00), 진남초 모두모임 (내일 오후 3:00)" in r.work)
        assertFalse(r.work, "언급" in r.work)
    }
}
