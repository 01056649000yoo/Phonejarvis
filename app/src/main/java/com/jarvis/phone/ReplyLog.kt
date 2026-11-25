package com.jarvis.phone

import android.content.Context

/**
 * ✔ 내가 답한 방 기록(2026-11-24 선생님: 앞 요약의 🙋 물음에 답했는데 하루 정리에 계속 '답할 연락'으로 남음).
 * 메시지 알림에 딸려 오는 '내가 보낸 말'의 시각만 방(단톡방 이름, 1:1 은 상대 이름)마다 가장 최근 것으로. 내용은 적지 않음.
 * 카톡 알림에는 내 답이 다음 메시지가 올 때 같이 실려 오므로, 답한 뒤 아무도 말하지 않으면 모를 수 있음.
 */
object ReplyLog {
    // 30일(2026-11-25): '내가 말한 방'은 🎯 나와의 거리에서 '내가 속한 방'을 알아보는 데도 씀(Relevance)
    private const val KEEP_MS = 30 * 24 * 3600 * 1000L
    private fun p(ctx: Context) = ctx.getSharedPreferences("reply_log", Context.MODE_PRIVATE)

    fun mark(ctx: Context, room: String, time: Long) {
        if (room.isBlank() || time <= 0) return
        val old = p(ctx).getLong(room, 0L)
        if (time <= old) return
        val now = System.currentTimeMillis()
        p(ctx).edit().apply {
            putLong(room, time)
            p(ctx).all.forEach { (k, v) -> if (v is Long && now - v > KEEP_MS) remove(k) }   // 30일 지난 것은 지움
        }.apply()
    }

    /** 방 → 내가 마지막으로 답한 시각 */
    fun all(ctx: Context): Map<String, Long> = p(ctx).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
}
