package com.jarvis.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 다른 앱에서 알림이 올 때마다 호출되어 제목·내용을 DB에 저장. */
class NotificationCollectorService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return                       // 내 앱 알림 제외
        if (sbn.isOngoing) return                                          // 음악 재생, 다운로드 진행 등 상시 알림 제외
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return       // "새 메시지 3개" 같은 묶음 요약 제외
        if (sbn.packageName in IGNORED_PACKAGES) return
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

        NotificationDb.get(this).insert(
            pkg = sbn.packageName,
            app = app,
            title = title.take(200),
            text = text.take(1000),
            time = sbn.postTime,
            room = room,
        )
        val item = NotiItem(0, sbn.packageName, app, title.take(200), text.take(1000), sbn.postTime, room)
        runCatching {
            Students.log(this, Students.names(this), item)   // 🧒 학생 이름이 나오면 학생별 기록에
            TaskExtractor.save(this, item)                   // 📌 마감·할 일이 있으면 할 일 목록에
        }

        // 🏫 수업 중 · 🌙 퇴근 후: 모은 알림을 상태바에서 숨김 (나중에 브리핑으로 한 번에)
        //    단, 전화·알람·재난문자·인증번호는 절대 숨기지 않음
        if (QuietMode.hideInShade(this) && QuietMode.state(this) != QuietMode.State.NONE && !mustNeverHide(sbn, title, text)) {
            runCatching { cancelNotification(sbn.key) }
        }
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
