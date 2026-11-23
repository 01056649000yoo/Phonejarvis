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

    @Test fun 대화방이_많아도_모두_요약하고_30개가_넘는_방은_앞_정리에_이어서_고쳐_쓴다() = runBlocking {
        val many = (1..14).map { i -> kakao("방$i", "사람$i", "긴 이야기 ".repeat(10) + i) }
        val big = (1..65).map { i -> kakao("큰방", "사람${i % 5}", "회의 이야기 $i " + "가".repeat(20)) }
        val prompts = mutableListOf<String>()
        val r = BriefingComposer.compose(many + big, ask = { p ->
            prompts += p
            when {
                "앞서 이렇게 정리했어" in p -> "요점: 큰방 회의를 종합한 한 문장\n요청: 자료 올려 주세요, 명단 내 주세요, 사진 보내 주세요"
                "큰방" in p -> "요점: 큰방 첫 덩어리\n요청: 자료 올려 주세요"
                else -> "요점: 이야기\n요청: 없음"
            }
        })
        assertEquals(14 + 3, prompts.size)                             // 작은 방 14곳 + 큰방 65개 → 30·30·5 세 덩어리(합치기 따로 없음)
        assertEquals(2, prompts.count { "앞서 이렇게 정리했어" in it })   // 둘째·셋째 덩어리는 앞 정리에 이어서
        assertFalse(r.people.contains("그 밖의 방: 방"))                // 빠진 방 없음
        assertTrue(r.people, r.people.contains("큰방 회의를 종합한 한 문장") && !r.people.contains("큰방 첫 덩어리"))
        assertFalse(r.people, r.people.contains("사진 보내 주세요"))      // 요청은 2개까지
    }

    @Test fun 미리_요약해_둔_방은_다시_묻지_않고_그_뒤_온_것만_이어서() = runBlocking {
        val old = listOf(kakao("동학년방", "김부장", "금요일까지 명단 올려 주세요"), kakao("동학년방", "이선생", "양식은 어디 있나요?"))
        val since = 100L
        val saved = mutableMapOf<String, BriefingComposer.Cached>()
        var calls = 0
        BriefingComposer.compose(old, ask = { calls++; "요점: 금요일까지 명단 제출\n요청: 명단 올려 달라고 함" }, since = since, onCached = { saved[it.key] = it })
        assertEquals(1, calls); assertEquals(2, saved.values.single().count)
        // 같은 구간, 새 메시지 없음 → 모델 안 부르고 저장한 그대로
        val same = BriefingComposer.compose(old, ask = { error("부르면 안 됨") }, cache = saved, since = since)
        assertTrue(same.people, same.people.contains("금요일까지 명단 제출"))
        // 새 메시지 하나 → 앞 정리 + 새 메시지만 넣어 한 번
        val more = old + kakao("동학년방", "김부장", "마감이 목요일로 당겨졌어요")
        var prompt = ""
        val r = BriefingComposer.compose(more, ask = { prompt = it; "요점: 명단 마감이 목요일로 당겨짐\n요청: 목요일까지 명단 올려 달라고 함" }, cache = saved, since = since)
        assertTrue(prompt, "금요일까지 명단 제출" in prompt && "목요일로 당겨졌어요" in prompt && "양식은 어디" !in prompt)
        assertTrue(r.people, r.people.contains("목요일로 당겨짐"))
        // 구간이 바뀌었거나(다음 요약 뒤) 메시지 수가 다르면(뺀 방·지운 알림) 버리고 처음부터
        assertEquals(0, BriefingComposer.pendingForModel(old, saved, since))
        assertEquals(2, BriefingComposer.pendingForModel(old, saved, since + 1))
        assertEquals(1, BriefingComposer.pendingForModel(listOf(old[0]), saved, since).let { if (it == 0) -1 else it }.coerceAtLeast(1))
    }

    @Test fun 잡담은_모델에_넣지_않고_답이_한_줄로_붙어도_나눈다() {
        val conv = BriefingComposer.conversations(listOf(kakao("방", "가", "네 알겠습니다"), kakao("방", "나", "ㅋㅋㅋ"), kakao("방", "다", "내일 8시 30분 교무회의"), kakao("방", "라", "넵!")))[0]
        val tr = BriefingComposer.transcript(conv)
        assertTrue(tr, "교무회의" in tr && "알겠습니다" !in tr && "넵" !in tr)
        val r = BriefingComposer.parse(conv, "요점: 내일 8시 30분 교무회의가 있다고 함 요청: 없음 답장: 아님")
        assertEquals("내일 8시 30분 교무회의가 있다고 함", r.point); assertEquals("", r.ask)
    }

    @Test fun 원문에_없는_숫자를_쓰면_그_칸은_믿지_않는다() {
        val conv = BriefingComposer.conversations(listOf(kakao("", "지호 어머니", "수요일 상담 4시로 바꿔도 될까요?")))[0]
        val r = BriefingComposer.parse(conv, "요점: 수요일 상담을 3시로 바꾸고 싶다고 함\n요청: 상담 시간 변경 확인해 달라고 함")
        assertEquals("수요일 상담 4시로 바꿔도 될까요?", r.point)
        assertTrue(BriefingComposer.grounded("12월 5일까지 3만 원", "12월 5일까지 1권당 3만 원"))
        assertFalse(BriefingComposer.grounded("12월 6일", "12월 5일"))
    }

    @Test fun 급한_요청이_먼저_모델이_놓친_급한_물음도_요청에() {
        assertEquals(listOf("내일 2교시 후 등교 허락해 달라고 함", "회신서 보내 달라고 함"),
            BriefingComposer.rankAsks(listOf("회신서 보내 달라고 함", "상담 날짜 바꿔 달라고 함", "내일 2교시 후 등교 허락해 달라고 함")))
        val conv = BriefingComposer.conversations(listOf(kakao("", "하준 어머니", "선생님 하준이 내일 병원 때문에 2교시 후 등교해도 될까요? 회신서 한 장 더 보내 주실 수 있나요? 상담 날짜도 바꿀 수 있을까요?")))[0]
        val r = BriefingComposer.parse(conv, "요점: 등교·회신서·상담 문의\n요청: 회신서 한 장 더 보내 달라고 함, 상담 날짜를 바꿔 달라고 함")
        assertTrue(r.ask, r.ask.startsWith("하준이 내일 병원 때문에 2교시 후 등교해도 될까요?") && r.ask.contains("회신서"))
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

    @Test fun 일대일_대화에서_모델이_요청을_놓쳐도_묻는_말이면_답장할_연락() {
        val conv = BriefingComposer.conversations(listOf(
            kakao("", "서윤 어머니", "서윤이가 열이 나서 2교시 후에 등교해도 될까요?"),
            kakao("", "서윤 어머니", "병원 다녀왔는데 감기래요. 체육은 쉬게 해 주시면 감사하겠습니다."),
        ))[0]
        val r = BriefingComposer.parse(conv, "요점: 서윤이가 감기로 늦게 등교함\n요청: 없음\n답장: 아님")
        assertTrue(r.needReply)
        assertTrue(r.ask.contains("쉬게 해 주시면"))
        val group = BriefingComposer.conversations(listOf(kakao("동학년방", "최선생", "명단 양식 있을까요?")))[0]
        assertFalse(BriefingComposer.parse(group, "요점: 양식 문의\n요청: 없음\n답장: 아님").needReply)
    }

    @Test fun 봇_채널_알림톡_메신저_자체_안내는_사람_메시지가_아니다() {
        val tg = { title: String, text: String -> NotiItem(0, "org.telegram.messenger", "Telegram", title, text, t++) }
        assertFalse(BriefingComposer.isPerson(tg("자비스", "배포 완료: 독서록 글자 수 수정")))
        assertFalse(BriefingComposer.isPerson(tg("agit_status_bot", "서버 점검 모두 정상")))
        assertFalse(BriefingComposer.isPerson(kakao("", "쿠팡", "고객님의 주문하신 상품이 배송 출발했습니다.")))
        assertFalse(BriefingComposer.isPerson(kakao("", "카카오톡", "새로운 기기에서 로그인했습니다.")))
        assertTrue(BriefingComposer.isPerson(kakao("", "서윤 어머니", "선생님, 내일 도시락 챙겨야 하나요?")))
        assertTrue(BriefingComposer.isPerson(kakao("", "도윤 아버님", "체험학습비 결제는 어떻게 하나요?")))
        assertFalse(BriefingComposer.isPerson(kakao("", "CJ대한통운", "운송장 1234 상품이 오늘 도착 예정입니다.")))
        // 단톡방에서 사람이 '배송'이라는 말을 써도 사람 메시지
        assertTrue(BriefingComposer.isPerson(kakao("6학년 동학년방", "김부장", "학예회 소품 배송은 목요일에 와요")))
    }

    @Test fun 답장할_연락은_명부_키워드가_나오는_방만() = runBlocking {
        val rules = AppFilter.Rules(emptySet(), emptySet(), keywords = listOf("민준"))
        val items = listOf(
            kakao("", "민준 어머님", "내일 도시락 챙겨야 하나요?"),
            kakao("", "택배기사", "문 앞에 둘까요?"),
        )
        val r = BriefingComposer.compose(items, ask = { error("부르면 안 됨") }, replyAllowed = { c -> c.items.any { rules.mentionsRoster(it) } })
        assertTrue(r.people, r.people.contains("🙋 답장할 연락 (1)") && r.people.contains("민준 어머님"))
        // 명부에 없는 사람의 묻는 말은 💬 메시지 칸에 그대로
        val talk = r.people.substringAfter("💬")
        assertTrue(r.people, talk.contains("택배기사") && talk.contains("문 앞에 둘까요"))
    }

    @Test fun 한_사람의_여러_마디는_모델이_종합하고_한_마디만_원문() = runBlocking {   // 2026-11-23 선생님: 나열 말고 종합
        val items = listOf(kakao("", "도윤 아버님", "선생님 안녕하세요"), kakao("", "도윤 아버님", "내일 체험학습"), kakao("", "도윤 아버님", "도시락 싸야 하나요?"))
        var calls = 0
        val r = BriefingComposer.compose(items, ask = { calls++; "요점: 내일 체험학습 도시락을 싸야 하는지 물음\n요청: 도시락 필요 여부 알려 달라고 함\n답장: 필요" })
        assertEquals(1, calls)
        assertTrue(r.people, r.people.contains("🙋 답장할 연락 (1)") && r.people.contains("도시락 필요 여부") && !r.people.contains(" / "))
        val one = BriefingComposer.compose(listOf(kakao("", "서윤 어머니", "2교시 후에 등교해도 될까요?"), kakao("", "서윤 어머니", "ㅎㅎ")), ask = { error("부르면 안 됨") })
        assertTrue(one.people, one.people.contains("등교해도 될까요?"))
    }

    @Test fun 요청은_두_개까지() {
        assertEquals("가, 나", BriefingComposer.topAsks("가, 나, 다"))
        assertEquals("명단 올려 달라고 함", BriefingComposer.topAsks("명단 올려 달라고 함"))
    }

    @Test fun 단톡방_여러_메시지는_짧아도_모델로() = runBlocking {
        var calls = 0
        BriefingComposer.compose(listOf(kakao("동학년방", "김선생", "내일 회의"), kakao("동학년방", "이선생", "몇 시요?")), ask = { calls++; "요점: 내일 회의\n요청: 없음\n답장: 아님" })
        assertEquals(1, calls)
    }

    @Test fun 걸린_시간_한_줄() {
        val s = Summarizer.Stats().apply { loadMs = 21_400; backend = "GPU"; calls = 2; callMs = 27_000 }
        assertEquals("⏱ 48초 · 모델 불러오기 21초(GPU) · AI 2번 27초", Briefings.timingLine(48_900, s))
        assertEquals("⏱ 1초 · AI 안 씀", Briefings.timingLine(300, Summarizer.Stats()))
    }

    @Test fun 딱딱한_말은_부드럽게() {
        assertEquals("다음에 연락하라고 함", BriefingComposer.soften("다음에 연락하라는 지시"))
        assertEquals("다음에 연락하라고 함", BriefingComposer.soften("다음에 연락하라고 지시함"))
        assertEquals("자료를 보내 달라고 함", BriefingComposer.soften("자료를 보내 달라는 지시를 함"))
        assertEquals("엄마가 말함", BriefingComposer.soften("엄마가 통보함"))
        assertEquals("주말에 밥 먹자고 함", BriefingComposer.soften("주말에 밥 먹자고 함"))
    }
}
