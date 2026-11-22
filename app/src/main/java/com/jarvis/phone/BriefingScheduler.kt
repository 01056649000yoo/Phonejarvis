package com.jarvis.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * 정확한 시각 알람(AlarmManager)으로 요약을 예약 (v1.7.0: 규칙이 여러 개라 '다음 한 번'만 예약).
 * 정확한 알람은 백그라운드에서도 포그라운드 서비스를 시작할 수 있는 예외라서,
 * 폰이 잠겨 있어도 정해진 시각에 모델을 돌릴 수 있다. 요약이 끝나면(그리고 알람이 울리면) 그다음을 다시 건다.
 */
object BriefingScheduler {
    const val ACTION_BRIEFING = "com.jarvis.phone.ACTION_BRIEFING"
    const val ACTION_PREPARE = "com.jarvis.phone.ACTION_PREPARE"
    const val ACTION_DAILY = "com.jarvis.phone.ACTION_DAILY"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MINUTE = "minute"
    /**
     * 정한 시각보다 이만큼 먼저 백그라운드에서 요약을 돌려 둠 (2026-11-22 선생님: 15분 → 5분 전).
     * 정한 시각까지 못 끝나면 끝나는 대로 바로 알림(showWhenDone).
     */
    const val LEAD_MINUTES = 5

    fun nextRun(ctx: Context, now: Long = System.currentTimeMillis()): SummarySchedule.Run? =
        SummarySchedule.next(now, SummarySchedule.config(ctx)) { RestDays.isRest(ctx, it) }

