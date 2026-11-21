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


    /**
     * 아직 요약하지 않은 알림의 시작 시각: 오늘 0시와 지난 요약이 덮은 시각 다음 중 늦은 쪽.
     * 한 번 요약한 알림은 다음 요약(자동·수동 모두)에 다시 들어가지 않음.
     */
    fun pendingFrom(db: NotificationDb): Long =
        maxOf((db.lastBriefing()?.time ?: -1L) + 1, NotificationDb.startOfToday())

    /** 자동 브리핑·"지금 정리하기" 공용: 지난 요약 이후 새로 온 오늘 알림만 요약해 저장. */
    suspend fun runSinceLast(ctx: Context, label: String, onStatus: (String) -> Unit): Outcome =
        run(ctx, label, pendingFrom(NotificationDb.get(ctx)), onStatus)

    suspend fun run(ctx: Context, label: String, from: Long, onStatus: (String) -> Unit): Outcome {
        val db = NotificationDb.get(ctx)
        val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel()) return Outcome("$label 건너뜀", "AI 모델이 없어요. 쌤톡스 앱에서 모델 파일을 선택하세요.")

        // 이 시각까지 온 알림을 이번 요약이 맡음. 요약하는 몇 분 사이에 온 알림은 다음 요약으로 넘어감.
        val cutoff = System.currentTimeMillis()
        // 설정에서 뺀 앱·방은 요약에서도 제외 (뺀 방이라도 중요 키워드가 있으면 포함)
        val rules = AppFilter.rules(ctx)
        val items = db.since(from).filter { it.time <= cutoff && rules.allows(it) }
        if (items.isEmpty()) return Outcome(label, "지난 요약 이후 새로 온 알림이 없어요.")

        val start = System.currentTimeMillis()
        // ⭐ 키워드 알림: AI 없이 규칙으로 찾아 맨 위에 (놓치지 않게)
        val keywordPart = keywordSection(items, rules.keywords)
        // 👪 학부모 연락: 지정한 학부모 방의 연락을 분류해 맨 위에 (규칙, 즉시)
        val parentPart = parentSection(ParentAdvisor.contacts(items, ParentAdvisor.parentRooms(ctx), Students.names(ctx)))
        // 📌 오늘·내일 마감 (할 일 목록에서, 끝내지 않은 것)
        items.forEach { runCatching { TaskExtractor.save(ctx, it) } }
        val taskPart = taskSection(db.tasks())
        val text = taskPart + parentPart + keywordPart + summarizer.summarize(items, onStatus)
        val now = System.currentTimeMillis()
        // time = 이번 요약이 덮은 마지막 시각 (다음 요약은 이 다음부터)
        db.insertBriefing(Briefing(time = cutoff, fromTime = from, label = label, count = items.size, text = text))
        onStatus("완료 (${(now - start) / 1000}초)")
        return Outcome("$label · 알림 ${items.size}건", text)
    }

    /** 오늘·내일 마감인 할 일 (지난 것 포함). 없으면 빈 문자열. */
    fun taskSection(tasks: List<Task>): String {
        val now = System.currentTimeMillis()
        val soon = tasks.filter { !it.done && TaskExtractor.daysBetween(now, it.due) <= 1 }
        if (soon.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        return buildString {
            append("📌 오늘·내일 마감 (${soon.size}건)\n")
            soon.take(8).forEach { t ->
                val time = if (t.hasTime) " ${fmt.format(t.due)}" else ""
                append("- [${t.dLabel(now)}$time] ${t.title} (${t.room})\n")
            }
            append("\n")
        }
    }

    /** 학부모 연락 요약 (방마다 한 줄). 없으면 빈 문자열. */
    fun parentSection(contacts: List<ParentContact>): String {
        if (contacts.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        val need = contacts.count { it.category.needsReply }
        return buildString {
            append("👪 학부모 연락 (${contacts.size}건, 답장 필요 ${need})\n")
            contacts.take(10).forEach { c ->
                val mark = if (c.category.needsReply) " · 답장 필요" else ""
                val who = if (c.students.isNotEmpty()) " (${c.students.joinToString(", ")})" else ""
                append("- ${fmt.format(c.last.time)} ${c.room}$who [${c.category.label}$mark] ${c.last.text.replace('\n', ' ').take(70)}\n")
            }
            append("\n")
        }
    }

    /** 중요 키워드가 들어간 알림 목록 (최근 것부터 최대 10개). 없으면 빈 문자열. */
    fun keywordSection(items: List<NotiItem>, keywords: List<String>): String {
        if (keywords.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        val hits = items.filter { AppFilter.hasKeyword(keywords, it.title, it.text, it.room) }
            .distinctBy { it.roomOrTitle to it.text }
            .takeLast(10).reversed()
        if (hits.isEmpty()) return ""
        return buildString {
            append("⭐ 키워드 알림 (${hits.size}건)\n")
            hits.forEach { n ->
                val who = if (n.room.isNotEmpty() && n.room != n.title) "[${n.room}] ${n.title}" else n.title
                append("- ${fmt.format(n.time)} $who: ${n.text.replace('\n', ' ').take(80)}\n")
            }
            append("\n")
        }
    }
}

object Notifications {
    private const val CH_PROGRESS = "progress"
    private const val CH_BRIEFING = "briefing"
    const val ID_PROGRESS = 1
    const val ID_RESULT = 2
    const val ID_QUIET = 3

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
