package com.jarvis.phone

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * 🔧 카톡 알림이 방 이름을 어느 칸에 넣는지 보기 (2026-11-24: 오픈채팅이 보낸 사람마다 1:1 방으로 갈라짐).
 * 메시지 내용(본문)은 적지 않고 제목·방 이름 후보 칸과 칸 이름만 최근 5개. 폰 안에만 남음.
 */
object NotiProbe {
    private const val KEY = "probe"
    private const val SEP = "\n────────\n"
    private fun p(ctx: Context) = ctx.getSharedPreferences("probe", Context.MODE_PRIVATE)

    fun record(ctx: Context, ex: Bundle, style: NotificationCompat.MessagingStyle?, senders: List<String>, room: String, conv: String = "") {
        fun cs(k: String) = runCatching { ex.getCharSequence(k)?.toString() }.getOrNull()?.take(60)
        val entry = buildString {
            append("시각 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(System.currentTimeMillis())}\n")
            append("→ 쌤톡스가 정한 방: ${room.ifEmpty { "(없음 — 1:1 로 봄)" }}\n")
            append("title: ${cs(Notification.EXTRA_TITLE)}\n")
            append("subText: ${cs(Notification.EXTRA_SUB_TEXT)}\n")
            append("conversationTitle: ${cs(Notification.EXTRA_CONVERSATION_TITLE)}\n")
            append("hiddenConversationTitle: ${cs("android.hiddenConversationTitle")}\n")
            append("대화 표시(shortcut·tag·id): $conv\n")
            append("isGroupConversation: ${ex.getBoolean("android.isGroupConversation", false)} / style: ${style?.isGroupConversation}\n")
            append("style.conversationTitle: ${style?.conversationTitle?.toString()?.take(60)}\n")
            append("summaryText: ${cs(Notification.EXTRA_SUMMARY_TEXT)} · infoText: ${cs(Notification.EXTRA_INFO_TEXT)}\n")
            append("보낸 사람: ${senders.distinct().take(5).joinToString(", ")}\n")
            append("칸 이름: ${ex.keySet().sorted().joinToString(", ") { it.removePrefix("android.") }}")
        }
        val old = all(ctx)
        p(ctx).edit().putString(KEY, (listOf(entry) + old).take(5).joinToString(SEP)).apply()
    }

    fun all(ctx: Context): List<String> = p(ctx).getString(KEY, null)?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()
}