    /** 다음 요약 한 번(그리고 정한 시각이면 5분 전 미리 준비)을 예약 */
    fun scheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // 🗓 하루 정리: 요약 예약과 따로 하루 한 번
        val dailyPi = PendingIntent.getBroadcast(ctx, 20, Intent(ctx, BriefingReceiver::class.java).setAction(ACTION_DAILY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(dailyPi)
        DailyDigest.next(System.currentTimeMillis(), SummarySchedule.config(ctx)) { RestDays.isRest(ctx, it) }?.let { set(am, it, dailyPi) }
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
    fun startNow(ctx: Context, label: String, prepare: Boolean = false, auto: Boolean = false, rolling: Boolean = false,
                 topUp: Ready? = null) {
        val i = Intent(ctx, BriefingService::class.java).putExtra(BriefingService.EXTRA_LABEL, label)
            .putExtra(BriefingService.EXTRA_PREPARE, prepare).putExtra(BriefingService.EXTRA_AUTO, auto)
            .putExtra(BriefingService.EXTRA_ROLLING, rolling)
        topUp?.let {
            i.putExtra(BriefingService.EXTRA_TOPUP_TIME, it.briefingTime).putExtra(BriefingService.EXTRA_TOPUP_TITLE, it.title)
                .putExtra(BriefingService.EXTRA_TOPUP_BODY, it.body)
        }
        startService(ctx, i, label)
    }

    /**
     * 백그라운드에서 요약 서비스 시작. 정확한 알람 권한이 없을 때(대략적인 시각 알람) 안드로이드가 시작을 막을 수 있어
     * 앱이 죽지 않게 받고, 📝 최근 자동 요약에 남긴다.
     */
    fun startService(ctx: Context, i: Intent, label: String) {
        try { ContextCompat.startForegroundService(ctx, i) }
        catch (e: Exception) { AutoLog.add(ctx, label, "실패 · 백그라운드 시작 막힘(알람 및 리마인더 권한을 켜 주세요)") }
    }

    fun labelOf(kind: SummarySchedule.Kind, minute: Int): String {
        val hm = "%02d:%02d".format(minute / 60, minute % 60)
        return when (kind) {
            SummarySchedule.Kind.FIXED -> when { minute < 11 * 60 -> "🌅 아침 요약"; minute >= 16 * 60 -> "🌙 저녁 요약"; else -> "📋 $hm 요약" }
            SummarySchedule.Kind.INTERVAL -> "⏱ $hm 요약"
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
 * - 준비 알람(정한 시각 5분 전): 백그라운드에서 조용히 요약해 결과를 저장
 * - 요약 알람: 정한 시각이면 준비해 둔 결과를 바로 알림(없으면 그때 요약), 간격·수업 끝·출근이면 그때 요약
 * 울릴 때마다 그다음 요약을 다시 예약. 재부팅·시간 변경·앱 업데이트 때 알람 다시 등록.
 */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = runCatching { SummarySchedule.Kind.valueOf(intent.getStringExtra(BriefingScheduler.EXTRA_KIND)!!) }.getOrNull()
        val minute = intent.getIntExtra(BriefingScheduler.EXTRA_MINUTE, 0)
        when (intent.action) {
            BriefingScheduler.ACTION_DAILY -> {
                BriefingScheduler.scheduleAll(ctx)        // 내일 하루 정리
                // 다른 요약이 도는 중이면 그 요약이 끝난 뒤 이어서
                if (BriefingService.active) BriefingService.dailyAfter = true
                else BriefingScheduler.startService(ctx, Intent(ctx, BriefingService::class.java)
                    .putExtra(BriefingService.EXTRA_LABEL, DailyDigest.LABEL).putExtra(BriefingService.EXTRA_DAILY, true), DailyDigest.LABEL)
            }
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
                        // ➕ 미리 준비한 뒤(5분 사이) 온 알림도 이번 요약에 — 예전엔 다음 요약으로 넘어가
                        // 정각 요약에 카톡 일부가 빠져 보였다(2026-11-22 선생님)
                        val pending = runCatching {
                            val db = NotificationDb.get(ctx); val rules = AppFilter.rules(ctx)
                            db.since(Briefings.pendingFrom(db)).count { rules.allows(it) }
                        }.getOrDefault(0)
                        if (ready.briefingTime == 0L && pending > 0) {
                            // 준비 때는 새 알림이 없었는데 그 뒤 왔음 → '없음' 대신 지금 요약
                            AutoLog.add(ctx, label, "준비 뒤 새 알림 ${pending}건 · 지금 요약")
                            BriefingScheduler.startNow(ctx, label, auto = true)
                        } else {
                            AutoLog.add(ctx, label, "알림 보냄 · 미리 준비한 요약")
                            AppScope.main.launch { SummaryWidget.refresh(ctx) }
                            Notifications.ensureChannels(ctx)
                            // ⏰ 정한 시각에 요약 알림 (누르면 세부 내역)
                            Notifications.notify(ctx, Notifications.idFor(ready.briefingTime),
                                Notifications.result(ctx, ready.title, ready.body, ready.briefingTime))
                            // 그 뒤 온 알림은 이어서 요약해 같은 알림을 고쳐 씀(소리 없이) — 그 실행이 문자 답장 초안도 씀
                            if (pending > 0) BriefingScheduler.startNow(ctx, label, auto = true, topUp = ready)
                            // 새로 온 것은 없고 초안을 기다리는 답장할 문자만 있으면 초안만 씀(미리 준비 때는 결과가 늦지 않게 안 씀)
                            else if (Briefings.hasPendingDrafts(ctx)) BriefingScheduler.startService(ctx,
                                Intent(ctx, BriefingService::class.java).putExtra(BriefingService.EXTRA_LABEL, label)
                                    .putExtra(BriefingService.EXTRA_DRAFT_ONLY, true), label)
                        }
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

/**
 * 📝 자동 요약 기록 (2026-11-22 선생님: "9시 자동 요약이 안 돌아감", "자동으로 돌면 위젯에도") —
 * 예약으로 돈 요약마다 언제·무엇을·결과(요약함/새 알림 없음/실패…)를 최근 15개까지. 설정과 위젯에 보여 줌.
 */
object AutoLog {
    data class Entry(val at: Long, val label: String, val result: String)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autolog", Context.MODE_PRIVATE)
    fun add(ctx: Context, label: String, result: String, at: Long = System.currentTimeMillis()) {
        val list = (listOf(Entry(at, label, result)) + all(ctx)).take(15)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("at", it.at).put("label", it.label).put("result", it.result)) }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }
    fun all(ctx: Context): List<Entry> = runCatching {
        val a = org.json.JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getLong("at"), o.getString("label"), o.getString("result")) } }
    }.getOrDefault(emptyList())
    fun last(ctx: Context): Entry? = all(ctx).firstOrNull()
}
