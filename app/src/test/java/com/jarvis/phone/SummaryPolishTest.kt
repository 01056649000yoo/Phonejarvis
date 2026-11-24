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

    @Test fun 높임말로_줄이지_않음() {
        assertEquals("사진을 보내며 이런 활동을 하는구나 하고 말함", BriefingComposer.soften("사진을 보내며 이런 활동을 하는구나 하고 말씀하심"))
        assertEquals("자료를 보냄", BriefingComposer.soften("자료를 보내심"))
        assertEquals("회의에 참석했다고 함", BriefingComposer.soften("회의에 참석하셨다고 함"))
        assertEquals("관심을 보임", BriefingComposer.soften("관심을 보임"))
    }

    @Test fun 묶음_부탁은_짧게_답장은_표시() {
        val p = BriefingComposer.paragraph("12월 1일 5교시 보결을 넣기로 함", "4학년애들 급식실 임장 해주세요~~ 체육끝나고.", reply = true)
        assertTrue(p, p.startsWith("12월 1일 5교시 보결을 넣기로 함. 🙋 "))
        assertFalse(p, "~~" in p)
        assertEquals("사진을 보냄.", BriefingComposer.paragraph("사진을 보내심", ""))
    }

    @Test fun 맞장구_물음은_요청이_아님() {
        listOf("아 그래요?", "진짜요?", "헐 대박", "그렇군요 ㅎㅎ").forEach { assertTrue(it, BriefingComposer.isFiller(it)) }
        assertFalse(BriefingComposer.isFiller("그래요? 그럼 내일 몇 시에 가요?"))
    }

    @Test fun 혼잣말_물음은_요청이_아니고_긴_주소는_링크로() {
        listOf("급락..?", "와이프에게 빌붙기 정도..?", "아니 카톡친추네여?").forEach { assertTrue(it, BriefingComposer.isRhetorical(it)) }
        listOf("내일 몇 시에 오세요?", "외장 SSD 박으면 안되나요?", "밥 먹었니?").forEach { assertFalse(it, BriefingComposer.isRhetorical(it)) }
        assertEquals(listOf("교육쪽인지 물음"), BriefingComposer.splitAsks("교육쪽인지 물음, 급락..?"))
        assertEquals("모두 축하해 달라고 함 (링크)", BriefingComposer.soften("모두 축하해 달라고 함 https://n.news.naver.com/article/050/0000113676?cds=news_media_pc"))
        assertEquals("외장 SSD 박으면 안되는지 물음", BriefingComposer.soften("[댓글]  외장 SSD 박으면 안되는지 물음"))
    }

    @Test fun 묶음_줄_문장_다듬기() {
        // 묶음 이름(👔 지인)을 붙여 '김진수 지인이 …'
        assertEquals("주식 하락과 날씨 때문에 늦을 것 같다고 함", BriefingComposer.dropName("김진수 지인이 주식 하락과 날씨 때문에 늦을 것 같다고 함", "김진수", "👔 지인"))
        // 물음표 뒤에 마침표를 겹치지 않음, 짐작하는 물음은 붙이지 않음
        assertEquals("묘도 소나무가 머리털이 빠지는 중이라고 함.", BriefingComposer.paragraph("묘도 소나무가 머리털이 빠지는 중이라고 함", "빠져죽는거 아녜요?"))
        assertTrue(BriefingComposer.paragraph("일정을 알림", "내일 오세요?").endsWith("?"))
    }

    @Test fun 업체_웹문자는_키워드_칸에_안_올림() {
        val mms = "<제목: [CJ대한통운]>  [Web발신]\n[CJ대한통운_배송완료] 일요일에도 신속하게!!"
        assertTrue(Briefings.isBusinessMms(mms))
        assertFalse(Briefings.isBusinessMms("[Web발신]\n[행복초등학교] 내일 민준이 체험학습 안내"))
        assertEquals("[CJ대한통운] [CJ대한통운_배송완료] 일요일에도 신속하게!!", Briefings.cleanMms(mms))
    }

    @Test fun 원문에_없는_감정은_지어내지_않음() {
        // 2026-11-24 '사랑하는 마누라': 짜증 낸 적 없는데 '짜증을 내며' → 요약 대신 원문
        val conv = BriefingComposer.Conversation("카카오톡", "사랑하는 마누라",
            listOf(n("사랑하는 마누라", "교감선생님이 계획서 보자고 하셨어", 1), n("사랑하는 마누라", "계획서 다 기억해야하겠지?", 2)))
        val r = BriefingComposer.parse(conv, "요점: 교감 선생님 관련 언급에 대해 짜증을 내며 계획서 공유를 요청함\n요청: 없음")
        assertFalse(r.point, "짜증" in r.point)
        assertTrue(r.point, "계획서" in r.point)
        // 드러난 감정은 그대로
        assertFalse(BriefingComposer.madeUpFeeling("비행기 운항을 걱정함", "제주도 비행기 뜰까요 ㅠㅠ 걱정이네"))
        assertTrue(BriefingComposer.madeUpFeeling("노트북 때문에 부끄러움을 느낌", "노트북 하드 녹음 기사님이 들으심"))
    }

    @Test fun 짧으면_원문_그대로() = runBlocking {
        // 2026-11-24 '사랑하는 마누라' 3건 → AI 가 '해양교육 관련하여 불만이 많다고 함'으로 뜻을 바꿈
        val items = listOf(n("사랑하는 마누라", "에이씨...해양교육 오ㅐ이리 많오", 1), n("사랑하는 마누라", "화양 또 냅?", 2), n("사랑하는 마누라", "거기는 진짜 너무하네 ㅋ", 3))
        val r = BriefingComposer.compose(items, ask = { error("짧은 말은 AI 를 부르지 않음") })
        assertTrue(r.people, "에이씨...해양교육 오ㅐ이리 많오 / 화양 또 냅? / 거기는 진짜 너무하네 ㅋ" in r.people)
        assertFalse(r.people, "불만" in r.people)
        // 👥 묶음 줄도 원문 그대로, 'ㅋ.' 처럼 마침표를 붙이지 않음
        val g = BriefingComposer.compose(emptyList(), ask = { error("짧은 말은 AI 를 부르지 않음") },
            groups = listOf(BriefingComposer.Group("👔 지인", items.map { "마누라" to it })))
        assertTrue(g.groups, g.groups.contains("거기는 진짜 너무하네 ㅋ") && !g.groups.contains("ㅋ."))
    }

    @Test fun 메일은_보낸_사람과_제목을_한_줄씩() = runBlocking {
        fun gm(who: String, subject: String, t: Long) = NotiItem(t, "com.google.android.gm", "Gmail", who, "$subject\n본문 내용이 길게 이어짐", t)
        val work = BriefingComposer.Group(BriefingComposer.WORK_TITLE, listOf(gm("yshgg", "[Phonejarvis] Run failed: Build APK", 1), gm("NC", "개인정보 이용내역 안내", 2)).map { it.app to it })
        val r = BriefingComposer.compose(emptyList(), ask = { error("메일은 AI 를 부르지 않음") }, groups = listOf(work))
        assertTrue(r.work, "NC: 개인정보 이용내역 안내 / yshgg: [Phonejarvis] Run failed: Build APK" in r.work)
        assertFalse(r.work, "물음" in r.work)
    }

    @Test fun 원문_물음은_따옴표로() {
        assertEquals("\"두형이형 차놔두고왔어요?\"라고 물음", BriefingComposer.quotedAsk("두형이형 차놔두고왔어요?"))
        assertEquals("내일 도시락 챙겨야 하는지 물음", BriefingComposer.quotedAsk("내일 도시락 챙겨야 하나요?"))   // 바꿀 수 있으면 짧은 말
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

    @Test fun 잡담은_간단히_많으면_핵심_요약() = runBlocking {
        val items = listOf(
            n("캔디", "비트코인 손실 났다가 본전 왔어", 1, room = "코인방"), n("햅삐", "이더리움도 올랐네", 2, room = "코인방"), // 단톡 잡담
            n("김지우맘", "선생님 내일 준비물이 뭔가요?", 3),                                                           // 선생님을 부름
            n("친구", "오늘 저녁 같이 먹을래?", 4),                                                                    // 1:1 물음
            n("정구영", "어제 말한 책 찾았어", 8),                                                                     // 1:1 은 물음이 없어도 요약
            n("뉙릭", "ㅋㅋ", 9),                                                                                     // 1:1 이라도 맞장구뿐이면 이름만
            n("이선생", "내일까지 각 반 명단 내 주세요", 5, room = "6학년 동학년"),                                       // 단톡 + 기한 있는 부탁
            n("박", "젠슨 황 컴백 ㄷㄷ", 6, room = "투자 수다방"), n("최", "도파민 솟는다", 7, room = "투자 수다방"),       // 단톡 잡담
        )
        val big = (1..6).map { n("동창$it", "주말 등산 코스 이야기 $it", 20L + it, room = "동창 모임") }      // 많은 잡담 → 핵심 요약
        val seen = mutableListOf<String>()
        // 명부에 없는 사람들(compose 기본값은 모든 방을 명부로 봄 — 앱은 늘 실제 명부 규칙을 넘김)
        val r = BriefingComposer.compose(items + big, ask = { p -> seen += p; "요점: 등산 코스를 정함\n요청: 없음" }, replyAllowed = { false }, focus = true)
        assertTrue(seen.toString(), seen.none { "비트코인" in it || "젠슨" in it })      // 몇 마디뿐인 잡담 방은 AI 없이
        assertTrue(seen.toString(), seen.any { "등산" in it })                            // 많으면 AI 핵심 요약
        assertTrue(r.people, "투자 수다방 (2건): 박: 젠슨 황 컴백" in r.people && "최: 도파민" in r.people)   // 간단한 내용은 보임
        assertTrue(r.people, "코인방 (2건): 캔디: 비트코인" in r.people)
        assertTrue(r.people, "동창 모임 (6건)" in r.people)
        val other = r.people.lines().first { it.startsWith("- 그 밖의 방") }
        assertEquals("- 그 밖의 방: 뉙릭 1건", other)                                   // 맞장구뿐인 방만 이름
        listOf("김지우맘", "친구", "6학년 동학년", "정구영").forEach { assertTrue(it, it in r.people) }
        assertFalse(r.people, "🙋 투자 수다방" in r.people)
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
