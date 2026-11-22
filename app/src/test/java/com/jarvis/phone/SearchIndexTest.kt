package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchIndexTest {
    @Test fun 두_글자씩_겹쳐_자름() {
        assertEquals("현장 장체 체험 험학 학습 습은 도시 시락", SearchIndex.grams("현장체험학습은, 도시락!"))
        assertEquals("밥 bu us", SearchIndex.grams("밥 BUS"))
    }
    @Test fun 찾을_말은_조각_구절로() {
        assertEquals("\"체험 험학 학습\" \"도시 시락\"", SearchIndex.matchQuery("체험학습  도시락"))
        assertEquals("\"77 77\"", SearchIndex.matchQuery("777"))
        assertNull(SearchIndex.matchQuery("밥"))          // 한 글자는 훑어서
        assertNull(SearchIndex.matchQuery("  "))
    }
}
