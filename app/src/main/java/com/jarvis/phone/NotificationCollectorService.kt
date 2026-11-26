package com.jarvis.phone

import kotlinx.coroutines.launch
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 다른 앱에서 알림이 올 때마다 호출되어 제목·내용을 DB에 저장. */
class NotificationCollectorService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 화면을 그리는 줄(메인 스레드)에서 하지 않고 따로 한 줄로(온 순서대로) — 알림마다 DB 를 읽고 쓰는 일이라,
        // 앱 화면이 떠 있을 때 단톡방 알림이 몰리면 화면이 끊겼다(2026-11-26 선생님: 더 가볍게)
        worker.execute {
            // 알림 하나에서 난 오류(저장 공간 부족·이상한 알림)로 수집 서비스가 죽으면 그 뒤 알림을 모두 놓침 — 그 알림만 건너뜀
            try { collect(sbn) } catch (e: Exception) { android.util.Log.w("Collector", "알림 하나를 모으지 못함: ${sbn.packageName}", e) }
        }
    }

    /** 앱 이름은 알림마다 시스템에 묻지 않고 기억해 둠 */
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun collect(sbn: StatusBarNotification) {
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
        val room = roomOf(sbn, style, burst, title, learn = true)
        // 🔧 카톡 알림 구조 기록(메시지 내용 없이) — 오픈채팅 방 이름이 어디 들어오는지 설정 → 시스템에서 보려고
        if (sbn.packageName == "com.kakao.talk") runCatching { NotiProbe.record(this, ex, style, burst.map { it.who }, room,
            "${n.shortcutId ?: "없음"} · ${sbn.tag ?: "없음"} · ${sbn.id}") }
        // ✔ 내가 이 방에 답한 시각(알림에 딸려 온 내 말) — 하루 정리에서 답한 🙋 물음을 내리려고
        runCatching {
            val me = style?.user?.name?.toString().orEmpty()
            if (me.isNotBlank()) MyNames.noteMe(this, me)   // 🙋 나를 부르는 말 추천(카톡의 내 이름)
            style?.messages?.filter { m -> m.person == null || (me.isNotEmpty() && m.person?.name?.toString()?.trim() == me) }
                ?.maxByOrNull { it.timestamp }?.let { m ->
                    ReplyLog.mark(this, room.ifEmpty { title }, m.timestamp)
                    ReplyLog.noteText(this, room.ifEmpty { title }, m.timestamp, m.text?.toString().orEmpty())   // 내 말에 한 답을 내용으로 알아보려고
                }
        }

        // 📒 앱·방 이름 기억 (내용 X): 뺀 앱·끈 방도 목록에서 사라지지 않고 다시 켤 수 있게
        val app = appLabel(sbn.packageName)
        runCatching {
            val roomKey = room.ifEmpty { if (AppAdvisor.isMessaging(app, sbn.packageName)) title.take(100) else "" }
            NotificationDb.get(this).touchSeen(sbn.packageName, app, roomKey, sbn.postTime)
        }
        if (sbn.packageName in AppFilter.excluded(this)) return            // 설정에서 '빼기'한 앱

        // 설정에서 뺀 방은 모으지 않음 (단, 중요 키워드가 있으면 모음)
        val rules = AppFilter.cachedRules(this)
        val incoming = if (burst.isNotEmpty()) burst.map { Incoming(it.who.ifEmpty { title }, it.text, it.time, it.key) }
            else listOf(Incoming(title, text, sbn.postTime, null))
        var kept = 0
        var ads = 0
        for (m in incoming) {
            // 🚫 광고성 문자·카톡은 모으지 않음 (오늘 거른 수만 셈)
            if (rules.isBlockedAd(sbn.packageName, room, m.title, m.text)) { AdFilter.addOne(this); ads++; continue }
            if (!rules.allows(sbn.packageName, room, m.title, m.text)) continue
            // 🔐 인증번호·승인번호 알림은 모으지 않음(저장·요약·위젯에 남지 않게) — 상태바에는 그대로 둠
            if (isSecretCode("${m.title} ${m.text}")) continue
            val t = m.title.take(200); val body = m.text.take(1000)
            // 📜 카톡 알림에는 그 방의 지난 메시지(어제 것까지)가 딸려 옴 — 이미 모은 것이면 넣지 않음.
            // 방 이름 알아보기가 바뀌거나 예전 판에서 모은 것은 열쇠가 달라 지금 시각으로 다시 들어가 '어제 일이 섞이던' 것(2026-11-24)
            if (m.key != null && m.key > 0 && NotificationDb.get(this).has(sbn.packageName, t, body, m.key - 60_000L)) continue
            val added = NotificationDb.get(this).insert(pkg = sbn.packageName, app = app, title = t, text = body, time = m.time, room = room,
                dedupe = dedupeKey(sbn.packageName, room, t, body, m.time, m.key))
            if (!added) continue                                           // 이미 모은 메시지(알림이 고쳐져 다시 온 것)
            kept++
            runCatching {
                Students.log(this, Students.names(this), NotiItem(0, sbn.packageName, app, t, body, m.time, room))   // 🧒 학생 이름이 나오면 학생별 기록에
                // 📌 마감·할 일 자동 등록은 하지 않음(2026-11-23 선생님: 변수가 너무 많아 엉뚱한 것이 들어감)
            }
        }
        if (kept == 0 && incoming.none { rules.allows(sbn.packageName, room, it.title, it.text) }) {
            // 🚫 거른 광고뿐인 알림은 숨기는 때라면 상태바에서도 지움(2026-11-26 선생님 캡처: 카톡 채널 '(광고)' 알림이 요약 뒤에도 위에 그대로 남음).
            //    광고는 일부러 버리는 것이라 저장하지 않고 지워도 됨 — 끈 앱·끈 방·인증번호처럼 '안 모으는' 알림은 지금처럼 그대로 둠
            if (ads == incoming.size && shouldHide(this) && !mustNeverHide(sbn, title, judgeText(text, burst))) {
                runCatching { cancelNotification(sbn.key) }
                cleanGroup(sbn.packageName, sbn.groupKey)
            }
            return   // 모으지 않는 알림은 숨기지도 않음
        }
        // ✉️ 문자면 '답장' 단추·번호를 메모리에 들고 있음(답장 초안을 바로 보내려고)
        if (Sms.isSms(sbn.packageName) && room.isEmpty()) runCatching { ReplyHandles.capture(sbn, title.take(200)) }
        if (kept > 0) AppScope.io.launch { runCatching { PreSummary.nudge(this@NotificationCollectorService) } }   // ⚡ 쌓였으면 미리 요약 예약

        // 🏫 수업 중 · 🌙 퇴근 후: 모은 알림을 상태바에서 숨김 (나중에 브리핑으로 한 번에)
        //    단, 전화·알람·재난문자·인증번호는 절대 숨기지 않음
        //    숨기면 안 되는 말은 새로 온 문자 하나로만 봄(judgeText) — 안 읽은 문자 묶음 전체를 보면 예전 문자의 '긴급' 하나로 뒤 문자마다 묶음째 뜸
        if (shouldHide(this) && !mustNeverHide(sbn, title, judgeText(text, burst))) {
            runCatching { cancelNotification(sbn.key) }
            cleanGroup(sbn.packageName, sbn.groupKey)
        }
    }

    override fun onListenerConnected() {
        instance = this
        // 🌱 알림창 훑기: 지금 알림창에 남은 알림을 앱별로 세어 '알림 앱 정리' 추천 재료로(요약 재료에는 넣지 않음)
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        // 🔁 다시 연결됐을 때(앱 업데이트·폰 재시작·시스템이 서비스를 쉬게 한 뒤): 끊긴 동안 와서 알림창에 남아 있는 알림을 모음.
        //    마지막으로 알림을 본 시각(seen) 뒤에 온 것만 — 처음 연결할 때(본 적 없음)는 이미 읽은 알림이 첫 요약에 섞이지 않게 모으지 않음.
        //    이미 모은 것은 중복 열쇠로 걸러짐. 아래 훑기가 seen 을 고치기 전에 먼저.
        runCatching {
            val lastSeen = NotificationDb.get(this).lastSeenTime()
            if (lastSeen > 0) active.filter { it.postTime > lastSeen }.sortedBy { it.postTime }.forEach { onNotificationPosted(it) }
        }
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
    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        // 시스템이 연결을 끊으면 다시 붙여 달라고 요청(권한을 끈 경우에는 아무 일도 없음) — 그대로 두면 다음 재시작까지 알림을 못 모음
        runCatching { requestRebind(android.content.ComponentName(this, NotificationCollectorService::class.java)) }
    }

    /**
     * 이 알림의 방 이름(없으면 빈 값 — 1:1·일반 앱). 모을 때와 상태바를 정리할 때(sweep) 같은 규칙으로 —
     * 예전엔 sweep 이 방 이름을 따로(더 단순하게) 구해, 오픈채팅처럼 제목에서 방을 알아낸 '끈 방'을 켠 방으로 보고 모으지도 않은 알림을 지웠다.
     */
    private fun roomOf(sbn: StatusBarNotification, style: androidx.core.app.NotificationCompat.MessagingStyle?, burst: List<Msg>, title: String,
                       /** 모을 때만 true: 이 대화에서 본 보낸 사람을 기억함(ConvRooms) */ learn: Boolean = false): String {
        val ex = sbn.notification.extras
        val groupFlag = ex.getBoolean("android.isGroupConversation", false) || style?.isGroupConversation == true
        return (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: style?.conversationTitle
            ?: (if (sbn.packageName == "com.kakao.talk") ex.getCharSequence(Notification.EXTRA_SUB_TEXT) else null)
            // 2026-11-24: 오픈채팅이 그래도 사람마다 갈라짐 — 1:1 로 표시하는 대화는 방 이름을 숨긴 제목 칸에 넣기도 함
            ?: groupTitle(ex.getCharSequence("android.hiddenConversationTitle")?.toString().orEmpty(), burst.map { it.who })
            ?: groupTitle(title, burst.map { it.who }))
            ?.toString()?.trim().orEmpty().take(100)
            // 방 이름은 못 찾았지만 알림이 '여럿이 있는 대화'라고 알려 주면 1:1 로 보지 않음(2026-11-26 선생님 캡처: 같은 단톡방의 말이
            // 사람마다 1:1 연락처럼 '지인 연락'에 올라옴). 이름을 모르니 한데 묶어 건수만 — 나를 부른 말은 그래도 찾음
            // 💬 이름 없는 단톡방: 같은 대화(대화 바로가기 id)에서 보낸 사람이 둘 넘게 나오면 단톡방으로 — 방 이름은 '김희엽·최성우 단톡방'
            .ifEmpty {
                val conv = sbn.notification.shortcutId?.takeIf { it.isNotBlank() }?.let { "${sbn.packageName}|$it" }
                if (conv != null && AppAdvisor.isMessaging("", sbn.packageName))
                    runCatching { ConvRooms.roomFor(this, conv, burst.map { it.who }.ifEmpty { listOf(title) }, groupFlag, learn) }.getOrDefault("")
                else ""
            }
            .ifEmpty { if (groupFlag) UNNAMED_GROUP else "" }
    }

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
    fun sweep() = worker.execute { runCatching { sweepNow() } }

    private fun sweepNow() {
        val rules = AppFilter.cachedRules(this)
        val excluded = AppFilter.excluded(this)
        val db = NotificationDb.get(this)
        val since = System.currentTimeMillis() - 3 * 24L * 3600 * 1000
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter { it.packageName != packageName && !it.isOngoing && it.packageName !in excluded && it.packageName !in IGNORED_PACKAGES
                && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .forEach { sbn ->
                runCatching {
                    val ex = sbn.notification.extras
                    val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                    val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
                    val style = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) }.getOrNull()
                    val burst = runCatching { style?.let { messagesOf(it, sbn.postTime) } }.getOrNull().orEmpty()
                    val room = roomOf(sbn, style, burst, title)
                    // 새로 온 메시지(목록이 있으면 가장 늦은 것) 기준으로: 모으는 방이고, 실제로 모아 둔 것만 지움.
                    // 알림 접근을 켜기 전·서비스가 쉬는 동안 온 알림, 광고·인증번호처럼 저장하지 않은 알림은 지우면 어디에도 안 남음
                    val newest = burst.maxByOrNull { it.key }
                    val who = (newest?.who?.ifEmpty { title } ?: title).take(200)
                    val body = (newest?.text ?: text).take(1000)
                    // 상태바에 남은 알림은 최근 것 — 사흘 안에서만 찾음(시각 색인을 써서 표 전체를 훑지 않게)
                    val stored = rules.allows(sbn.packageName, room, who, body) && db.has(sbn.packageName, who, body, since)
                    val ad = rules.isBlockedAd(sbn.packageName, room, who, body)   // 거른 광고도 함께 지움
                    if ((stored || ad) && !mustNeverHide(sbn, title, judgeText(text, burst))) cancelNotification(sbn.key)
                }
            }
        active.map { it.packageName to it.groupKey }.distinct().forEach { (p, g) -> if (p !in excluded) cleanGroup(p, g) }
    }

    /** 수업 중·퇴근 모드에서도 숨기면 안 되는 알림: 전화, 알람·타이머, 재난·긴급 문자, 인증번호(OTP) */
    private fun mustNeverHide(sbn: StatusBarNotification, title: String, text: String): Boolean {
        val cat = sbn.notification.category
        if (cat in NEVER_HIDE_CATEGORIES) return true
        if (NEVER_HIDE_PACKAGES.any { sbn.packageName.startsWith(it) }) return true
        // 인증번호·임시 비밀번호는 저장하지 않으므로(isSecretCode) 숨기면 어디에도 안 남음 — 예전엔 '보안코드'·'비밀번호' 알림이 모이지도 않고 숨겨졌다
        return urgentWords("$title $text") || isSecretCode("$title $text")
    }

    private fun appLabel(pkg: String): String = labels[pkg] ?: try {
        val pm = packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().also { if (labels.size < 300) labels[pkg] = it }
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

        /** 숨기면 안 되는 말(재난·긴급·경보·부재중·인증번호)이 들었는가(순수 — 시험) */
        fun urgentWords(t: String): Boolean = NEVER_HIDE_WORDS.any { it in t } || OTP.containsMatchIn(t)

        /**
         * 숨길지 판단할 글(순수 — 시험): 메시지 목록이 있으면 새로 온 것(보낸 시각이 가장 늦은 것) 하나만.
         * 2026-11-25: 삼성 메시지는 안 읽은 문자를 한 알림에 모두 묶어 다시 보냄 — 묶음 전체를 보면
         * 예전 문자 하나의 '긴급'·'부재중' 때문에 뒤에 오는 평범한 문자마다 묶음째 상태바에 떴다.
         */
        fun judgeText(text: String, burst: List<Msg>): String = burst.maxByOrNull { it.key }?.text ?: text

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

        /** 알림을 온 순서대로 하나씩 처리하는 줄(메인 스레드 밖). 서비스가 다시 만들어져도 순서가 섞이지 않게 하나만 */
        private val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "collector").apply { isDaemon = true }
        }

        const val UNNAMED_GROUP = "단톡방(이름 미확인)"

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
        private val SECRET_WORDS = listOf("인증번호", "인증 번호", "인증코드", "승인번호", "OTP")
        /**
         * '비밀번호'·'보안코드'는 그 뒤에 실제 값(숫자가 든 4자 이상)이 있을 때만 — 예전엔 낱말만 있어도 버려서
         * "와이파이 비밀번호 뭐였죠?", "나이스 비밀번호 변경 안내" 같은 연락·공지가 통째로 모이지 않았다.
         */
        private val SECRET_VALUE = Regex("""(보안\s?코드|비밀\s?번호)[^\n]{0,15}?(?=[A-Za-z0-9!@#$%^&*]*\d)[A-Za-z0-9!@#$%^&*]{4,}""")
        /** 시스템이 내주는 비밀번호는 낱말만으로("483920 이 임시 비밀번호입니다"처럼 값이 앞에 와도) */
        private val SECRET_ISSUED = Regex("""(임시|초기|일회용|새)\s?(비밀\s?번호|보안\s?코드|패스워드)|(비밀\s?번호|패스워드)\s?(재설정|초기화)""")
        /** '비밀번호는 qwerasdf'·'비밀번호: abcd'처럼 숫자가 없어도 바로 뒤에 영문·숫자 값이 오면 */
        private val SECRET_ASSIGN = Regex("""(보안\s?코드|비밀\s?번호|패스워드)\s?(는|은|:|=)\s?\[?[A-Za-z0-9!@#$%^&*]{4,}""")
        /** 인증번호·일회용 비밀번호처럼 남기면 안 되는 알림인지 */
        fun isSecretCode(t: String): Boolean = SECRET_WORDS.any { it in t } || OTP.containsMatchIn(t) || SECRET_VALUE.containsMatchIn(t) ||
            SECRET_ISSUED.containsMatchIn(t) || SECRET_ASSIGN.containsMatchIn(t)

        /**
         * 같은 알림을 두 번 넣지 않는 열쇠(순수 — 시험).
         * - 메시지 목록의 메시지: 보낸 시각([msgKey])까지 같아야 같은 메시지
         * - 그 밖의 알림: 같은 날 같은 글이면 같은 알림(고쳐져 다시 온 것). 예전엔 날짜가 없어 보관 기간 내내 한 번만 모였다 —
         *   매일 같은 글로 오는 알림("새 알림장이 등록되었습니다", 날마다 같은 일정 알림)이 첫날만 모이고 그 뒤로는 숨겨지기만 했다.
         */
        fun dedupeKey(pkg: String, room: String, title: String, text: String, time: Long, msgKey: Long?): String =
            if (msgKey != null) "$pkg|$room|$title|$text|$msgKey" else "$pkg|$room|$title|$text|d${localDay(time)}"

        /** 이 폰 시간대의 날짜 번호(자정에 바뀜) */
        fun localDay(time: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
            Math.floorDiv(time + zone.getOffset(time), 24L * 3600 * 1000)

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
