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
enum class BriefingSlot(val label: String, val defaultMinute: Int, val presets: List<Int>) {
    MORNING("🌅 아침 브리핑 (조회)", 8 * 60, listOf(7 * 60 + 30, 8 * 60, 8 * 60 + 30, 9 * 60)),
    EVENING("🌙 저녁 브리핑 (종례)", 21 * 60, listOf(16 * 60 + 30, 17 * 60, 18 * 60, 21 * 60));

    private val key get() = name.lowercase()

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("${key}_on", false)
    fun minute(ctx: Context) = prefs(ctx).getInt("${key}_min", defaultMinute)

    fun save(ctx: Context, enabled: Boolean, minute: Int) {
        prefs(ctx).edit().putBoolean("${key}_on", enabled).putInt("${key}_min", minute).apply()
        BriefingScheduler.schedule(ctx, this)
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("briefing", Context.MODE_PRIVATE)

        /** 주말(토·일)에는 자동 브리핑 쉬기 */
        fun skipWeekend(ctx: Context) = prefs(ctx).getBoolean("skip_weekend", false)
        fun setSkipWeekend(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("skip_weekend", on).apply()
        fun isWeekendNow() = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
    }
}

/**
 * 정확한 시각 알람(AlarmManager)으로 브리핑을 예약.
 * 정확한 알람은 백그라운드에서도 포그라운드 서비스를 시작할 수 있는 예외라서,
 * 폰이 잠겨 있어도 정해진 시각에 모델을 돌릴 수 있다.
 */
object BriefingScheduler {
    const val ACTION_BRIEFING = "com.jarvis.phone.ACTION_BRIEFING"
    const val ACTION_PREPARE = "com.jarvis.phone.ACTION_PREPARE"
    const val EXTRA_SLOT = "slot"
    /** 브리핑 시각보다 이만큼 먼저 백그라운드에서 요약을 돌려 둠 (모델 불러오기 + 최대 5분 요약) */
    const val LEAD_MINUTES = 15

    fun scheduleAll(ctx: Context) = BriefingSlot.entries.forEach { schedule(ctx, it) }

    /** 브리핑 시각(결과 보여 주기)과 그 15분 전(미리 준비) 두 알람을 예약. */
    fun schedule(ctx: Context, slot: BriefingSlot) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = pendingIntent(ctx, slot, ACTION_BRIEFING)
        val prep = pendingIntent(ctx, slot, ACTION_PREPARE)
        am.cancel(show); am.cancel(prep)
        if (!slot.isEnabled(ctx)) return

        val at = nextTrigger(slot.minute(ctx))
        set(am, at, show)
        // 준비 시각이 이미 지났으면(예: 브리핑 10분 전에 설정을 바꿈) 준비 없이 그 시각에 바로 요약
        val prepAt = at - LEAD_MINUTES * 60_000L
        if (prepAt > System.currentTimeMillis() + 5_000) set(am, prepAt, prep)
    }

    private fun set(am: AlarmManager, at: Long, pi: PendingIntent) {
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

    private fun pendingIntent(ctx: Context, slot: BriefingSlot, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, slot.ordinal + if (action == ACTION_PREPARE) 10 else 0,
            Intent(ctx, BriefingReceiver::class.java).setAction(action).putExtra(EXTRA_SLOT, slot.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 브리핑 서비스 시작. prepareSlot이 있으면 결과를 알리지 않고 저장만 (미리 준비). */
    fun startNow(ctx: Context, label: String, prepareSlot: BriefingSlot? = null) {
        val i = Intent(ctx, BriefingService::class.java).putExtra(BriefingService.EXTRA_LABEL, label)
        if (prepareSlot != null) i.putExtra(BriefingService.EXTRA_PREPARE, prepareSlot.name)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun labelOf(slot: BriefingSlot) = if (slot == BriefingSlot.MORNING) "아침 브리핑" else "저녁 브리핑"

    // ── 미리 준비한 결과 (브리핑 시각에 꺼내 보여 줌) ──
    private fun readyPrefs(ctx: Context) = ctx.getSharedPreferences("briefing_ready", Context.MODE_PRIVATE)
    fun saveReady(ctx: Context, slot: BriefingSlot, title: String, body: String, briefingTime: Long = 0) {
        readyPrefs(ctx).edit().putString("${slot.name}_title", title).putString("${slot.name}_body", body)
            .putLong("${slot.name}_bt", briefingTime)
            .putLong("${slot.name}_at", System.currentTimeMillis()).apply()
    }
    data class Ready(val title: String, val body: String, val briefingTime: Long)
    /** 최근 1시간 안에 준비해 둔 결과를 꺼내고 지움. 없으면 null */
    fun takeReady(ctx: Context, slot: BriefingSlot): Ready? {
        val p = readyPrefs(ctx)
        val at = p.getLong("${slot.name}_at", 0)
        val title = p.getString("${slot.name}_title", null)
        val body = p.getString("${slot.name}_body", null)
        val bt = p.getLong("${slot.name}_bt", 0)
        p.edit().remove("${slot.name}_at").remove("${slot.name}_title").remove("${slot.name}_body").remove("${slot.name}_bt").apply()
        if (title == null || body == null || System.currentTimeMillis() - at > 60 * 60_000L) return null
        return Ready(title, body, bt)
    }
}

/**
 * 알람 도착.
 * - 준비 알람(15분 전): 백그라운드에서 조용히 요약해 결과를 저장
 * - 브리핑 알람(정한 시각): 준비해 둔 결과를 바로 알림으로. 없으면(폰이 꺼져 있었거나 실패) 그때 요약
 * 재부팅·시간 변경 시 알람 다시 등록.
 */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val slot = runCatching { BriefingSlot.valueOf(intent.getStringExtra(BriefingScheduler.EXTRA_SLOT)!!) }.getOrNull()
        val restDay = BriefingSlot.skipWeekend(ctx) && BriefingSlot.isWeekendNow()   // 주말엔 쉬기
        when (intent.action) {
            BriefingScheduler.ACTION_PREPARE -> {
                if (slot != null && slot.isEnabled(ctx) && !restDay)
                    BriefingScheduler.startNow(ctx, BriefingScheduler.labelOf(slot), prepareSlot = slot)
            }
            BriefingScheduler.ACTION_BRIEFING -> {
                slot ?: return
                BriefingScheduler.schedule(ctx, slot)        // 내일 같은 시각 (준비 알람 포함)
                if (!slot.isEnabled(ctx) || restDay) return
                val ready = BriefingScheduler.takeReady(ctx, slot)
                when {
                    ready != null -> {
                        Notifications.ensureChannels(ctx)
                        // ⏰ 정한 시각에 정기 브리핑 알림 (누르면 세부 내역)
                        Notifications.notify(ctx, Notifications.idFor(ready.briefingTime),
                            Notifications.result(ctx, ready.title, ready.body, ready.briefingTime))
                    }
                    // 아직 준비 중이면(요약이 길어짐) 끝나는 대로 바로 알리게 표시
                    BriefingService.preparing == slot -> BriefingService.showWhenDone = true
                    else -> BriefingScheduler.startNow(ctx, BriefingScheduler.labelOf(slot))
                }
            }
            else -> { BriefingScheduler.scheduleAll(ctx); QuietMode.tick(ctx) }   // BOOT_COMPLETED, 시간/시간대 변경, 앱 업데이트
        }
    }
}
