package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 2026-11-23 선생님: 광고 문자가 🙋·⭐ 에 들어감, 같은 사람 연락은 모아서, 서버·시간 되면 오는 알림은 빼기 */
class NoiseFilterTest {
    private var t = 1_700_000_000_000L
    private fun sms(who: String, text: String) = NotiItem(0, "com.samsung.android.messaging", "메시지", who, text, t++)
    private fun kakao(room: String, who: String, text: String) = NotiItem(0, "com.kakao.talk", "카카오톡", who, text, t++, room)

    @Test fun 대표번호_문자와_1대1_광고는_사람_연락이_아니다() {
        assertTrue(AdFilter.isBusinessNumber("1588-1234")); assertTrue(AdFilter.isBusinessNumber("080-123-4567"))
        assertTrue(AdFilter.isBusinessNumber("02-123-4567")); assertTrue(AdFilter.isBusinessNumber("#1234"))
        assertFalse(AdFilter.isBusinessNumber("010-1234-5678")); assertFalse(AdFilter.isBusinessNumber("+82 10-1234-5678"))
        assertFalse(AdFilter.isBusinessNumber("도윤 아버님"))
        assertFalse(BriefingComposer.isPerson(sms("1588-1234", "홍길동님 이번 주 특별 혜택을 확인해 보세요")))
        assertFalse(BriefingComposer.isPerson(sms("01099998888", "홍길동님 최대 50% 할인 쿠폰이 도착했어요")))
        assertTrue(BriefingComposer.isPerson(sms("01012345678", "선생님 내일 도시락 싸야 하나요?")))
    }

    @Test fun 키워드가_있어도_광고는_거른다_학부모_내_묶음은_살린다() {
        val rules = AppFilter.Rules(emptySet(), emptySet(), listOf("홍길동"), adFilter = true, people = listOf("엄마"))
        assertTrue(rules.isBlockedAd("com.samsung.android.messaging", "", "1588-1234", "(광고) 홍길동님 혜택 안내 무료수신거부 080"))
        assertFalse(rules.isBlockedAd("com.kakao.talk", "", "엄마", "(광고) 이거 쿠폰 할인 이벤트래"))
    }

    @Test fun 키워드가_부르는_말로만_든_알림은_키워드가_아니다() {
        assertFalse(AppFilter.hasRealKeyword(listOf("홍길동"), "홍길동님, 주문하신 상품이 출발했어요"))
        assertFalse(AppFilter.hasRealKeyword(listOf("홍길동"), "홍길동 고객님 결제 완료"))
        assertTrue(AppFilter.hasRealKeyword(listOf("홍길동"), "홍길동 선생님 내일 회의 자료 부탁드려요"))
        assertTrue(AppFilter.hasRealKeyword(listOf("홍길동"), "홍길동님 안녕하세요. 홍길동 선생 반 체험학습 건으로"))
        val s = Briefings.keywordSection(listOf(sms("1588-1234", "홍길동님 특가 혜택"), kakao("", "교감", "홍길동 선생님 3시 협의")), listOf("홍길동"))
        assertTrue(s, s.contains("3시 협의") && !s.contains("특가"))
    }

    @Test fun 매일_비슷한_시각에_오는_앱_알림은_정기_알림() {
        val zone = TimeZone.getTimeZone("Asia/Seoul")
        val day = 24L * 3600 * 1000
        val base = 1_763_856_000_000L   // 어느 날 09:00 KST 근처
        fun a(pkg: String, app: String, title: String, at: Long) = NotiItem(0, pkg, app, title, "본문", at)
        val weather = (0 until 4).map { a("com.weather", "날씨", "오늘 ${it + 3}도 맑음", base + it * day + it * 5 * 60_000L) }
        val shop = listOf(a("com.shop", "쇼핑", "배송 출발", base), a("com.shop", "쇼핑", "배송 출발", base + day + 6 * 3600_000L), a("com.shop", "쇼핑", "배송 출발", base + 2 * day + 3 * 3600_000L))
        val parentsDaily = (0 until 5).map { NotiItem(0, "com.kakao.talk", "카카오톡", "하이클래스 알림", "오늘 준비물", base + it * day) }
        val keys = Routine.detect(weather + shop + parentsDaily, zone)
        assertEquals(setOf("com.weather|오늘 #도 맑음"), keys)       // 시각이 들쭉날쭉한 쇼핑·메시지 앱은 아님
        val bot = kakao("", "자비스", "서버 점검: 모두 정상")
        assertTrue(Routine.isQuiet(bot, emptySet(), emptySet()))
        assertTrue(Routine.isQuiet(weather[0], keys, emptySet()))
        assertFalse(Routine.isQuiet(a("com.weather", "날씨", "오늘 9도 홍길동 체험학습 우천", base), keys, emptySet(), listOf("체험학습")))   // 키워드면 살림
        assertTrue(Routine.isQuiet(a("com.x", "서버", "백업 완료 3건", base), emptySet(), setOf("com.x|백업 완료 #건")))     // 직접 뺀 꼴
        assertTrue(Routine.line(listOf(bot, weather[0])).startsWith("🔁 정기·자동 알림 2건"))
    }

