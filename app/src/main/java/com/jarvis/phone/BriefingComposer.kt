package com.jarvis.phone

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AI 요약 한 장 만들기 (v1.4.0, 2026-11-21 선생님 "요약이 너무 성의 없다").
 *
 * 예전: 남은 알림을 1,500자씩 잘라 요약 → 그 요약들을 다시 "15줄 이내"로 줄임(두 번 줄이며 누가 무엇을 원했는지 사라짐),
 *       사람 메시지와 쇼핑·배송·시스템 알림이 한데 섞임.
 * 지금: ① 규칙으로 나눔 — 메시지 앱(카톡·문자·밴드·하이클래스 등) = 사람, 그 밖 = 앱 알림
 *       ② 사람 메시지는 **대화방마다 따로** 모델에 묻고, 답은 정해진 세 줄(요점·요청·답장)로만 받는다(다시 줄이는 단계 없음)
 *       ③ 앱 알림은 AI 없이 앱별 건수와 최근 제목만
 *       ④ 짧은 메시지 하나뿐인 방은 모델을 부르지 않고 그 말을 그대로 쓴다(빠르고 지어낼 일이 없음)
 * 안드로이드에 기대지 않는 순수 코드라 맥미니에서 같은 모델(Gemma)로 시뮬레이션할 수 있다(src/test).
 */
object BriefingComposer {

    /** 대화방 하나: 앱·방 이름·메시지(오래된 것부터) */
    data class Conversation(val app: String, val room: String, val items: List<NotiItem>) {
        val last: Long get() = items.maxOf { it.time }
        /** 단톡방이면 보낸 사람을 붙인다 */
        fun who(n: NotiItem) = if (n.room.isNotEmpty() && n.title != n.room) n.title else ""
    }

    /** 모델이 돌려준 세 줄(또는 규칙으로 만든 값) */
    data class RoomSummary(val conv: Conversation, val point: String, val ask: String, val needReply: Boolean, val byModel: Boolean)

    const val CHUNK = 30              // 대화방 하나에서 모델에 한 번에 넣는 메시지 수 — 넘으면 30개씩 나눠 요약한 뒤 합침(2026-11-21 선생님)
    const val ROOM_CHARS = 2400       // 한 번에 넣는 글자 상한(최근 메시지부터 채움)
    const val SHORT_CHARS = 60        // 이보다 짧은 메시지 하나뿐인 방은 모델 없이

    private val AD = Regex("^\\(광고\\)")
    private val QUESTION = Regex("[?？]|(주세요|주시면|주실|부탁|가능할까|될까요|되나요|인가요|있나요|어떠세요|알려|확인해|회신|답장|연락 ?주)")

    /** 사람 메시지(메시지 앱)인가 — 업무 앱·사람 묶음은 이 앞에서 이미 빠진다. 메시지 앱이어도 봇·채널·앱 안내는 사람이 아니다 */
    fun isPerson(n: NotiItem): Boolean = AppAdvisor.isMessaging(n.app, n.pkg) && !isAutomated(n)

    // 2026-11-21 선생님: 사람 메시지에 자비스(텔레그램 봇)·카카오톡 알림이 섞임
    private val BOT_NAME = Regex("(?i)(bot\\b|봇$|봇\\b|자비스|jarvis|openclaw|오픈클로|noreply|no-reply|알림봇|알림센터|고객센터|공지사항)")
    // 기업 알림톡: 이 말이 하나라도 있으면 기업 알림
    private val BUSINESS_STRONG = Regex("(\\[Web발신]|\\(광고\\)|고객님|회원님|수신거부|채널 ?추가|인증번호|이용해 주셔서|발송되었습니다|접수되었습니다)")
    // 흔한 말이라 두 가지 이상 함께 나와야 기업 알림(학부모가 '체험학습비 결제'라고 써도 사람 메시지로)
    private val BUSINESS_WEAK = listOf("주문", "배송", "결제", "승인", "포인트", "쿠폰", "예약번호", "운송장", "도착 예정", "출고")

