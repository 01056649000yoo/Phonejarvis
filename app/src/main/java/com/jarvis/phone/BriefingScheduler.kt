package com.jarvis.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * 정확한 시각 알람(AlarmManager)으로 요약을 예약 (v1.7.0: 규칙이 여러 개라 '다음 한 번'만 예약).
 * 정확한 알람은 백그라운드에서도 포그라운드 서비스를 시작할 수 있는 예외라서,
 * 폰이 잠겨 있어도 정해진 시각에 모델을 돌릴 수 있다. 요약이 끝나면(그리고 알람이 울리면) 그다음을 다시 건다.
 */
object BriefingScheduler {
    const val ACTION_BRIEFING = "com.jarvis.phone.ACTION_BRIEFING"
    const val ACTION_PREPARE = "com.jarvis.phone.ACTION_PREPARE"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MINUTE = "minute"
    /** 정한 시각보다 이만큼 먼저 백그라운드에서 요약을 돌려 둠 (모델 불러오기 + 최대 5분 요약) */
    const val LEAD_MINUTES = 15

    fun day(ctx: Context) = SummarySchedule.Day(
        classEnds = if (QuietMode.classOn(ctx)) QuietMode.ranges(ctx).map { it.second } else emptyList(),
        workStart = if (QuietMode.afterWorkOn(ctx)) QuietMode.workStart(ctx) else null,
    )

    fun nextRun(ctx: Context, now: Long = System.currentTimeMillis()): SummarySchedule.Run? =
        SummarySchedule.next(now, SummarySchedule.config(ctx), day(ctx)) { RestDays.isRest(ctx, it) }

    /** 다음 요약 한 번(그리고 정한 시각이면 15분 전 미리 준비)을 예약 */
    fun scheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val run = nextRun(ctx)
        val show = pendingIntent(ctx, ACTION_BRIEFING, run)
        val prep = pendingIntent(ctx, ACTION_PREPARE, run)
        am.cancel(show); am.cancel(prep)
        run ?: return
        set(am, run.at, show)
        // 정한 시각만 미리 준비. 준비 시각이 이미 지났으면 그 시각에 바로 요약
        val prepAt = run.at - LEAD_MINUTES * 60_000L
        if (run.kind == SummarySchedule.Kind.FIXED && prepAt > System.currentTimeMillis() + 5_000) set(am, prepAt, prep)
    }

    private fun set(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)   // 권한이 없을 때 대략적인 시각
    }

    fun canExact(am: AlarmManager) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun pendingIntent(ctx: Context, action: String, run: SummarySchedule.Run?): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, if (action == ACTION_PREPARE) 10 else 0,
            Intent(ctx, BriefingReceiver::class.java).setAction(action)
                .putExtra(EXTRA_KIND, run?.kind?.name).putExtra(EXTRA_MINUTE, run?.minute ?: 0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** auto: 예약으로 돈 요약(새 알림이 없으면 알리지 않음), rolling: 알림 하나를 새 내용으로 바꿔 보여 줌(간격·수업 끝) */
    fun startNow(ctx: Context, label: String, prepare: Boolean = false, auto: Boolean = false, rolling: Boolean = false) {
        val i = Intent(ctx, BriefingService::class.java).putExtra(BriefingService.EXTRA_LABEL, label)
            .putExtra(BriefingService.EXTRA_PREPARE, prepare).putExtra(BriefingService.EXTRA_AUTO, auto)
            .putExtra(BriefingService.EXTRA_ROLLING, rolling)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun labelOf(kind: SummarySchedule.Kind, minute: Int): String {
        val hm = "%02d:%02d".format(minute / 60, minute % 60)
        return when (kind) {
            SummarySchedule.Kind.FIXED -> when { minute < 11 * 60 -> "🌅 아침 요약"; minute >= 16 * 60 -> "🌙 저녁 요약"; else -> "📋 $hm 요약" }
            SummarySchedule.Kind.INTERVAL -> "⏱ $hm 요약"
            SummarySchedule.Kind.CLASS_END -> "🏫 수업 뒤 요약"
            SummarySchedule.Kind.QUIET_END -> "🌅 밤사이 요약"
        }
    }

    // ── 미리 준비한 결과 (정한 시각에 꺼내 보여 줌) ──
    private fun readyPrefs(ctx: Context) = ctx.getSharedPreferences("briefing_ready", Context.MODE_PRIVATE)
    fun saveReady(ctx: Context, title: String, body: String, briefingTime: Long = 0) {
        readyPrefs(ctx).edit().putString("title", title).putString("body", body)
            .putLong("bt", briefingTime).putLong("at", System.currentTimeMillis()).apply()
    }
    data class Ready(val title: String, val body: String, val briefingTime: Long)
    /** 최근 1시간 안에 준비해 둔 결과를 꺼내고 지움. 없으면 null */
    fun takeReady(ctx: Context): Ready? {
        val p = readyPrefs(ctx)
        val at = p.getLong("at", 0); val title = p.getString("title", null); val body = p.getString("body", null); val bt = p.getLong("bt", 0)
        p.edit().clear().apply()
        if (title == null || body == null || System.currentTimeMillis() - at > 60 * 60_000L) return null
        return Ready(title, body, bt)
    }
}

/**
 * 알람 도착.
 * - 준비 알람(정한 시각 15분 전): 백그라운드에서 조용히 요약해 결과를 저장
 * - 요약 알람: 정한 시각이면 준비해 둔 결과를 바로 알림(없으면 그때 요약), 간격·수업 끝·출근이면 그때 요약
 * 울릴 때마다 그다음 요약을 다시 예약. 재부팅·시간 변경·앱 업데이트 때 알람 다시 등록.
 */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = runCatching { SummarySchedule.Kind.valueOf(intent.getStringExtra(BriefingScheduler.EXTRA_KIND)!!) }.getOrNull()
        val minute = intent.getIntExtra(BriefingScheduler.EXTRA_MINUTE, 0)
        when (intent.action) {
            BriefingScheduler.ACTION_PREPARE -> {
                if (kind != null) BriefingScheduler.startNow(ctx, BriefingScheduler.labelOf(kind, minute), prepare = true, auto = true)
            }
            BriefingScheduler.ACTION_BRIEFING -> {
                BriefingScheduler.scheduleAll(ctx)        // 그다음 요약 (준비 알람 포함)
                kind ?: return
                val label = BriefingScheduler.labelOf(kind, minute)
                if (kind != SummarySchedule.Kind.FIXED) {
                    BriefingScheduler.startNow(ctx, label, auto = true, rolling = true); return
                }
                val ready = BriefingScheduler.takeReady(ctx)
                when {
                    ready != null -> {
                        Notifications.ensureChannels(ctx)
                        // ⏰ 정한 시각에 요약 알림 (누르면 세부 내역)
                        Notifications.notify(ctx, Notifications.idFor(ready.briefingTime),
                            Notifications.result(ctx, ready.title, ready.body, ready.briefingTime))
                    }
                    // 아직 준비 중이면(요약이 길어짐) 끝나는 대로 바로 알리게 표시
                    BriefingService.preparing -> BriefingService.showWhenDone = true
                    else -> BriefingScheduler.startNow(ctx, label, auto = true)
                }
            }
            else -> { BriefingScheduler.scheduleAll(ctx); QuietMode.tick(ctx) }   // BOOT_COMPLETED, 시간/시간대 변경, 앱 업데이트
        }
    }
}
