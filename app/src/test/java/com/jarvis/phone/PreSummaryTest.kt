package com.jarvis.phone

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⚡ 미리 요약을 할지: AI 에 넣을 글자가 MIN_CHARS 를 넘으면(충전 중이든 아니든), 배터리로는 30% 이상, 언제나 화면이 꺼져 있을 때만 */
class PreSummaryTest {
    private fun c(chars: Int, battery: Int = 80, charging: Boolean = false, screenOn: Boolean = false, thermal: Int = PowerManager.THERMAL_STATUS_NONE,
                  powerSave: Boolean = false, busy: Boolean = false) =
        PreSummary.Check(chars, PhoneLoad.State(thermal, battery, charging, powerSave), screenOn, busy, hasModel = true)
    private val min = PreSummary.MIN_CHARS

    @Test fun 글자_수를_넘어야_미리_요약_충전_중이어도_같음() {
        assertEquals("쌓인 글자 ${min - 1}자", PreSummary.blockReason(c(min - 1)))
        assertEquals("쌓인 글자 ${min - 1}자", PreSummary.blockReason(c(min - 1, charging = true)))
        assertNull(PreSummary.blockReason(c(min)))
        assertNull(PreSummary.blockReason(c(min, battery = 10, charging = true)))
        assertEquals("쌓인 것 없음", PreSummary.blockReason(c(0, charging = true)))
        assertEquals(PreSummary.READY_DELAY_MIN, PreSummary.delayMin(c(min)))
        assertNull(PreSummary.delayMin(c(min - 1)))
    }

    @Test fun 배터리로는_30퍼센트_이상_절전_아닐_때만() {
        assertEquals("배터리 29%", PreSummary.blockReason(c(min * 2, battery = 29)))
        assertEquals("절전 모드", PreSummary.blockReason(c(min * 2, powerSave = true)))
    }

    @Test fun 화면이_켜져_있거나_따뜻하거나_다른_요약_중이면_안_함() {
        assertEquals("화면 켜짐", PreSummary.blockReason(c(min, charging = true, screenOn = true)))
        assertEquals(PreSummary.READY_DELAY_MIN, PreSummary.delayMin(c(min, charging = true, screenOn = true)))   // 화면만 걸리면 예약은 함
        assertEquals("폰이 따뜻함", PreSummary.blockReason(c(min, charging = true, thermal = PowerManager.THERMAL_STATUS_MODERATE)))
        assertNull(PreSummary.delayMin(c(min, thermal = PowerManager.THERMAL_STATUS_MODERATE)))
        assertEquals("다른 요약 중", PreSummary.blockReason(c(min, charging = true, busy = true)))
    }

    @Test fun 하는_중에도_화면이_켜지거나_배터리가_떨어지면_멈춤() {
        assertNull(PreSummary.keepGoingBlock(PhoneLoad.State(0, 50, false, false), false))
        assertEquals("화면이 켜져 멈춤", PreSummary.keepGoingBlock(PhoneLoad.State(0, 50, true, false), true))
        assertEquals("배터리를 아끼려고 멈춤", PreSummary.keepGoingBlock(PhoneLoad.State(0, 29, false, false), false))
        assertNull(PreSummary.keepGoingBlock(PhoneLoad.State(0, 10, true, false), false))
    }
}
