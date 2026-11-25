package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 요약 글을 칸 제목·사람·시각·내용으로 나누기(화면만 — 저장 글은 그대로) */
class SummaryTextTest {
    @Test fun 줄_나누기() {
        assertEquals(SummaryLine("김민수 선생님", "3건 · 09:00", "내일 3시 협의회."), splitLine("- 김민수 선생님 (3건) · 09:00: 내일 3시 협의회."))
        assertEquals(SummaryLine("민준 어머님", "14:20", "도시락 챙겨야 하는지 물음"), splitLine("- 민준 어머님 · 14:20: 도시락 챙겨야 하는지 물음"))
        assertEquals(SummaryLine("01055551234", "14:04", "선생님 민준이 내일"), splitLine("- 14:04 01055551234: 선생님 민준이 내일"))
        assertEquals(SummaryLine("방1", "오늘", "회의 자료 확인 부탁드려요"), splitLine("- [오늘] 회의 자료 확인 부탁드려요 (방1)"))
        assertEquals(SummaryLine("그 밖의 방", "", "가족방 2건"), splitLine("- 그 밖의 방: 가족방 2건"))
        assertEquals(SummaryLine("", "", "머리 없는 줄"), splitLine("- 머리 없는 줄"))
    }

    @Test fun 칸_제목_알아보기() {
        assertTrue(isSectionHead("🙋 답장할 연락 (2)"))
        assertTrue(isSectionHead("👔 교직원 연락 (1명 · 3건)"))
        assertFalse(isSectionHead("- 엄마 (3건): 꽃"))
        assertFalse(isSectionHead("(⏱ 6초)"))
        assertFalse(isSectionHead("요약 7번 · 알림 422건"))
    }

    @Test fun 칸_이모지를_아이콘으로() {
        assertEquals(R.drawable.ic_line_chat to "대화 요약 (2개 방)", splitIcon("💬 대화 요약 (2개 방)"))
        assertEquals(R.drawable.ic_line_calendar to "하루 정리", splitIcon("🗓️ 하루 정리"))
        assertEquals(R.drawable.ic_line_box to "앱 알림 21건 · 광고 9건 · 정기 알림 4건", splitIcon("📦 앱 알림 21건 · 🚫 광고 9건 · 🔁 정기 알림 4건"))
        assertEquals(R.drawable.ic_line_people to "교직원 연락 (1명)", splitIcon("👔 교직원 연락 (1명)"))
        assertEquals(true to "- 민준 어머님 · 14:20: 물음", splitAsk("- 🙋 민준 어머님 · 14:20: 물음"))
        assertEquals(false to "- 엄마: 꽃", splitAsk("- 엄마: 꽃"))
    }

    @Test fun 메뉴_이모지를_아이콘으로() {
        assertEquals(R.drawable.ic_line_filter to "알림 앱 정리", menuIcon("🌱 알림 앱 정리"))
        assertEquals(R.drawable.ic_line_restore to "백업·복원", menuIcon("💾 백업·복원"))
        assertEquals(R.drawable.ic_line_person to "선생님", menuIcon("👩‍🏫 선생님"))
    }

    @Test fun 글_속_앱_이모지는_지우고_메시지_이모지는_그대로() {
        assertEquals("답장 2 · 광고 9건", Emoji.strip("🙋 답장 2 · 🚫 광고 9건"))
        assertEquals("✓ 답함", Emoji.strip("✔️ 답함"))
        assertEquals("감사합니다 😊", Emoji.strip("감사합니다 😊"))
        assertEquals(null to "감사합니다 😊", Emoji.lead("감사합니다 😊"))
    }
}
