package com.jarvis.phone

import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLoadTest {
    private fun st(t: Int = PowerManager.THERMAL_STATUS_NONE, b: Int = 80, c: Boolean = false, ps: Boolean = false) = PhoneLoad.State(t, b, c, ps)

    @Test fun hotOrLowBatterySkipsAi() {
        assertNull(PhoneLoad.skipAiReason(st()))
        assertNotNull(PhoneLoad.skipAiReason(st(t = PowerManager.THERMAL_STATUS_SEVERE)))
        assertNotNull(PhoneLoad.skipAiReason(st(b = 15)))
        assertNull(PhoneLoad.skipAiReason(st(b = 15, c = true)))   // 충전 중이면 AI 씀
        assertNull(PhoneLoad.skipAiReason(st(t = PowerManager.THERMAL_STATUS_MODERATE)))
    }

    @Test fun warmOrPowerSaveDefersDrafts() {
        assertFalse(PhoneLoad.deferLightWork(st()))
        assertTrue(PhoneLoad.deferLightWork(st(t = PowerManager.THERMAL_STATUS_MODERATE)))
        assertTrue(PhoneLoad.deferLightWork(st(ps = true)))
        assertFalse(PhoneLoad.deferLightWork(st(ps = true, c = true)))
        assertTrue(PhoneLoad.deferLightWork(st(b = 20)))
    }

    @Test fun timingShowsMemoryAndHeat() {
        val s = Summarizer.Stats().apply { loadMs = 21_000; backend = "GPU"; calls = 2; callMs = 27_000; memMb = 3174 }
        assertEquals("⏱ 48초 · 모델 불러오기 21초(GPU) · AI 2번 27초 · 메모리 3.1GB · 발열 보통",
            Briefings.timingLine(48_900, s, PowerManager.THERMAL_STATUS_NONE))
    }

    @Test fun noAiComposeKeepsLatestWords() = runBlocking {
        val items = (1..5).map { NotiItem(it.toLong(), "com.kakao.talk", "카카오톡", "학년 협의회", "회의 자료 ${it}쪽 확인 부탁드립니다 선생님들", 1000L * it, "학년 협의회") }
        val r = BriefingComposer.compose(items, ask = { "" })
        assertTrue(r.people, r.people.contains("회의 자료 5쪽"))
    }
}
