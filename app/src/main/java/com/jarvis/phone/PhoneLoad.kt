package com.jarvis.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager

/**
 * 🌡️ 폰에 무리가 가지 않게 (2026-11-22 선생님: "Gemma 로 가자. 대신 휴대폰에 무리가 가지 않게").
 * AI(수 GB 모델을 GPU 에 올림)를 쓰기 전에 폰 상태를 본다.
 *  - 폰이 뜨겁거나(발열 '심함' 이상) 배터리가 15% 이하인데 충전 중이 아니면 → AI 없이 정리(최근 말 그대로)
 *  - 조금 뜨겁거나(발열 '보통') 절전 모드·배터리 20% 이하면 → 요약은 하되 급하지 않은 AI 일(답장 초안)은 미룸
 */
object PhoneLoad {
    data class State(val thermal: Int, val battery: Int, val charging: Boolean, val powerSave: Boolean)

    fun state(ctx: Context): State {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val thermal = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val bat = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = bat?.let { i ->
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else 100
        } ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return State(thermal, level, plugged, runCatching { pm.isPowerSaveMode }.getOrDefault(false))
    }

    /** AI 를 아예 쓰지 말아야 하면 그 까닭(요약 끝에 한 줄로), 아니면 null */
    fun skipAiReason(s: State): String? = when {
        s.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "🌡️ 폰이 뜨거워 AI 없이 정리했어요(최근 말 그대로). 식은 뒤 다시 요약하면 AI가 정리해요."
        s.battery <= 15 && !s.charging -> "🔋 배터리가 ${s.battery}%라 AI 없이 정리했어요(최근 말 그대로). 충전 중에 다시 요약하면 AI가 정리해요."
        else -> null
    }

    /** 답장 초안처럼 급하지 않은 AI 일을 미뤄야 하나 */
    fun deferLightWork(s: State): Boolean = skipAiReason(s) != null ||
        s.thermal >= PowerManager.THERMAL_STATUS_MODERATE || (s.powerSave && !s.charging) || (s.battery <= 20 && !s.charging)

    fun thermalName(t: Int): String = when (t) {
        PowerManager.THERMAL_STATUS_NONE -> "보통"
        PowerManager.THERMAL_STATUS_LIGHT -> "조금 따뜻"
        PowerManager.THERMAL_STATUS_MODERATE -> "따뜻"
        PowerManager.THERMAL_STATUS_SEVERE -> "뜨거움"
        else -> "아주 뜨거움"
    }

    /** 지금 쌤톡스가 쓰는 메모리(MB, 모델 포함 — 모델이 올라 있을 때 재야 의미 있음) */
    fun appMemoryMb(): Int = runCatching { (Debug.getPss() / 1024).toInt() }.getOrDefault(0)

    /** CPU 로 돌 때 쓸 일꾼(스레드) 수 — 코어를 다 쓰면 화면이 버벅이므로 둘은 남기고 2~4개 */
    fun cpuThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
}
