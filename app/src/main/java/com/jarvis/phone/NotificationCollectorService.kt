package com.jarvis.phone

import kotlinx.coroutines.launch
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 다른 앱에서 알림이 올 때마다 호출되어 제목·내용을 DB에 저장. */
class NotificationCollectorService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return                       // 내 앱 알림 제외
        if (sbn.isOngoing) return                                          // 음악 재생, 다운로드 진행 등 상시 알림 제외
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {            // "새 메시지 3개" 같은 묶음 요약은 모으지 않음
            // 숨기는 때라면 묶음 안 알림이 다 숨겨졌을 때 묶음 요약도 지움(카톡 묶음이 상태바에 남던 것)
            if (shouldHide(this) && sbn.packageName !in AppFilter.excluded(this)) cleanGroup(sbn.packageName, sbn.groupKey)
            return
        }
        if (sbn.packageName in IGNORED_PACKAGES || sbn.packageName == packageName) return   // 쌤톡스 자기 알림(요약 중·완료)은 모으지 않음
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // 단톡방 이름: 메시지 앱은 대화 제목(단톡방)을 따로 넣어 줌. 1:1 대화면 비어 있고 제목(보낸 사람)이 곧 방.
        // 💬 메시지 목록이 든 알림(카톡·문자 등): 한 번에 여러 메시지가 오면 마지막 것만 남던 것(2026-11-24 점검) → 목록의 메시지를 하나씩
        val style = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val burst = runCatching { style?.let { messagesOf(it, sbn.postTime) } }.getOrNull().orEmpty()
        val room = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: style?.conversationTitle
            ?: (if (sbn.packageName == "com.kakao.talk") ex.getCharSequence(Notification.EXTRA_SUB_TEXT) else null)
            // 2026-11-24: 오픈채팅이 그래도 사람마다 갈라짐 — 1:1 로 표시하는 대화는 방 이름을 숨긴 제목 칸에 넣기도 함
            ?: groupTitle(ex.getCharSequence("android.hiddenConversationTitle")?.toString().orEmpty(), burst.map { it.who })
            ?: groupTitle(title, burst.map { it.who }))
            ?.toString()?.trim().orEmpty().take(100)
        // 🔧 카톡 알림 구조 기록(메시지 내용 없이) — 오픈채팅 방 이름이 어디 들어오는지 설정 → 시스템에서 보려고
        if (sbn.packageName == "com.kakao.talk") runCatching { NotiProbe.record(this, ex, style, burst.map { it.who }, room) }

        // 📒 앱·방 이름 기억 (내용 X): 뺀 앱·끈 방도 목록에서 사라지지 않고 다시 켤 수 있게
        val app = appLabel(sbn.packageName)
        runCatching {
            val roomKey = room.ifEmpty { if (AppAdvisor.isMessaging(app, sbn.packageName)) title.take(100) else "" }
            NotificationDb.get(this).touchSeen(sbn.packageName, app, roomKey, sbn.postTime)
        }
        if (sbn.packageName in AppFilter.excluded(this)) return            // 설정에서 '빼기'한 앱

        // 설정에서 뺀 방은 모으지 않음 (단, 중요 키워드가 있으면 모음)
        val rules = AppFilter.rules(this)
        val incoming = if (burst.isNotEmpty()) burst.map { Incoming(it.who.ifEmpty { title }, it.text, it.time, it.key) }
            else listOf(Incoming(title, text, sbn.postTime, null))
        var kept = 0
        for (m in incoming) {
            // 🚫 광고성 문자·카톡은 모으지 않음 (오늘 거른 수만 셈)
            if (rules.isBlockedAd(sbn.packageName, room, m.title, m.text)) { AdFilter.addOne(this); continue }
            if (!rules.allows(sbn.packageName, room, m.title, m.text)) continue
            // 🔐 인증번호·승인번호 알림은 모으지 않음(저장·요약·위젯에 남지 않게) — 상태바에는 그대로 둠
            if (isSecretCode("${m.title} ${m.text}")) continue
            val t = m.title.take(200); val body = m.text.take(1000)
            // 📜 카톡 알림에는 그 방의 지난 메시지(어제 것까지)가 딸려 옴 — 이미 모은 것이면 넣지 않음.
            // 방 이름 알아보기가 바뀌거나 예전 판에서 모은 것은 열쇠가 달라 지금 시각으로 다시 들어가 '어제 일이 섞이던' 것(2026-11-24)
            if (m.key != null && m.key > 0 && NotificationDb.get(this).has(sbn.packageName, t, body, m.key - 60_000L)) continue
            val added = NotificationDb.get(this).insert(pkg = sbn.packageName, app = app, title = t, text = body, time = m.time, room = room,
                dedupe = m.key?.let { "${sbn.packageName}|$room|$t|$body|$it" })
            if (!added) continue                                           // 이미 모은 메시지(알림이 고쳐져 다시 온 것)
            kept++
            runCatching {
                Students.log(this, Students.names(this), NotiItem(0, sbn.packageName, app, t, body, m.time, room))   // 🧒 학생 이름이 나오면 학생별 기록에
                // 📌 마감·할 일 자동 등록은 하지 않음(2026-11-23 선생님: 변수가 너무 많아 엉뚱한 것이 들어감)
            }
        }
        if (kept == 0 && incoming.none { rules.allows(sbn.packageName, room, it.title, it.text) }) return   // 모으지 않는 알림은 숨기지도 않음
        // ✉️ 문자면 '답장' 단추·번호를 메모리에 들고 있음(답장 초안을 바로 보내려고)
        if (Sms.isSms(sbn.packageName) && room.isEmpty()) runCatching { ReplyHandles.capture(sbn, title.take(200)) }
        if (kept > 0) AppScope.io.launch { runCatching { PreSummary.nudge(this@NotificationCollectorService) } }   // ⚡ 쌓였으면 미리 요약 예약

        // 🏫 수업 중 · 🌙 퇴근 후: 모은 알림을 상태바에서 숨김 (나중에 브리핑으로 한 번에)
        //    단, 전화·알람·재난문자·인증번호는 절대 숨기지 않음
        if (shouldHide(this) && !mustNeverHide(sbn, title, text)) {
            runCatching { cancelNotification(sbn.key) }
            cleanGroup(sbn.packageName, sbn.groupKey)
        }
    }

    override fun onListenerConnected() {
        instance = this
        // 🌱 알림창 훑기: 지금 알림창에 남은 알림을 앱별로 세어 '알림 앱 정리' 추천 재료로(요약 재료에는 넣지 않음)
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        AppScope.io.launch {
            runCatching {
                val db = NotificationDb.get(this@NotificationCollectorService)
                val seen = active.filter { it.packageName != packageName && it.packageName !in IGNORED_PACKAGES && !it.isOngoing
                        && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                    .mapNotNull { sbn ->
                        val ex = sbn.notification.extras
                        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                        if (title.isEmpty() && text.isEmpty()) return@mapNotNull null
                        val app = appLabel(sbn.packageName)
                        db.touchSeen(sbn.packageName, app, "", sbn.postTime)   // 📒 앱 이름 기억(내용 X)
                        ShadeSeed.Seen(sbn.packageName, app, if (isSecretCode("$title $text")) "" else title)
                    }
                ShadeSeed.save(this@NotificationCollectorService, seen)
            }
        }
    }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    /** 묶음 안에 보이는 알림이 하나도 없으면 묶음 요약("새 메시지 N개")도 지움 */
    private fun cleanGroup(pkg: String, groupKey: String?) {
        val same = runCatching { activeNotifications }.getOrNull()?.filter { it.packageName == pkg && it.groupKey == groupKey } ?: return
        if (same.isNotEmpty() && same.all { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
            same.forEach { runCatching { cancelNotification(it.key) } }
    }

    /**
     * 지금 상태바에 남아 있는, 모으는 앱의 알림을 지움(이미 도착할 때 모아 두었으므로 지우기만).
     * 모아 두는 시간이 시작될 때·'언제나 숨기기'를 켤 때 부름. 전화·알람·재난 문자·인증번호는 남김.
     */
    fun sweep() {
        val rules = AppFilter.rules(this)
        val excluded = AppFilter.excluded(this)
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter { it.packageName != packageName && !it.isOngoing && it.packageName !in excluded && it.packageName !in IGNORED_PACKAGES
                && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .forEach { sbn ->
                val ex = sbn.notification.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
                val room = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?: if (sbn.packageName == "com.kakao.talk") ex.getCharSequence(Notification.EXTRA_SUB_TEXT) else null)?.toString()?.trim().orEmpty()
                if (rules.allows(sbn.packageName, room, title, text) && !mustNeverHide(sbn, title, text)) runCatching { cancelNotification(sbn.key) }
            }
        active.map { it.packageName to it.groupKey }.distinct().forEach { (p, g) -> if (p !in excluded) cleanGroup(p, g) }
    }

    /** 수업 중·퇴근 모드에서도 숨기면 안 되는 알림: 전화, 알람·타이머, 재난·긴급 문자, 인증번호(OTP) */
    private fun mustNeverHide(sbn: StatusBarNotification, title: String, text: String): Boolean {
        val cat = sbn.notification.category
        if (cat in NEVER_HIDE_CATEGORIES) return true
        if (NEVER_HIDE_PACKAGES.any { sbn.packageName.startsWith(it) }) return true
        val t = "$title $text"
        return NEVER_HIDE_WORDS.any { it in t } || OTP.containsMatchIn(t)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** 모을 메시지 하나: 보낸 사람(제목), 내용, 저장 시각, 메시지 목록에서 왔으면 그 메시지의 보낸 시각(중복 막는 키) */
    private data class Incoming(val title: String, val text: String, val time: Long, val key: Long?)

    /** 알림의 메시지 목록(MessagingStyle). 내가 보낸 말은 뺌. 없으면 빈 목록 */
    private fun messagesOf(style: androidx.core.app.NotificationCompat.MessagingStyle, postTime: Long): List<Msg> {
        val me = style.user.name?.toString().orEmpty()
        val raw = style.messages.mapNotNull { m ->
            val text = m.text?.toString()?.trim().orEmpty()
            val who = m.person?.name?.toString()?.trim().orEmpty()
            // 보낸 사람이 없거나 나(사용자)인 메시지는 내가 쓴 답장
            if (text.isEmpty() || m.person == null || (me.isNotEmpty() && who == me)) null else Triple(who, text, m.timestamp)
        }
        return arrivals(raw, postTime)
    }

    companion object {
        /**
         * 방 이름이 따로 없을 때 알림 제목이 곧 단톡방 이름인가(순수 규칙 — 시험).
         * 2026-11-24: 오픈채팅은 제목이 방 이름, 메시지마다 보낸 사람이 따로 있어 — 보낸 사람마다 1:1 방으로 갈라져 13개 방이 됨.
         * 보낸 사람 중 누구와도 제목이 다르면(1:1 은 제목이 곧 보낸 사람) 제목을 방 이름으로
         */
        fun groupTitle(title: String, senders: List<String>): String? {
            val who = senders.filter { it.isNotBlank() }.distinct()
            if (title.isBlank() || who.isEmpty()) return null
            return title.takeIf { t -> who.none { t == it || t.startsWith(it) || it.startsWith(t) } }
        }

        /** 메시지 목록에서 꺼낸 메시지 하나 */
        data class Msg(val who: String, val text: String, val time: Long, val key: Long)

        /**
         * 저장 시각 정하기(순수 규칙 — 시험): 보낸 시각이 알림 시각과 2분 안이면 그대로, 훨씬 전이면(폰이 잠들었다 깨서 늦게 받음)
         * 받은 시각으로 — 지난 요약이 덮은 시각보다 앞이라 어느 요약에도 안 들어가는 일을 막음. 순서는 1ms 씩 앞당겨 지킴.
         */
        fun arrivals(raw: List<Triple<String, String, Long>>, postTime: Long): List<Msg> = raw.mapIndexed { i, (who, text, ts) ->
            val late = postTime - ts > 2 * 60_000L
            Msg(who, text, if (late || ts <= 0) postTime - (raw.size - 1 - i) else ts, ts)
        }

        /** 지금 연결된 알림 수집 서비스(모아 두는 시간이 시작될 때 상태바 정리에 씀) */
        @Volatile var instance: NotificationCollectorService? = null
            private set

        /** 지금 모으는 앱 알림을 상태바에서 숨길 때인가: '언제나' 또는 모아 두는 시간 */
        fun shouldHide(ctx: android.content.Context): Boolean = when (QuietMode.hideWhen(ctx)) {
            QuietMode.Hide.ALWAYS -> true
            QuietMode.Hide.QUIET -> QuietMode.state(ctx) != QuietMode.State.NONE
            QuietMode.Hide.NEVER -> false
        }

        private val NEVER_HIDE_CATEGORIES = setOf(
            Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_NAVIGATION, "missed_call",
        )
        /** 전화 앱, 재난문자(긴급 재난), 시계 */
        private val NEVER_HIDE_PACKAGES = listOf(
            "com.android.cellbroadcast", "com.google.android.cellbroadcast", "com.samsung.android.cellbroadcast",
            "com.android.server.telecom", "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer",
            "com.sec.android.app.clockpackage", "com.google.android.deskclock",
        )
        private val NEVER_HIDE_WORDS = listOf("인증번호", "인증 번호", "인증코드", "승인번호", "OTP", "재난", "긴급", "경보", "부재중")
        private val SECRET_WORDS = listOf("인증번호", "인증 번호", "인증코드", "승인번호", "OTP", "보안코드", "비밀번호")
        /** 인증번호·일회용 비밀번호처럼 남기면 안 되는 알림인지 */
        fun isSecretCode(t: String): Boolean = SECRET_WORDS.any { it in t } || OTP.containsMatchIn(t)

        /**
         * "[Web발신] 인증번호 [123456]" 같은 4~8자리 인증번호.
         * 2026-11-24 점검: 예전엔 '확인' 뒤 12자 안에 숫자 4개면 인증번호로 봐서 "확인 부탁드려요 15000원",
         * "확인 후 010-1234-5678로" 같은 학부모 메시지를 통째로 모으지 않았다 → '확인'만으로는 안 보고,
         * 전화번호 조각(앞뒤 -)·금액(뒤에 원·,)·더 긴 숫자의 일부는 인증번호가 아님.
         */
        private val OTP = Regex("""(인증|본인\s?확인|확인\s?(번호|코드))[^\n]{0,12}?(?<![\d,.-])\d{4,8}(?![\d,.-]|\s?원)""")

        /** 시스템 잡음성 알림. 필요하면 추가하세요. */
        val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.samsung.android.lool",          // 디바이스 케어
            "com.sec.android.app.launcher",
        )
    }
}
