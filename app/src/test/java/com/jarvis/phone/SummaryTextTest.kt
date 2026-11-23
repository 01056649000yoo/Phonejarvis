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
}
