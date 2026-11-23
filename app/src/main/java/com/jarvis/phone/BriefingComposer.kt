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
    // 2026-11-22 빠르게(선생님: "2건인데 오래 걸림"): 넣는 글·받는 글을 줄이고, 짧은 1:1 은 모델 없이
    const val ROOM_CHARS = 1600       // 한 번에 넣는 글자 상한(최근 메시지부터 채움) — 2400 에서 줄임
    const val LINE_CHARS = 200        // 메시지 한 개에서 넣는 글자(300 에서 줄임)
    const val SHORT_CHARS = 60        // 이보다 짧은 메시지만 있는 1:1 대화(3개까지)는 모델 없이
    const val SHORT_MAX_MSGS = 1      // 2026-11-23 선생님: 한 사람 말은 종합해서 — 원문 그대로는 한 마디일 때만(3개까지 이어 붙이니 나열처럼 보임)
    const val MAX_ASKS = 2            // 요청 칸은 가장 중요한 것 2개까지(여러 개를 쉼표로 늘어놓으면 핵심이 흐려짐)
    // 2026-11-23 두 줄(요점·요청)만 받음 — 답장 필요는 요청이 있으면 필요(규칙). 답 쓰기가 폰에서 가장 느림
    const val MAX_ANSWER_TOKENS = 120 // 두 줄 답의 상한 — 넘게 쓰지 않게(모델은 한 글자씩 만들어 길수록 느림)
    private val URL = Regex("https?://\\S+")

    private val AD = Regex("^\\(광고\\)")
    /** 오늘·내일·기한이 든 말 — 급한 요청을 앞에(2026-11-23) */
    private val URGENT = Regex("(오늘|내일|지금|당장|아침|오전|오후|저녁|이따|까지|급히|급해|빨리|\\d+시|\\d+교시)")
    /** 맞장구·인사뿐인 말 — 모델에 넣지 않음(단톡방 '네 알겠습니다'가 요점이 되던 것) */
    private val ACK = Regex("^((네+|넵+|넹|예|옙|알겠습니다|알겠어요|알겠어|감사합니다|감사해요|고맙습니다|고마워|수고하셨(어요|습니다)|수고 ?많으셨(어요|습니다)|확인했(어요|습니다)|확인했음|좋아요|좋습니다|오케이|ok|ㅇㅋ|ㅇㅇ)[\\s.,!~^ㅎㅋㅠ]*)+$", RegexOption.IGNORE_CASE)
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
        // 'ㅋㅋ'·'네 알겠습니다' 같은 말은 빼고(모두 그런 말이면 그대로) — 넣는 글이 줄어 빠르고, 모델이 잡담을 요점으로 삼지 않음
        val meaningful = conv.items.filterNot { isFiller(it.text) }.ifEmpty { conv.items }
        for (n in meaningful.asReversed()) {
            val who = conv.who(n).let { if (it.isNotEmpty()) "$it: " else "" }
            val line = "${fmt.format(n.time)} $who${n.text.replace('\n', ' ').replace(URL, "[링크]").trim().take(LINE_CHARS)}"
            if (size + line.length > ROOM_CHARS && picked.isNotEmpty()) break
            picked.addFirst(line); size += line.length + 1
        }
        return picked.joinToString("\n")
    }

    fun isFiller(text: String): Boolean = text.replace('\n', ' ').trim().let { isChatter(it) || (it.length <= 20 && ACK.matches(it)) }

    private const val RULES = """
요점: 가장 중요한 내용을 40자 안쪽 한 문장으로. 메시지를 하나씩 옮기지 말고 종합해. 날짜·시간·장소·숫자는 메시지 그대로.
요청: 사용자에게 해 달라거나 묻거나 허락을 구하는 것(…해 주세요, …해도 될까요, …줄래?)을 구체적으로(무엇을, 언제까지). 여러 개면 오늘·내일처럼 급한 것부터 2개까지 쉼표로. 없으면 없음.
단톡방에서 '각 반', '모두', '선생님들'에게 하는 부탁과 '너'에게 하는 부탁도 사용자에게 하는 요청이다.
말투: 보낸 사람이 한 말을 부드럽게 옮겨(…하자고 함, …해 달라고 함, …라고 함). '지시', '명령', '통보', '언급' 같은 딱딱한 말은 쓰지 마.

예시(내용은 다름):
요점: 수요일 학부모 상담 시간이 2시에서 4시로 바뀜
요청: 바뀐 상담 시간을 확인해 달라고 함"""

    fun prompt(conv: Conversation): String =
        "너는 교사인 사용자의 비서야. 아래는 '${conv.room}'(${conv.app})에 온 메시지 ${conv.items.size}개야. 사용자도 이 대화에 들어 있는 사람이야.\n" +
        "---\n${transcript(conv)}\n---\n" +
        "위 메시지만 보고 정확히 두 줄로 답해. 다른 말, 꾸밈, 별표는 쓰지 마.\n" + RULES.trim()

    /**
     * 이어서 요약(2026-11-23 미리 요약·30개 넘는 방): 앞서 정리한 요점·요청 + 그 뒤에 온 메시지만 넣어 고쳐 쓴다.
     * 예전엔 30개씩 따로 요약해 이어 붙여 나열처럼 보였다.
     */
    fun updatePrompt(conv: Conversation, prev: RoomSummary, before: Int): String =
        "너는 교사인 사용자의 비서야. '${conv.room}'(${conv.app})에 온 메시지 ${before}개를 앞서 이렇게 정리했어.\n" +
        "요점: ${prev.point}\n요청: ${prev.ask.ifEmpty { "없음" }}\n" +
        "그 뒤에 새 메시지 ${conv.items.size}개가 왔어. 사용자도 이 대화에 들어 있는 사람이야.\n" +
        "---\n${transcript(conv)}\n---\n" +
        "앞의 정리와 새 메시지를 합쳐 대화 전체를 정확히 두 줄로 다시 정리해. 나중에 바뀐 내용이 앞의 것보다 우선이야. 다른 말, 꾸밈, 별표는 쓰지 마.\n" + RULES.trim()

    private val FIELD = Regex("\\s*(요점|요청|답장)\\s*:")

    /**
     * 모델 답 → 요점·요청. 형식이 깨지면 첫 줄을 요점으로, 그래도 없으면 [prev] 요점, 그것도 없으면 마지막 메시지로.
     * 한 줄에 '요점: … 요청: …' 처럼 붙여 써도 나눔(2026-11-23 채점에서 '…필요'가 요점에 붙던 것).
     * 원문에 없는 숫자를 쓰면 그 칸은 믿지 않는다(작은 모델이 4시를 3시로 바꾸는 일 막기).
     */
    fun parse(conv: Conversation, reply: String, prev: RoomSummary? = null, source: String = ""): RoomSummary {
        val clean = reply.replace("*", "").replace(FIELD) { "\n${it.groupValues[1]}:" }
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun field(name: String) = clean.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim().orEmpty()
        val src = source.ifEmpty { conv.items.joinToString(" ") { it.text } } + " " + conv.room + " " + (prev?.let { "${it.point} ${it.ask}" } ?: "")
        val lastSaid = (conv.items.lastOrNull { !isFiller(it.text) } ?: conv.items.last()).text.replace('\n', ' ').trim().take(80)
        val modelPoint = field("요점").ifEmpty { clean.firstOrNull { ':' !in it.take(4) } ?: "" }
        val point = modelPoint.takeIf { it.isNotEmpty() && grounded(it, src) } ?: prev?.point?.takeIf { modelPoint.isEmpty() } ?: lastSaid
        val modelAsk = field("요청").let { if (it.isEmpty() || it.startsWith("없음") || it == "-") "" else it }
            .let { a -> if (grounded(a, src)) a else "" }
        val oneToOne = conv.items.all { it.room.isEmpty() }
        val asks = rankAsks(splitAsks(modelAsk) + (prev?.ask?.let(::splitAsks).orEmpty()).filter { modelAsk.isEmpty() },
            // 급한 말(내일·2교시·4시 등)이 이미 요점·요청에 다 있으면 원문 물음을 또 붙이지 않음(같은 말 두 번)
            if (oneToOne) urgentQuestions(conv).filterNot { u -> URGENT.findAll(u).all { it.value in "$modelAsk $point" } } else emptyList())
        // 1:1 대화에서 묻는 말이 있는데 모델이 요청을 놓치면 마지막 물음·부탁을 그대로 요청에(단톡방은 서로 묻는 말이 많아 하지 않는다)
        val lastQ = if (oneToOne && modelAsk.isEmpty()) conv.items.lastOrNull { QUESTION.containsMatchIn(it.text) }?.text?.replace('\n', ' ')?.trim()?.take(100) else null
        val ask = (asks + listOfNotNull(lastQ?.takeIf { q -> asks.none { overlap(q, it) >= 0.5 || overlap(it, q) >= 0.5 } })).take(MAX_ASKS).joinToString(", ")
        // 요청이 있으면 답장할 연락 — 모델이 요청을 적고도 '답장: 아님'이라 하는 일이 있었다(2026-11-21). '답장: 필요'만 쓴 옛 답도 받음
        val need = ask.isNotEmpty() || field("답장").startsWith("필요") || (prev?.needReply == true && modelAsk.isEmpty())
        return RoomSummary(conv, soften(point).take(160), soften(ask).take(160), need, byModel = true)
    }

    private val NUM = Regex("\\d+")
    /** 모델이 쓴 숫자가 모두 원문에 있는가 */
    fun grounded(text: String, source: String): Boolean {
        val have = NUM.findAll(source).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()
        return NUM.findAll(text).all { it.value.trimStart('0').ifEmpty { "0" } in have }
    }

    fun splitAsks(ask: String): List<String> = ask.split(Regex("\\s*[,，]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    /** 1:1 대화에서 오늘·내일처럼 급한 물음·부탁 문장(모델이 놓쳐도 요청 칸에 오게) */
    fun urgentQuestions(conv: Conversation): List<String> = conv.items.asReversed().asSequence()
        .flatMap { it.text.replace('\n', ' ').split(Regex("(?<=[?？.!])\\s+")).asSequence() }
        .map { it.trim().removePrefix("선생님").trim(' ', ',') }
        .filter { it.length in 4..70 && QUESTION.containsMatchIn(it) && URGENT.containsMatchIn(it) }
        .toList()

    /**
     * 요청 고르기: 급한 말(오늘·내일·몇 시·까지)이 든 것부터 [MAX_ASKS]개. [urgent] 는 원문에서 찾은 급한 물음 —
     * 모델 요청에 같은 내용(두 글자 조각이 절반 넘게 겹침)이 없을 때만 넣는다.
     */
    fun rankAsks(model: List<String>, urgent: List<String> = emptyList()): List<String> {
        val extra = urgent.filter { u -> model.none { overlap(u, it) >= 0.5 } }.take(1)
        return (extra + model).distinct().withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { if (URGENT.containsMatchIn(it.value)) 1 else 0 }.thenBy { it.index })
            .map { it.value }.take(MAX_ASKS)
    }
    private fun grams(s: String) = s.filter { !it.isWhitespace() }.windowed(2).toSet()
    private fun overlap(a: String, b: String): Double { val ga = grams(a); val gb = grams(b); return if (ga.isEmpty()) 0.0 else ga.count { it in gb }.toDouble() / ga.size }

    /** (옛 이름) 요청을 앞의 [MAX_ASKS]개만 */
    fun topAsks(ask: String): String = rankAsks(splitAsks(ask)).joinToString(", ")

    // 2026-11-22 선생님: "'다음에 연락해' 문자를 '지시'라고 씀" — 모델이 말에 딱딱한 이름표를 붙이면 부드럽게
    private val STIFF = listOf(
        Regex("(라|자|달라)는 (지시|명령|통보)(를 (함|했음|내림|받음))?") to "$1고 함",
        Regex("(라|자|달라)고 (지시|명령|통보)(함|했음|하였음|을 내림)") to "$1고 함",
        Regex("(지시|명령|통보)(함|했음|하였음)") to "말함",
        Regex("(지시|명령)") to "부탁",
        Regex("통보") to "알림",
    )
    /** 요약 글에서 '지시·명령·통보'를 '…라고 함'·'부탁'·'알림'으로 */
    fun soften(s: String): String = STIFF.fold(s) { acc, (re, to) -> re.replace(acc, to) }

    /**
     * 모델 없이 그 말을 그대로: 짧은 메시지 하나뿐인 방, 또는 짧은 메시지(60자 이하) 3개까지뿐인 1:1 대화(2026-11-22).
     * 짧은 말은 요약하는 것보다 원문이 더 정확하고, 모델을 부르지 않아 빠름. 단톡방 여러 메시지는 누구에게 한 부탁인지 봐야 해서 모델로.
     */
    fun direct(conv: Conversation): RoomSummary? {
        val texts = conv.items.map { it.text.replace('\n', ' ').trim() }
        if (texts.all { isChatter(it) }) return if (texts.size == 1) RoomSummary(conv, "", "", false, byModel = false) else null
        val said = conv.items.zip(texts).filterNot { isChatter(it.second) }
        // 'ㅋㅋ' 같은 말은 세지 않고, 뜻 있는 말이 한 마디뿐일 때만(1:1·단톡 같음)
        if (said.size > SHORT_MAX_MSGS || said.any { it.second.length > SHORT_CHARS }) return null
        val who = conv.who(said.last().first).let { if (it.isNotEmpty()) "$it: " else "" }
        val point = who + said.joinToString(" / ") { it.second }
        // 물음·부탁이 든 말은 모두(1:1 에서 두 번 물으면 둘 다 — 앞의 물음이 빠지던 것)
        val ask = said.filter { QUESTION.containsMatchIn(it.second) }.joinToString(" / ") { it.second }
        return RoomSummary(conv, point, ask, ask.isNotEmpty(), byModel = false)
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
    data class Result(val people: String, val apps: String, val replies: List<RoomSummary> = emptyList()) {
        val text: String get() = listOf(people, apps.trim()).filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { "요약할 알림이 없어요." }
    }

    /**
     * 미리 요약해 둔 방 하나(2026-11-23 선생님: 배터리 30% 이상이고 쌓인 게 많거나, 충전 중이면 수시로 미리).
     * [since] 요약 구간의 시작(지난 요약 다음) — 다음 요약이 끝나 구간이 바뀌면 버린다. [upTo] 까지 온 메시지 [count] 개를 덮었다.
     */
    data class Cached(val key: String, val since: Long, val upTo: Long, val count: Int, val point: String, val ask: String, val needReply: Boolean)
    fun keyOf(conv: Conversation) = "${conv.app}\u0001${conv.room}"

    /** 이 방의 미리 요약이 아직 맞는가: 같은 구간이고, 덮은 시각까지의 메시지 수가 그대로(뺀 방·지운 알림이 있으면 버림) */
    fun validCache(conv: Conversation, cache: Map<String, Cached>, since: Long): Cached? =
        cache[keyOf(conv)]?.takeIf { c -> c.since == since && c.count > 0 && conv.items.count { it.time <= c.upTo } == c.count }
    private fun freshOf(conv: Conversation, c: Cached?) = if (c == null) conv.items else conv.items.filter { it.time > c.upTo }

    /** 지금 요약하면 모델에 넣어야 할 사람 메시지 수(미리 요약해 둔 것·모델 없이 쓰는 한 마디는 뺌) — 미리 요약을 할지 정할 때 */
    fun pendingForModel(items: List<NotiItem>, cache: Map<String, Cached>, since: Long): Int =
        conversations(items.filter(::isPerson)).sumOf { conv ->
            val c = validCache(conv, cache, since)
            if (c == null && direct(conv) != null) 0 else freshOf(conv, c).size
        }

    suspend fun compose(
        items: List<NotiItem>,
        ask: suspend (String) -> String,
        onStatus: (String) -> Unit = {},
        stopReason: () -> String? = { null },
        /** 🙋 답장할 연락으로 올려도 되는 방(명부·키워드가 나오는 방). 아니면 묻는 말이 있어도 💬 메시지에 요청만 붙여 둠 */
        replyAllowed: (Conversation) -> Boolean = { true },
        /** 미리 요약해 둔 방들(키 → 값)과 이번 구간의 시작. 덮은 메시지는 다시 묻지 않고, 그 뒤 온 것만 이어서 */
        cache: Map<String, Cached> = emptyMap(),
        since: Long = 0L,
        /** 방 하나를 (덩어리까지) 정리할 때마다 — 미리 요약이 저장해 둠 */
        onCached: (Cached) -> Unit = {},
    ): Result {
        val (people, apps) = items.partition(::isPerson)
        val convs = conversations(people)
        val rooms = mutableListOf<RoomSummary>()
        val leftover = mutableListOf<Conversation>()
        var cut: String? = null
        // 방마다 아직 안 덮은 메시지
        data class Todo(val conv: Conversation, val prev: Cached?, val fresh: List<NotiItem>)
        val todos = convs.map { conv -> val c = validCache(conv, cache, since); Todo(conv, c, freshOf(conv, c)) }
        // 모델에 묻는 횟수(진행 표시용): 새 메시지를 30개씩 나눈 덩어리 수(30개 넘으면 앞 덩어리 정리에 이어서 고쳐 씀)
        val total = todos.filter { it.fresh.isNotEmpty() && (it.prev != null || direct(it.conv) == null) }.sumOf { (it.fresh.size + CHUNK - 1) / CHUNK }
        var asked = 0
        for ((conv, c, fresh) in todos) {
            var prev: RoomSummary? = c?.let { RoomSummary(conv, it.point, it.ask, it.needReply, byModel = true) }
            if (fresh.isEmpty() && prev != null) { rooms += prev; continue }        // 미리 요약해 둔 그대로
            if (prev == null) direct(conv)?.let { rooms += it; continue }
            var covered = c?.count ?: 0
            for (chunk in fresh.chunked(CHUNK)) {
                if (cut == null) cut = stopReason()
                if (cut != null) break
                asked++
                onStatus("요약 중… $asked/$total")
                val part = Conversation(conv.app, conv.room, chunk)
                val p = prev
                prev = parse(part, ask(if (p == null) prompt(part) else updatePrompt(part, p, covered)), p)
                covered += chunk.size
                onCached(Cached(keyOf(conv), since, chunk.last().time, covered, prev.point, prev.ask, prev.needReply))
            }
            val done = prev
            if (done == null) { leftover += conv; continue }
            rooms += done.copy(conv = conv)
        }
        // 명부·키워드가 안 나오는 방은 답장할 연락에서 뺌(요청은 💬 줄에 → 로 남김)
        rooms.replaceAll { if (it.needReply && !replyAllowed(it.conv)) it.copy(needReply = false) else it }
        // 방 순서는 최근에 온 것부터
        rooms.sortByDescending { it.conv.last }
        val notes = buildList {
            if (cut != null && leftover.isNotEmpty()) add("$cut 대화방 ${leftover.size}곳은 이름만 적었어요.")
            else if (cut != null) add(cut!!)
        }
        return Result(render(rooms, leftover, notes), appSection(apps.filterNot { AD.containsMatchIn(it.title) || AD.containsMatchIn(it.text) }),
            rooms.filter { it.needReply })
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
                if (r.ask.isNotEmpty() && !r.point.contains(r.ask)) append(" → ${r.ask}")   // 짧은 한 마디는 요점이 곧 물음이라 두 번 쓰지 않음
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
