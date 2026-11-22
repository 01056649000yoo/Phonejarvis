package com.jarvis.phone

import android.app.Notification
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ✉️ 문자 답장 초안 (2026-11-22 선생님: "답장할 메시지 초안 — 문자만. 카톡은 카톡 안에 기능이 있어").
 *  - 요약 때 🙋 답장할 연락 중 **문자**만 골라 폰 안의 AI 로 초안을 미리 만들어 둠(한 번에 5건까지)
 *  - 요약 탭 '✉️ 답장할 문자'에서 고쳐 쓰고 [💬 보내기] — 문자 알림의 '답장' 기능으로 그 사람에게 바로 보냄.
 *    그 기능을 못 쓰면(재부팅·오래된 문자) 복사한 뒤 문자 앱을 엶. 자동으로 보내는 일은 없음.
 */
object Sms {
    val PACKAGES = setOf(
        "com.samsung.android.messaging", "com.google.android.apps.messaging",
        "com.android.mms", "com.android.messaging",
    )
    fun isSms(pkg: String) = pkg in PACKAGES
}

/** 한 사람에게 보낼 답장 초안 (DB reply_draft). sender = 문자 보낸 사람(알림 제목) */
data class ReplyDraft(val pkg: String, val sender: String, val lastTime: Long, val question: String, val draft: String)

/**
 * 문자 알림의 '답장' 단추(RemoteInput)와 번호를 **메모리에만** 들고 있음(번호는 저장하지 않음).
 * 알림을 숨겨도 단추는 쓸 수 있지만, 앱이 다시 켜지면(재부팅 등) 사라져 그때는 복사 + 문자 앱 열기.
 */
object ReplyHandles {
    private data class Handle(val pkg: String, val action: Notification.Action?, val number: String?, val at: Long)
    private val map = ConcurrentHashMap<String, Handle>()

    fun capture(sbn: StatusBarNotification, sender: String) {
        val n = sbn.notification ?: return
        val action = n.actions?.firstOrNull { a ->
            !a.remoteInputs.isNullOrEmpty() && (a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || a.remoteInputs.any { it.allowFreeFormInput })
        }
        val number = runCatching {
            @Suppress("DEPRECATION")
            val people = n.extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
            people?.mapNotNull { it.uri }?.firstOrNull { it.startsWith("tel:") }?.removePrefix("tel:")
        }.getOrNull() ?: sender.takeIf { s -> s.count { it.isDigit() } >= 8 && s.all { it.isDigit() || it in "-+ ()" } }
        map[sender] = Handle(sbn.packageName, action ?: map[sender]?.action, number ?: map[sender]?.number, System.currentTimeMillis())
        // 오래된 것은 버림(하루)
        val old = System.currentTimeMillis() - 24 * 3600_000L
        map.entries.removeIf { it.value.at < old }
    }

    fun canReplyDirectly(sender: String) = map[sender]?.action != null

    /** 보내기: 바로 답장 → 안 되면 번호로 문자 쓰기 화면 → 안 되면 복사 + 문자 앱. 무엇을 했는지 한 줄로 돌려줌 */
    fun send(ctx: Context, pkg: String, sender: String, text: String): String {
        val h = map[sender]
        h?.action?.let { a ->
            val ok = runCatching {
                val results = Bundle().apply { a.remoteInputs.forEach { putCharSequence(it.resultKey, text) } }
                val fill = Intent()
                RemoteInput.addResultsToIntent(a.remoteInputs, fill, results)
                a.actionIntent.send(ctx, 0, fill)
            }.isSuccess
            if (ok) { map.remove(sender); return "$sender 님께 보냈어요" }
        }
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("답장", text))
        h?.number?.let { num ->
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return "문자 쓰기 화면을 열었어요 · 확인하고 보내 주세요"
        }
        ctx.packageManager.getLaunchIntentForPackage(h?.pkg ?: pkg)?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        return "복사했어요 · 문자 앱에서 $sender 님 대화에 붙여 넣어 주세요"
    }
}

object ReplyDrafter {
    const val MAX_AUTO = 5

    /** 초안 만들기 질문. 지어내지 않고 모르는 사실은 [ ] 로 비워 둠 */
    fun prompt(sender: String, items: List<NotiItem>): String {
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
        val msgs = items.sortedBy { it.time }.takeLast(8).joinToString("\n") { "${fmt.format(it.time)} ${it.text.replace('\n', ' ').take(300)}" }
        return """
            너는 초등학교 선생님이 받은 문자에 보낼 답장 초안을 쓰는 비서야.
            규칙:
            - 존댓말로 2~3문장. 짧은 인사로 시작해.
            - 물어본 것·부탁한 것에만 답해.
            - 시간·장소·준비물처럼 모르는 사실은 지어내지 말고 [ ]로 비워 둬. 예: [시간]에 출발합니다.
            - 다른 학생 이름이나 개인정보는 쓰지 마.
            - 답장 글만 써. 따옴표·설명·제목 없이.

            보낸 사람: $sender
            받은 문자(오래된 것부터):
            $msgs
        """.trimIndent()
    }

    /** 모델 답 정리: 따옴표·머리말 떼고 400자까지 */
    fun clean(reply: String): String = reply.replace("*", "").lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("답장") && !it.startsWith("초안") }
        .joinToString("\n").trim().trim('"', '“', '”', '\'').take(400)
}
