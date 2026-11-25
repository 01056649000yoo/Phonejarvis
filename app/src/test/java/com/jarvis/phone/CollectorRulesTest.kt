package com.jarvis.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-24 점검: 알림을 모으기 전에 버리는 규칙 */
class CollectorRulesTest {
    private fun secret(t: String) = NotificationCollectorService.isSecretCode(t)

    @Test fun 인증번호는_거름() {
        listOf(
            "[Web발신] 인증번호 [482913]을 입력해 주세요",
            "본인확인 번호 5521",
            "카카오 인증번호는 123456 입니다",
            "확인코드: 7788",
        ).forEach { assertTrue(it, secret(it)) }
    }

    @Test fun 확인_금액_전화번호가_든_학부모_메시지는_모음() {
        listOf(
            "체험학습비 확인 부탁드려요 15000원",
            "확인 후 010-1234-5678로 연락 주세요",
            "확인했습니다 2026년 계획서 보낼게요",
            "확인 부탁드립니다 15,000원 입금했어요",
            "내일 1교시 확인 부탁드려요",
        ).forEach { assertFalse(it, secret(it)) }
    }

    @Test fun 메시지_앱_이름_맞추기() {
        assertTrue(AppAdvisor.isMessaging("LINE", "jp.naver.line.android"))
        assertTrue(AppAdvisor.isMessaging("밴드", "com.nhn.android.band"))
        assertTrue(AppAdvisor.isMessaging("카카오톡", "com.kakao.talk"))
        assertFalse(AppAdvisor.isMessaging("온라인 클래스 쇼핑", "com.example.onlineshop"))   // '온라인' 이 '라인' 에 걸리던 것
        assertFalse(AppFilter.hasKeyword(emptyList(), "x"))
    }

    @Test fun 한꺼번에_온_메시지는_모두_순서대로_늦게_받으면_받은_시각으로() {
        val post = 10_000_000L
        // 방금 보낸 두 개(그대로) — 보낸 시각이 중복 키
        val now = NotificationCollectorService.arrivals(listOf(Triple("김민수", "내일 회의", post - 30_000), Triple("김민수", "3시예요", post - 1_000)), post)
        org.junit.Assert.assertEquals(listOf(post - 30_000, post - 1_000), now.map { it.time })
        org.junit.Assert.assertEquals(listOf(post - 30_000, post - 1_000), now.map { it.key })
        // 폰이 잠들어 한 시간 늦게 받은 세 개 — 받은 시각으로, 순서 그대로
        val late = NotificationCollectorService.arrivals(List(3) { Triple("학부모", "말$it", post - 3_600_000 + it) }, post)
        org.junit.Assert.assertEquals(listOf(post - 2, post - 1, post), late.map { it.time })
        assertTrue(late.zipWithNext().all { (a, b) -> a.time < b.time })
    }

    @Test fun 오픈채팅은_제목이_방_이름() {
        // 2026-11-24: 오픈채팅 사람마다 1:1 방으로 갈라져 13개 방이 됨
        org.junit.Assert.assertEquals("교사 개발자 모임", NotificationCollectorService.groupTitle("교사 개발자 모임", listOf("좌절하는 라이언", "보스코허")))
        org.junit.Assert.assertEquals("교사 개발자 모임", NotificationCollectorService.groupTitle("교사 개발자 모임", listOf("방장아님")))
        // 1:1 은 제목이 곧 보낸 사람
        org.junit.Assert.assertNull(NotificationCollectorService.groupTitle("햅삐", listOf("햅삐", "햅삐")))
        org.junit.Assert.assertNull(NotificationCollectorService.groupTitle("김민수 선생님", listOf("김민수")))
        org.junit.Assert.assertNull(NotificationCollectorService.groupTitle("엄마", emptyList()))
    }

    @Test fun 숨길지는_새로_온_문자로만_판단() {
        val c = NotificationCollectorService
        val stack = listOf(
            NotificationCollectorService.Companion.Msg("학교", "[긴급] 내일 휴업 안내", 1_000L, 1_000L),
            NotificationCollectorService.Companion.Msg("민준 어머님", "내일 준비물 알려 주세요", 9_000L, 9_000L),
        )
        // 예전 문자의 '긴급' 때문에 묶음째 뜨지 않게
        assertFalse(c.urgentWords(c.judgeText("[긴급] 내일 휴업 안내\n내일 준비물 알려 주세요", stack)))
        // 새로 온 문자가 긴급이면 그대로 뜸
        val urgent = stack + NotificationCollectorService.Companion.Msg("시청", "[재난] 폭설 주의", 12_000L, 12_000L)
        assertTrue(c.urgentWords(c.judgeText("", urgent)))
        // 메시지 목록이 없는 알림은 글 전체로
        assertTrue(c.urgentWords(c.judgeText("부재중 전화 1통", emptyList())))
    }
}
