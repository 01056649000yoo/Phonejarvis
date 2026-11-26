package com.jarvis.phone

import android.content.Context

/**
 * 🧭 나에게 한 말인가 — 점수로 판정하고, 쓸수록 다듬는다 (2026-11-26 선생님: "가중치 방식으로, 쓰면 쓸수록 잘 구분하게.
 * 하루 정리 때 가중치를 조정하는 작업이 자동으로 — 그게 AI 를 쓰는 방향").
 *
 * 예전엔 '이름이 있나 / 내 말 바로 뒤인가' 같은 규칙 하나만 맞으면 예, 아니면 아니오였다. 이제 단서마다 점수(가중치)를 주고 더한다:
 *   멘션·내 이름(고정, 늘 넘김) · 학생 이름 · 내 말 바로 뒤 · 내 말과 내용이 겹침 · 나를 부른 사람이 이어서 한 말 ·
 *   묻거나 부탁함 · 말하는 사람이 적은 방 · 보낸 사람(내가 자주 답하는 사람일수록 +)
 * 합이 [THRESHOLD] 이상이면 나에게 한 말. 처음 가중치는 예전 규칙과 같은 결과가 나오게 잡았다.
 *
 * 다듬기([learn], 하루 정리 때 한 번 — 폰 안에서만):
 *  ① 내가 답한 말: 내가 그 방에서 말하기 바로 전의 말은 나에게 한 말이었다(정답 '예')
 *  ② 애매한 말은 폰 안의 AI 에게 '예/아니오'만 물어 정답으로 삼는다(하루 [MAX_AI]번까지)
 * 틀린 쪽으로 판정한 말의 단서·보낸 사람 점수를 조금씩 올리거나 내린다. 이름·멘션은 내리지 않는다(놓치면 안 되는 단서).
 * 안드로이드에 기대지 않는 순수 규칙([score]·[adjust])은 시험할 수 있다.
 */
object ToMe {
    const val MENTION = "mention"; const val NAME = "name"          // 고정
    const val STUDENT = "student"; const val THREAD = "thread"; const val REPLY = "reply"; const val FOLLOW = "follow"
    const val ASK = "ask"; const val SMALL = "small"
    val LEARNED = listOf(STUDENT, THREAD, REPLY, FOLLOW, ASK, SMALL)
    val DEFAULT = mapOf(STUDENT to 1.0, THREAD to 1.0, REPLY to 1.0, FOLLOW to 1.0, ASK to 0.2, SMALL to 0.2)
    const val THRESHOLD = 1.0
    const val LR = 0.1                 // 한 번에 고치는 크기
    const val W_MIN = 0.0; const val W_MAX = 1.3
    const val S_MIN = -0.5; const val S_MAX = 0.8          // 보낸 사람 점수
    const val SMALL_ROOM = 4           // 말하는 사람이 이만큼 이하면 작은 방
    const val MAX_AI = 8               // 하루에 AI 에게 묻는 말 수(충전 중이 아닐 때)
    const val MAX_AI_CHARGING = 20     // 충전 중이면 더 많이 물어 더 빨리 익힘
    const val LABEL = "🧭 나에게 한 말 다듬기"
    const val MAX_SENDERS = 200

    data class Weights(val w: Map<String, Double> = DEFAULT, val senders: Map<String, Double> = emptyMap(), val trained: Int = 0) {
        fun of(f: String) = w[f] ?: DEFAULT[f] ?: 0.0
    }

    /** 점수(순수): 고정 단서가 있으면 넘김, 아니면 단서 가중치 + 보낸 사람 점수 */
    fun score(features: Set<String>, sender: String, wt: Weights): Double {
        if (MENTION in features || NAME in features) return THRESHOLD + 1.0
        return features.sumOf { wt.of(it) } + (wt.senders[sender] ?: 0.0)
    }
    fun isToMe(features: Set<String>, sender: String, wt: Weights) = score(features, sender, wt) >= THRESHOLD

