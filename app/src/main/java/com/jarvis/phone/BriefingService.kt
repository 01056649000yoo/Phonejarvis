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
        // 미리 준비(브리핑 5분 전)면 결과를 알리지 않고 저장만 → 브리핑 시각에 바로 보여 줌
        val prepare = intent?.getBooleanExtra(EXTRA_PREPARE, false) == true
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        val rolling = intent?.getBooleanExtra(EXTRA_ROLLING, false) == true
        // ➕ 정각에 이미 보낸 '미리 준비한 요약'에 그 뒤 온 알림을 이어 붙이는 실행
        val topUpTime = intent?.getLongExtra(EXTRA_TOPUP_TIME, 0L) ?: 0L
        val topUpTitle = intent?.getStringExtra(EXTRA_TOPUP_TITLE).orEmpty()
        val topUpBody = intent?.getStringExtra(EXTRA_TOPUP_BODY).orEmpty()
        val topUp = topUpTime > 0 && topUpBody.isNotEmpty()
        // ✉️ 초안만 쓰는 실행(정각에 미리 준비한 요약을 보낸 뒤)
        val draftOnly = intent?.getBooleanExtra(EXTRA_DRAFT_ONLY, false) == true
        val daily = intent?.getBooleanExtra(EXTRA_DAILY, false) == true
        val pre = intent?.getBooleanExtra(EXTRA_PRE, false) == true
        val title = if (pre) "⚡ 미리 요약 중" else if (prepare) "요약 미리 준비 중" else "쌤톡스가 알림을 정리하는 중"
        ServiceCompat.startForeground(
            this, Notifications.ID_PROGRESS, Notifications.progress(this, "브리핑 준비 중…", title = title, quiet = prepare || topUp || draftOnly || pre),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (running && PreSummary.running && !pre && intent != null) {
            // ⚡ 미리 요약 중에 진짜 요약이 옴 → 미리 요약을 멈추고(정리한 방은 저장됨) 끝나는 대로 이어서
            queued = intent
            Summarizer.get(this).requestStop()
            return START_NOT_STICKY
        }
        if (running) {
            // 이미 도는 중이면 겹쳐 실행하지 않음. 정각 5분 전 '미리 준비' 중에 위젯·앱에서 직접 요약을 누른 것이면
            // 준비하던 요약을 끝나는 대로 바로 알림(누른 결과가 안 오는 것처럼 보이지 않게)
            if (!prepare && !auto && preparing) showWhenDone = true
            return START_NOT_STICKY
        }
        running = true
        active = !pre   // 미리 요약은 화면의 '정리 중'·위젯에 안 보임
        AppScope.main.launch { SummaryWidget.refresh(this@BriefingService) }   // 🏠 위젯에 '요약 중…'
        preparing = prepare
        showWhenDone = false

        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "브리핑"
        // 화면이 꺼져 있어도 CPU가 잠들지 않도록 (최대 30분)
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:briefing")
            .apply { acquire(30 * 60 * 1000L) }

        scope.launch {
            if (pre) {
                // ⚡ 미리 요약: 결과 알림 없이 방마다 정리해 저장만. 화면이 켜지거나 진짜 요약이 오면 멈춤
                PreSummary.running = true
                try {
                    val r = PreSummary.run(this@BriefingService) { }
                    AutoLog.add(this@BriefingService, PreSummary.LABEL, r)
                } catch (_: Summarizer.StoppedException) {
                } catch (t: Throwable) {
                    AutoLog.add(this@BriefingService, PreSummary.LABEL, "실패 · ${t.javaClass.simpleName}")
                } finally {
                    PreSummary.running = false
                    runCatching { Summarizer.get(this@BriefingService).release() }
                    if (wake.isHeld) wake.release()
                    running = false; active = false; preparing = false
                    val next = queued; queued = null
                    ServiceCompat.stopForeground(this@BriefingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (next != null) onStartCommand(next, 0, startId)      // 기다리던 진짜 요약
                    else { runCatching { PreSummary.nudge(this@BriefingService, force = true) }; stopSelf() }   // 충전 중이면 다음 미리 요약 예약
                }
                return@launch
            }
            if (draftOnly) {
                try {
                    runCatching { Briefings.draftPending(this@BriefingService) { } }
                    // 🗓 초안을 쓰는 사이 하루 정리 시각이 왔으면 이어서(2026-11-24 점검: 이 길에서는 그날 하루 정리가 빠졌다)
                    if (dailyAfter) { dailyAfter = false; runCatching { Briefings.runDaily(this@BriefingService) } }
                }
                finally {
                    runCatching { Summarizer.get(this@BriefingService).release() }
                    if (wake.isHeld) wake.release()
                    running = false; active = false; preparing = false
                    ServiceCompat.stopForeground(this@BriefingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            try {
                if (daily) { Briefings.runDaily(this@BriefingService); return@launch }   // finally 에서 정리
                val result = Briefings.runSinceLast(this@BriefingService, label, { s ->
                    Notifications.notify(this@BriefingService, Notifications.ID_PROGRESS,
                        Notifications.progress(this@BriefingService, s, title = title, quiet = (prepare && !showWhenDone) || topUp))
                }) { t, body, time ->
                    // ⏳ 6분 안에 다 못 끝냄 → 지금까지 정리한 것을 먼저 알림(같은 알림을 다 끝나면 고쳐 씀). 미리 준비·이어 붙이기 중엔 안 보냄
                    if (!(prepare && !showWhenDone) && !topUp && !rolling)
                        Notifications.notify(this@BriefingService, Notifications.idFor(time), Notifications.result(this@BriefingService, t, body, time))
                }
                // 📝 자동 요약은 결과를 기록(설정의 '최근 자동 요약'·위젯 머리줄) — 조용히 넘어가도 돈 것은 보이게
                if (auto) AutoLog.add(this@BriefingService, label, when {
                    result.time != 0L -> (if (prepare && !showWhenDone) "미리 준비 · " else "요약 · ") + result.title.substringAfter(" · ", "") +
                        result.timing.removePrefix("⏱").let { if (it.isNotBlank()) " ·$it" else "" }
                    result.title.endsWith("건너뜀") -> "건너뜀 · AI 모델 없음"
                    else -> "새 알림 없음"
                })
                if (topUp) {
                    // 새로 온 것이 있으면 정각에 보낸 알림을 '준비한 요약 + 그 뒤 온 알림'으로 고쳐 씀
                    if (result.time != 0L) {
                        val n0 = Regex("알림 (\\d+)건").find(topUpTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val n1 = Regex("알림 (\\d+)건").find(result.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
                        Notifications.notify(this@BriefingService, Notifications.idFor(topUpTime),
                            Notifications.result(this@BriefingService, "$label · 알림 ${n0 + n1}건",
                                topUpBody + "\n\n➕ ${hm.format(topUpTime)} 뒤에 온 알림 (${n1}건)\n" + Briefings.topUpPart(result.body),
                                topUpTime, quiet = true))
                    }
                } else if (rolling && result.time == 0L) {
                    // 간격 요약인데 새 알림이 없음 → 알림은 안 보냄(자주 요약해도 귀찮지 않게). 기록·위젯에는 남김
                    // 정한 시각 요약은 새 알림이 없어도 '없음'을 알린다(2026-11-22 선생님: "12시 요약이 와야 하는데 안 옴")
                } else if (prepare && !showWhenDone) {
                    // 미리 준비 완료: 저장해 두었다가 정한 시각에 알림
                    BriefingScheduler.saveReady(this@BriefingService, result.title, result.body, result.time)
                } else if (rolling) {
                    // 간격·수업 끝 요약: 알림 하나를 새 내용으로 바꿔 보여 줌(쌓이지 않게)
                    Notifications.notify(this@BriefingService, Notifications.ID_ROLLING,
                        Notifications.result(this@BriefingService, result.title, result.body, result.time))
                } else {
                    // 일반 실행이거나, 준비가 길어져 브리핑 시각이 이미 지난 경우 → 바로 알림
                    Notifications.notify(this@BriefingService, Notifications.idFor(result.time),
                        Notifications.result(this@BriefingService, result.title, result.body, result.time))
                }
                // ✉️ 문자 답장 초안: 결과 알림을 보낸 뒤에(미리 준비 중이면 정각 알림 뒤 BriefingReceiver 가 따로)
                if (!(prepare && !showWhenDone)) runCatching { Briefings.draftPending(this@BriefingService) { s -> Notifications.notify(this@BriefingService,
                    Notifications.ID_PROGRESS, Notifications.progress(this@BriefingService, s, title = title, quiet = true)) } }
            } catch (s: Summarizer.StoppedException) {
                if (auto) AutoLog.add(this@BriefingService, label, "중지됨")
                Notifications.notify(this@BriefingService, Notifications.ID_RESULT,
                    Notifications.result(this@BriefingService, "$label 중지됨", s.message ?: "중지했어요."))
            } catch (t: Throwable) {
                if (auto) AutoLog.add(this@BriefingService, label, "실패 · ${t.javaClass.simpleName}")
                Notifications.notify(this@BriefingService, Notifications.ID_RESULT,
                    Notifications.result(this@BriefingService, "$label 실패", "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                // 🗓 이 요약 중에 하루 정리 시각이 왔으면 이어서 — 요약이 실패·중지돼도(2026-11-24 점검: 예전엔 성공했을 때만 해서 그날 하루 정리가 빠졌다)
                if (dailyAfter) { dailyAfter = false; runCatching { Briefings.runDaily(this@BriefingService) } }
                // 백그라운드에서 수 GB 모델을 계속 들고 있지 않도록 내림
                runCatching { Summarizer.get(this@BriefingService).release() }
                runCatching { Backup.autoBackup(this@BriefingService) }   // 💾 하루 한 번 자동 백업
                runCatching { Storage.autoClean(this@BriefingService) }   // 🧹 앱을 안 열어도 오래된 기록 자동 정리
                if (wake.isHeld) wake.release()
                running = false
                active = false
                preparing = false
                runCatching { BriefingScheduler.scheduleAll(this@BriefingService) }   // 그다음 요약
                runCatching { SummaryWidget.refresh(this@BriefingService) }   // 🏠 위젯에 새 요약
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
        const val EXTRA_AUTO = "auto"
        const val EXTRA_ROLLING = "rolling"
        const val EXTRA_TOPUP_TIME = "topup_time"
        const val EXTRA_TOPUP_TITLE = "topup_title"
        const val EXTRA_TOPUP_BODY = "topup_body"
        const val EXTRA_DRAFT_ONLY = "draft_only"
        const val EXTRA_DAILY = "daily"
        const val EXTRA_PRE = "pre"
        /** 미리 요약 중에 온 진짜 요약(미리 요약이 멈추면 이어서) */
        @Volatile private var queued: Intent? = null
        /** 🗓 하루 정리 시각에 다른 요약이 돌고 있었으면 그 요약 끝에 이어서 */
        @Volatile var dailyAfter = false
        const val ACTION_STOP = "com.jarvis.phone.STOP_BRIEFING"

        /** 백그라운드 정리가 도는 중인지 (화면에서 "정리 중" 표시용) */
        @Volatile var active = false
            private set
        /** 지금 미리 준비 중인 브리핑 (없으면 null) */
        @Volatile var preparing: Boolean = false
            private set
        /** 준비가 브리핑 시각을 넘겼으면 끝나는 대로 바로 알림 */
        @Volatile var showWhenDone = false
    }
}

/** 브리핑 실행 로직 (서비스·화면 공용). */
object Briefings {
    /** time: 저장한 브리핑의 시각 키 (알림을 누르면 이 브리핑의 세부 내역을 엶). 0이면 저장 안 함 */
    /** timing: ⏱ 걸린 시간 한 줄(자동 요약 기록에도 남김) */
    data class Outcome(val title: String, val body: String, val time: Long = 0, val timing: String = "")


    /**
     * 아직 요약하지 않은 알림의 시작 시각: 지난 요약이 덮은 시각 다음부터(최대 3일 전까지).
     * 한 번 요약한 알림은 다음 요약(자동·수동 모두)에 다시 들어가지 않음.
     * 2026-11-22: 예전엔 '오늘 0시'에서 잘라서, 어제 저녁 요약 뒤~자정에 온 알림이 아침 요약에서 빠지고
     * 자정 뒤 새 알림이 없으면 아침 자동 요약이 '새 알림 없음'으로 조용히 넘어갔다(선생님: 9시 자동 요약이 안 돎).
     */
    const val MAX_BACK_MS = 3 * 24L * 3600 * 1000   // 금요일 저녁 → 월요일 아침도 덮음
    fun pendingFrom(db: NotificationDb, now: Long = System.currentTimeMillis()): Long =
        maxOf((db.lastBriefing()?.time ?: -1L) + 1, now - MAX_BACK_MS)

    /**
     * 🕳 빠지는 알림 막기(2026-11-24 선생님: 9시 55분에 온 문자가 요약에서 빠짐).
     * 예전엔 '지난 요약 시각 다음부터'를 알림의 시각으로 잘랐다. 그런데 문자·카톡은 보낸 시각(알림 속 시각)으로 저장되고,
     * 그 시각은 폰에 도착한 때보다 몇 초~2분 이를 수 있다 — 요약(특히 정각 5분 전 미리 준비)이 그 사이에 돌면
     * 그 메시지는 이번 요약엔 아직 없고, 다음 요약엔 '지난 요약 시각보다 앞'이라 빠졌다.
     * 이제 요약마다 맡은 마지막 알림 번호(uptoId)를 남기고, 다음 요약은 그 번호 다음에 저장된 알림을 모두 맡는다(시각은 넉넉히 거슬러 봄).
     */
    const val LATE_MS = 30 * 60_000L
    fun pendingItems(db: NotificationDb, now: Long = System.currentTimeMillis(), uptoId: Long = Long.MAX_VALUE): List<NotiItem> {
        val last = db.lastBriefing()
        val from = pendingFrom(db, now)
        return uncovered(db.since(if ((last?.uptoId ?: 0L) > 0) from - LATE_MS else from), from, last?.uptoId ?: 0L, uptoId)
    }

    /** 아직 어느 요약도 맡지 않은 알림(순수 — 시험): 지난 요약이 번호를 남겼으면 번호로, 예전 요약이면 시각으로 */
    fun uncovered(items: List<NotiItem>, from: Long, afterId: Long, uptoId: Long = Long.MAX_VALUE): List<NotiItem> =
        items.filter { it.id <= uptoId && if (afterId > 0) it.id > afterId else it.time >= from }

    /** 자동 브리핑·"지금 정리하기" 공용: 지난 요약 이후 새로 온 오늘 알림만 요약해 저장. */
    /**
     * 🗓 하루 정리: 아직 요약 안 한 알림이 있으면 먼저 요약(알림은 따로 안 보냄)한 뒤, 오늘 요약들을 합쳐 한 번 알림.
     * 오늘 요약이 하나도 없으면 알리지 않고 기록만.
     */
    suspend fun runDaily(ctx: Context) {
        val db = NotificationDb.get(ctx)
        val rules = AppFilter.rules(ctx)
        val pending = pendingItems(db).count { rules.allows(it) }
        if (pending > 0 && Summarizer.get(ctx).hasModel()) runCatching {
            val hm = "%02d:%02d".format(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE))
            run(ctx, "🌙 $hm 요약", pendingFrom(db), { s -> Notifications.notify(ctx, Notifications.ID_PROGRESS,
                Notifications.progress(ctx, s, title = "하루 정리 준비 중", quiet = true)) })
        }
        // 🤖 AI 한 번(답 400토큰까지)으로 우선순위 정리 — 모델이 없거나 폰이 뜨겁거나 배터리가 바닥이면 AI 없이
        val summarizer = Summarizer.get(ctx)
        val ask: (suspend (String) -> String)? = if (summarizer.hasModel() && PhoneLoad.skipAiReason(PhoneLoad.state(ctx)) == null)
            { p -> summarizer.complete(p, { s -> Notifications.notify(ctx, Notifications.ID_PROGRESS,
                Notifications.progress(ctx, "🗓 하루 정리 중… $s", title = "하루 정리 준비 중", quiet = true)) }, 400) } else null
        val out = DailyDigest.build(ctx, ask)
        if (out == null) { AutoLog.add(ctx, DailyDigest.LABEL, "오늘 요약이 없어 건너뜀"); return }
        AutoLog.add(ctx, DailyDigest.LABEL, "알림 보냄")
        Notifications.notify(ctx, Notifications.idFor(out.time), Notifications.result(ctx, out.title, out.body, out.time))
    }

    suspend fun runSinceLast(ctx: Context, label: String, onStatus: (String) -> Unit,
                             onPartial: (String, String, Long) -> Unit = { _, _, _ -> }): Outcome =
        run(ctx, label, pendingFrom(NotificationDb.get(ctx)), onStatus, onPartial)

    /** 1차 정리 시간(넘으면 먼저 알리고 남은 방은 이어서) · 이어서 정리하는 시간 */
    const val FIRST_PASS_MS = 6 * 60_000L
    const val SECOND_PASS_MS = 15 * 60_000L

    /** onPartial: 1차 정리에서 시간이 모자라 남은 방이 있으면 먼저 보낼 (제목, 내용) — 그 뒤 이어서 마무리하고 같은 요약을 고쳐 씀 */
    suspend fun run(ctx: Context, label: String, from: Long, onStatus: (String) -> Unit,
                    onPartial: (String, String, Long) -> Unit = { _, _, _ -> }): Outcome {
        val db = NotificationDb.get(ctx)
        val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel()) return Outcome("$label 건너뜀", "AI 모델이 없어요. 쌤톡스 앱에서 모델 파일을 선택하세요.")

        // 이 시각까지 온 알림을 이번 요약이 맡음. 요약하는 몇 분 사이에 온 알림은 다음 요약으로 넘어감.
        val cutoff = System.currentTimeMillis()
        val uptoId = db.maxNotiId()   // 이 번호까지 저장된 알림을 이번 요약이 맡음(시각 말고 번호로 — pendingItems)
        val prevBriefing = db.lastBriefing()   // 알림 맨 위 '언제부터' 표시용(이번 요약을 저장하기 전)
        // 설정에서 뺀 앱·방은 요약에서도 제외 (뺀 방이라도 중요 키워드가 있으면 포함)
        val rules = AppFilter.rules(ctx)
        // [from] 은 늘 pendingFrom(지난 요약 다음) — 어떤 알림을 맡을지는 번호로(pendingItems)
        val items = pendingItems(db, cutoff, uptoId).filter { rules.allows(it) }
        if (items.isEmpty()) return Outcome("$label · 새 알림 없음", db.lastBriefing()?.let { b ->
            "${java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA).format(b.time)} ${b.label} 이후 새로 온 알림이 없어요."
        } ?: "지난 요약 이후 새로 온 알림이 없어요.")

        val start = System.currentTimeMillis()
        summarizer.resetStats()
        // 🔁 정기·자동 알림(매일 같은 시각·봇·직접 뺀 꼴)은 요약에서 빼고 건수 한 줄 — 나머지(loud)만 정리
        val split = splitForAi(ctx, items)
        // ⭐ 키워드 알림: AI 없이 규칙으로 찾아 맨 위에 (놓치지 않게)
        val keywordPart = keywordSection(split.loud, rules.keywords)
        // 2026-11-23 선생님: 칸을 줄임 — 👪 학부모 전용 칸·📌 오늘·내일 마감 칸은 없앰(학부모는 사람 묶음 하나로, 마감은 변수가 많아 안 함)
        // 👥 사람 묶음(사람별)·💼 지정한 업무·일정 앱(앱별)·나머지(rest, 대화방별) 모두 AI
        val rest = split.rest
        // ⚡ 미리 요약해 둔 방(PreSummary)은 다시 묻지 않음 — 이번 구간(from)의 것만
        val cache = PreSummary.load(ctx, from)
        // 🙋 답장할 연락·💬 사람 메시지(대화방마다 AI 세 줄)·📦 앱 알림(AI 없이 건수) — BriefingComposer
        // 🌡️ 폰이 뜨겁거나 배터리가 바닥이면 모델을 올리지 않고 AI 없이(대화방마다 최근 말 그대로)
        val load = PhoneLoad.state(ctx)
        val skipAi = PhoneLoad.skipAiReason(load)
        val focus = focusOn(ctx)   // 🎯 중요한 대화만 요약(IDENTITY.md 원칙 2)
        val before = beforeOf(db, from, ThreadMemo.load(ctx))   // 🧵 지난 요약 끝에서 이어진 대화 + 📝 방별 이어가기 메모(참고용)
        val near = closeness(ctx, rules, focus)   // 🎯 나와의 거리(나에게 · 내가 속한 방 · 그 밖)
        val ai = when {
            rest.isEmpty() && split.groups.isEmpty() -> null
            skipAi != null -> BriefingComposer.compose(rest, ask = { "" }, replyAllowed = near.reply, cache = cache, since = from, groups = split.groups, focus = focus, before = before, levels = near.levels)
            else -> {
                // 2026-11-23 선생님: 알림이 100건 넘으면 요약이 중간에 끊김 — 정리한 방은 기억해 두고(캐시), 6분 안에 못 끝내면
                // 지금까지 정리한 것을 먼저 알리고 남은 방을 이어서 마무리(중요한 방부터 정리하므로 먼저 간 알림에도 꼭 볼 것은 들어 있음)
                val mem = cache.toMutableMap()
                val allowed = near.reply
                val first = summarizer.summarize(rest, onStatus, allowed, mem, from, onCached = { mem[it.key] = it }, groups = split.groups, maxMillis = FIRST_PASS_MS, focus = focus, before = before, levels = near.levels)
                if (first.cutRooms > 0 && summarizer.cutByTime) {
                    val partial = (order(first.groups, keywordPart, first.work, first.people, first.apps) +
                        "\n\n(⏳ 남은 대화방 ${first.cutRooms}곳은 이어서 정리하고 있어요. 다 되면 이 알림이 바뀌어요)")
                    runCatching { onPartial("$label · 알림 ${items.size}건 (정리 중)", partial, cutoff) }
                    summarizer.summarize(rest, onStatus, allowed, mem, from, onCached = { mem[it.key] = it }, groups = split.groups, maxMillis = SECOND_PASS_MS, focus = focus, before = before, levels = near.levels)
                } else first
            }
        }
        // ✉️ 답장할 문자: 🙋 답장할 연락 중 문자(1:1)만 올리고, 모델이 켜진 김에 초안을 5건까지 미리
        // ✉️ 답장할 문자는 목록에만 올림 — 초안은 요약 알림을 보낸 뒤에 씀(결과가 늦어지지 않게, draftPending)
        runCatching { upsertSmsTargets(db, ai?.replies.orEmpty()) }
        val now = System.currentTimeMillis()
        val timing = timingLine(now - start, summarizer.stats, PhoneLoad.state(ctx).thermal)
        val text = ((order(ai?.groups ?: "", keywordPart, ai?.work ?: "", ai?.people ?: "", ai?.apps ?: "") + "\n\n" + Routine.line(split.quiet)).trim()
            .ifEmpty { "요약할 알림이 없어요." }) + (if (skipAi != null && rest.isNotEmpty()) "\n\n$skipAi" else "") + "\n\n($timing)"
        // time = 이번 요약이 덮은 마지막 시각 (다음 요약은 이 다음부터)
        db.insertBriefing(Briefing(time = cutoff, fromTime = from, label = label, count = items.size, text = text, uptoId = uptoId))
        // 🗓 방별 하루 기록 — 하루 정리가 그날 모든 방을 모으는 재료(요약 글을 다시 읽지 않음)
        runCatching { db.addRoomDays(SummarySchedule.startOfDay(cutoff), roomDays(ai, split, from, near)) }
        PreSummary.clear(ctx)   // 이번 구간은 끝 — 다음 구간은 새로 미리 요약
        // 📝 이번 구간의 안 풀린 물음·정해진 것을 방마다 적어 둠 — 다음 요약이 이어 볼 수 있게(AI 없이)
        runCatching { ThreadMemo.update(ctx, BriefingComposer.memoConvs(rest, split.groups), cutoff) }
        onStatus("완료 (${(now - start) / 1000}초)")
        // 알림 맨 위에 이번 요약이 맡은 구간 — 앞선 요약(위젯·지금 정리하기·이전 시각)에 이미 들어간 메시지는 다시 나오지 않으므로
        val hm = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA)
        val range = "🕘 " + (prevBriefing?.let { "${hm.format(it.time)} ${it.label} 뒤" } ?: hm.format(items.minOf { it.time })) +
            " ~ ${hm.format(cutoff)}에 온 알림"
        return Outcome("$label · 알림 ${items.size}건", range + "\n\n" + text, cutoff, timing)
    }

    /** 이번 요약 → 방별 하루 기록들(💬 대화방·👥 묶음·💼 업무·📦 앱 건수) */
    fun roomDays(ai: BriefingComposer.Result?, split: Split, from: Long, near: Closeness): List<RoomDay.Entry> {
        fun lv(c: BriefingComposer.Conversation) = near.levels?.invoke(c) ?: Relevance.ME
        val out = mutableListOf<RoomDay.Entry>()
        ai?.rooms?.forEach { out += RoomDay.of(it, RoomDay.TALK, it.level, from) }
        // 정리 못 한 방: 그 밖의 방은 이름·건수만, 나머지는 AI 없이 그 말을 짧게
        ai?.cut?.forEach { c -> out += if (lv(c) == Relevance.OTHER) RoomDay.of(BriefingComposer.RoomSummary(c, "", "", false, byModel = false), RoomDay.TALK, Relevance.OTHER, from)
            else RoomDay.ofCut(c, RoomDay.TALK, lv(c), from) }
        ai?.groupRooms?.forEach { out += RoomDay.of(it, RoomDay.groupKind(it.conv), Relevance.ME, from) }
        ai?.groupCut?.forEach { out += RoomDay.ofCut(it, RoomDay.groupKind(it), Relevance.ME, from) }
        out += RoomDay.apps(split.quiet + split.rest.filterNot(BriefingComposer::isPerson))
        return out
    }

    /** 읽는 순서(2026-11-23 선생님): 👥 사람 묶음 → ⭐ 키워드 → 💼 업무·일정 → 💬 대화 요약 → 📦 앱 알림(건수) */
    fun order(groups: String, keywords: String, work: String, people: String, apps: String): String =
        listOf(groups, keywords, work, people, apps).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /**
     * 요약할 알림 나누기 — 요약·미리 요약 공용:
     * 🔁 quiet(정기·자동·직접 뺀 꼴, 건수만) / 💼 work(업무·일정, AI 없이) / 👥 groups(내 묶음, 사람별 AI) / rest(나머지, AI)
     */
    data class Split(val quiet: List<NotiItem>, val loud: List<NotiItem>, val work: List<NotiItem>,
                     val groups: List<BriefingComposer.Group>, val rest: List<NotiItem>)
    fun splitForAi(ctx: Context, items: List<NotiItem>): Split {
        val keywords = AppFilter.rules(ctx).keywords
        val routine = Routine.keys(ctx); val muted = Routine.muted(ctx)
        // 💼 지정한 업무·일정 앱(2026-11-23: 그 앱 알림만 AI 가 앱마다 요약 — 묶음 하나처럼, 미리 요약에도 같이)
        val isWorkApp = AppFilter.isWorkApp(ctx)
        // 💼 업무 앱은 '정기 알림' 짐작으로 빼지 않음(2026-11-24 점검: 메일은 제목이 보낸 사람이라 매일 비슷한 시각에 메일을 주는 동료가
        // 🔁 건수로만 남았다). 선생님이 직접 뺀 꼴은 업무 앱도 뺌
        val (quiet, loud) = items.partition { Routine.isQuiet(it, if (isWorkApp(it.app, it.pkg)) emptySet() else routine, muted, keywords) ||
            // 메일로 온 업체 안내(Google·OO은행·카드사…)는 💼 업무 요약에 넣지 않고 건수만(2026-11-24 점검 — 하루 정리에 Google Gemini 안내)
            (isMail(it) && AdFilter.isBusinessName(it.title) && !AppFilter.hasRealKeyword(keywords, it.title, it.text, it.room)) }
        val workItems = loud.filter { isWorkApp(it.app, it.pkg) }
        val book = People.customBook(ctx)
        // 한 사람은 한 묶음에만(묶음 순서대로 먼저 맞는 곳) — 그 연락은 아래 대화 요약에 다시 나오지 않음
        // 메시지 앱의 사람 연락만(2026-11-23 선생님: 카카오페이가 지인으로 — 송금 알림 제목의 이름이 묶음 이름과 맞음)
        // 단톡방에서 한 말은 그 방 대화 요약에 그대로(2026-11-24 선생님: 사람별로 떼어 모으니 대화 흐름이 사라져 엉뚱하게 이어 붙임) — 묶음 칸은 1:1 연락만
        val hits = (loud - workItems.toSet()).filter(BriefingComposer::isPerson).filter { it.room.isEmpty() }
            .mapNotNull { n -> People.find(book, n, bodyToo = false)?.let { (g, name) -> Triple(g, name, n) } }
        val groups = hits.groupBy { it.first.id }.map { (_, l) -> BriefingComposer.Group(l.first().first.title, l.map { it.second to it.third }) } +
            listOfNotNull(workItems.takeIf { it.isNotEmpty() }?.let { w -> BriefingComposer.Group(BriefingComposer.WORK_TITLE, w.map { it.app to it }) })
        return Split(quiet, loud, workItems, groups, loud - workItems.toSet() - hits.map { it.third }.toSet())
    }

    // ── 🎯 중요한 대화만 요약(2026-11-24, IDENTITY.md 원칙 2 — 기본 켬). 끄면 예전처럼 모든 방을 AI 로 ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("briefing", Context.MODE_PRIVATE)
    fun focusOn(ctx: Context) = prefs(ctx).getBoolean("focus_talk", true)
    fun setFocus(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("focus_talk", on).apply()
    /** 명부·학부모 방·키워드·학생 이름이 나오는 대화인가 */
    fun roster(ctx: Context): (BriefingComposer.Conversation) -> Boolean {
        val rules = AppFilter.rules(ctx)
        return { c -> c.items.any { rules.mentionsRoster(it) } }
    }

    /**
     * 🎯 나와의 거리(Relevance, 2026-11-25 선생님: 나와 관련된 것만 요약) — levels: 방 → 1·2·3, reply: 🙋 로 올려도 되나.
     * '중요한 대화만 요약'을 끄면 예전처럼(모든 방 AI, 🙋 는 명부·키워드가 나오는 방)
     */
    class Closeness(val levels: ((BriefingComposer.Conversation) -> Int)?, val reply: (BriefingComposer.Conversation) -> Boolean)
    fun closeness(ctx: Context, rules: AppFilter.Rules = AppFilter.rules(ctx), focus: Boolean = focusOn(ctx)): Closeness {
        if (!focus) return Closeness(null) { c -> c.items.any { rules.mentionsRoster(it) } }
        val names = rules.keywords                      // 내 이름·별명·학생 이름은 ⭐ 키워드에(선생님이 넣어 둠)
        val joined = ReplyLog.all(ctx).keys             // 내가 말한 방(1:1 은 상대 이름) — 30일
        val tiers = RoomTier.all(ctx)
        // 학부모: 명부의 이름·번호, 학부모 방 — 키워드는 '나를 부름'으로 따로 봄(방 아무 데나 키워드가 있다고 방 전체가 나에게 온 것은 아님)
        val parent = { c: BriefingComposer.Conversation -> c.items.any { n ->
            rules.isLinkedParent(n.pkg, n.room, n.title, n.text) || ParentAdvisor.isParent(rules.parentRooms, n.pkg, n.room.ifEmpty { n.title }) } }
        val memo = HashMap<String, Int>()
        val level = { c: BriefingComposer.Conversation -> memo.getOrPut(BriefingComposer.keyOf(c)) {
            Relevance.level(c, names, parent, { it in joined }, tiers[RoomTier.key(c)] ?: Relevance.AUTO,
                trusted1to1 = c.items.all { Sms.isSms(it.pkg) }) } }
        return Closeness(level) { c -> Relevance.replyOk(c, level(c), names, parent) }
    }

    /** 🧵 대화방마다 이번 구간(from) 바로 앞 30분 안의 같은 방 메시지 — 업무 앱(💼)은 빼고 */
    fun beforeOf(db: NotificationDb, from: Long, memo: Map<String, List<BriefingComposer.Memo>> = emptyMap()): (BriefingComposer.Conversation) -> List<NotiItem> = { c ->
        val n = c.items.firstOrNull()
        if (n == null || from <= 0L || c.app == BriefingComposer.WORK_TITLE) emptyList()
        // 📝 방별 이어가기 메모(오래된 물음·정해진 것) + 지난 구간 끝의 원문 — 어느 것이 이번 대화와 이어지는지는 BriefingComposer.linked 가 고름
        else memo[BriefingComposer.keyOf(c)].orEmpty().filter { it.time < from }.map { NotiItem(0, n.pkg, n.app, it.who, it.text, it.time, n.room) } +
            db.lastBefore(n.pkg, n.room, n.title, from, from - BriefingComposer.LINK_MS)
    }

    /** 메일 앱 알림인가(제목이 보낸 사람) — 캘린더 같은 다른 업무 앱은 제목이 일정 이름이라 업체 이름 거르기를 하지 않음 */
    private val MAIL = Regex("(?i)(mail|메일|outlook|아웃룩)")
    fun isMail(n: NotiItem) = MAIL.containsMatchIn(n.app + " " + n.pkg)

    /** ⏱ 48초 · 모델 불러오기 21초(GPU) · AI 2번 27초 — 어디서 시간이 드는지 실제 폰에서 보려고 */
    fun timingLine(totalMs: Long, s: Summarizer.Stats, thermal: Int? = null): String {
        fun sec(ms: Long) = if (ms in 1..999) "1초" else "${ms / 1000}초"
        return listOfNotNull(
            "⏱ ${sec(totalMs)}",
            if (s.loadMs > 0) "모델 불러오기 ${sec(s.loadMs)}(${s.backend.ifEmpty { "?" }})" else null,
            if (s.calls > 0) "AI ${s.calls}번 ${sec(s.callMs)}" else "AI 안 씀",
            // 🌡️ 폰에 얼마나 무리가 갔나: 모델이 올라 있을 때 쌤톡스 메모리, 끝났을 때 발열
            if (s.memMb > 0) "메모리 ${"%.1f".format(s.memMb / 1024.0)}GB" else null,
            thermal?.let { "발열 ${PhoneLoad.thermalName(it)}" },
        ).joinToString(" · ")
    }

    private fun upsertSmsTargets(db: NotificationDb, replies: List<BriefingComposer.RoomSummary>) {
        val sms = replies.filter { r -> r.conv.items.all { Sms.isSms(it.pkg) && it.room.isEmpty() } }
        sms.forEach { r -> db.upsertReplyTarget(r.conv.items.last().pkg, r.conv.room, r.conv.last, r.conv.items.takeLast(2).joinToString(" / ") { it.text.replace('\n', ' ') }) }
    }

    /** 초안이 없는 답장할 문자에 초안 쓰기(5건까지) — 요약 알림을 보낸 뒤에 부름. 쓴 수를 돌려줌 */
    suspend fun draftPending(ctx: Context, onStatus: (String) -> Unit): Int {
        val db = NotificationDb.get(ctx); val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel() || summarizer.stopRequested) return 0
        // 🌡️ 폰이 따뜻하거나 절전·배터리 부족이면 초안은 다음 요약 때로 미룸(목록에는 남아 있고 '다시 쓰기'로 직접 쓸 수 있음)
        if (PhoneLoad.deferLightWork(PhoneLoad.state(ctx))) return 0
        var made = 0
        val todo = db.openReplies().filter { it.draft.isEmpty() }.take(ReplyDrafter.MAX_AUTO)
        todo.forEachIndexed { i, d ->
            onStatus("문자 답장 초안 ${i + 1}/${todo.size}")
            val items = db.since(d.lastTime - 3 * 24 * 3600_000L).filter { it.pkg == d.pkg && it.room.isEmpty() && it.title == d.sender }
            if (items.isEmpty()) return@forEachIndexed
            val draft = ReplyDrafter.clean(summarizer.complete(ReplyDrafter.prompt(d.sender, items), maxTokens = ReplyDrafter.MAX_TOKENS))
            if (draft.isNotEmpty()) { db.setReplyDraft(d.pkg, d.sender, draft); made++ }
        }
        return made
    }
    fun hasPendingDrafts(ctx: Context) = runCatching { NotificationDb.get(ctx).openReplies().any { it.draft.isEmpty() } }.getOrDefault(false)

    /** 이어 붙일 때: 맨 위 구간 줄(🕘)과 앞 요약에 이미 있는 📌 마감 칸은 뺌 */
    fun topUpPart(body: String): String = body.trim().split(Regex("\\n\\s*\\n"))
        .filterNot { it.startsWith("🕘") || it.startsWith("📌") }.joinToString("\n\n").ifBlank { "(새로 정리할 것이 없어요)" }

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

    /** 중요 키워드가 들어간 알림 목록 (최근 것부터 최대 10개). 없으면 빈 문자열. */
    /**
     * 업체가 보낸 웹 문자(택배·카드…)인가 — '[Web발신] [CJ대한통운_배송완료] …' (2026-11-24: 택배 문자가 ⭐ 키워드 알림에 올라옴).
     * 학교·학부모가 보낸 웹 문자는 대괄호 이름이 업체가 아니라 그대로 둠
     */
    private val BRACKET = Regex("\\[([^\\]]{2,30})\\]")
    fun isBusinessMms(text: String): Boolean = text.contains("Web발신") &&
        BRACKET.findAll(text).any { m -> AdFilter.isBusinessName(m.groupValues[1].split('_', ' ').first().trim()) }
    /** 문자 앱의 '<제목: …>'·'[Web발신]' 머리를 떼고 한 줄로 */
    fun cleanMms(text: String): String = text.replace('\n', ' ').replace(Regex("<제목:\\s*([^>]*)>"), "$1")
        .replace("[Web발신]", "").replace(Regex("\\s{2,}"), " ").trim()

    fun keywordSection(items: List<NotiItem>, keywords: List<String>): String {
        if (keywords.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        // 광고·자동 알림은 빼고, 키워드가 부르는 말(OO님)로만 든 것도 뺌(2026-11-23)
        val hits = items.filter { AppFilter.hasRealKeyword(keywords, it.title, it.text, it.room) && !AdFilter.isAd(it.title, it.text) && !isBusinessMms(it.text) }
            .distinctBy { it.roomOrTitle to it.text }
            .takeLast(10).reversed()
        if (hits.isEmpty()) return ""
        return buildString {
            append("⭐ 키워드 알림 (${hits.size}건)\n")
            hits.forEach { n ->
                val who = if (n.room.isNotEmpty() && n.room != n.title) "[${n.room}] ${n.title}" else n.title
                append("- ${fmt.format(n.time)} $who: ${cleanMms(n.text).take(80)}\n")
            }
            append("\n")
        }
    }
}

object Notifications {
    private const val CH_PROGRESS = "progress"
    /** 정한 시각 5분 전 미리 준비: 알림창 맨 아래에 접혀 소리·상단 표시 없이(안드로이드가 백그라운드 작업에 알림을 꼭 요구함) */
    private const val CH_PREPARE = "prepare_min"
    private const val CH_BRIEFING = "briefing"
    const val ID_PROGRESS = 1
    const val ID_RESULT = 2
    /** 브리핑마다 다른 알림 번호 → 아침 브리핑을 저녁 브리핑이 덮어쓰지 않음 */
    fun idFor(briefingTime: Long): Int = if (briefingTime > 0) 1000 + ((briefingTime / 1000) % 100_000).toInt() else ID_RESULT
    const val ID_QUIET = 3
    /** 간격·수업 끝 요약: 같은 알림을 새 내용으로 바꿈 */
    const val ID_ROLLING = 4

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "브리핑 진행 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_PREPARE, "요약 미리 준비(조용히)", NotificationManager.IMPORTANCE_MIN))
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

    fun progress(ctx: Context, text: String, showStop: Boolean = true, title: String = "쌤톡스가 알림을 정리하는 중", quiet: Boolean = false): Notification =
        NotificationCompat.Builder(ctx, if (quiet) CH_PREPARE else CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_ssamtox)
            .setColor(0xFF4A4741.toInt())  // 알림 강조색 = Ssam.Stone
            .setContentTitle(Emoji.strip(title))
            .setContentText(Emoji.strip(text))
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

    fun result(ctx: Context, title: String, body: String, briefingTime: Long = 0, quiet: Boolean = false): Notification =
        NotificationCompat.Builder(ctx, CH_BRIEFING)
            .setOnlyAlertOnce(quiet)   // 이미 보낸 요약을 고쳐 쓸 때는 다시 울리지 않음
            .setSmallIcon(R.drawable.ic_stat_ssamtox)
            .setColor(0xFF4A4741.toInt())  // 알림 강조색 = Ssam.Stone
            // 알림창에는 이모지 없이(2026-11-25 디자인) — 칸 표시 이모지는 지우고 글만
            .setContentTitle(Emoji.strip(title))
            .setContentText(Emoji.strip(body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("🕘") } ?: body))   // 접힌 알림엔 구간 줄 말고 내용
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.lines().joinToString("\n") { Emoji.strip(it) }))
            .setContentIntent(openApp(ctx, briefingTime))
            .setAutoCancel(true)
            // 🔒 잠금화면에서는 내용(학부모·학생 이름)을 숨기고 "새 브리핑"만 보여 줌
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(ctx, CH_BRIEFING)
                    .setSmallIcon(R.drawable.ic_stat_ssamtox)
                    .setColor(0xFF4A4741.toInt())  // 알림 강조색 = Ssam.Stone
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
