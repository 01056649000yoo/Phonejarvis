package com.jarvis.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 🗓 방별 하루 기록 (2026-11-24 선생님: "하루 정리는 하루 종일 온 알림을 전체적으로 총정리 — 하루 500개를 가정").
 *
 * 예전 하루 정리는 그날 요약 '글'을 정규식으로 다시 읽어 20줄만 골라 AI 로 15줄 안쪽으로 줄였다 — 알림이 많을수록 빠지는 것이 많았다.
 * 지금은 요약할 때마다 방 하나를 [Entry] 로 쌓아 두고(NotificationDb.room_day), 하루 정리는 이 기록을 방별로 모은다:
 *  - 구간마다의 요약 줄([Part]) — 하루 정리에서 '방별 하루' 한 줄과 펼쳐 보는 구간별 요약
 *  - 🙋 답할 것([Ask]) — 상한 없이 모두(놓친 중요 연락 0건)
 *  - 건수·처음/마지막 시각·중요한 대화인지
 * 안드로이드에 기대지 않는 순수 규칙(JSON 읽고 쓰기 빼고)이라 시험할 수 있다.
 */
object RoomDay {
    const val TALK = "talk"      // 💬 대화방(단톡·1:1)
    const val GROUP = "group"    // 👥 내 묶음 사람(app = 묶음 이름)
    const val WORK = "work"      // 💼 업무·일정 앱
    const val APP = "app"        // 📦 앱 알림(건수만)

    /** 요약 한 번이 덮은 이 방의 한 구간: 시각, 건수, 요약 줄. asked = 그 줄이 🙋 답할 것으로 올라간 내용 */
    data class Part(val from: Long, val to: Long, val count: Int, val point: String, val asked: Boolean = false)
    /** 🙋 답할 것 하나: 그 구간의 마지막 시각과 내용 */
    data class Ask(val time: Long, val text: String)

    data class Entry(
        val key: String, val app: String, val room: String, val kind: String,
        val count: Int, val first: Long, val last: Long,
        val parts: List<Part> = emptyList(), val asks: List<Ask> = emptyList(),
        /** 중요한 대화(명부·키워드·선생님 부르기·1:1·👥·💼) — 방별 하루에서 앞에, 핵심 재료로 */
        val important: Boolean = false,
    ) {
        /** 내용 있는 방인가 — 아니면 '그 밖의 방'에 이름과 건수만 */
        val hasContent: Boolean get() = parts.any { it.point.isNotBlank() }
    }

    /** 같은 방의 앞 기록과 새 기록 합치기: 건수는 더하고, 구간·물음은 이어 붙임(같은 말은 한 번) */
    fun merge(old: Entry?, new: Entry): Entry {
        if (old == null) return new
        val parts = (old.parts + new.parts).filter { it.point.isNotBlank() }
            .distinctBy { it.from to it.point }.sortedBy { it.from }
        val asks = (old.asks + new.asks).sortedBy { it.time }
            .fold(listOf<Ask>()) { acc, a -> if (acc.any { same(it.text, a.text) }) acc.filterNot { same(it.text, a.text) } + a else acc + a }
        return new.copy(
            app = new.app.ifEmpty { old.app }, room = new.room.ifEmpty { old.room },
            count = old.count + new.count, first = minOf(old.first, new.first), last = maxOf(old.last, new.last),
            parts = parts, asks = asks, important = old.important || new.important,
        )
    }

    private fun same(a: String, b: String) = a == b || maxOf(BriefingComposer.overlapPublic(a, b), BriefingComposer.overlapPublic(b, a)) >= 0.6

