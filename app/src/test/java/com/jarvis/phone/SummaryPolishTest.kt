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

    @Test fun 서비스_자동_메일은_업체() {
        listOf("GitHub", "Notion", "Zoom", "Slack").forEach { assertTrue(it, AdFilter.isBusinessName(it)) }
        assertFalse(AdFilter.isBusinessName("김민수 선생님"))
    }

    @Test fun 줄일_때_낱말_가운데서_자르지_않음() {
        val s = "[repo/Phonejarvis] Run failed: Build APK - feat/identity (d39140c) [링크] 자세한 내용은 여기"
        val c = BriefingComposer.clip(s, 70)
        assertTrue(c, c.endsWith("…") && !c.contains("[…") && !c.endsWith("[…"))
        assertTrue(c, c.length <= 71)
        assertEquals("짧은 말", BriefingComposer.clip("짧은 말", 80))
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

    @Test fun 중요한_대화만_요약_잡담은_이름만() = runBlocking {
        val items = listOf(
            n("서울_개복치(캔디)", "비트코인 손실 났다가 본전 왔어", 1), n("서울_개복치(캔디)", "이더리움도 올랐네", 2),        // 사적 잡담 1:1
            n("김지우맘", "선생님 내일 준비물이 뭔가요?", 3),                                                           // 선생님을 부름
            n("친구", "오늘 저녁 같이 먹을래?", 4),                                                                    // 1:1 물음
            n("이선생", "내일까지 각 반 명단 내 주세요", 5, room = "6학년 동학년"),                                       // 단톡 + 기한 있는 부탁
            n("박", "젠슨 황 컴백 ㄷㄷ", 6, room = "투자 수다방"), n("최", "도파민 솟는다", 7, room = "투자 수다방"),       // 단톡 잡담
        )
        val seen = mutableListOf<String>()
        // 명부에 없는 사람들(compose 기본값은 모든 방을 명부로 봄 — 앱은 늘 실제 명부 규칙을 넘김)
        val r = BriefingComposer.compose(items, ask = { p -> seen += p; "요점: 정리함\n요청: 없음" }, replyAllowed = { false }, focus = true)
        assertTrue(seen.toString(), seen.none { "비트코인" in it || "젠슨" in it })      // 잡담 방은 AI 도 안 부름
        val other = r.people.lines().first { it.startsWith("- 그 밖의 방") }
        assertTrue(other, "서울_개복치(캔디) 2건" in other && "투자 수다방 2건" in other)
        listOf("김지우맘", "친구", "6학년 동학년").forEach { assertFalse(it, it in other); assertTrue(it, it in r.people) }
        // 끄면 예전처럼 모든 방을 AI 로
        val all = mutableListOf<String>()
        BriefingComposer.compose(items, ask = { p -> all += p; "요점: 정리함\n요청: 없음" }, focus = false)
        assertTrue(all.toString(), all.any { "비트코인" in it } && all.any { "젠슨" in it })
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
