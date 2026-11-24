package com.jarvis.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 📝 방별 이어가기 메모 (2026-11-24 선생님: 앞 요약과 다음 요약 사이의 이어짐이 요약에 안 담김).
 * 요약이 끝날 때 방마다 '안 풀린 물음·부탁'과 '날짜·시간과 함께 정해진 것'을 AI 없이 규칙으로 뽑아(BriefingComposer.memoOf)
 * 최근 4개까지 적어 둔다. 요약 구간이 바뀌어도 남아, 다음 요약 때 그 방의 새 말과 이어지면 AI 에 참고로 주고 줄 끝에 '↪ 앞: …'.
 * 사흘 지난 것, 내가 답한 물음(ReplyLog)은 지운다. 알림 원문을 모두 지우면 같이 지운다.
 */
object ThreadMemo {
    private fun p(ctx: Context) = ctx.getSharedPreferences("thread_memo", Context.MODE_PRIVATE)

    /** 방 키(BriefingComposer.keyOf) → 메모(오래된 것부터). 사흘 지난 것은 빼고 */
    fun load(ctx: Context, now: Long = System.currentTimeMillis()): Map<String, List<BriefingComposer.Memo>> = runCatching {
        val o = JSONObject(p(ctx).getString("rooms", null) ?: "{}")
        val out = mutableMapOf<String, List<BriefingComposer.Memo>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            val list = (0 until a.length()).map { i -> a.getJSONObject(i).let { m -> BriefingComposer.Memo(m.getString("w"), m.getString("t"), m.getLong("m")) } }
                .filter { now - it.time <= BriefingComposer.MEMO_KEEP_MS }
            if (list.isNotEmpty()) out[k] = list
        }
        out
    }.getOrDefault(emptyMap())

    /** 요약이 끝날 때: 이번 구간의 대화들에서 메모를 뽑아 앞의 메모에 이어 붙임 */
    @Synchronized fun update(ctx: Context, convs: List<BriefingComposer.Conversation>, now: Long = System.currentTimeMillis()) {
        val old = load(ctx, now)
        val replied = ReplyLog.all(ctx)
        val fresh = convs.associate { c -> BriefingComposer.keyOf(c) to (c to BriefingComposer.memoOf(c)) }
        val o = JSONObject()
        for (k in old.keys + fresh.keys) {
            val conv = fresh[k]?.first
            // 내가 답한 시각: 방 이름(1:1 은 상대 이름)으로 — 이번 구간에 그 방 말이 없었으면 알 수 없어 그대로 둠
            val repliedAt = conv?.items?.lastOrNull()?.let { replied[it.roomOrTitle] } ?: 0L
            val list = BriefingComposer.memoCarry(old[k].orEmpty(), fresh[k]?.second.orEmpty(), repliedAt, now)
            if (list.isEmpty()) continue
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("w", it.who).put("t", it.text).put("m", it.time)) }
            o.put(k, a)
        }
        p(ctx).edit().putString("rooms", o.toString()).apply()
    }

    fun clear(ctx: Context) { p(ctx).edit().remove("rooms").apply() }
}
