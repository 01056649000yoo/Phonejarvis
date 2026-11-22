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
        val room = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: if (sbn.packageName == "com.kakao.talk") ex.getCharSequence(Notification.EXTRA_SUB_TEXT) else null)
            ?.toString()?.trim().orEmpty().take(100)

        // 📒 앱·방 이름 기억 (내용 X): 뺀 앱·끈 방도 목록에서 사라지지 않고 다시 켤 수 있게
        val app = appLabel(sbn.packageName)
        runCatching {
            val roomKey = room.ifEmpty { if (AppAdvisor.isMessaging(app, sbn.packageName)) title.take(100) else "" }
            NotificationDb.get(this).touchSeen(sbn.packageName, app, roomKey, sbn.postTime)
        }
        if (sbn.packageName in AppFilter.excluded(this)) return            // 설정에서 '빼기'한 앱

        // 설정에서 뺀 방은 모으지 않음 (단, 중요 키워드가 있으면 모음)
        val rules = AppFilter.rules(this)
        // 🚫 광고성 문자·카톡은 모으지 않음 (오늘 거른 수만 셈)
        if (rules.isBlockedAd(sbn.packageName, room, title, text)) { AdFilter.addOne(this); return }
        if (!rules.allows(sbn.packageName, room, title, text)) return
        // 🔐 인증번호·승인번호 알림은 모으지 않음(저장·요약·위젯에 남지 않게) — 상태바에는 그대로 둠
        if (isSecretCode("$title $text")) return

        NotificationDb.get(this).insert(
            pkg = sbn.packageName,
            app = app,
            title = title.take(200),
            text = text.take(1000),
            time = sbn.postTime,
            room = room,
        )
        val item = NotiItem(0, sbn.packageName, app, title.take(200), text.take(1000), sbn.postTime, room)
        // ✉️ 문자면 '답장' 단추·번호를 메모리에 들고 있음(답장 초안을 바로 보내려고)
        if (Sms.isSms(sbn.packageName) && room.isEmpty()) runCatching { ReplyHandles.capture(sbn, title.take(200)) }
        AppScope.main.launch { SummaryWidget.refreshForNewNoti(this@NotificationCollectorService) }   // 🏠 위젯 '요약 N'(10분에 한 번까지)
        runCatching {
            Students.log(this, Students.names(this), item)   // 🧒 학생 이름이 나오면 학생별 기록에
            TaskExtractor.save(this, item)                   // 📌 마감·할 일이 있으면 할 일 목록에
        }

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

    companion object {
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

        /** "[Web발신] … 123456" 같은 6자리 안팎 인증번호 */
        private val OTP = Regex("""(인증|확인|본인)[^\n]{0,12}\d{4,8}""")

        /** 시스템 잡음성 알림. 필요하면 추가하세요. */
        val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.samsung.android.lool",          // 디바이스 케어
            "com.sec.android.app.launcher",
        )
    }
}