    /**
     * 정답([toMe])과 다르게 판정했으면 가중치를 조금 고침(순수). 맞게 판정했으면 그대로.
     * '예'인데 놓쳤으면 그 말의 단서·보낸 사람을 올리고, '아니오'인데 잡았으면 내림(고정 단서로 잡은 것은 건드리지 않음)
     */
    fun adjust(wt: Weights, features: Set<String>, sender: String, toMe: Boolean, lr: Double = LR): Weights {
        val fixed = MENTION in features || NAME in features
        val pred = isToMe(features, sender, wt)
        if (pred == toMe || fixed) return wt
        val d = if (toMe) lr else -lr
        val w = wt.w.toMutableMap()
        features.filter { it in LEARNED }.forEach { f -> w[f] = (wt.of(f) + d).coerceIn(W_MIN, W_MAX) }
        val s = wt.senders.toMutableMap()
        if (sender.isNotBlank()) s[sender] = ((s[sender] ?: 0.0) + d).coerceIn(S_MIN, S_MAX)
        return Weights(w, s, wt.trained)
    }

    /** 대화방 하나의 말마다 단서를 뽑아 주는 것 — 이름·내 말·부른 사람을 한 번만 계산 */
    class Scorer(val weights: Weights, private val mine: Collection<String>, private val students: Collection<String>,
                 private val saidAt: (String) -> Long, private val saidText: (String) -> String) {
        private class Ctx(val thread: Set<NotiItem>, val reply: Set<NotiItem>, val follow: Set<NotiItem>, val small: Boolean)
        private val memo = HashMap<String, Ctx>()
        private fun ctx(c: BriefingComposer.Conversation) = memo.getOrPut(BriefingComposer.keyOf(c)) {
            val at = saidAt(c.room)
            val all = Relevance.myThread(c.items, at, saidText(c.room))
            val first = Relevance.myThread(c.items, at).toSet()
            Ctx(first, all.toSet() - first,
                Relevance.followUps(c.items) { Relevance.addressesMe(it.text, mine) }.toSet(),
                c.items.any { it.room.isNotEmpty() } && Relevance.speakers(c) <= SMALL_ROOM)
        }
        fun features(c: BriefingComposer.Conversation, n: NotiItem): Set<String> = buildSet {
            val x = ctx(c)
            if (Relevance.addressesMe(n.text, emptyList())) add(MENTION)          // 카톡 '나를 멘션'
            if (mine.isNotEmpty() && Relevance.addressesMe(n.text, mine)) add(NAME)
            if (students.any { Relevance.occursAsName(it, n.text) }) add(STUDENT)
            if (n in x.thread) add(THREAD)
            if (n in x.reply) add(REPLY)
            if (n in x.follow) add(FOLLOW)
            if (BriefingComposer.asksSomething(n.text) && !Relevance.isBroadcast(n.text)) add(ASK)
            if (x.small) add(SMALL)
        }
        fun sender(c: BriefingComposer.Conversation, n: NotiItem) = c.who(n).ifEmpty { n.title.trim() }
        fun score(c: BriefingComposer.Conversation, n: NotiItem) = score(features(c, n), sender(c, n), weights)
        fun toMe(c: BriefingComposer.Conversation, n: NotiItem) = score(c, n) >= THRESHOLD
    }

    // ── 저장 ──
    private fun p(ctx: Context) = ctx.getSharedPreferences("to_me", Context.MODE_PRIVATE)
    fun load(ctx: Context): Weights {
        val all = p(ctx).all
        return Weights(
            LEARNED.associateWith { f -> (all["w_$f"] as? Float)?.toDouble() ?: DEFAULT.getValue(f) },
            all.filterKeys { it.startsWith("s_") }.mapNotNull { (k, v) -> (v as? Float)?.let { k.removePrefix("s_") to it.toDouble() } }.toMap(),
            (all["trained"] as? Int) ?: 0)
    }
    fun save(ctx: Context, wt: Weights) {
        p(ctx).edit().apply {
            clear()
            LEARNED.forEach { putFloat("w_$it", wt.of(it).toFloat()) }
            // 점수가 0에 가까운 사람은 지우고, 많으면 점수가 큰 사람부터 남김
            wt.senders.filterValues { kotlin.math.abs(it) >= 0.05 }.entries.sortedByDescending { kotlin.math.abs(it.value) }.take(MAX_SENDERS)
                .forEach { putFloat("s_${it.key}", it.value.toFloat()) }
            putInt("trained", wt.trained)
        }.apply()
    }
    /** 설정 화면에 보일 한 줄: 몇 번 다듬었고 누구의 말을 더 잘 보는지 */
    fun status(ctx: Context): String {
        val wt = load(ctx)
        if (wt.trained == 0) return "아직 다듬기 전이에요. 밤에 폰을 쓰지 않을 때 지난 하루 대화를 보고 저절로 다듬어요."
        val close = wt.senders.filterValues { it >= 0.2 }.entries.sortedByDescending { it.value }.take(4).map { it.key }
        return "지금까지 ${wt.trained}건으로 다듬었어요." + if (close.isNotEmpty()) " 자주 대화하는 사람: ${close.joinToString(", ")}" else ""
    }