    /** 🙋 줄에 넣는 말 — 요약 화면의 답장할 연락 줄과 같게 */
    fun replyDetail(r: BriefingComposer.RoomSummary): String = when {
        r.ask.isNotEmpty() && r.byModel && !r.point.contains(r.ask) -> "${r.ask} (${BriefingComposer.dropName(r.point, r.conv.room)})"
        !r.byModel && r.ask.isNotEmpty() && r.point.contains(r.ask) -> BriefingComposer.dropName(r.point, r.conv.room)
        else -> r.ask.ifEmpty { BriefingComposer.dropName(r.point, r.conv.room) }
    }

    /** 묶음 방(👥·💼)의 종류 */
    fun groupKind(conv: BriefingComposer.Conversation) = if (conv.app == BriefingComposer.WORK_TITLE) WORK else GROUP

    /** 요약한 방 하나 → 기록. [from] 은 이번 요약 구간의 시작 */
    fun of(r: BriefingComposer.RoomSummary, kind: String, important: Boolean, from: Long): Entry {
        val conv = r.conv
        val group = if (kind == TALK) "" else conv.app
        val point = BriefingComposer.dropName(r.point.removePrefix("${conv.room}: ").trim(), conv.room, group)
        // 🙋 으로 올라가지 않은 부탁은 요약 줄 뒤에 '→ 부탁'(요약 화면과 같게)
        val body = if (!r.needReply && r.ask.isNotEmpty() && !r.point.contains(r.ask)) "$point → ${r.ask}" else point
        val part = Part(maxOf(from, conv.items.first().time), conv.last, conv.items.size, BriefingComposer.soften(body), asked = r.needReply)
        return Entry(
            key = BriefingComposer.keyOf(conv), app = conv.app, room = conv.room, kind = kind,
            count = conv.items.size, first = conv.items.first().time, last = conv.last,
            parts = if (part.point.isNotBlank()) listOf(part) else emptyList(),
            asks = if (r.needReply) listOf(Ask(conv.last, BriefingComposer.soften(replyDetail(r)))) else emptyList(),
            important = important || kind != TALK,
        )
    }

    /** 시간이 모자라 AI 로 정리하지 못한 방: AI 없이 그 말을 짧게(내용이 빠지지 않게) */
    fun ofCut(conv: BriefingComposer.Conversation, kind: String, important: Boolean, from: Long): Entry =
        of(BriefingComposer.peek(conv), kind, important, from)

    /** 📦 앱 알림: 앱별 건수만 */
    fun apps(items: List<NotiItem>): List<Entry> = items.groupBy { it.app }.map { (app, l) ->
        Entry("📦\u0001$app", app, app, APP, l.size, l.minOf { it.time }, l.maxOf { it.time })
    }

    // ── 저장(JSON) ──
    fun toJson(e: Entry): String = JSONObject().apply {
        put("k", e.key); put("a", e.app); put("r", e.room); put("t", e.kind); put("n", e.count)
        put("f", e.first); put("l", e.last); put("i", e.important)
        put("p", JSONArray().apply { e.parts.forEach { p -> put(JSONObject().put("f", p.from).put("t", p.to).put("n", p.count).put("s", p.point).put("q", p.asked)) } })
        put("q", JSONArray().apply { e.asks.forEach { a -> put(JSONObject().put("t", a.time).put("s", a.text)) } })
    }.toString()

    fun fromJson(s: String): Entry? = runCatching {
        val o = JSONObject(s)
        val p = o.optJSONArray("p") ?: JSONArray()
        val q = o.optJSONArray("q") ?: JSONArray()
        Entry(
            o.getString("k"), o.optString("a"), o.optString("r"), o.optString("t", TALK), o.optInt("n"),
            o.optLong("f"), o.optLong("l"),
            (0 until p.length()).map { i -> p.getJSONObject(i).let { Part(it.optLong("f"), it.optLong("t"), it.optInt("n"), it.optString("s"), it.optBoolean("q")) } },
            (0 until q.length()).map { i -> q.getJSONObject(i).let { Ask(it.optLong("t"), it.optString("s")) } },
            o.optBoolean("i"),
        )
    }.getOrNull()
}
