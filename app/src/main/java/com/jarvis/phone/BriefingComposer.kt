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
    /** fellBack: 모델 답에 원문에 없는 숫자가 있어 버렸음 — 한 번 더 물어볼 신호 */
    data class RoomSummary(val conv: Conversation, val point: String, val ask: String, val needReply: Boolean, val byModel: Boolean, val fellBack: Boolean = false,
                           /** 요약 줄에 못 들어간 부탁 수(단톡방) — '외 부탁 N건' */ val extra: Int = 0)

    const val CHUNK = 30              // 대화방 하나에서 모델에 한 번에 넣는 메시지 수 — 넘으면 30개씩 나눠 요약한 뒤 합침(2026-11-21 선생님)
    // 2026-11-22 빠르게(선생님: "2건인데 오래 걸림"): 넣는 글·받는 글을 줄이고, 짧은 1:1 은 모델 없이
    const val ROOM_CHARS = 1600       // 한 번에 넣는 글자 상한(최근 메시지부터 채움) — 2400 에서 줄임
    const val LINE_CHARS = 200        // 메시지 한 개에서 넣는 글자(300 에서 줄임)
    const val SHORT_CHARS = 60        // 이보다 짧은 메시지만 있는 1:1 대화(3개까지)는 모델 없이
    const val SHORT_MAX_MSGS = 1      // 2026-11-23 선생님: 한 사람 말은 종합해서 — 원문 그대로는 한 마디일 때만(3개까지 이어 붙이니 나열처럼 보임)
    const val MAX_ASKS = 2            // 요청 칸은 가장 중요한 것 2개까지(여러 개를 쉼표로 늘어놓으면 핵심이 흐려짐)
    const val MAX_GROUP_ASKS = 3      // 단톡방은 사람마다 '이름: 부탁' 3개까지(2026-11-23 — 큰 방에서 한 사람 부탁만 남고 나머지가 빠지던 것)
    // 2026-11-23 두 줄(요점·요청)만 받음 — 답장 필요는 요청이 있으면 필요(규칙). 답 쓰기가 폰에서 가장 느림
    const val MAX_ANSWER_TOKENS = 120 // 두 줄 답의 상한 — 넘게 쓰지 않게(모델은 한 글자씩 만들어 길수록 느림)
    private val URL = Regex("https?://\\S+")

    private val AD = Regex("^\\(광고\\)")
    /** 오늘·내일·기한이 든 말 — 급한 요청을 앞에(2026-11-23) */
    private val URGENT = Regex("(오늘|내일|지금|당장|아침|오전|오후|저녁|이따|까지|급히|급해|빨리|\\d+시|\\d+교시)")
    /** 맞장구·인사뿐인 말 — 모델에 넣지 않음(단톡방 '네 알겠습니다'가 요점이 되던 것) */
    private val ACK = Regex("^((네+|넵+|넹|예|옙|알겠습니다|알겠어요|알겠어|감사합니다|감사해요|고맙습니다|고마워|수고하셨(어요|습니다)|수고 ?많으셨(어요|습니다)|확인했(어요|습니다)|확인했음|좋아요|좋습니다|오케이|ok|ㅇㅋ|ㅇㅇ|((아|오|와|헐|앗|엥) ?)?(그래요|그렇군요|그렇구나|그런가요|그쵸|그죠|진짜요|정말요|대박))[\\s.,!?~^ㅎㅋㅠ]*)+$", RegexOption.IGNORE_CASE)
    private val QUESTION = Regex("[?？]|(주세요|주시면|주실|부탁|가능할까|될까요|되나요|인가요|있나요|어떠세요|알려|확인해|회신|답장|연락 ?주)")

    /** 사람 메시지(메시지 앱)인가 — 업무 앱·사람 묶음은 이 앞에서 이미 빠진다. 메시지 앱이어도 봇·채널·앱 안내는 사람이 아니다 */
    fun isPerson(n: NotiItem): Boolean = AppAdvisor.isMessaging(n.app, n.pkg) && !isAutomated(n)

    // 2026-11-21 선생님: 사람 메시지에 자비스(텔레그램 봇)·카카오톡 알림이 섞임
    private val BOT_NAME = Regex("(?i)(bot\\b|봇$|봇\\b|자비스|jarvis|openclaw|오픈클로|noreply|no-reply|알림봇|알림센터|고객센터|공지사항)")
    // 기업 알림톡: 이 말이 하나라도 있으면 기업 알림
    private val BUSINESS_STRONG = Regex("(\\[Web발신]|\\(광고\\)|고객님|회원님|수신거부|채널 ?추가|인증번호|이용해 주셔서|발송되었습니다|접수되었습니다)")
    // 흔한 말이라 두 가지 이상 함께 나와야 기업 알림(학부모가 '체험학습비 결제'라고 써도 사람 메시지로)
    private val BUSINESS_WEAK = listOf("주문", "배송", "결제", "승인", "포인트", "쿠폰", "예약번호", "운송장", "도착 예정", "출고")

    /** 봇·서버 알림(텔레그램 봇 등) — 요약에서 빼고 건수만(정기·자동 알림 줄) */
    fun isBot(n: NotiItem): Boolean = BOT_NAME.containsMatchIn(n.title.trim()) || BOT_NAME.containsMatchIn(n.room)

    /** 봇·채널 알림톡·메시지 앱 자체 안내 — 사람이 쓴 말이 아닌 것 */
    fun isAutomated(n: NotiItem): Boolean {
        val sender = n.title.trim()
        if (sender.isEmpty() || sender == n.app) return true
        if (isBot(n)) return true
        // 2026-11-23 광고·업체 문자가 🙋 답장할 연락에 들어감: 1:1 로 온 광고, 대표번호(15xx·080·02…)에서 온 문자는 사람이 아님
        if (n.room.isEmpty() && (AdFilter.isAd(n.title, n.text) || (Sms.isSms(n.pkg) && AdFilter.isBusinessNumber(sender)))) return true
        // 이름으로 오는 업체(토스·OO은행·뱅크샐러드·Google …) 1:1 메시지도 사람이 아님 — 앱 알림 건수로
        if (n.room.isEmpty() && AdFilter.isBusinessName(sender)) return true
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

    // 시뮬레이션으로 고르는 값(2026-11-23 — 긴 글이 섞인 방에서 앞 메시지·긴 공지 끝이 빠지던 것)
    @Volatile var roomChars = ROOM_CHARS      // AI 에 한 번에 넣는 글자
    @Volatile var lineChars = LINE_CHARS      // 메시지 한 개에서 넣는 글자
    @Volatile var chunkByChars = false        // true: 방을 글자 수로 나눔(빠지는 메시지 없음) · false: 30개씩(예전)
    @Volatile var keepTail = false            // true: lineChars 넘는 긴 글은 앞 1/4 … 끝 3/4 (부탁은 대개 끝에)
    const val MAX_PROMPT_TOKENS = 3000        // 폰 AI 작업 공간 4096 — 질문이 이보다 클 것 같으면 덩어리를 반으로
    private const val PROMPT_OVERHEAD_TOKENS = 700   // 질문의 규칙·예시 부분

    /** 질문 토큰 어림(한글 글자당 0.75, 그 밖 0.5 — 넉넉히. 맥미니 Gemma 로 잰 한글 위주 글은 글자당 약 0.57) */
    fun estTokens(text: String): Int = text.sumOf { c -> if (c in '가'..'힣') 0.75 else 0.5 }.toInt()

    /** AI 에 넣는 한 줄: '시각 보낸 사람: 내용'(링크는 [링크], 길면 lineChars 에서 자르거나 끝을 살림) */
    private fun lineOf(conv: Conversation, n: NotiItem): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        // 모든 줄에 말한 사람(1:1 도 보낸 사람) — AI 가 누가 한 말인지 섞지 않게(2026-11-23)
        val who = conv.who(n).ifEmpty { n.title.trim() }.let { if (it.isNotEmpty()) "$it: " else "" }
        val body = n.text.replace('\n', ' ').replace(URL, "[링크]").trim()
        val cut = when {
            body.length <= lineChars -> body
            keepTail -> body.take(lineChars / 4) + " … " + body.takeLast(lineChars - lineChars / 4)
            else -> body.take(lineChars)
        }
        return "${fmt.format(n.time)} $who$cut"
    }

    // ── 🧩 사실 먼저 뽑기(2026-11-23 — 여러 사람 대화에서 AI 는 '누가 말했나'를 자주 틀림: 이름은 앱이, AI 는 고르고 줄이기만) ──
    @Volatile var factFirst = false
    const val MAX_CANDS = 20
    data class Cand(val who: String, val text: String)

    /** 부탁 후보: 잡담이 아닌 메시지에서 부탁·물음 문장(메시지마다 마지막 2개까지), 같은 말은 한 번, 최근 것 MAX_CANDS 개 */
    fun candidates(conv: Conversation): List<Cand> = conv.items.filterNot { isFiller(it.text) }.flatMap { n ->
        val who = conv.who(n).ifEmpty { n.title.trim() }
        n.text.replace('\n', ' ').replace(URL, "[링크]").split(Regex("(?<=[?？.!])\\s+")).map { it.trim() }
            .filter { it.length >= 10 && (REQUEST.containsMatchIn(it) || (it.endsWith("?") && it.length >= 14)) }
            .takeLast(2).map { Cand(who, if (it.length > 140) "… " + it.takeLast(140) else it) }   // 긴 문장은 끝(부탁)을 살림
    }.distinctBy { it.text.replace(Regex("[\\d()\\[\\]\\s]"), "").take(30) }.takeLast(MAX_CANDS)

    fun factPrompt(conv: Conversation, cands: List<Cand>, prev: RoomSummary?): String {
        val saved = roomChars
        roomChars = minOf(roomChars, 1200)   // 요점용 대화 일부는 짧게 — 부탁은 후보 목록에 다 있음
        val tr = try { transcript(conv) } finally { roomChars = saved }
        return "너는 교사인 사용자의 비서야. 단톡방 '${conv.room}'(${conv.app})에 메시지 ${conv.items.size}개가 왔어. 사용자도 이 방에 있어.\n" +
            (prev?.let { "앞서 이렇게 정리했어 — 요점: ${it.point} / 부탁: ${it.ask.ifEmpty { "없음" }}\n" } ?: "") +
            "[대화 일부]\n---\n$tr\n---\n" +
            "[부탁 후보] 앱이 메시지에서 뽑은 것. 번호. 보낸 사람: 문장\n" +
            cands.mapIndexed { i, c -> "${i + 1}. ${c.who}: ${c.text}" }.joinToString("\n") + "\n" +
            "정확히 아래 두 줄로 답해. 다른 말, 꾸밈, 별표는 쓰지 마.\n" +
            "요점: 대화 전체의 핵심을 40자 안쪽 한 문장으로. 날짜·시간·숫자는 메시지 그대로.\n" +
            "부탁: 후보 중 사용자(선생님)가 실제로 해야 할 일만 오늘·내일처럼 급한 것부터 3개까지 '번호=줄인 말' 꼴로 쉼표로. 줄인 말은 '…해 달라고 함'처럼 25자 안쪽, 날짜·시간은 그대로. 없으면 없음.\n" +
            "예시: 부탁: 2=명단 금요일까지 올려 달라고 함, 5=버스 인원 수요일까지 알려 달라고 함"
    }

    private val DEADLINE = Regex("(까지|오늘|내일|모레|[월화수목금토일]요일|\\d+일)")
    private val PICK = Regex("""(\d{1,2})\s*[=:.)]\s*([^,，\n]+)""")
    /** 답 → 요점과 '보낸 사람: 줄인 말'(이름은 후보에서). 번호를 못 읽으면 후보를 급한 순서로 3개(AI 없이 줄임) */
    fun parseFacts(conv: Conversation, reply: String, cands: List<Cand>, prev: RoomSummary?): RoomSummary {
        val base = parse(conv, reply.replace(Regex("(?m)^\\s*부탁\\s*:"), "요청:"), prev)   // 요점·숫자 검사·말투는 그대로
        val line = reply.lines().firstOrNull { it.trim().startsWith("부탁") }?.substringAfter(':').orEmpty()
        val src = conv.items.joinToString(" ") { it.text }
        val picked = PICK.findAll(line).mapNotNull { m ->
            val c = cands.getOrNull(m.groupValues[1].toInt() - 1) ?: return@mapNotNull null
            val short = condense(m.groupValues[2].trim().trimEnd('.').ifEmpty { c.text }).replace(Regex("\\s*\\(\\d+차 안내\\)"), "")   // 원문을 그대로 옮겼으면 짧은 말로
            "${c.who}: ${if (grounded(short, src)) short else condense(c.text)}"
        }.distinct().toList()
        // AI 가 3개보다 적게 골랐으면 남은 자리는 앱이 채움: 기한(까지·오늘·내일·요일)이 있고 긴 메시지(공지)에서 나온 부탁부터
        // 채우기는 기한이 있는 부탁만(잡담 속 '알려 주세요'는 안 채움), AI 가 고른 것과 겹치면(양쪽 방향 비교) 빼기, 같은 말은 한 번
        fun same(a: String, b: String) = maxOf(overlap(a.replace(ENDING, ""), b), overlap(b.replace(ENDING, ""), a)) >= 0.4
        val fill = cands
            .filter { REQUEST.containsMatchIn(it.text) && DEADLINE.containsMatchIn(it.text) && it.text.length >= 20 }
            .map { "${it.who}: ${condense(it.text.removePrefix("… "))}" }
            .filter { f -> picked.none { p -> p.substringBefore(':') == f.substringBefore(':') && same(f.substringAfter(':'), p.substringAfter(':')) } }
            .fold(listOf<String>()) { acc, f -> if (acc.any { same(it.substringAfter(':'), f.substringAfter(':')) }) acc else acc + f }
        val asks = (picked + fill).take(MAX_GROUP_ASKS)
            .let { rankAsks(it + (prev?.ask?.let(::splitAsks).orEmpty()), limit = MAX_GROUP_ASKS) }
        val ask = soften(asks.joinToString(", "))
        return base.copy(ask = ask, needReply = ask.isNotEmpty())
    }

    // 부탁처럼 보이는 문장(넘치는 부탁 세기용 — 물음표 잡담은 빼고 '해 주세요·부탁·제출' 같은 말만)
    private val REQUEST = Regex("(주세요|주시면|주실|부탁|알려 ?주|확인해|내 ?주|올려 ?주|보내 ?주|제출|참석해|도와주)")

    /**
     * 요약 줄에 못 들어간 부탁 수(2026-11-23 — 단톡방 부탁은 3개만 보이므로 나머지는 '외 부탁 N건'). AI 없이 원문에서 셈:
     * 부탁 문장 중 보이는 요점·부탁과 겹치지 않는 것(보낸 사람·문장별 한 번).
     */
    fun extraAsks(conv: Conversation, shown: String): Int =
        // 메시지마다 마지막 부탁 문장 하나만(공지 가운데 '궁금하면 말씀해 주세요' 같은 인사말은 안 셈), 숫자·괄호만 다른 같은 말은 한 번
        conv.items.filterNot { isFiller(it.text) }.mapNotNull { n ->
            n.text.replace('\n', ' ').split(Regex("(?<=[?？.!])\\s+")).map { it.trim() }
                .lastOrNull { it.length >= 12 && REQUEST.containsMatchIn(it) && !it.endsWith("?") }
        }.distinctBy { it.replace(Regex("[\\d()\\[\\]\\s]"), "").take(24) }
            .count { sent -> overlap(sent.replace(ENDING, ""), shown) < 0.4 }

    /** 방 하나를 AI 에 넣을 덩어리로: 글자 수 기준이면 한 덩어리가 roomChars 안에 다 들어가게(잡담은 0자로 셈) */
    fun chunksOf(conv: Conversation, items: List<NotiItem>): List<List<NotiItem>> {
        if (!chunkByChars) return items.chunked(CHUNK)
        val out = mutableListOf<MutableList<NotiItem>>(); var size = 0
        for (n in items) {
            val len = if (isFiller(n.text)) 0 else lineOf(conv, n).length + 1
            if (out.isEmpty() || (size + len > roomChars && out.last().any { !isFiller(it.text) })) { out += mutableListOf<NotiItem>(); size = 0 }
            out.last() += n; size += len
        }
        // 🛡️ 토큰 안전장치: 영어·숫자·주소가 많아 질문이 폰 한도를 넘을 것 같으면 그 덩어리를 반으로(한 줄이 될 때까지)
        fun fit(ch: List<NotiItem>): List<List<NotiItem>> {
            val est = PROMPT_OVERHEAD_TOKENS + estTokens(ch.filterNot { isFiller(it.text) }.joinToString("\n") { lineOf(conv, it) })
            return if (est <= MAX_PROMPT_TOKENS || ch.size <= 1) listOf(ch) else ch.chunked((ch.size + 1) / 2).flatMap { fit(it) }
        }
        return out.flatMap { fit(it) }
    }

    /** 모델에 넣는 대화 내용: 최근 메시지부터 roomChars 안에서, 시간 순서로 */
    fun transcript(conv: Conversation): String {
        val picked = ArrayDeque<String>()
        var size = 0
        // 'ㅋㅋ'·'네 알겠습니다' 같은 말은 빼고(모두 그런 말이면 그대로) — 넣는 글이 줄어 빠르고, 모델이 잡담을 요점으로 삼지 않음
        val meaningful = conv.items.filterNot { isFiller(it.text) }.ifEmpty { conv.items }
        for (n in meaningful.asReversed()) {
            val line = lineOf(conv, n)
            if (size + line.length > roomChars && picked.isNotEmpty()) break
            picked.addFirst(line); size += line.length + 1
        }
        return picked.joinToString("\n")
    }

    fun isFiller(text: String): Boolean = text.replace('\n', ' ').trim().let { t ->
        // 부르는 말을 떼고 봄 — '감사합니다 선배님 !!' 이 요점이 되던 것(2026-11-24)
        val bare = t.replace(ADDRESS, " ").trim()
        isChatter(t) || (t.length <= 24 && bare.isNotEmpty() && ACK.matches(bare))
    }
    /** 부르는 말(선배님·선생님·쌤·부장님…) */
    private val ADDRESS = Regex("(교감|교장|부장|실장)?\\s?(선배님|선생님|선배|쌤|샘|님)(?=[\\s.,!~^ㅎㅋㅠ]|$)")

    private const val RULES = """
요점: 가장 중요한 내용을 40자 안쪽 한 문장으로. 메시지를 하나씩 옮기지 말고 종합해. 날짜·시간·장소·숫자는 메시지 그대로.
요청: 사용자에게 해 달라거나 묻거나 허락을 구하는 것(…해 주세요, …해도 될까요, …줄래?)을 구체적으로(무엇을, 언제까지). 단톡방이면 '이름: 부탁' 꼴로 사람마다 따로 써. 여러 개면 오늘·내일처럼 급한 것부터 3개까지 쉼표로. 없으면 없음.
사람: 줄 앞의 이름이 그 말을 한 사람이야. 한 사람의 말을 다른 사람이 한 것처럼 쓰지 마.
단톡방에서 '각 반', '모두', '선생님들'에게 하는 부탁과 '너'에게 하는 부탁도 사용자에게 하는 요청이다.
이름: 보낸 사람의 이름과 호칭(예: 김민수 선생님)은 줄이거나 바꾸지 말고 그대로 써. '선생님'처럼 호칭만 남기지 마.
말투: 보낸 사람이 한 말을 부드럽게 옮겨(…하자고 함, …해 달라고 함, …라고 함). '지시', '명령', '통보', '언급' 같은 딱딱한 말은 쓰지 마.

예시(내용은 다름):
요점: 수요일 학부모 상담 시간이 2시에서 4시로 바뀜
요청: 바뀐 상담 시간을 확인해 달라고 함"""

    /**
     * 질문 앞부분을 모든 방이 같게(rulesFirst — 2026-11-23): 고정 규칙을 맨 앞에 두면 AI 가 그 부분을 한 번만 읽고 다음 방부터는
     * 다시 읽지 않음(폰: LiteRT-LM Conversation.clone(), 맥미니 Ollama 는 저절로). 방마다 다른 내용은 그 뒤에.
     */
    @Volatile var rulesFirst = false
    private const val HEAD = "너는 교사인 사용자의 비서야. 메시지 대화를 읽고 정확히 두 줄로 답해. 다른 말, 꾸밈, 별표는 쓰지 마. 사용자도 그 대화에 들어 있는 사람이야."

    fun prompt(conv: Conversation): String = if (rulesFirst)
        HEAD + "\n" + RULES.trim() + "\n\n" + "이제 '${conv.room}'(${conv.app})에 온 메시지 ${conv.items.size}개야.\n---\n${transcript(conv)}\n---\n위 메시지만 보고 두 줄로 답해."
    else
        "너는 교사인 사용자의 비서야. 아래는 '${conv.room}'(${conv.app})에 온 메시지 ${conv.items.size}개야. 사용자도 이 대화에 들어 있는 사람이야.\n" +
        "---\n${transcript(conv)}\n---\n" +
        "위 메시지만 보고 정확히 두 줄로 답해. 다른 말, 꾸밈, 별표는 쓰지 마.\n" + RULES.trim()

    /**
     * 이어서 요약(2026-11-23 미리 요약·30개 넘는 방): 앞서 정리한 요점·요청 + 그 뒤에 온 메시지만 넣어 고쳐 쓴다.
     * 예전엔 30개씩 따로 요약해 이어 붙여 나열처럼 보였다.
     */
    fun updatePrompt(conv: Conversation, prev: RoomSummary, before: Int): String = if (rulesFirst)
        HEAD + "\n" + RULES.trim() + "\n\n" + "이제 '${conv.room}'(${conv.app})에 온 메시지 ${before}개를 앞서 이렇게 정리했어.\n요점: ${prev.point}\n요청: ${prev.ask.ifEmpty { "없음" }}\n" +
            "그 뒤에 새 메시지 ${conv.items.size}개가 왔어.\n---\n${transcript(conv)}\n---\n앞의 정리와 새 메시지를 합쳐 대화 전체를 두 줄로 다시 정리해. 나중에 바뀐 내용이 우선이야."
    else
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
        val lastSaid = clip(condenseAll((conv.items.lastOrNull { !isFiller(it.text) } ?: conv.items.last()).text.replace('\n', ' ').trim()), 80)
        val modelPoint = field("요점").ifEmpty { clean.firstOrNull { ':' !in it.take(4) } ?: "" }
        val point = modelPoint.takeIf { it.isNotEmpty() && grounded(it, src) } ?: prev?.point?.takeIf { modelPoint.isEmpty() } ?: lastSaid
        // AI 가 '요청:' 이름표 없이 '총무: …' 처럼 그 방 사람 이름으로 바로 쓴 줄도 요청으로(2026-11-23 채점에서 빠지던 것)
        val senders = conv.items.map { conv.who(it).ifEmpty { it.title.trim() } }.filter { it.length >= 2 }.toSet()
        val bareAsks = if (field("요청").isEmpty()) clean.filter { l -> !l.startsWith("요점") && senders.any { l.startsWith("$it:") || l.startsWith("${it}님:") } } else emptyList()
        val modelAsk = (field("요청").ifEmpty { bareAsks.joinToString(", ") }).let { if (it.isEmpty() || it.startsWith("없음") || it == "-") "" else it }
            .let { a -> if (grounded(a, src)) a else "" }
        val oneToOne = conv.items.all { it.room.isEmpty() }
        val asks = rankAsks(splitAsks(modelAsk) + (prev?.ask?.let(::splitAsks).orEmpty()).filter { modelAsk.isEmpty() }, limit = if (oneToOne) MAX_ASKS else MAX_GROUP_ASKS, urgent =
            // 급한 말(내일·2교시·4시 등)이 이미 요점·요청에 다 있으면 원문 물음을 또 붙이지 않음(같은 말 두 번)
            if (oneToOne) urgentQuestions(conv).filterNot { u -> URGENT.findAll(u).all { it.value in "$modelAsk $point" } } else emptyList())
        // 1:1 대화에서 묻는 말이 있는데 모델이 요청을 놓치면 마지막 물음·부탁을 그대로 요청에(단톡방은 서로 묻는 말이 많아 하지 않는다)
        // 글자 없는 물음('??'·'ㅋㅋ?')은 요청이 아님 — '→ ??' 가 붙던 것(2026-11-24)
        val lastQ = if (oneToOne && modelAsk.isEmpty()) conv.items.lastOrNull { QUESTION.containsMatchIn(it.text) && hasWords(it.text) }?.text?.takeUnless(::isRhetorical)?.let(::askSentence)?.take(100)?.takeIf(::hasWords) else null
        val limit = if (oneToOne) MAX_ASKS else MAX_GROUP_ASKS
        val ask = fixAskNames(asks + listOfNotNull(lastQ?.takeIf { q -> asks.none { overlap(q, it) >= 0.5 || overlap(it, q) >= 0.5 } }), conv)
            .take(limit).joinToString(", ")
        // 요청이 있으면 답장할 연락 — 모델이 요청을 적고도 '답장: 아님'이라 하는 일이 있었다(2026-11-21). '답장: 필요'만 쓴 옛 답도 받음
        val need = ask.isNotEmpty() || field("답장").startsWith("필요") || (prev?.needReply == true && modelAsk.isEmpty())
        return RoomSummary(conv, soften(point).take(160), soften(ask).take(160), need, byModel = true,
            fellBack = modelPoint.isNotEmpty() && !grounded(modelPoint, src))
    }

    // ── AI 없이 물음·부탁을 짧은 말로(2026-11-23 선생님: 1건이면 원문 그대로 나옴) ──
    private val GREETING = Regex("^((선생님|쌤)(?=[,!.~\\s]|$)[,!.~\\s]*)?((안녕하세요|안녕하십니까)[.!~,\\s]*)?")   // '선생님께서도'의 '선생님'은 안 뗌
    private val TAIL = Regex("[\\s?？!.~^ㅠㅜㅎㅋ]+$")
    private val CONDENSE = listOf(
        Regex("(\\S+?)\\s?주실 수 (있나요|있을까요|있으세요|있으신가요|있어요)$") to "$1 달라고 함",
        Regex("(\\S+?)\\s?주시면 (감사하겠습니다|감사하겠어요|감사해요|좋겠어요|좋겠습니다)$") to "$1 달라고 함",
        Regex("(\\S+?)\\s?(주세요|주시겠어요|주실래요|줄래|줄 수 있어|주라)$") to "$1 달라고 함",
        Regex("부탁(드려요|드립니다|드릴게요|해요|합니다)$") to "부탁함",
        Regex("도 (될까요|되나요|돼요|괜찮을까요|괜찮나요|괜찮아요)$") to "도 되는지 물음",
        Regex("(인가요|일까요|인지요)$") to "인지 물음",
        Regex("나요$") to "는지 물음",
        Regex("까요$") to "지 물음",
    )
    private val ASK_IS = Regex("(이에요|예요|에요)$")

    /** 한 문장: '…싸야 하나요?' → '…싸야 하는지 물음', '…보내 주세요' → '…보내 달라고 함'. 못 바꾸면 그대로 */
    fun condense(sentence: String): String {
        val q = sentence.trim().let { it.endsWith("?") || it.endsWith("？") }
        val body = sentence.trim().replace(GREETING, "").replace(TAIL, "").trim()
        if (body.isEmpty()) return sentence.trim()
        for ((re, to) in CONDENSE) if (re.containsMatchIn(body)) return re.replace(body, to)
        if (q && ASK_IS.containsMatchIn(body)) return ASK_IS.replace(body, "인지 물음")
        return sentence.trim().replace(GREETING, "").trim().ifEmpty { sentence.trim() }
    }
    private val SENT = Regex("(?<=[?？.!])\\s+")
    /** 여러 문장이면 문장마다 */
    fun condenseAll(text: String): String = text.split(SENT).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") { condense(it) }
    /** 요청 칸에 넣을 말: 물음·부탁이 든 마지막 문장을 짧게 */
    fun askSentence(text: String): String = text.replace('\n', ' ').split(SENT).map { it.trim() }
        .lastOrNull { QUESTION.containsMatchIn(it) }?.let(::condense) ?: condenseAll(text.replace('\n', ' ').trim())

    private val NUM = Regex("\\d+")
    /** 모델이 쓴 숫자가 모두 원문에 있는가 */
    fun grounded(text: String, source: String): Boolean {
        val have = NUM.findAll(source).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()
        return NUM.findAll(text).all { it.value.trimStart('0').ifEmpty { "0" } in have }
    }

    fun splitAsks(ask: String): List<String> = ask.split(Regex("\\s*[,，]\\s*")).map { it.trim() }.filter { it.isNotEmpty() && hasWords(it) && !isRhetorical(it) }

    /** 혼잣말·감탄 물음('급락..?'·'카톡친추네여?')은 나에게 묻는 말이 아님(2026-11-24) */
    private val RHETORICAL = Regex("(\\.\\.|…)\\s*\\?|(네여|네요|군요|구나|겠지)\\s*\\?+\\s*$|(거|것|게) ?(아녜요|아니에요|아닌가요|아냐|아님)\\s*\\?+\\s*$")
    fun isRhetorical(s: String) = RHETORICAL.containsMatchIn(s.trim())

    /** 1:1 대화에서 오늘·내일처럼 급한 물음·부탁 문장(모델이 놓쳐도 요청 칸에 오게) */
    fun urgentQuestions(conv: Conversation): List<String> = conv.items.asReversed().asSequence()
        .flatMap { it.text.replace('\n', ' ').split(Regex("(?<=[?？.!])\\s+")).asSequence() }
        .map { it.trim().removePrefix("선생님").trim(' ', ',') }
        .filter { it.length in 4..70 && QUESTION.containsMatchIn(it) && URGENT.containsMatchIn(it) }
        .map(::condense)
        .toList()

    /**
     * 요청 고르기: 급한 말(오늘·내일·몇 시·까지)이 든 것부터 [MAX_ASKS]개. [urgent] 는 원문에서 찾은 급한 물음 —
     * 모델 요청에 같은 내용(두 글자 조각이 절반 넘게 겹침)이 없을 때만 넣는다.
     */
    fun rankAsks(model: List<String>, urgent: List<String> = emptyList(), limit: Int = MAX_ASKS): List<String> {
        val extra = urgent.filter { u -> model.none { overlap(u, it) >= 0.5 } }.take(1)
        return (extra + model).distinct().withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { if (URGENT.containsMatchIn(it.value)) 1 else 0 }.thenBy { it.index })
            .map { it.value }.take(limit)
    }
    private fun grams(s: String) = s.filter { !it.isWhitespace() }.windowed(2).toSet()
    fun overlapPublic(a: String, b: String) = overlap(a, b)
    private fun overlap(a: String, b: String): Double { val ga = grams(a); val gb = grams(b); return if (ga.isEmpty()) 0.0 else ga.count { it in gb }.toDouble() / ga.size }

    /**
     * '이름: 부탁' 의 이름이 맞는지 앱이 확인(2026-11-23 선생님: 다른 사람으로 착각) — 그 방에 그 이름이 없으면 이름을 떼고,
     * 부탁 내용이 다른 사람의 말과 훨씬 더 닮았으면(두 글자 조각) 그 사람 이름으로 바꾼다. AI 를 다시 부르지 않음.
     */
    fun fixAskNames(asks: List<String>, conv: Conversation): List<String> {
        val said = conv.items.groupBy { conv.who(it).ifEmpty { it.title.trim() } }.filterKeys { it.isNotEmpty() }
            .mapValues { (_, l) -> l.joinToString(" ") { it.text } }
        // 1:1 은 보낸 사람이 하나뿐 — 'OO 어머니: …' 처럼 붙은 이름은 떼기(줄 머리에 이미 있음)
        // 한 사람뿐인 대화(1:1, 또는 👥 묶음에서 '김민수 선생님'·'김민수'처럼 앱마다 이름만 다른 한 사람)
        val oneSender = said.keys.all { a -> said.keys.all { b -> a.contains(b) || b.contains(a) } }
        if (said.size < 2 || oneSender) return asks.map { a -> a.substringBefore(':', "").trim().let { n -> if (n.isNotEmpty() && (n in said.keys || conv.room.contains(n) || n.contains(conv.room))) a.substringAfter(':').trim() else a } }
        return asks.map { a ->
            val name = a.substringBefore(':', "").trim()
            if (name.isEmpty()) {
                // 이름 없이 온 부탁: 원문에서 그 말을 한 사람이 뚜렷하면 이름을 붙임 — 누가 부탁했는지(2026-11-23).
                // 두 글자 조각이 35% 넘게 겹치고, 두 번째로 닮은 사람보다 0.1 넘게 더 닮았을 때만(공지들이 같은 배경 문단을 나눠 써도 헷갈리지 않게)
                val core0 = a.replace(ENDING, "")
                val ranked = said.map { (k, t) -> k to overlap(core0, t) }.sortedByDescending { it.second }
                val top = ranked.firstOrNull(); val second = ranked.getOrNull(1)?.second ?: 0.0
                return@map if (top != null && top.second >= 0.35 && top.second - second > 0.1) "${top.first}: $a" else a
            }
            if (name.length > 20) return@map a
            val body = a.substringAfter(':').trim()
            val core = body.replace(ENDING, "")
            val owner = said.keys.firstOrNull { it == name || name.contains(it) || it.contains(name) }
            val best = said.maxByOrNull { (_, t) -> overlap(core, t) }
            when {
                owner == null -> body                                                     // 그 방에 없는 이름 — 떼기
                best != null && best.key != owner && overlap(core, best.value) >= 0.5 && overlap(core, best.value) > overlap(core, said[owner]!!) * 1.5 -> "${best.key}: $body"
                else -> a
            }
        }
    }

    /** (옛 이름) 요청을 앞의 [MAX_ASKS]개만 */
    fun topAsks(ask: String): String = rankAsks(splitAsks(ask)).joinToString(", ")

    // 2026-11-22 선생님: "'다음에 연락해' 문자를 '지시'라고 씀" — 모델이 말에 딱딱한 이름표를 붙이면 부드럽게
    private val STIFF = listOf(
        // 2026-11-24: '…아쉬워하며 이더리움의 과거 상승률을 언급함' — 규칙에 '언급'을 쓰지 말라 해도 씀
        Regex("에 대해 언급(함|했음|하였음)") to "에 대해 이야기함",
        Regex("언급(함|했음|하였음)") to "말함",
        Regex("언급하며") to "말하며",
        Regex("(라|자|달라)는 (지시|명령|통보)(를 (함|했음|내림|받음))?") to "$1고 함",
        Regex("(라|자|달라)고 (지시|명령|통보)(함|했음|하였음|을 내림)") to "$1고 함",
        Regex("(지시|명령|통보)(함|했음|하였음)") to "말함",
        Regex("(지시|명령)") to "부탁",
        Regex("통보") to "알림",
        // 2026-11-24: '사진을 보내며 … 말씀하심' — 다른 줄은 '…함'인데 높임말로 줄이는 일이 있음
        Regex("말씀하(심|셨음|셨습니다|셨다)") to "말함",
        Regex("말씀하시(며|고)") to "말하$1",
        Regex("하(셨음|심)(?![가-힣])") to "함",
        Regex("하셨(다고|으며|고)") to "했$1",
        Regex("보내(셨음|심)(?![가-힣])") to "보냄",
        Regex("주(셨음|심)(?![가-힣])") to "줌",
        Regex("물으(셨음|심)(?![가-힣])") to "물음",
        Regex("말씀") to "말",
        // 긴 주소는 줄을 다 차지함(2026-11-24: '→ https://n.news.naver.com/article/050/…')
        Regex("https?://\\S+") to "(링크)",
        Regex("\\[댓글\\]\\s*") to "",
    )
    /** 요약 글에서 '지시·명령·통보'를 '…라고 함'·'부탁'·'알림'으로 */
    fun soften(s: String): String = STIFF.fold(s) { acc, (re, to) -> re.replace(acc, to) }

    /** 이름 뒤 조사(햅삐가·김민수 선생님께서·캔디는) */
    private val NAME_TAIL = listOf("님께서 ", "께서 ", "님이 ", "님은 ", "이가 ", "이는 ", "이 ", "가 ", "은 ", "는 ", ": ")
    /**
     * 줄 앞에 이름이 이미 굵게 있으니 내용 첫머리의 그 이름을 뗌 — '햅삐 · 햅삐가 2주 스윙 후…'(2026-11-24).
     * '서울_개복치(캔디)' 처럼 괄호가 붙은 이름은 괄호 앞 이름도 봄. 떼고 나서 남는 게 없으면 그대로
     */
    fun dropName(point: String, name: String, group: String = ""): String {
        val base = listOf(name.trim(), name.substringBefore('(').trim()).filter { it.length >= 2 }.distinct()
        // '김진수 지인이 …' — 묶음 이름(👔 지인)을 프롬프트에서 보고 붙임(2026-11-24)
        val word = group.trim().split(' ').last().takeIf { it.length >= 2 && it.any { c -> c in '가'..'힣' } }
        val names = (if (word != null) base.map { "$it $word" } else emptyList()) + base
        for (n in names) {
            if (!point.startsWith(n)) continue
            val rest = point.substring(n.length)
            val tail = NAME_TAIL.firstOrNull { rest.startsWith(it) } ?: continue
            return rest.substring(tail.length).trim().ifEmpty { point }
        }
        return point
    }

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
        // 한 마디는 AI 없이 — 물음·부탁은 짧은 말로(…하는지 물음·…달라고 함), 인사는 뗌(2026-11-23)
        val point = who + said.joinToString(" / ") { condenseAll(it.second) }
        val ask = said.filter { QUESTION.containsMatchIn(it.second) && hasWords(it.second) && !isRhetorical(it.second) }.joinToString(" / ") { askSentence(it.second) }
        return RoomSummary(conv, point, ask, ask.isNotEmpty(), byModel = false)
    }

    /**
     * [max] 자로 줄이기 — 낱말 가운데서 자르지 않고 '…'(2026-11-24: '…(d39140c) [.' 처럼 '[링크]' 가운데서 잘려 괄호만 남던 것).
     * 띄어 쓴 자리에서 자르고, 끝에 남은 여는 괄호·기호는 뗌
     */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.take(max).let { t -> t.lastIndexOf(' ').takeIf { it >= max / 2 }?.let { t.take(it) } ?: t }
        return cut.trimEnd(' ', '[', '(', '<', '{', '-', ',', '·', ':') + "…"
    }

    /** 이보다 적은 마디의 잡담 방은 AI 없이 그 말을 짧게, 많으면 AI 핵심 요약 */
    const val PEEK_MAX = 3
    private fun said(conv: Conversation) = conv.items.filter { hasWords(it.text) && !isFiller(it.text) }

    /** AI 없이 간단한 내용: 뜻 있는 말을 '보낸 사람: 말' 로 이어 90자 안쪽. 답장 표시(🙋)는 붙이지 않음 */
    fun peek(conv: Conversation): RoomSummary {
        val point = clip(said(conv).joinToString(" / ") { m ->
            conv.who(m).let { if (it.isNotEmpty()) "$it: " else "" } + condenseAll(m.text.replace('\n', ' ').trim())
        }, 90)
        return RoomSummary(conv, point, "", false, byModel = false)
    }

    /** 뜻 있는 글자(완성 한글·영문·숫자)가 하나라도 있는가 */
    fun hasWords(s: String) = s.any { it in '가'..'힣' || it.isLetterOrDigit() && it.code < 0x3130 }

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
    /** cutRooms: 시간·중지로 다 못 한 방 수(이름만 적음) — 요약이 이어서 마무리할지 정할 때 */
    data class Result(val people: String, val apps: String, val replies: List<RoomSummary> = emptyList(), val groups: String = "", val cutRooms: Int = 0,
                      /** 💼 지정한 업무·일정 앱 요약(앱마다 한 문단) */
                      val work: String = "") {
        val text: String get() = listOf(groups.trim(), work.trim(), people, apps.trim()).filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { "요약할 알림이 없어요." }
    }

    /** 💼 업무·일정 묶음 이름 — 사람 묶음과 같은 길(AI·미리 요약)로 정리하고 따로 그림 */
    const val WORK_TITLE = "💼 업무·일정"

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
    fun pendingForModel(items: List<NotiItem>, cache: Map<String, Cached>, since: Long, groups: List<Group> = emptyList()): Int =
        (conversations(items.filter(::isPerson)) + groups.flatMap { groupConvs(it) }).sumOf { conv ->
            val c = validCache(conv, cache, since)
            if (c == null && direct(conv) != null) 0 else freshOf(conv, c).size
        }

    /**
     * 지금 요약하면 AI 에 새로 넣을 글자 수(메시지마다 [lineChars] 까지만 셈 — AI 가 실제로 읽는 만큼).
     * ⚡ 미리 요약은 이게 [PreSummary.MIN_CHARS] 를 넘으면 돈다(2026-11-23 선생님: 개수 말고 글자 수로 미리미리).
     */
    fun pendingCharsForModel(items: List<NotiItem>, cache: Map<String, Cached>, since: Long, groups: List<Group> = emptyList()): Int =
        (conversations(items.filter(::isPerson)) + groups.flatMap { groupConvs(it) }).sumOf { conv ->
            val c = validCache(conv, cache, since)
            if (c == null && direct(conv) != null) 0 else freshOf(conv, c).sumOf { minOf(it.text.length, lineChars) }
        }

    /** 👥 내 묶음(가족·교직원 등) 하나: 묶음 이름과 (사람 이름, 알림)들 */
    data class Group(val title: String, val hits: List<Pair<String, NotiItem>>)

    /**
     * 묶음 안에서 같은 이름의 사람이 보낸 알림은 카톡·문자·단톡을 가리지 않고 한 대화로(2026-11-23 선생님:
     * "지인 연락에서 같은 이름의 사람 알림이면 모아서 요약" — 예전엔 원문을 8줄까지 그대로 늘어놓았다).
     */
    fun groupConvs(g: Group): List<Conversation> = g.hits
        .filterNot { (_, n) -> AD.containsMatchIn(n.title) || AD.containsMatchIn(n.text) }
        .distinctBy { (_, n) -> listOf(n.pkg, n.room, n.title, n.text) }
        .groupBy({ it.first }, { it.second })
        .map { (name, list) -> Conversation(g.title, displayName(name, list), list.sortedBy { it.time }) }
        .sortedByDescending { it.last }

    /**
     * 묶음 사람의 보이는 이름: 알림에 찍힌 보낸 사람 이름 그대로(예: '김민수 선생님') — 묶음에 적은 짧은 이름으로 줄이지 않음
     * (2026-11-23 선생님: "OOO선생님에서 선생님만 남기면 누가 한 말인지 모르겠어"). 여러 개면 가장 많이 쓴 것, 같으면 긴 것.
     */
    fun displayName(name: String, items: List<NotiItem>): String {
        val key = name.filterNot { it.isWhitespace() }
        return items.map { it.title.trim() }.filter { t -> t.isNotEmpty() && key.isNotEmpty() && key in t.filterNot { it.isWhitespace() } }
            .groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })?.key ?: name
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
        /** 👥 내 묶음별 사람 연락 — 사람마다 한 줄로 요약해 Result.groups 에 */
        groups: List<Group> = emptyList(),
        /** 🎯 중요한 대화만 요약(설정 '중요한 대화만', 기본 켬) — 중요한지는 [important]([replyAllowed] 를 명부로) */
        focus: Boolean = false,
    ): Result {
        val (people, apps) = items.partition(::isPerson)
        val groupList = groups.map { it.title to groupConvs(it) }.filter { it.second.isNotEmpty() }
        val groupKeys = groupList.flatMap { (_, cs) -> cs.map(::keyOf) }.toSet()
        val convs = conversations(people) + groupList.flatMap { it.second }
        val rooms = mutableListOf<RoomSummary>()
        val leftover = mutableListOf<Conversation>()
        var cut: String? = null
        // 방마다 아직 안 덮은 메시지
        data class Todo(val conv: Conversation, val prev: Cached?, val fresh: List<NotiItem>)
        // 중요한 방부터(2026-11-23): 시간이 모자라 잘려도 꼭 볼 것은 먼저 끝나게 — 👥 내 묶음·명부·키워드 방 → 1:1 → 단톡 → 큰 단톡(30건 넘음)
        fun rank(cv: Conversation) = when {
            keyOf(cv) in groupKeys || replyAllowed(cv) -> 0
            cv.items.all { it.room.isEmpty() } -> 1
            cv.items.size <= CHUNK -> 2
            else -> 3
        }
        val todos = convs.sortedWith(compareBy<Conversation> { rank(it) }.thenByDescending { it.last })
            .map { conv -> val c = validCache(conv, cache, since); Todo(conv, c, freshOf(conv, c)) }
        // 모델에 묻는 횟수(진행 표시용): 새 메시지를 30개씩 나눈 덩어리 수(30개 넘으면 앞 덩어리 정리에 이어서 고쳐 씀)
        val total = todos.filter { it.fresh.isNotEmpty() && (it.prev != null || direct(it.conv) == null) }.sumOf { chunksOf(it.conv, it.fresh).size }
        var asked = 0
        for ((conv, c, fresh) in todos) {
            var prev: RoomSummary? = c?.let { RoomSummary(conv, it.point, it.ask, it.needReply, byModel = true) }
            // 📅 캘린더는 대화가 아니라 일정 목록 — AI 없이 일정 이름·시각 그대로(2026-11-24: '모두모임에서 내일 관련하여 언급함')
            if (conv.app == WORK_TITLE && isCalendar(conv)) { rooms += calendar(conv); continue }
            // 🙏 인사·맞장구뿐인 방은 요약 줄 없이 '그 밖의 방'에 이름만(2026-11-24: '감사합니다 선배님 !!')
            if (conv.items.all { isFiller(it.text) }) { rooms += RoomSummary(conv, "", "", false, byModel = false); continue }
            // 🎯 온 연락은 간단한 내용까지, 많으면 핵심 요약(2026-11-24 선생님) — 중요하지 않은 방이 몇 마디뿐이면 AI 없이 그 말을 짧게
            if (focus && keyOf(conv) !in groupKeys && !important(conv, replyAllowed) && said(conv).size <= PEEK_MAX) { rooms += peek(conv); continue }
            if (fresh.isEmpty() && prev != null) { rooms += prev; continue }        // 미리 요약해 둔 그대로
            if (prev == null) direct(conv)?.let { rooms += it; continue }
            var covered = c?.count ?: 0
            // 🧩 사실 먼저(factFirst): 단톡방은 앱이 부탁 후보를 (보낸 사람, 문장)으로 뽑고 AI 는 번호만 골라 줄임 — 이름은 앱이 붙임
            if (factFirst && conv.items.any { it.room.isNotEmpty() } && fresh.any { !isFiller(it.text) }) {
                if (cut == null) cut = stopReason()
                if (cut == null) {
                    asked++
                    onStatus("요약 중… $asked/$total")
                    val part = Conversation(conv.app, conv.room, fresh)
                    val cands = candidates(part)
                    val got = parseFacts(part, ask(factPrompt(part, cands, prev)), cands, prev)
                    prev = got
                    onCached(Cached(keyOf(conv), since, fresh.last().time, (c?.count ?: 0) + fresh.size, got.point, got.ask, got.needReply))
                }
                val done = prev
                if (done == null) { leftover += conv; continue }
                rooms += done.copy(conv = conv)
                continue
            }
            for (chunk in chunksOf(conv, fresh)) {
                if (cut == null) cut = stopReason()
                if (cut != null) break
                asked++
                onStatus("요약 중… $asked/$total")
                val part = Conversation(conv.app, conv.room, chunk)
                val p = prev
                val q = if (p == null) prompt(part) else updatePrompt(part, p, covered)
                var got = parse(part, ask(q), p)
                // 원문에 없는 숫자를 써서 버렸으면 한 번 더 — 숫자는 그대로 옮기라고 덧붙여(2026-11-23, 원문으로 떨어지는 일 줄이기)
                if (got.fellBack && (cut ?: stopReason()) == null) {
                    asked++
                    got = parse(part, ask(q + "\n주의: 날짜·시간·숫자는 메시지에 쓰인 그대로 옮겨. 메시지에 없는 숫자는 쓰지 마."), p)
                }
                prev = got
                covered += chunk.size
                onCached(Cached(keyOf(conv), since, chunk.last().time, covered, prev.point, prev.ask, prev.needReply))
            }
            val done = prev
            if (done == null) { leftover += conv; continue }
            rooms += done.copy(conv = conv)
        }
        // 👥 묶음 사람들은 따로 떼어 묶음별로
        val (groupRooms, plainRooms) = rooms.partition { keyOf(it.conv) in groupKeys }
        val rendered = groupList.map { (title, cs) -> title to cs.mapNotNull { c -> groupRooms.firstOrNull { keyOf(it.conv) == keyOf(c) } } }
        val groupLeftover = leftover.filter { keyOf(it) in groupKeys }
        val groupText = renderGroups(rendered.filter { it.first != WORK_TITLE }, groupLeftover)
        val workText = renderGroups(rendered.filter { it.first == WORK_TITLE }, groupLeftover)
        rooms.clear(); rooms += plainRooms
        val groupLeft = leftover.count { keyOf(it) in groupKeys }
        leftover.removeAll { keyOf(it) in groupKeys }
        // 명부·키워드가 안 나오는 방은 답장할 연락에서 뺌(요청은 💬 줄에 → 로 남김)
        rooms.replaceAll { if (it.needReply && !replyAllowed(it.conv)) it.copy(needReply = false) else it }
        // 단톡방: 줄에 못 들어간 부탁 수(AI 없이 원문에서) — 전부는 세부 내역에서
        rooms.replaceAll { r -> if (r.byModel && r.conv.items.any { it.room.isNotEmpty() }) r.copy(extra = extraAsks(r.conv, r.point + " " + r.ask)) else r }
        // 방 순서는 최근에 온 것부터
        rooms.sortByDescending { it.conv.last }
        val notes = buildList {
            if (cut != null && leftover.isNotEmpty()) add("$cut 대화방 ${leftover.size}곳은 이름만 적었어요.")
            else if (cut != null) add(cut!!)
        }
        return Result(render(rooms, leftover, notes), appSection(apps.filterNot { AD.containsMatchIn(it.title) || AD.containsMatchIn(it.text) }),
            rooms.filter { it.needReply }, groupText, cutRooms = leftover.size + groupLeft, work = workText)
    }

    /** 선생님을 부르는 말 */
    private val CALLS_TEACHER = Regex("(선생님|쌤|선생 님)")

    /**
     * 🎯 중요한 대화인가(IDENTITY.md 원칙 2 — 2026-11-24 선생님: 업무는 요약, 사적 잡담은 이름만).
     * 명부·학부모 방·키워드·학생 이름이 나오는 대화([roster]) · '선생님/쌤'을 부르는 대화 · 나에게 묻거나 부탁한 대화
     * (1:1 은 인사뿐이 아니면 늘, 단톡방은 오늘·내일·몇 시·까지 같은 기한이 든 물음·부탁이 있으면). 👥 묶음·💼 업무는 따로 늘 요약.
     */
    fun important(conv: Conversation, roster: (Conversation) -> Boolean): Boolean {
        if (roster(conv)) return true
        if (conv.items.any { CALLS_TEACHER.containsMatchIn(it.text) }) return true
        val oneToOne = conv.items.all { it.room.isEmpty() }
        // 1:1 은 나에게 직접 온 연락 — 인사·맞장구뿐이 아니면 늘 요약(2026-11-24: 1:1 두 방이 모두 이름만 나와 요약이 텅 빔)
        if (oneToOne) return conv.items.any { hasWords(it.text) && !isFiller(it.text) }
        return conv.items.any { val t = it.text
            hasWords(t) && !isFiller(t) && QUESTION.containsMatchIn(t) && URGENT.containsMatchIn(t) }
    }

    private val CALENDAR = Regex("(?i)(calendar|캘린더|일정)")
    fun isCalendar(conv: Conversation) = conv.items.firstOrNull()?.let { CALENDAR.containsMatchIn(it.app + " " + it.pkg) } == true

    /** 캘린더 알림 → '일정 (시각·장소), …' 한 줄. 같은 일정은 한 번(최근 알림의 내용으로) */
    fun calendar(conv: Conversation): RoomSummary {
        val events = conv.items.asReversed().distinctBy { it.title.trim() }.asReversed().map { n ->
            val what = n.title.trim().ifEmpty { n.text.trim() }
            val detail = n.text.replace('\n', ' ').trim().takeIf { it.isNotEmpty() && it != what }?.take(30)
            if (detail != null) "$what ($detail)" else what
        }
        return RoomSummary(conv, events.joinToString(", ").take(160), "", false, byModel = false)
    }

    private val ENDING = Regex("(해 ?)?(달라고|라고|자고|고) ?함\\.?$")
    /** 요점과 부탁을 한 문단으로: '…하기로 함. 꽃을 사 와 달라고 함.' (부탁이 요점에 이미 있으면 요점만) */
    fun paragraph(point: String, ask: String, reply: Boolean = false): String {
        // 한 문단(2026-11-23 선생님) — 높임말은 '…함'으로 맞춤(2026-11-24: '말씀하심')
        fun end(x: String) = soften(x.trim().trimEnd('.', '。')).let { if (it.isEmpty() || it.last() in "?!？！") it else "$it." }   // '아녜요?.' 처럼 겹치지 않게
        // 부탁 내용이 요점에 이미 들어 있으면(끝말 '…달라고 함'을 떼고 두 글자 조각이 절반 넘게 겹침) 한 번만
        fun core(x: String) = x.trim().replace(ENDING, "")
        return if (ask.isEmpty() || isRhetorical(ask) || point.contains(ask) || overlap(core(ask), core(point)) >= 0.5) end(point) else end(point) + (if (reply) " 🙋 " else " ") + end(groupAsk(ask))
    }

    /** 묶음 칸의 부탁이 원문 그대로면('…해주세요~~ 체육끝나고.') 짧은 말('…달라고 함')로 */
    fun groupAsk(ask: String): String {
        val a = ask.trim().trimEnd('.')
        return if (Regex("(함|물음|요청|부탁)$").containsMatchIn(a)) a else askSentence(a.replace(Regex("~+"), "")).trimEnd('.')
    }

    /** 👥 묶음별: '🏠 가족 연락 (2명 · 5건)' 아래 사람마다 이름 그대로 + 한 말 한 문단 */
    fun renderGroups(groups: List<Pair<String, List<RoomSummary>>>, leftover: List<Conversation>): String = buildString {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        for ((title, list) in groups) {
            val rest = leftover.filter { it.app == title }
            if (list.isEmpty() && rest.isEmpty()) continue
            val n = list.sumOf { it.conv.items.size } + rest.sumOf { it.items.size }
            append(if (title == WORK_TITLE) "$title 알림 (앱 ${list.size + rest.size}개 · ${n}건)\n" else "$title 연락 (${list.size + rest.size}명 · ${n}건)\n")
            // 사람 이름은 그대로, 그 사람이 한 말만 한 문단으로(요점 + 부탁을 이어서) — 2026-11-23 선생님
            list.sortedByDescending { it.conv.last }.forEach { r ->
                val point = r.point.ifBlank { r.conv.items.last().text.replace('\n', ' ').take(60) }.removePrefix("${r.conv.room}: ").let { dropName(it, r.conv.room, title) }
                append("- ${r.conv.room} (${r.conv.items.size}건) · ${fmt.format(r.conv.last)}: ${paragraph(point, r.ask, r.needReply)}\n")
            }
            if (rest.isNotEmpty()) append("- 그 밖에: " + rest.joinToString(", ") { "${it.room} ${it.items.size}건" } + "\n")
            append("\n")
        }
    }.trimEnd().let { if (it.isEmpty()) it else it + "\n\n" }

    /** 사람 메시지 요약 → 화면 글 */
    private fun more(r: RoomSummary) = if (r.extra > 0) " · 외 부탁 ${r.extra}건(세부 내역)" else ""

    fun render(rooms: List<RoomSummary>, leftover: List<Conversation>, notes: List<String>): String = buildString {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        // 2026-11-23 선생님: 칸을 줄임 — 🙋 답장할 연락을 따로 두지 않고 💬 대화 요약 맨 위에 🙋 표시로
        val replies = rooms.filter { it.needReply }
        val chatter = rooms.filter { !it.needReply && it.point.isBlank() }.map { it.conv }
        val talk = rooms.filterNot { it.needReply || it.point.isBlank() }
        val others = leftover + chatter
        if (replies.isNotEmpty() || talk.isNotEmpty() || others.isNotEmpty()) {
            append("💬 대화 요약 (${rooms.size + leftover.size}개 방" + (if (replies.isNotEmpty()) " · 🙋 답장 ${replies.size}" else "") + ")\n")
            replies.forEach { r ->
                val detail = if (r.ask.isNotEmpty() && r.byModel && !r.point.contains(r.ask)) "${r.ask} (${dropName(r.point, r.conv.room)})" else r.ask.ifEmpty { dropName(r.point, r.conv.room) }
                append("- 🙋 ${r.conv.room} · ${fmt.format(r.conv.last)}: $detail" + more(r) + "\n")
            }
            talk.forEach { r ->
                append("- ${r.conv.room} (${r.conv.items.size}건): ${dropName(r.point, r.conv.room)}")
                if (r.ask.isNotEmpty() && !r.point.contains(r.ask)) append(" → ${r.ask}")   // 짧은 한 마디는 요점이 곧 물음이라 두 번 쓰지 않음
                append(more(r) + "\n")
            }
            if (others.isNotEmpty()) {
                append("- 그 밖의 방: " + others.joinToString(", ") { "${it.room} ${it.items.size}건" } + "\n")
            }
            append("\n")
        }
        notes.forEach { append("($it)\n") }
    }.trimEnd()
}