    /** 봇·채널 알림톡·메시지 앱 자체 안내 — 사람이 쓴 말이 아닌 것 */
    fun isAutomated(n: NotiItem): Boolean {
        val sender = n.title.trim()
        if (sender.isEmpty() || sender == n.app) return true
        if (BOT_NAME.containsMatchIn(sender) || BOT_NAME.containsMatchIn(n.room)) return true
        // 1:1 로 온 기업 알림톡(배송·결제·예약)은 앱 알림으로. 단톡방은 사람 말 속 낱말일 수 있어 보지 않는다
        return n.room.isEmpty() && (BUSINESS_STRONG.containsMatchIn(n.text) || BUSINESS_WEAK.count { it in n.text } >= 2)
    }

    /** 광고·같은 알림 거르고 대화방별로 묶기(최근 방부터) */
    fun conversations(items: List<NotiItem>): List<Conversation> = items
        .filterNot { AD.containsMatchIn(it.title) || AD.containsMatchIn(it.text) }
        .distinctBy { listOf(it.app, it.room, it.title, it.text) }
        .groupBy { it.app to it.roomOrTitle }
        .map { (key, list) -> Conversation(key.first, key.second, list.sortedBy { it.time }) }
        .sortedByDescending { it.last }

    /** 모델에 넣는 대화 내용: 최근 메시지부터 ROOM_CHARS 안에서, 시간 순서로 */
    fun transcript(conv: Conversation): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val picked = ArrayDeque<String>()
        var size = 0
        for (n in conv.items.asReversed()) {
            val who = conv.who(n).let { if (it.isNotEmpty()) "$it: " else "" }
            val line = "${fmt.format(n.time)} $who${n.text.replace('\n', ' ').trim().take(300)}"
            if (size + line.length > ROOM_CHARS && picked.isNotEmpty()) break
            picked.addFirst(line); size += line.length + 1
        }
        return picked.joinToString("\n")
    }

    fun prompt(conv: Conversation): String = """
        너는 교사인 사용자의 비서야. 아래는 '${conv.room}'(${conv.app})에 온 메시지 ${conv.items.size}개야. 사용자도 이 대화에 들어 있는 사람이야.
        ---
        ${transcript(conv)}
        ---
        위 메시지만 보고 정확히 세 줄로 답해. 다른 말, 꾸밈, 별표는 쓰지 마.
        요점: 가장 중요한 내용 하나를 40자 안쪽 한 문장으로. 날짜·시간·장소·숫자는 메시지 그대로.
        요청: 사용자에게 해 달라거나 묻거나 허락을 구하는 것(…해 주세요, …해도 될까요, …줄래?)을 구체적으로(무엇을, 언제까지). 여러 개면 쉼표로. 없으면 없음.
              단톡방에서 '각 반', '모두', '선생님들'에게 하는 부탁과 '너'에게 하는 부탁도 사용자에게 하는 요청이다.
        답장: 사용자가 답하거나 해야 할 일이 있으면 필요, 없으면 아님.

        예시(내용은 다름):
        요점: 수요일 학부모 상담 시간이 2시에서 4시로 바뀜
        요청: 바뀐 상담 시간을 확인해 달라고 함
        답장: 필요
    """.trimIndent()

    /** 모델 답 → 세 줄. 형식이 깨지면 첫 줄을 요점으로, 그래도 없으면 마지막 메시지로 */
    fun parse(conv: Conversation, reply: String): RoomSummary {
        val clean = reply.replace("*", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun field(name: String) = clean.firstOrNull { it.startsWith("$name:") || it.startsWith("$name :") }
            ?.substringAfter(':')?.trim().orEmpty()
        val point = field("요점").ifEmpty { clean.firstOrNull { ':' !in it.take(4) } ?: "" }
            .ifEmpty { conv.items.last().text.replace('\n', ' ').take(80) }
        val modelAsk = field("요청").let { if (it.isEmpty() || it.startsWith("없음") || it == "-") "" else it }
        // 1:1 대화에서 묻는 말이 있는데 모델이 놓치면 그 말을 그대로 요청으로(단톡방은 서로 묻는 말이 많아 하지 않는다)
        val ask = modelAsk.ifEmpty {
            if (conv.items.all { it.room.isEmpty() }) conv.items.lastOrNull { QUESTION.containsMatchIn(it.text) }?.text?.replace('\n', ' ')?.trim()?.take(100).orEmpty() else ""
        }
        val reply3 = field("답장")
        // 요청이 있으면 답장할 연락이다 — 큰 모델(E4B)이 요청을 적고도 '답장: 아님'이라 하는 일이 있었다(2026-11-21 시뮬레이션)
        val need = ask.isNotEmpty() || reply3.startsWith("필요")
        return RoomSummary(conv, point.take(160), ask.take(160), need, byModel = true)
    }

    /** 짧은 메시지 하나뿐인 방: 모델 없이 그 말을 그대로 */
    fun direct(conv: Conversation): RoomSummary? {
        val only = conv.items.singleOrNull() ?: return null
        val text = only.text.replace('\n', ' ').trim()
        if (isChatter(text)) return RoomSummary(conv, "", "", false, byModel = false)
        if (text.length > SHORT_CHARS) return null
        val asking = QUESTION.containsMatchIn(text)
        val who = conv.who(only).let { if (it.isNotEmpty()) "$it: " else "" }
        return RoomSummary(conv, "$who$text", if (asking) text else "", asking, byModel = false)
    }

    /** 'ㅋㅋ'·'ㅎㅎ'·'ㅇㅇ'·이모지·문장부호뿐인 말 — 요약 줄로 쓰지 않고 그 밖의 방에 이름만 */
    fun isChatter(text: String): Boolean = text.isBlank() || text.all { it.isWhitespace() || it in "ㅋㅎㅠㅜㅇㄴㄷㅅ.!?~^,;:)(" || Character.getType(it).let { t -> t == Character.SURROGATE.toInt() || t == Character.OTHER_SYMBOL.toInt() } }

    /** 앱 알림(사람 아님): 앱별 건수와 최근 제목 두 개 — AI 없이 */
    fun appSection(items: List<NotiItem>): String {
        if (items.isEmpty()) return ""
        val byApp = items.groupBy { it.app }.entries.sortedByDescending { it.value.size }
        return buildString {
            append("📦 앱 알림 (${items.size}건 · 요약 안 함)\n")
            byApp.take(8).forEach { (app, list) ->
                // 메시지 앱의 봇·채널 알림은 보낸 이만으로는 알 수 없어 '보낸 이: 내용'으로
                val titles = list.sortedByDescending { it.time }.map {
                    val line = if (AppAdvisor.isMessaging(it.app, it.pkg) && it.text.isNotBlank()) "${it.title}: ${it.text}" else it.title.ifBlank { it.text }
                    line.replace('\n', ' ').take(if (AppAdvisor.isMessaging(it.app, it.pkg)) 34 else 24)
                }
                    .distinct().take(2).joinToString(", ")
                append("- $app ${list.size}건" + (if (titles.isNotBlank()) " · $titles" else "") + "\n")
            }
            if (byApp.size > 8) append("- 그 밖의 앱 ${byApp.size - 8}개\n")
            append("\n")
        }
    }

    /**
     * 전체 흐름. [ask] 는 모델 한 번 부르기, [stopReason] 은 멈춰야 하면 까닭(중지·시간 초과), 아니면 null.
     * 대화방마다 30개씩 나눠 모델에 묻고 합친다(모든 방). 멈추면 거기까지 정리한 것과 나머지 방 이름만 보여 준다.
     */
    /** 사람 메시지 칸(답장할 연락·사람에게 온 메시지)과 앱 알림 칸을 따로 돌려준다 — 브리핑에서 놓는 자리가 달라서 */
    data class Result(val people: String, val apps: String) {
        val text: String get() = listOf(people, apps.trim()).filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { "요약할 알림이 없어요." }
    }

    suspend fun compose(
        items: List<NotiItem>,
        ask: suspend (String) -> String,
        onStatus: (String) -> Unit = {},
        stopReason: () -> String? = { null },
    ): Result {
        val (people, apps) = items.partition(::isPerson)
        val convs = conversations(people)
        val rooms = mutableListOf<RoomSummary>()
        val leftover = mutableListOf<Conversation>()
        var cut: String? = null
        // 모델에 묻는 횟수(진행 표시용): 대화방마다 30개씩 나눈 덩어리 수
        val total = convs.filter { direct(it) == null }.sumOf { (it.items.size + CHUNK - 1) / CHUNK }
        var asked = 0
        for (conv in convs) {
            direct(conv)?.let { rooms += it; continue }
            val parts = mutableListOf<RoomSummary>()
            for (chunk in conv.items.chunked(CHUNK)) {
                if (cut == null) cut = stopReason()
                if (cut != null) break
                asked++
                onStatus("요약 중… $asked/$total")
                val part = Conversation(conv.app, conv.room, chunk)
                parts += parse(part, ask(prompt(part)))
            }
            if (parts.isEmpty()) { leftover += conv; continue }
            rooms += merge(conv, parts)
        }
        // 방 순서는 최근에 온 것부터
        rooms.sortByDescending { it.conv.last }
        val notes = buildList {
            if (cut != null && leftover.isNotEmpty()) add("$cut 대화방 ${leftover.size}곳은 이름만 적었어요.")
            else if (cut != null) add(cut!!)
        }
        return Result(render(rooms, leftover, notes), appSection(apps.filterNot { AD.containsMatchIn(it.title) || AD.containsMatchIn(it.text) }))
    }

    /** 한 대화방을 30개씩 나눠 요약한 것들을 하나로: 요점·요청은 이어 붙이고(같은 말은 한 번), 하나라도 답장 필요면 필요 */
    fun merge(conv: Conversation, parts: List<RoomSummary>): RoomSummary {
        if (parts.size == 1) return parts[0].copy(conv = conv)
        val point = parts.map { it.point }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
        val ask = parts.map { it.ask }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        return RoomSummary(conv, point.take(240), ask.take(200), parts.any { it.needReply }, byModel = true)
    }

    /** 사람 메시지 요약 → 화면 글 */
    fun render(rooms: List<RoomSummary>, leftover: List<Conversation>, notes: List<String>): String = buildString {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val replies = rooms.filter { it.needReply }
        if (replies.isNotEmpty()) {
            append("🙋 답장할 연락 (${replies.size})\n")
            replies.forEach { r ->
                val detail = if (r.ask.isNotEmpty() && r.byModel && !r.point.contains(r.ask)) "${r.ask} (${r.point})" else r.ask.ifEmpty { r.point }
                append("- ${r.conv.room} · ${fmt.format(r.conv.last)}: $detail\n")
            }
            append("\n")
        }
        val chatter = rooms.filter { !it.needReply && it.point.isBlank() }.map { it.conv }
        val talk = rooms.filterNot { it.needReply || it.point.isBlank() }
        val others = leftover + chatter
        if (talk.isNotEmpty() || others.isNotEmpty()) {
            append("💬 사람에게 온 메시지 (${rooms.size + leftover.size}개 방)\n")
            talk.forEach { r ->
                append("- ${r.conv.room} (${r.conv.items.size}건): ${r.point}")
                if (r.ask.isNotEmpty()) append(" → ${r.ask}")
                append("\n")
            }
            if (others.isNotEmpty()) {
                append("- 그 밖의 방: " + others.joinToString(", ") { "${it.room} ${it.items.size}건" } + "\n")
            }
            append("\n")
        }
        notes.forEach { append("($it)\n") }
    }.trimEnd()
}