    // ── 다듬기 ──
    fun askPrompt(room: String, name: String, context: List<String>, who: String, text: String): String =
        "아래는 단톡방 '$room'의 대화야.\n" + context.joinToString("\n") + (if (context.isNotEmpty()) "\n" else "") +
        "▶ $who: ${text.replace('\n', ' ').take(160)}\n" +
        "▶ 표시한 말은 '$name'에게 한 말이야? ('$name'에게 묻거나 부탁하거나 답한 말이면 예, 다른 사람에게 했거나 모두에게 돌린 말이면 아니오)\n" +
        "'예' 또는 '아니오' 한 마디로만 답해."

    /** AI 답 → 예/아니오. 못 알아들으면 null(그 말은 배우지 않음) */
    fun parseYesNo(reply: String): Boolean? {
        val r = reply.trim().trimStart('-', '*', ' ', '"', '\'')
        return when {
            r.startsWith("예") || r.startsWith("네") || r.startsWith("맞") || r.startsWith("yes", true) -> true
            r.startsWith("아니") || r.startsWith("no", true) -> false
            else -> null
        }
    }

    /** 배울 말 하나: 그 말의 단서·보낸 사람과 정답 */
    data class Example(val features: Set<String>, val sender: String, val toMe: Boolean)

    /**
     * ① 내가 답한 말(순수): 내가 그 방에서 말하기 전 [BEFORE_MS] 안의 마지막 두 마디 — 나에게 한 말이었던 것.
     * 여럿이 떠드는 방에서는 바로 앞 말이 꼭 나에게 한 말은 아니어서, 묻거나 부탁한 말이거나 작은 방일 때만
     */
    const val BEFORE_MS = 10 * 60_000L
    fun answered(c: BriefingComposer.Conversation, saidAt: Long, sc: Scorer): List<Example> =
        answeredItems(c, saidAt, sc).map { n -> Example(sc.features(c, n), sc.sender(c, n), true) }

    /** 내가 답한 말(원문) */
    fun answeredItems(c: BriefingComposer.Conversation, saidAt: Long, sc: Scorer): List<NotiItem> {
        if (saidAt <= 0) return emptyList()
        return c.items.filter { it.time < saidAt && saidAt - it.time <= BEFORE_MS && !BriefingComposer.isFiller(it.text) }
            .sortedBy { it.time }.takeLast(2)
            .filter { n -> sc.features(c, n).let { f -> ASK in f || SMALL in f } }
    }

    private val CALLING = Regex("([가-힣]{1,4}) ?(쌤|샘|선생님|부장님)(?![가-힣])")
    /**
     * 🙋 호칭 추천(순수 — 시험): 내가 답한 말들에서 사람들이 나를 부른 꼴('승현쌤', '유 부장님')을 찾아, 두 번 넘게 나온 것만.
     * 이미 넣은 이름이 들어 있는 꼴은 뺌(이미 알아봄). 추천일 뿐 — 설정 → 나를 부르는 말에 '추천'으로 보이고 눌러야 들어감
     */
    fun nicknames(texts: List<String>, mine: Collection<String>): List<String> =
        texts.flatMap { t -> CALLING.findAll(t).map { m -> (m.groupValues[1] + m.groupValues[2]) }.toList().distinct() }
            .filter { n -> mine.none { it in n || n in it } }
            .groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.toList()