    @Test fun 내_묶음은_같은_이름이면_카톡_문자_단톡을_합쳐_사람마다_한_줄() = runBlocking {
        val g = BriefingComposer.Group("🏠 가족", listOf(
            "엄마" to kakao("", "엄마", "일요일 할머니 생신 점심 12시야 다들 시간 맞춰서 와야 해 할머니가 많이 기다리셔 선물도 미리 챙겨 둬"),
            "엄마" to sms("엄마", "꽃도 좀 사 와 줄래?"),
            "엄마" to kakao("우리 가족", "엄마", "늦지 말고 와"),
            "동생" to kakao("우리 가족", "동생", "나 케이크 사 갈게"),
        ))
        val prompts = mutableListOf<String>()
        val r = BriefingComposer.compose(emptyList(), ask = { p -> prompts += p; "요점: 일요일 12시 할머니 생신 점심에 늦지 말라고 함\n요청: 꽃 사 와 달라고 함" }, groups = listOf(g))
        assertEquals(1, prompts.size)                                    // 엄마 3건 → AI 한 번, 동생 한 마디 → 원문
        assertTrue(prompts[0], "꽃도 좀" in prompts[0] && "늦지 말고" in prompts[0] && "케이크" !in prompts[0])
        assertTrue(r.groups, r.groups.startsWith("🏠 가족 연락 (2명 · 4건)"))
        assertTrue(r.groups, r.groups.contains("- 엄마 (3건)") && r.groups.contains("늦지 말라고 함. 🙋 꽃 사 와 달라고 함.") && r.groups.contains("케이크"))
        assertFalse(r.people.contains("엄마"))                            // 🙋·💬 에는 안 나옴
        assertEquals(3, BriefingComposer.pendingForModel(emptyList(), emptyMap(), 0, listOf(g)))
    }

    @Test fun 묶음_사람_이름은_알림에_찍힌_그대로_줄이지_않는다() = runBlocking {
        val g = BriefingComposer.Group("👔 교직원", listOf(
            "김민수" to kakao("", "김민수 선생님", "내일 3시 학년 협의회 2층 회의실이에요"),
            "김민수" to kakao("6학년 동학년방", "김민수 선생님", "학예회 명단도 금요일까지 부탁드려요"),
            "김민수" to sms("김민수", "자료는 메신저로 보냈어요"),
        ))
        var prompt = ""
        val r = BriefingComposer.compose(emptyList(), ask = { prompt = it; "요점: 김민수 선생님이 내일 3시 2층 회의실 협의회를 알림\n요청: 학예회 명단 금요일까지 내 달라고 함" }, groups = listOf(g))
        assertTrue(prompt, "'김민수 선생님'" in prompt && "이름과 호칭" in prompt)
        assertTrue(r.groups, r.groups.contains("- 김민수 선생님 (3건)"))
        assertTrue(r.groups, r.groups.contains("협의회를 알림. 🙋 학예회 명단 금요일까지 내 달라고 함.") && "→" !in r.groups)
        // 부탁이 요점에 이미 들어 있으면 한 번만
        assertEquals("내일 3시 협의회, 반별 명단 금요일까지 올리라고 함.",
            BriefingComposer.paragraph("내일 3시 협의회, 반별 명단 금요일까지 올리라고 함", "반별 명단을 금요일까지 단톡에 올려 달라고 함"))
    }
}
