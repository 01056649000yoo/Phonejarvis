package com.jarvis.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * ⚡ 미리 요약 (2026-11-23 선생님: 처음엔 "배터리 30% 이상이고 몇 개 이상이면 미리미리, 충전 중이면 수시로" →
 * 같은 날 "글자 수를 넘으면 미리 요약을 돌리는 방식으로").
 * 요약 시각이 되어서야 모든 방을 AI 로 묻던 것을, 쌓이는 동안 방마다 요점·요청을 정리해 저장(캐싱)해 둔다.
 * 요약 시각에는 저장해 둔 방은 그대로 쓰고, 그 뒤에 온 메시지만 '앞서 정리한 것 + 새 메시지'로 이어서 묻는다.
 *
 * 언제 하나 (알림이 올 때 살펴보고 알람을 걸어 둠 — 알람이 울릴 때 다시 확인):
 *  - AI 에 새로 넣을 글자가 [MIN_CHARS]자(AI 한 번에 넣는 양쯤)를 넘으면 [READY_DELAY_MIN]분 뒤 — 충전 중이든 아니든 같은 규칙
 *  - 충전 중이 아니면 배터리 [MIN_BATTERY]% 이상·절전 모드 아닐 때만
 *  - 언제나: 화면이 꺼져 있을 때만(쓰는 중에 GPU 를 다투면 화면이 멈춘 듯 보임), 폰이 '따뜻' 이상이면 안 함,
 *    다른 요약이 도는 중이면 안 함. 하다가 화면이 켜지거나 조건이 깨지면 그 방까지만 하고 멈춤(정리한 방은 남음).
 */
object PreSummary {
    const val MIN_BATTERY = 30
    const val MIN_CHARS = 1500             // 이만큼 쌓이면 미리 요약(AI 한 번에 넣는 글자 BriefingComposer.ROOM_CHARS 1600 쯤)
    const val READY_DELAY_MIN = 3          // 글자 수를 넘긴 뒤 조금 더 모아서
    const val SCREEN_ON_RETRY_MIN = 15     // 화면이 켜져 있어 미뤘을 때 다시 볼 때까지
    const val BIG_CHARS = 4500             // 이만큼 쌓이면 화면이 꺼지는 틈을 더 자주 살핌(100건 넘으면 요약이 끊기던 것)
    const val BIG_RETRY_MIN = 5
    const val ACTION = "com.jarvis.phone.ACTION_PRESUMMARY"
    private const val CHECK_GAP_MS = 60_000L   // 알림마다 DB 를 세지 않게 1분에 한 번만 살핌

    /** 지금 미리 요약이 도는 중(진짜 요약이 오면 멈추고 넘겨줌) */
    @Volatile var running = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pre_summary", Context.MODE_PRIVATE)

