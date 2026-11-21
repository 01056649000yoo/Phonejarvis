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
        // 미리 준비(브리핑 15분 전)면 결과를 알리지 않고 저장만 → 브리핑 시각에 바로 보여 줌
        val prepareSlot = intent?.getStringExtra(EXTRA_PREPARE)?.let { runCatching { BriefingSlot.valueOf(it) }.getOrNull() }
        val title = if (prepareSlot != null) "브리핑 미리 준비 중" else "쌤톡스가 알림을 정리하는 중"
        ServiceCompat.startForeground(
            this, Notifications.ID_PROGRESS, Notifications.progress(this, "브리핑 준비 중…", title = title),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (running) return START_NOT_STICKY     // 이미 도는 중이면 겹쳐 실행하지 않음
        running = true
        active = true
        preparing = prepareSlot
        showWhenDone = false

        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "브리핑"
        // 화면이 꺼져 있어도 CPU가 잠들지 않도록 (최대 30분)
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:briefing")
            .apply { acquire(30 * 60 * 1000L) }

        scope.launch {
            try {
                val result = Briefings.runSinceLast(this@BriefingService, label) { s ->
                    Notifications.notify(this@BriefingService, Notifications.ID_PROGRESS,
                        Notifications.progress(this@BriefingService, s, title = title))
                }
                if (prepareSlot != null && !showWhenDone) {
                    // 미리 준비 완료: 저장해 두었다가 브리핑 시각에 알림
                    BriefingScheduler.saveReady(this@BriefingService, prepareSlot, result.title, result.body, result.time)
                } else {
                    // 일반 실행이거나, 준비가 길어져 브리핑 시각이 이미 지난 경우 → 바로 알림
                    Notifications.notify(this@BriefingService, Notifications.idFor(result.time),
                        Notifications.result(this@BriefingService, result.title, result.body, result.time))
                }
            } catch (s: Summarizer.StoppedException) {
                Notifications.notify(this@BriefingService, Notifications.ID_RESULT,
                    Notifications.result(this@BriefingService, "$label 중지됨", s.message ?: "중지했어요."))
            } catch (t: Throwable) {
                Notifications.notify(this@BriefingService, Notifications.ID_RESULT,
                    Notifications.result(this@BriefingService, "$label 실패", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                // 백그라운드에서 수 GB 모델을 계속 들고 있지 않도록 내림
                runCatching { Summarizer.get(this@BriefingService).release() }
                runCatching { Backup.autoBackup(this@BriefingService) }   // 💾 하루 한 번 자동 백업
                runCatching { Storage.autoClean(this@BriefingService) }   // 🧹 앱을 안 열어도 오래된 기록 자동 정리
                if (wake.isHeld) wake.release()
                running = false
                active = false
                preparing = null
                ServiceCompat.stopForeground(this@BriefingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_PREPARE = "prepare"
        const val ACTION_STOP = "com.jarvis.phone.STOP_BRIEFING"

        /** 백그라운드 정리가 도는 중인지 (화면에서 "정리 중" 표시용) */
        @Volatile var active = false
            private set
        /** 지금 미리 준비 중인 브리핑 (없으면 null) */
        @Volatile var preparing: BriefingSlot? = null
            private set
        /** 준비가 브리핑 시각을 넘겼으면 끝나는 대로 바로 알림 */
        @Volatile var showWhenDone = false
    }
}

/** 브리핑 실행 로직 (서비스·화면 공용). */
object Briefings {
    /** time: 저장한 브리핑의 시각 키 (알림을 누르면 이 브리핑의 세부 내역을 엶). 0이면 저장 안 함 */
    data class Outcome(val title: String, val body: String, val time: Long = 0)


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
        val parentPart = if (People.isVisible(ctx, "parents")) parentSection(ParentAdvisor.contacts(ctx, items)) else ""
        // 📌 오늘·내일 마감 (할 일 목록에서, 끝내지 않은 것)
        items.forEach { runCatching { TaskExtractor.save(ctx, it) } }
        val taskPart = taskSection(db.tasks())
        // 💼 캘린더·할 일·메일 같은 업무·일정 앱 알림: AI 요약에 섞지 않고 시간순으로 바로
        val important = AppFilter.importantApps(ctx)
        val workItems = items.filter { AppAdvisor.isWork(it.app, it.pkg) || it.pkg in important }
        val workPart = workSection(workItems)
        // 👥 직접 만든 묶음(교직원·가족 등)의 사람 연락: 묶음별로 바로
        val (peoplePart, peopleItems) = People.section(People.customBook(ctx), items - workItems.toSet())
        val rest = items - workItems.toSet() - peopleItems
        // 🙋 답장할 연락·💬 사람 메시지(대화방마다 AI 세 줄)·📦 앱 알림(AI 없이 건수) — BriefingComposer
        val ai = if (rest.isEmpty()) null else summarizer.summarize(rest, onStatus)
        // 읽는 순서: 마감 → 학부모 → 답장할 연락·사람 메시지 → 내 묶음 → 업무 → 키워드 → 앱 알림
        val aiPeople = ai?.people?.takeIf { it.isNotBlank() }?.let { it + "\n\n" } ?: ""
        val text = (taskPart + parentPart + aiPeople + peoplePart + workPart + keywordPart + (ai?.apps ?: "")).trim()
            .ifEmpty { "요약할 알림이 없어요." }
        val now = System.currentTimeMillis()
        // time = 이번 요약이 덮은 마지막 시각 (다음 요약은 이 다음부터)
        db.insertBriefing(Briefing(time = cutoff, fromTime = from, label = label, count = items.size, text = text))
        onStatus("완료 (${(now - start) / 1000}초)")
        return Outcome("$label · 알림 ${items.size}건", text, cutoff)
    }

    /** 💼 업무·일정 앱(캘린더·할 일·메일 등) 알림을 시간순으로. 없으면 빈 문자열. */
    fun workSection(items: List<NotiItem>): String {
        if (items.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        val list = items.distinctBy { it.app to (it.title + it.text) }.sortedBy { it.time }
        return buildString {
            append("💼 업무·일정 알림 (${list.size}건)\n")
            list.takeLast(12).forEach { n ->
                val body = n.text.replace('\n', ' ').take(60)
                append("- ${fmt.format(n.time)} [${n.app}] ${n.title}" + (if (body.isNotBlank() && body != n.title) ": $body" else "") + "\n")
            }
            if (list.size > 12) append("  … 외 ${list.size - 12}건은 📄 세부 내역에서\n")
            append("\n")
        }
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
                val who = if (c.link == null && c.students.isNotEmpty()) " (${c.students.joinToString(", ")})" else ""
                append("- ${fmt.format(c.last.time)} ${c.displayName}$who [${c.category.label}$mark] ${c.last.text.replace('\n', ' ').take(70)}\n")
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
    /** 브리핑마다 다른 알림 번호 → 아침 브리핑을 저녁 브리핑이 덮어쓰지 않음 */
    fun idFor(briefingTime: Long): Int = if (briefingTime > 0) 1000 + ((briefingTime / 1000) % 100_000).toInt() else ID_RESULT
    const val ID_QUIET = 3

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "브리핑 진행 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_BRIEFING, "쌤톡스 브리핑", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** 알림을 누르면 앱을 열고, 브리핑 시각이 있으면 그 브리핑의 세부 내역을 바로 보여 줌 */
    const val EXTRA_BRIEFING = "briefing_time"
    private fun openApp(ctx: Context, briefingTime: Long = 0): PendingIntent = PendingIntent.getActivity(
        ctx, if (briefingTime > 0) idFor(briefingTime) else 0,   // 브리핑마다 따로 (같은 번호면 옛 알림을 눌러도 최신 브리핑이 열림)
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (briefingTime > 0) putExtra(EXTRA_BRIEFING, briefingTime) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun progress(ctx: Context, text: String, showStop: Boolean = true, title: String = "쌤톡스가 알림을 정리하는 중"): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_ssamtox)
            .setColor(0xFF2E4A3D.toInt())
            .setContentTitle(title)
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

    fun result(ctx: Context, title: String, body: String, briefingTime: Long = 0): Notification =
        NotificationCompat.Builder(ctx, CH_BRIEFING)
            .setSmallIcon(R.drawable.ic_stat_ssamtox)
            .setColor(0xFF2E4A3D.toInt())
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx, briefingTime))
            .setAutoCancel(true)
            // 🔒 잠금화면에서는 내용(학부모·학생 이름)을 숨기고 "새 브리핑"만 보여 줌
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(ctx, CH_BRIEFING)
                    .setSmallIcon(R.drawable.ic_stat_ssamtox)
                    .setColor(0xFF2E4A3D.toInt())
                    .setContentTitle("쌤톡스")
                    .setContentText("새 브리핑이 있어요 · 잠금을 풀면 볼 수 있어요")
                    .build()
            )
            .build()

    @android.annotation.SuppressLint("MissingPermission")
    fun notify(ctx: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) nm.notify(id, n)
    }
}
