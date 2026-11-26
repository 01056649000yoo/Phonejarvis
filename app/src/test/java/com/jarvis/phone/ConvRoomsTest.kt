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

    @Test fun 알림이_여럿이라고_알려_주면_한_사람만_봐도_단톡방() {
        assertEquals("김희엽 선생님 외 단톡방", ConvRooms.note(null, listOf("김희엽 선생님"), groupFlag = true).label)
        assertEquals("", ConvRooms.note(null, emptyList(), groupFlag = true).label)
    }
}
