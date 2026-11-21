package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 요약 규칙: 대화방마다 세 줄, 짧은 한 마디는 모델 없이, 앱 알림은 AI 없이 건수 */
class BriefingComposerTest {
    private var t = 1_700_000_000_000L
    private fun kakao(room: String, who: String, text: String) = NotiItem(0, "com.kakao.talk", "카카오톡", who, text, t++ * 1, room)
    private fun app(name: String, pkg: String, title: String, text: String = "") = NotiItem(0, pkg, name, title, text, t++)

    @Test fun 사람과_앱_알림을_나누고_앱은_모델에_보내지_않는다() = runBlocking {
        val asked = mutableListOf<String>()
        val items = listOf(
            kakao("6학년 동학년방", "김선생", "학예회 명단 금요일까지 부탁드려요"),
            kakao("6학년 동학년방", "이선생", "순서는 3반, 1반, 2반으로 정했습니다. 리허설은 목요일 5교시예요."),
            app("쿠팡", "com.coupang.mobile", "배송 출발", "오늘 도착 예정"),
            app("쿠팡", "com.coupang.mobile", "(광고) 오늘만 특가"),
        )
        val r = BriefingComposer.compose(items, ask = { p -> asked += p; "요점: 학예회 명단을 금요일까지 내고 순서는 3반·1반·2반\n요청: 학예회 명단을 금요일까지 내 주세요\n답장: 필요" })
        assertEquals(1, asked.size)
        assertTrue(asked[0].contains("김선생: 학예회 명단") && !asked[0].contains("쿠팡"))
        assertTrue(r.people.startsWith("🙋 답장할 연락 (1)"))
        assertTrue(r.people.contains("6학년 동학년방") && r.people.contains("금요일까지"))
        assertTrue(r.apps.contains("쿠팡 1건 · 배송 출발"))
        assertFalse(r.text.contains("특가"))
    }

    @Test fun 짧은_한_마디는_모델_없이_묻는_말이면_답장할_연락() = runBlocking {
        val r = BriefingComposer.compose(listOf(kakao("", "민준 어머님", "내일 도시락 챙겨야 하나요?")), ask = { error("부르면 안 됨") })
        assertTrue(r.people.contains("🙋 답장할 연락 (1)"))
        assertTrue(r.people.contains("민준 어머님") && r.people.contains("도시락"))
    }

    @Test fun 모델이_형식을_어기면_첫_줄을_요점으로_그것도_없으면_마지막_메시지() {
        val conv = BriefingComposer.conversations(listOf(kakao("가족방", "엄마", "일요일 점심은 할머니 댁에서 먹자. 12시까지 와."), kakao("가족방", "동생", "나는 늦을 듯")))[0]
        val loose = BriefingComposer.parse(conv, "**가족이 일요일 점심 약속을 정했습니다.**")
        assertEquals("가족이 일요일 점심 약속을 정했습니다.", loose.point)
        assertFalse(loose.needReply)
        val empty = BriefingComposer.parse(conv, "")
        assertEquals("나는 늦을 듯", empty.point)
        val none = BriefingComposer.parse(conv, "요점: 점심 약속\n요청: 없음\n답장: 아님")
        assertEquals("", none.ask); assertFalse(none.needReply)
    }

    @Test fun 대화방이_많으면_최근_방만_모델로_나머지는_이름만() = runBlocking {
        val items = (1..14).map { i -> kakao("방$i", "사람$i", "긴 이야기 ".repeat(10) + i) }
        var calls = 0
        val r = BriefingComposer.compose(items, ask = { calls++; "요점: 이야기\n요청: 없음\n답장: 아님" })
        assertEquals(BriefingComposer.MAX_ROOMS, calls)
        assertTrue(r.people.contains("그 밖의 방: ") && r.people.contains("방1 1건"))
        assertTrue(r.people.contains("최근 ${BriefingComposer.MAX_ROOMS} 곳만"))
    }

    @Test fun 중지하면_거기까지_정리하고_나머지는_이름만() = runBlocking {
        val items = (1..3).map { i -> kakao("방$i", "사람", "긴 이야기 ".repeat(15)) }
        var calls = 0
        val r = BriefingComposer.compose(items, ask = { calls++; "요점: 이야기\n요청: 없음\n답장: 아님" },
            stopReason = { if (calls >= 1) "중지해서 여기까지만 정리했어요." else null })
        assertEquals(1, calls)
        assertTrue(r.people.contains("대화방 2곳은 이름만"))
    }

    @Test fun 대화방_하나에_넣는_글은_최근_메시지부터_상한_안() {
        val conv = BriefingComposer.conversations((1..40).map { kakao("큰방", "사람$it", "메시지 $it " + "가".repeat(80)) })[0]
        val tr = BriefingComposer.transcript(conv)
        assertTrue(tr.length <= BriefingComposer.ROOM_CHARS + 120)
        assertTrue(tr.contains("메시지 40") && !tr.contains("메시지 1 "))
    }
}
