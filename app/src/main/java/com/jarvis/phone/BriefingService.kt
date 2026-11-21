package com.jarvis.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 예약 브리핑을 실행하는 포그라운드 서비스.
 * 오늘(0시 이후) 지난 브리핑 다음에 쌓인 알림을 Gemma로 요약 → DB 저장 → 결과를 알림으로 보여준다.
 */
class BriefingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 진행 알림의 "중지" 버튼
        if (intent?.action == ACTION_STOP) {
            Summarizer.get(this).requestStop()
            if (running) Notifications.notify(this, Notifications.ID_PROGRESS,
                Notifications.progress(this, "멈추는 중… 지금 하던 부분이 끝나면 멈춰요 (최대 1분)", showStop = false))
            else stopSelf()
            return START_NOT_STICKY
        }
        Notifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, Notifications.ID_PROGRESS, Notifications.progress(this, "브리핑 준비 중…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (running) return START_NOT_STICKY     // 이미 도는 중이면 겹쳐 실행하지 않음
        running = true

        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "브리핑"
        // 화면이 꺼져 있어도 CPU가 잠들지 않도록 (최대 30분)
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:briefing")
            .apply { acquire(30 * 60 * 1000L) }

        scope.launch {
            try {
                val result = Briefings.runSinceLast(this@BriefingService, label) { s ->
                    Notifications.notify(this@BriefingService, Notifications.ID_PROGRESS,
                        Notifications.progress(this@BriefingService, s))
                }
                Notifications.notify(this@BriefingService, Notifications.ID_RESULT,
                    Notifications.result(this@BriefingService, result.title, result.body))
            } catch (s: Summarizer.StoppedException) {
                Notifications.notify(this@BriefingService, Notifications.ID_RESULT,
                    Notifications.result(this@BriefingService, "$label 중지됨", s.message ?: "중지했어요."))
            } catch (t: Throwable) {
                Notifications.notify(this@BriefingService, Notifications.ID_RESULT,
                    Notifications.result(this@BriefingService, "$label 실패", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                // 백그라운드에서 수 GB 모델을 계속 들고 있지 않도록 내림
                runCatching { Summarizer.get(this@BriefingService).release() }
                if (wake.isHeld) wake.release()
                running = false
                ServiceCompat.stopForeground(this@BriefingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_LABEL = "label"
        const val ACTION_STOP = "com.jarvis.phone.STOP_BRIEFING"
    }
}

/** 브리핑 실행 로직 (서비스·화면 공용). */
object Briefings {
    data class Outcome(val title: String, val body: String)


    /** 하루 단위: 오늘 0시 이후, 지난 브리핑 다음에 온 알림만 요약해 저장 (어제 이전 알림은 넣지 않음). */
    suspend fun runSinceLast(ctx: Context, label: String, onStatus: (String) -> Unit): Outcome {
        val db = NotificationDb.get(ctx)
        val from = maxOf(db.lastBriefing()?.time ?: 0L, NotificationDb.startOfToday())
        return run(ctx, label, from, onStatus)
    }

    suspend fun run(ctx: Context, label: String, from: Long, onStatus: (String) -> Unit): Outcome {
        val db = NotificationDb.get(ctx)
        val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel()) return Outcome("$label 건너뜀", "AI 모델이 없어요. 쌤톡스 앱에서 모델 파일을 선택하세요.")

        val excluded = AppFilter.excluded(ctx)
        val items = db.since(from).filterNot { it.pkg in excluded }   // 설정에서 뺀 앱은 요약에서도 제외
        if (items.isEmpty()) return Outcome(label, "새로 온 알림이 없어요.")

        val start = System.currentTimeMillis()
        val text = summarizer.summarize(items, onStatus)
        val now = System.currentTimeMillis()
        db.insertBriefing(Briefing(time = now, fromTime = from, label = label, count = items.size, text = text))
        onStatus("완료 (${(now - start) / 1000}초)")
        return Outcome("$label · 알림 ${items.size}건", text)
    }
}

object Notifications {
    private const val CH_PROGRESS = "progress"
    private const val CH_BRIEFING = "briefing"
    const val ID_PROGRESS = 1
    const val ID_RESULT = 2

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "브리핑 진행 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_BRIEFING, "쌤톡스 브리핑", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun progress(ctx: Context, text: String, showStop: Boolean = true): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_ssamtox)
            .setColor(0xFF2E4A3D.toInt())
            .setContentTitle("쌤톡스가 알림을 정리하는 중")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                // 너무 오래 걸릴 때 알림창에서 바로 멈추기
                if (showStop) addAction(0, "중지", PendingIntent.getService(
                    ctx, 1,
                    Intent(ctx, BriefingService::class.java).setAction(BriefingService.ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
            }
            .build()

    fun result(ctx: Context, title: String, body: String): Notification =
        NotificationCompat.Builder(ctx, CH_BRIEFING)
            .setSmallIcon(R.drawable.ic_stat_ssamtox)
            .setColor(0xFF2E4A3D.toInt())
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()

    @android.annotation.SuppressLint("MissingPermission")
    fun notify(ctx: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) nm.notify(id, n)
    }
}
