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
}
