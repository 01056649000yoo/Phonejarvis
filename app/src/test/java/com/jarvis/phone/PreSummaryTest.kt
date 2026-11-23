package com.jarvis.phone

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⚡ 미리 요약을 할지: 충전 중이면 수시로, 아니면 배터리 30% 이상·15개 이상 쌓였을 때, 언제나 화면이 꺼져 있을 때만 */
class PreSummaryTest {
    private fun c(pending: Int, battery: Int = 80, charging: Boolean = false, screenOn: Boolean = false, thermal: Int = PowerManager.THERMAL_STATUS_NONE,
                  powerSave: Boolean = false, busy: Boolean = false) =
        PreSummary.Check(pending, PhoneLoad.State(thermal, battery, charging, powerSave), screenOn, busy, hasModel = true)

    @Test fun 충전_중이면_하나라도_쌓이면_10분마다() {
        assertNull(PreSummary.blockReason(c(1, battery = 10, charging = true)))
        assertEquals(PreSummary.CHARGING_GAP_MIN, PreSummary.delayMin(c(1, charging = true)))
        assertEquals("쌓인 것 없음", PreSummary.blockReason(c(0, charging = true)))
    }

    @Test fun 배터리로는_30퍼센트_이상이고_15개_이상일_때만() {
        assertEquals("쌓인 것 14개", PreSummary.blockReason(c(14)))
        assertNull(PreSummary.blockReason(c(15)))
        assertEquals("배터리 29%", PreSummary.blockReason(c(40, battery = 29)))
        assertEquals("절전 모드", PreSummary.blockReason(c(40, powerSave = true)))
        assertEquals(PreSummary.BATTERY_DELAY_MIN, PreSummary.delayMin(c(15)))
    }

    @Test fun 화면이_켜져_있거나_따뜻하거나_다른_요약_중이면_안_함() {
        assertEquals("화면 켜짐", PreSummary.blockReason(c(20, charging = true, screenOn = true)))
        assertEquals(PreSummary.CHARGING_GAP_MIN, PreSummary.delayMin(c(20, charging = true, screenOn = true)))   // 화면만 걸리면 예약은 함
        assertEquals("폰이 따뜻함", PreSummary.blockReason(c(20, charging = true, thermal = PowerManager.THERMAL_STATUS_MODERATE)))
        assertNull(PreSummary.delayMin(c(20, thermal = PowerManager.THERMAL_STATUS_MODERATE)))
        assertEquals("다른 요약 중", PreSummary.blockReason(c(20, charging = true, busy = true)))
    }

    @Test fun 하는_중에도_화면이_켜지거나_배터리가_떨어지면_멈춤() {
        assertNull(PreSummary.keepGoingBlock(PhoneLoad.State(0, 50, false, false), false))
        assertEquals("화면이 켜져 멈춤", PreSummary.keepGoingBlock(PhoneLoad.State(0, 50, true, false), true))
        assertEquals("배터리를 아끼려고 멈춤", PreSummary.keepGoingBlock(PhoneLoad.State(0, 29, false, false), false))
        assertNull(PreSummary.keepGoingBlock(PhoneLoad.State(0, 10, true, false), false))
    }
}
