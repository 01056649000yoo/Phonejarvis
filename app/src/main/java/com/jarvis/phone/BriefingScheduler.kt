package com.jarvis.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Calendar

/** 아침·저녁 브리핑 시간 설정. 시간은 자정부터의 분(예: 08:00 = 480). */
enum class BriefingSlot(val label: String, val defaultMinute: Int) {
    MORNING("아침 브리핑", 8 * 60),
    EVENING("저녁 브리핑", 21 * 60);

    private val key get() = name.lowercase()

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("${key}_on", false)
    fun minute(ctx: Context) = prefs(ctx).getInt("${key}_min", defaultMinute)

    fun save(ctx: Context, enabled: Boolean, minute: Int) {
        prefs(ctx).edit().putBoolean("${key}_on", enabled).putInt("${key}_min", minute).apply()
        BriefingScheduler.schedule(ctx, this)
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("briefing", Context.MODE_PRIVATE)
    }
}

/**
 * 정확한 시각 알람(AlarmManager)으로 브리핑을 예약.
 * 정확한 알람은 백그라운드에서도 포그라운드 서비스를 시작할 수 있는 예외라서,
 * 폰이 잠겨 있어도 정해진 시각에 모델을 돌릴 수 있다.
 */
object BriefingScheduler {
    const val ACTION_BRIEFING = "com.jarvis.phone.ACTION_BRIEFING"
    const val EXTRA_SLOT = "slot"

    fun scheduleAll(ctx: Context) = BriefingSlot.entries.forEach { schedule(ctx, it) }

    fun schedule(ctx: Context, slot: BriefingSlot) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(ctx, slot)
        am.cancel(pi)
        if (!slot.isEnabled(ctx)) return

        val at = nextTrigger(slot.minute(ctx))
        if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)   // 권한이 없을 때 대략적인 시각
    }

    fun canExact(am: AlarmManager) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** 다음 해당 시각. 이미 지났으면 내일. */
    fun nextTrigger(minuteOfDay: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now + 5_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pendingIntent(ctx: Context, slot: BriefingSlot): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, slot.ordinal,
            Intent(ctx, BriefingReceiver::class.java).setAction(ACTION_BRIEFING)
                .putExtra(EXTRA_SLOT, slot.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 브리핑 서비스 시작 (알람 수신 시, 또는 화면의 "지금 브리핑" 버튼). */
    fun startNow(ctx: Context, label: String) {
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, BriefingService::class.java).putExtra(BriefingService.EXTRA_LABEL, label)
        )
    }
}

/** 알람 시각 도착 → 브리핑 시작 + 다음 날 예약. 재부팅·시간 변경 시 알람 다시 등록. */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            BriefingScheduler.ACTION_BRIEFING -> {
                val slot = runCatching { BriefingSlot.valueOf(intent.getStringExtra(BriefingScheduler.EXTRA_SLOT)!!) }
                    .getOrNull() ?: return
                BriefingScheduler.schedule(ctx, slot)        // 내일 같은 시각
                if (slot.isEnabled(ctx)) BriefingScheduler.startNow(ctx, slot.label)
            }
            else -> BriefingScheduler.scheduleAll(ctx)      // BOOT_COMPLETED, 시간/시간대 변경, 앱 업데이트
        }
    }
}