    /**
     * 하루 정리 때 한 번: 오늘 온 단톡방 말로 가중치를 다듬는다. 돌려주는 값은 다듬은 건수(0이면 바뀐 것 없음).
     * [ask] 가 null(폰이 뜨겁거나 모델 없음)이면 ①만.
     */
    /**
     * [minGapHours]: 지난 다듬기 뒤 이만큼 안 지났으면 안 함(밤에 폰을 안 쓸 때 도는 것이 기본 20시간, 하루 정리 때의 예비는 44시간 —
     * 이틀 밤 내리 못 돌았을 때만). [maxAi]: AI 에게 묻는 말 수. [stop]: 멈출 때가 됐나(화면이 켜짐·폰이 따뜻해짐) — AI 에게 묻기 전마다 봄
     */
    suspend fun learn(ctx: Context, now: Long, ask: (suspend (String) -> String)?, minGapHours: Int = 20, maxAi: Int = MAX_AI,
                      stop: () -> Boolean = { false }): Int {
        val mine = MyNames.all(ctx)
        if (mine.isEmpty()) return 0
        val meta = p(ctx)
        if (now - meta.getLong("day", 0L) < minGapHours * 3600_000L) return 0
        val rules = AppFilter.rules(ctx)
        // 지난 24시간의 단톡방 말
        val items = NotificationDb.get(ctx).since(now - 24 * 3600_000L).filter { rules.allows(it) && BriefingComposer.isPerson(it) && it.room.isNotEmpty() }
        val said = ReplyLog.all(ctx); val texts = ReplyLog.texts(ctx)
        var wt = load(ctx)
        val sc = Scorer(wt, mine, Students.allTerms(Students.names(ctx)), { said[it] ?: 0L }, { texts[it].orEmpty() })
        val convs = BriefingComposer.conversations(items)
        val examples = mutableListOf<Example>()
        // ① 내가 답한 말
        val answeredTexts = mutableListOf<String>()
        convs.forEach { c ->
            val got = answeredItems(c, said[c.room] ?: 0L, sc)
            got.forEach { n -> examples += Example(sc.features(c, n), sc.sender(c, n), true); answeredTexts += n.text }
        }
        // 🙋 사람들이 나를 부르는 꼴을 추천으로(설정 → 나를 부르는 말)
        runCatching { nicknames(answeredTexts, mine).forEach { MyNames.noteMe(ctx, it) } }
        // ② 애매한 말을 AI 에게: 이름 없이 잡은 말(맞게 잡았나)과 아깝게 놓친 말(놓친 것 아닌가)을 반반
        if (ask != null) {
            data class Cand(val c: BriefingComposer.Conversation, val n: NotiItem, val f: Set<String>, val s: Double)
            val cands = convs.flatMap { c -> c.items.filterNot { BriefingComposer.isFiller(it.text) }.map { n -> Cand(c, n, sc.features(c, n), sc.score(c, n)) } }
                .filter { MENTION !in it.f && NAME !in it.f }
            val caught = cands.filter { it.s >= THRESHOLD }.sortedBy { it.s }.take(maxAi / 2)
            val near = cands.filter { it.s < THRESHOLD && it.s >= 0.4 }.sortedByDescending { it.s }.take(maxAi - caught.size)
            for (x in caught + near) {
                if (stop()) break
                val before = x.c.items.filter { it.time < x.n.time }.sortedBy { it.time }.takeLast(3)
                    .map { "${sc.sender(x.c, it)}: ${it.text.replace('\n', ' ').take(80)}" }
                val y = runCatching { parseYesNo(ask(askPrompt(x.c.room, mine.first(), before, sc.sender(x.c, x.n), x.n.text))) }.getOrNull() ?: continue
                examples += Example(x.f, sc.sender(x.c, x.n), y)
            }
        }
        var changed = 0
        for (e in examples) {
            val next = adjust(wt, e.features, e.sender, e.toMe)
            if (next != wt) { wt = next; changed++ }
        }
        if (changed > 0) save(ctx, wt.copy(trained = wt.trained + changed))
        p(ctx).edit().putLong("day", now).apply()
        return changed
    }
}
