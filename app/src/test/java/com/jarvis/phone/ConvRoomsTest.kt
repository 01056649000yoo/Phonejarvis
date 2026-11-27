package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** 💬 방 이름이 없는 단톡방 알아보기(2026-11-26 선생님 캡처: 단톡방 말이 1:1 처럼, 서로 다른 톡방 말이 한 줄로 섞임) */
class ConvRoomsTest {
    @Test fun 같은_대화에서_두_사람이_나오면_단톡방() {
        var e = ConvRooms.note(null, listOf("김희엽 선생님"))
        assertEquals("", e.label)                                       // 한 사람뿐이면 아직 1:1
        e = ConvRooms.note(e, listOf("김희엽 선생님"))
        assertEquals("", e.label)
        e = ConvRooms.note(e, listOf("최성우"))
        assertEquals("김희엽 선생님·최성우 단톡방", e.label)             // 둘째 사람이 나오는 순간 단톡방
        e = ConvRooms.note(e, listOf("김진수", "윤현정 교감선생님"))
        assertEquals("김희엽 선생님·최성우 단톡방", e.label)             // 한 번 정한 이름은 그대로(방이 갈라지지 않게)
        assertEquals(4, e.senders.size)
    }

    // 2026-11-27: 카톡은 1:1 에도 '여럿이 있는 대화' 표시를 붙여 지인 1:1 이 '류성하 외 단톡방'이 되어 사라짐 — 표시만으로는 단톡방이 아님
    @Test fun 여럿이라는_표시만으로는_단톡방이_아님() {
        assertEquals("", ConvRooms.note(null, listOf("류성하"), groupFlag = true).label)
        assertEquals("", ConvRooms.note(null, emptyList(), groupFlag = true).label)
        assertEquals("류성하·최성우 단톡방", ConvRooms.note(ConvRooms.note(null, listOf("류성하"), true), listOf("최성우"), true).label)
        // 예전에 표시만 보고 붙인 이름은 지우고 1:1 로, 두 사람 넘게 본 단톡방은 그대로
        assertEquals("", ConvRooms.repair(ConvRooms.Entry(listOf("류성하"), "류성하 외 단톡방")).label)
        assertEquals("류성하 외 단톡방", ConvRooms.repair(ConvRooms.Entry(listOf("류성하", "최성우"), "류성하 외 단톡방")).label)
        // 한 번 지운 뒤 같은 사람만 다시 나오면 계속 1:1
        assertEquals("", ConvRooms.note(ConvRooms.repair(ConvRooms.Entry(listOf("류성하"), "류성하 외 단톡방")), listOf("류성하"), true).label)
    }
}