    // ── 저장(캐싱) ──
    /** 이번 구간([since])의 미리 요약. 구간이 다르면 버림 */
    fun load(ctx: Context, since: Long): Map<String, BriefingComposer.Cached> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("rooms", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BriefingComposer.Cached(o.getString("k"), o.getLong("s"), o.getLong("u"), o.getInt("c"), o.getString("p"), o.getString("a"), o.getBoolean("r"))
        }.filter { it.since == since }.associateBy { it.key }
    }.getOrDefault(emptyMap())

    @Synchronized fun save(ctx: Context, c: BriefingComposer.Cached) {
        val all = load(ctx, c.since) + (c.key to c)
        val arr = JSONArray()
        all.values.forEach { arr.put(JSONObject().put("k", it.key).put("s", it.since).put("u", it.upTo).put("c", it.count).put("p", it.point).put("a", it.ask).put("r", it.needReply)) }
        prefs(ctx).edit().putString("rooms", arr.toString()).apply()
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove("rooms").apply() }

    // ── 할지 말지 ──
    /** [chars]: AI 에 새로 넣을 글자 수 */
    data class Check(val chars: Int, val load: PhoneLoad.State, val screenOn: Boolean, val busy: Boolean, val hasModel: Boolean)

    /** 지금 미리 요약을 해도 되나: 아니면 까닭, 되면 null (순수 규칙 — 시험) */
    fun blockReason(c: Check): String? = when {
        !c.hasModel -> "모델 없음"
        c.busy -> "다른 요약 중"
        c.chars <= 0 -> "쌓인 것 없음"
        c.load.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "폰이 따뜻함"
        !c.load.charging && c.load.powerSave -> "절전 모드"
        !c.load.charging && c.load.battery < MIN_BATTERY -> "배터리 ${c.load.battery}%"
        c.chars < MIN_CHARS -> "쌓인 글자 ${c.chars}자"
        c.screenOn -> "화면 켜짐"
        else -> null
    }

    /** 다음에 언제 살펴볼까(분): 글자 수를 넘었으면 조금 있다가. 화면만 걸리면 그래도 예약(울릴 때 다시 봄) */
    fun delayMin(c: Check): Int? = if (blockReason(c.copy(screenOn = false)) == null) READY_DELAY_MIN else null

    /** 하는 중에 멈춰야 하나: 화면이 켜짐·따뜻해짐·(충전 중이 아니면) 절전·배터리 30% 아래 */
    fun keepGoingBlock(l: PhoneLoad.State, screenOn: Boolean): String? = when {
        screenOn -> "화면이 켜져 멈춤"
        l.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "폰이 따뜻해져 멈춤"
        !l.charging && (l.powerSave || l.battery < MIN_BATTERY) -> "배터리를 아끼려고 멈춤"
        else -> null
    }
    private fun screenOn(ctx: Context) = runCatching { ctx.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)

    fun check(ctx: Context): Check {
        val db = NotificationDb.get(ctx)
        val from = Briefings.pendingFrom(db)
        val rules = AppFilter.rules(ctx)
        val items = db.since(from).filter { rules.allows(it) }
        val split = Briefings.splitForAi(ctx, items)
        val chars = BriefingComposer.pendingCharsForModel(split.rest, load(ctx, from), from, split.groups)
        return Check(chars, PhoneLoad.state(ctx), screenOn(ctx), BriefingService.active, Summarizer.get(ctx).hasModel())
    }

    // ── 알람 ──
    private fun alarm(ctx: Context) = PendingIntent.getBroadcast(ctx, 30, Intent(ctx, BriefingReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 알림이 올 때(1분에 한 번만): 조건이 맞을 것 같으면 알람을 걸어 둠. 이미 걸려 있으면 그대로 */
    fun nudge(ctx: Context, force: Boolean = false) {
        val p = prefs(ctx); val now = System.currentTimeMillis()
        if (!force && now - p.getLong("checked", 0L) < CHECK_GAP_MS) return
        p.edit().putLong("checked", now).apply()
        if (p.getLong("alarm", 0L) > now) return
        val c = runCatching { check(ctx) }.onFailure { android.util.Log.w("PreSummary", "check 실패", it) }.getOrNull() ?: return
        android.util.Log.i("PreSummary", "살핌: 쌓인 글자 ${c.chars}자 · ${blockReason(c) ?: "할 수 있음"}")
        val min = delayMin(c) ?: return
        schedule(ctx, min)
    }

    fun schedule(ctx: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs(ctx).edit().putLong("alarm", at).apply()
        BriefingScheduler.setAlarm(ctx.getSystemService(AlarmManager::class.java), at, alarm(ctx))
    }

    /** 알람이 울림: 다시 확인하고, 되면 요약 서비스를 미리 요약으로 시작. 화면만 켜져 있으면 나중에 다시 */
    fun onAlarm(ctx: Context) {
        prefs(ctx).edit().putLong("alarm", 0L).apply()
        val c = runCatching { check(ctx) }.getOrNull() ?: return
        when (blockReason(c)) {
            null -> BriefingScheduler.startService(ctx, Intent(ctx, BriefingService::class.java)
                .putExtra(BriefingService.EXTRA_LABEL, LABEL).putExtra(BriefingService.EXTRA_PRE, true), LABEL)
            "화면 켜짐" -> schedule(ctx, if (c.chars >= BIG_CHARS) BIG_RETRY_MIN else SCREEN_ON_RETRY_MIN)   // 많이 쌓였으면 화면이 꺼지는 틈을 더 자주 봄
            else -> Unit   // 다음 알림이 올 때 다시 살핌
        }
    }

    const val LABEL = "⚡ 미리 요약"

    /** 서비스 안에서: 쌓인 방을 정리해 저장. 돌려주는 값은 자동 요약 기록 한 줄 */
    suspend fun run(ctx: Context, onStatus: (String) -> Unit): String {
        val db = NotificationDb.get(ctx)
        val from = Briefings.pendingFrom(db)
        val rules = AppFilter.rules(ctx)
        val cutoff = System.currentTimeMillis()
        val split = Briefings.splitForAi(ctx, db.since(from).filter { it.time <= cutoff && rules.allows(it) })
        val rest = split.rest
        val cache = load(ctx, from)
        val before = BriefingComposer.pendingForModel(rest, cache, from, split.groups)
        if (before == 0) return "쌓인 것 없음"
        val start = System.currentTimeMillis()
        var rooms = 0
        Summarizer.get(ctx).summarize(rest, onStatus, cache = cache, since = from,
            onCached = { save(ctx, it); rooms++ },
            extraStop = { keepGoingBlock(PhoneLoad.state(ctx), screenOn(ctx)) }, groups = split.groups,
            replyAllowed = Briefings.roster(ctx), focus = Briefings.focusOn(ctx), before = Briefings.beforeOf(NotificationDb.get(ctx), from))
        val after = BriefingComposer.pendingForModel(rest, load(ctx, from), from, split.groups)
        return "메시지 ${before - after}개 정리 · ${(System.currentTimeMillis() - start) / 1000}초" + if (after > 0) " · 남음 $after" else ""
    }
}
