package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-24 점검: 학생 성 뺀 이름이 흔한 낱말 안에 걸리던 것 */
class StudentNameTest {
    private fun n(text: String, title: String = "학부모", room: String = "") = NotiItem(0, "p", "앱", title, text, 0, room)

    @Test fun 성_뺀_이름은_이름일_때만() {
        val names = listOf("이지우", "박민준")
        assertEquals(listOf("이지우"), Students.find(names, n("지우가 오늘 결석해요")))
        assertEquals(listOf("이지우"), Students.find(names, n("지우 어머니입니다")))
        assertEquals(listOf("박민준"), Students.find(names, n("민준이랑 같이 갈게요")))
        assertEquals(listOf("박민준"), Students.find(names, n("내일 준비물", title = "민준맘")))
        assertEquals(emptyList<String>(), Students.find(names, n("지우개 챙겨 주세요")))
        assertEquals(emptyList<String>(), Students.find(names, n("교실 지우개가 없어요")))
        assertEquals(listOf("이지우"), Students.find(names, n("이지우 학생 상담")))       // 성까지 쓴 이름은 그대로
    }

    @Test fun 흔한_낱말_이름은_성까지_쓸_때만() {
        val names = listOf("김하나")
        assertEquals(emptyList<String>(), Students.find(names, n("하나도 모르겠어요")))
        assertEquals(emptyList<String>(), Students.find(names, n("하나씩 나눠 주세요")))
        assertEquals(listOf("김하나"), Students.find(names, n("김하나 어머니예요")))
    }

    @Test fun 키워드_규칙도_같게() {
        val kw = Students.allTerms(listOf("이지우"))
        assertTrue(AppFilter.hasKeyword(kw, "지우가 아파요"))
        assertFalse(AppFilter.hasKeyword(kw, "지우개 사 오세요"))
        assertTrue(AppFilter.hasRealKeyword(kw, "지우 오늘 조퇴"))
        assertFalse(AppFilter.hasRealKeyword(kw, "지우개"))
        assertTrue(AppFilter.hasKeyword(listOf("회의"), "회의록 보내 주세요"))       // 선생님이 넣은 키워드는 예전처럼 낱말 안도
    }
}
