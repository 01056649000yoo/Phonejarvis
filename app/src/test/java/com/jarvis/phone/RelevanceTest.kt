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
        assertTrue(Relevance.addressesMe("유승현님 확인 부탁드려요", names))           // 사람이 보낸 말의 '이름+님'도 부르는 말(광고는 앞에서 거름)
        assertFalse(Relevance.addressesMe("주택 경매 증가와 세수 급감", names))
    }

    @Test fun 호칭_추천() {
        val v = MyNames.variants("유승현")
        assertTrue(v.toString(), listOf("승현쌤", "승현 선생님", "유쌤", "유 선생님").all { it in v })
        assertFalse(v.toString(), v.any { "유승현" in it })                           // 이름이 그대로 든 호칭은 이미 알아봄
        assertTrue(MyNames.variants("친절한 유쌤").isEmpty())                          // 별명은 그대로
    }

    @Test fun 내_말에_이어진_말() {
        val saidAt = 2_000_000L
        val after = NotiItem(1, "com.kakao.talk", "카카오톡", "김", "그럼 그걸로 할게요", saidAt + 10 * 60_000L, "끄적끄적아지트")
        val late = after.copy(time = saidAt + 40 * 60_000L)
        val before = after.copy(time = saidAt - 60_000L)
        assertTrue(Relevance.toMe(after, names, saidAt))
        assertFalse(Relevance.toMe(late, names, saidAt))
        assertFalse(Relevance.toMe(before, names, saidAt))
        // 방 자체의 거리(나에게 한 말을 빼고)는 그대로 그 밖의 방
        val c = conv("끄적끄적아지트", after, after.copy(id = 2, title = "박", text = "좋아요"))
        assertEquals(Relevance.ME, Relevance.level(c, names, noRoster, { false }, saidAt = saidAt))
        assertEquals(Relevance.OTHER, Relevance.level(c, names, noRoster, { false }, saidAt = saidAt, ignoreCalls = true))
    }

    @Test fun 큰_방은_나에게_한_말과_앞뒤만_원문으로() = runBlocking {
        val room = "AI 교사 커뮤니티"
        fun talk(r: IntRange) = r.map { msg(room, "사람${it % 9}", "AI 수업 자료 이야기 $it 번째 말이에요") }
        val items = talk(1..10) + msg(room, "햅삐", "@친절한유쌤 이 자료 내일까지 봐 주실 수 있나요?") + msg(room, "사람3", "저도 궁금해요") + talk(11..20)
        var asked = 0
        val m = BriefingComposer.Mentions(base = { Relevance.OTHER }, toMe = { _, n -> Relevance.addressesMe(n.text, names) },
            explicit = { n -> Relevance.addressesMe(n.text, names) })
        val r = BriefingComposer.compose(items, ask = { asked++; "" }, focus = true, levels = { Relevance.ME }, mentions = m,
            replyAllowed = { false })
        assertEquals(0, asked)                                                        // AI 없이
        val s = r.rooms.single()
        assertTrue(s.mention && s.needReply)
        assertTrue(s.point, "햅삐: @친절한유쌤 이 자료 내일까지 봐 주실 수 있나요?" in s.point)
        assertTrue(s.point, "AI 수업 자료 이야기 10 번째" in s.point && "저도 궁금해요" in s.point)   // 앞 한 마디·뒤 한 마디
        assertFalse(s.point, "이야기 1 번째" in s.point)
        assertTrue(s.point, "(이 방 다른 말 19마디)" in s.point)
        assertTrue(r.people, "🙋 $room" in r.people)
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
        // 단톡방: 나를 부르면 1, 그 밖은 3 — 내가 말해 본 방이라도 나를 부르지 않으면 건수만(2026-11-26: '내가 속한 방' 단계 없앰)
        assertEquals(Relevance.ME, lv(conv("끄적끄적아지트", msg("끄적끄적아지트", "햅삐", "[나를 멘션] @친절한유쌤 응답바랍니다."))))
        assertEquals(Relevance.OTHER, lv(conv("6학년 동학년방", msg("6학년 동학년방", "김", "학예회 금요일"), msg("6학년 동학년방", "박", "네"))))
        assertEquals(Relevance.OTHER, lv(conv("라이프오브파이", msg("라이프오브파이", "A", "INK REALM 오픈소스 공개"), msg("라이프오브파이", "B", "대박"))))
        // 1:1: 문자·내가 답한 사람·나를 부름이면 1, 처음 보는 카톡 1:1(오픈채팅일 수 있음)은 2
        assertEquals(Relevance.ME, lv(conv("", msg("", "사랑하는 마누라", "학원비는 12월에"))))
        assertEquals(Relevance.OTHER, lv(conv("", msg("", "크림치즈컵케이크", "계정 이관 방법이 있을지요?"))))
        assertEquals(Relevance.ME, lv(conv("", msg("", "01012345678", "내일 상담 가능할까요?", "com.samsung.android.messaging")), sms = true))
        // 방 고르기에서 직접 정한 값이 먼저
        assertEquals(Relevance.OTHER, lv(conv("", msg("", "사랑하는 마누라", "학원비")), override = Relevance.OTHER))
        assertEquals(Relevance.ME, lv(conv("라이프오브파이", msg("라이프오브파이", "A", "공개")), override = Relevance.ME))
    }

    @Test fun 처음_쓰는_사람도_놓치지_않게() {
        fun lv(c: BriefingComposer.Conversation, hasMine: Boolean, learning: Boolean) =
            Relevance.level(c, if (hasMine) names else emptyList(), noRoster, { false }, hasMyNames = hasMine, learning = learning)
        val small = conv("우리 학년방", msg("우리 학년방", "김", "학예회 금요일"), msg("우리 학년방", "박", "명단은 제가 모을게요"))
        val crowd = conv("교사 커뮤니티", *Array(9) { msg("교사 커뮤니티", "사람$it", "AI 수업 자료 공유합니다 $it") })
        // 익히는 기간은 없앰: 나를 부르지 않은 단톡방은 처음부터 건수만
        assertEquals(Relevance.OTHER, lv(small, hasMine = true, learning = true))
        assertEquals(Relevance.OTHER, lv(crowd, hasMine = true, learning = true))
        // 익힌 뒤에는 내가 말한 적 없는 방은 그 밖의 방
        assertEquals(Relevance.OTHER, lv(small, hasMine = true, learning = false))
        // 나를 부르는 말을 아직 안 넣었으면: '선생님'을 부르는 방은 나에게(예전 규칙), 나머지는 한 줄
        val calls = conv("우리 학년방", msg("우리 학년방", "김", "선생님 내일 회의 몇 시에 하나요?"), msg("우리 학년방", "박", "네"))
        assertEquals(Relevance.ME, lv(calls, hasMine = false, learning = false))
        assertEquals(Relevance.OTHER, lv(small, hasMine = false, learning = false))
        assertTrue(Relevance.replyOk(calls, Relevance.ME, emptyList(), noRoster, hasMyNames = false))
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

    // 2026-11-26 선생님: 답할 것에는 나를 언급한 대화만, 1:1 도 포함
    @Test fun 답할_것은_나를_언급한_말과_일대일만() {
        val mine = listOf("유쌤", "유승현")
        val all = mine + listOf("김민준")                       // 학생 이름도 '나에게 온 대화'의 기준
        val parentRoom = { _: BriefingComposer.Conversation -> true }
        // 단톡방(학부모 방이어도): 나를 부르지 않은 물음은 🙋 아님
        val notMe = conv("3반 학부모방", msg("3반 학부모방", "민준맘", "내일 준비물이 뭔가요?"))
        assertFalse(Relevance.replyOk(notMe, Relevance.ME, all, parentRoom, mine = mine))
        // 학생 이름만 나온 물음도 🙋 아님(내용은 나에게 온 대화에 보임)
        val student = conv("오픈채팅", msg("오픈채팅", "닉네임", "김민준 오늘 조퇴했나요?"))
        assertFalse(Relevance.replyOk(student, Relevance.ME, all, noRoster, mine = mine))
        // 나를 부르며 묻는 말은 🙋
        val me = conv("오픈채팅", msg("오픈채팅", "닉네임", "유쌤 내일까지 자료 보내 주실 수 있나요?"))
        assertTrue(Relevance.replyOk(me, Relevance.ME, all, noRoster, mine = mine))
        // 처음 보는 사람의 카톡 1:1 도 나에게 직접 물으면 나에게 온 것 → 🙋
        val first = conv("", msg("", "처음 보는 학부모", "선생님 안녕하세요. 내일 상담 가능할까요?"))
        val lv = Relevance.level(first, all, noRoster, { false })
        assertEquals(Relevance.ME, lv)
        assertTrue(Relevance.replyOk(first, lv, all, noRoster, mine = mine))
        // 1:1 처럼 온 오픈채팅: 나를 부르지 않은 물음·여럿에게 돌린 말은 그대로 한 줄
        listOf("이거 써 보신 분 계실까요", "계정 이관 방법이 있을지요?", "선생님들 이거 어떻게 하세요?", "샘플 파일 보내 주실 수 있나요?").forEach { t ->
            assertEquals(t, Relevance.OTHER, Relevance.level(conv("", msg("", "택배 상자를 든 네오", t)), all, noRoster, { false }))
        }
    }

    // 2026-11-26 선생님: 컨셉을 명확하게 — 단톡방은 나를 언급한 말만 요약, 나머지는 건수만(직접 누른 요약도 같음)
    @Test fun 단톡방은_나를_언급한_말만_요약() = runBlocking {
        val items = listOf(
            msg("동학년방", "김", "렉서스 새로 나온 건 가격이 그리 안 올랐대요"), msg("동학년방", "박", "보급형차로 옵션질하면 회사 힘들어져요"),
            msg("동학년방", "이", "사이즈가 좀 커졌죠"),
            msg("연구회", "최", "다음 주 모임 장소 정해야 해요"), msg("연구회", "정", "유쌤 금요일까지 발표 자료 보내 주실 수 있나요?"),
            msg("", "김민준 어머니", "선생님 내일 준비물이 뭔가요?", "com.samsung.android.messaging"))
        val joined = setOf("동학년방")   // 내가 말해 본 방이라도
        val levels = { c: BriefingComposer.Conversation -> Relevance.level(c, names, noRoster, { it in joined }, trusted1to1 = c.items.all { Sms.isSms(it.pkg) }) }
        val base = { c: BriefingComposer.Conversation -> Relevance.level(c, names, noRoster, { it in joined }, trusted1to1 = c.items.all { Sms.isSms(it.pkg) }, ignoreCalls = true) }
        val mentions = BriefingComposer.Mentions(base, { _, n -> Relevance.addressesMe(n.text, names) }, { n -> Relevance.addressesMe(n.text, names) })
        for (compact in listOf(false, true)) {
            val out = BriefingComposer.compose(items, ask = { "" }, focus = true, levels = levels, mentions = mentions,
                replyAllowed = { c -> Relevance.replyOk(c, levels(c), names, noRoster) }, compact = compact).people
            assertFalse(out, "렉서스" in out || "사이즈가" in out || "- 동학년방 (" in out)        // 나를 부르지 않은 방은 줄 없음
            assertTrue(out, "동학년방 3건" in out || "그 밖 1곳 3건" in out)                         // 건수만
            assertTrue(out, "🙋 연구회" in out && "유쌤 금요일까지 발표 자료" in out)                 // 나를 언급한 말
            assertTrue(out, "🙋 김민준 어머니" in out && "준비물" in out)                             // 1:1
        }
    }

    @Test fun 내_말에_이어진_말은_바로_뒤_다섯_마디까지() {
        val saidAt = 5_000_000L
        val after = List(12) { NotiItem(it.toLong(), "com.kakao.talk", "카카오톡", "닉$it", "말 $it", saidAt + (it + 1) * 60_000L, "큰 방") }
        val t = Relevance.myThread(after, saidAt)
        assertEquals(Relevance.THREAD_TURNS, t.size)
        assertEquals(after.take(5), t)
        assertTrue(Relevance.myThread(after, 0L).isEmpty())
    }

    // 2026-11-26 선생님: 놓치기 싫은 방은 원문 줄 대신 요약으로, 앞의 요약과 비교해서
    @Test fun 놓치기_싫은_방은_앞_요약과_비교해_풀어_쓴_요약() = runBlocking {
        val items = listOf(
            msg("6학년 동학년방", "김", "학예회 리허설 시간 바꿔야 할 것 같아요"), msg("6학년 동학년방", "박", "강당이 2시까지 다른 행사예요"),
            msg("6학년 동학년방", "이", "그럼 3시로 하죠"), msg("6학년 동학년방", "김", "네 3시 강당에서 봬요"))
        val asked = mutableListOf<String>()
        val r = BriefingComposer.compose(items, ask = { q -> asked += q; "요점: 학예회 리허설이 3시 강당으로 정해짐\n요청: 없음" }, focus = true,
            levels = { Relevance.ME }, prose = { true }, earlier = { "김: 학예회 리허설을 2시에 하자고 함" })
        assertEquals(1, asked.size)
        assertTrue(asked[0], "앞서 이 방을 이렇게 요약했어" in asked[0] && "리허설을 2시에 하자고 함" in asked[0])
        assertTrue(r.people, "- 6학년 동학년방 (4건): 학예회 리허설이 3시 강당으로 정해짐" in r.people)
        // 놓치기 싫은 방이 아니면 예전처럼 원문 줄 고르기(앞 요약을 넣지 않음)
        asked.clear()
        BriefingComposer.compose(items, ask = { q -> asked += q; "" }, focus = true, levels = { Relevance.ME }, earlier = { "앞 요약" })
        assertFalse(asked.joinToString(), asked.any { "앞서 이 방을 이렇게 요약했어" in it })
    }
}
