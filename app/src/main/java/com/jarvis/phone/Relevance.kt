package com.jarvis.phone

import android.content.Context

/**
 * 🎯 나와의 거리 (2026-11-25 선생님: "하루 정리에 굳이 안 봐도 될 잡담이 너무 많아 — 나와 관련된 것만 요약해서").
 *
 * 예전엔 '내용이 있나'로 걸러 커뮤니티·오픈채팅 잡담까지 방마다 요약됐고(하루 715건 · 66곳 중 57곳),
 * 방 안 아무 메시지에 키워드가 하나라도 있으면 그 방의 모든 부탁이 🙋 답할 것으로 올라갔다(여러 사람에게 돌린 '참여 부탁'까지).
 * 이제 방마다 나와의 거리를 셋으로 나눈다 — 평소 요약과 하루 정리 모두:
 *  1 [ME]     나에게 — 1:1 · 학부모 명부·학부모 방 · 나를 부르거나(🙋 나를 부르는 말: 내 이름·별명 + 학생 이름) 멘션한 말이 있는 방 → AI 요약, 🙋·핵심은 여기서만
 *  2 [JOINED] 내가 속한 방 — 내가 말해 본 적 있는 단톡방(ReplyLog, 30일)                                                 → 한 줄
 *  3 [OTHER]  그 밖 — 오픈채팅·커뮤니티·뉴스방·내가 말한 적 없는 단톡방                                                     → 이름과 건수만(AI 안 씀)
 * 👥 내 묶음·💼 업무는 이 앞에서 따로 늘 요약한다. 방마다 직접 정할 수도 있다(방 고르기: 늘 중요 / 한 줄 / 건수만).
 * 여러 선생님이 쓰는 앱이라(2026-11-25) 처음 쓰는 사람도 놓치지 않게:
 *  - 나를 부르는 말을 아직 안 넣었으면 예전 규칙('선생님/쌤'을 부르거나 기한 있는 물음이 있는 방은 나에게)
 *  - 익히는 기간은 없앰(2026-11-26): 한 번 말한 방은 바로 내가 속한 방, 말한 적 없는 방은 처음부터 이름·건수만
 * 정기 요약(정한 시각·하루 정리)은 나에게 온 것만, 내가 속한 방은 곳 수·건수만 — 직접 누른 요약에서만 한 줄씩(BriefingComposer.compose 의 compact)
 * 안드로이드에 기대지 않는 순수 규칙이라 시험할 수 있다.
 */
object Relevance {
    const val AUTO = 0
    const val ME = 1
    const val JOINED = 2
    const val OTHER = 3
    /** 하루에 말한 사람이 이만큼 넘는 단톡방은 커뮤니티·오픈채팅으로 봄 */
    const val CROWD = 8
    /** 처음 쓰기 시작한 뒤 이만큼은 '내가 말한 방'을 익히는 기간 */
    const val LEARN_MS = 14 * 24 * 3600_000L

    /** 카톡이 멘션 알림 앞에 붙이는 말 */
    private val MENTION_MARK = Regex("\\[?나를 ?멘션]?")

    /**
     * 나를 부르거나 멘션한 말인가: 카톡 '나를 멘션' 표시, 또는 ⭐ 키워드(내 이름·별명·학생 이름 …)가 내용에 있음.
     * '@친절한유쌤'처럼 띄어쓰기 없이 불러도 '친절한 유쌤'과 같게(세 글자 이상). 보낸 사람·방 이름은 보지 않음(방 이름에 내 이름이 있으면 모든 말이 나를 부르는 것이 됨)
     */
    fun addressesMe(text: String, names: Collection<String>): Boolean {
        if (MENTION_MARK.containsMatchIn(text)) return true
        // 사람이 보낸 말은 '유승현님 확인 부탁드려요'처럼 이름+님도 부르는 말(2026-11-25 — 예전엔 광고 거르기 규칙에 막혔다. 광고·업체는 앞에서 따로 거름)
        if (AppFilter.hasKeyword(names, text)) return true
        // 띄어 쓴 이름('친절한 유쌤')만 붙여 쓴 꼴('@친절한유쌤')로도 봄 — 붙여 쓴 이름은 위에서 이미 봄('OO님' 광고 거르기를 지나치지 않게)
        val flat = text.filterNot { it.isWhitespace() }
        return names.any { n -> n.trim().contains(' ') && n.filterNot { it.isWhitespace() }.let { it.length >= 3 && it in flat } }
    }

    /** 내가 그 방에서 말한 뒤 이만큼 안에 이어진 말은 나와 나누는 대화 */
    const val THREAD_MS = 30 * 60_000L
    fun inMyThread(n: NotiItem, saidAt: Long) = saidAt > 0 && n.time > saidAt && n.time - saidAt <= THREAD_MS
    /** 나에게 한 말: 나를 부르거나, 내가 말한 직후 이어진 말([saidAt] = 그 방에서 내가 마지막으로 말한 시각) */
    fun toMe(n: NotiItem, names: Collection<String>, saidAt: Long = 0L) = addressesMe(n.text, names) || inMyThread(n, saidAt)

    /** 여러 사람에게 돌린 말('관심 있으신 선생님들 참여 부탁', '사용해 보신 분 계실지') — 나에게 묻는 말이 아님 */
    private val BROADCAST = Regex("(관심 ?있으신|참여 ?(를 )?(부탁|바랍|해 ?주|하세요)|선생님들|여러분|분들|있으신 ?분|하실 ?분|해 ?보신 ?분|보신 ?(선생님|분)|계실까요|계신가요|계실지|계시면|모집|신청 ?(하세요|바랍)|서명|공유 ?(부탁|바랍)|널리 ?알려)")
    fun isBroadcast(text: String) = BROADCAST.containsMatchIn(text)

    /** 한 사람을 '선생님/쌤'하고 부르는 말('선생님들'·'쌤들'처럼 여럿을 부르는 말은 아님) */
    // '샘'은 뒤에 한글이 이어지지 않을 때만 — '샘플'·'샘물'이 부르는 말로 잡히지 않게
    private val TEACHER = Regex("(선생님|쌤|샘(?![가-힣]))(?!들)")
    fun callsTeacher(text: String) = TEACHER.containsMatchIn(text)

    /** 방 하나의 거리. [override] 는 방 고르기에서 직접 정한 값(AUTO 면 저절로) */
    /**
     * 1:1 은 문자(번호로 오는 사람), 내가 답한 적 있는 사람, 명부·나를 부른 말이면 1, 아니면 2(한 줄) —
     * 카톡 오픈채팅은 방 이름 없이 1:1 처럼 오기도 해서(크림치즈컵케이크·택배 상자를 든 네오) 처음 보는 1:1 은 한 줄로.
     * [trusted1to1] 은 문자 앱처럼 1:1 이 곧 사람인 경우
     */
    fun level(conv: BriefingComposer.Conversation, names: Collection<String>, roster: (BriefingComposer.Conversation) -> Boolean,
              joined: (String) -> Boolean, override: Int = AUTO, trusted1to1: Boolean = false,
              /** 나를 부르는 말을 넣었는가(학생 이름만 있으면 false) */ hasMyNames: Boolean = true,
              /** 익히는 기간인가 */ learning: Boolean = false,
              /** 그 방에서 내가 마지막으로 말한 시각 */ saidAt: Long = 0L,
              /** true 면 나를 부른 말은 보지 않은 '방 자체의' 거리(문장 단위로 보여 줄 때 나머지 대화의 거리) */ ignoreCalls: Boolean = false): Int {
        if (override in ME..OTHER) return override
        val called = !ignoreCalls && conv.items.any { toMe(it, names, saidAt) }
        // 처음 보는 1:1 이라도 '선생님/쌤'하고 나를 부르며 묻거나 부탁하면 나에게 온 것(2026-11-26 선생님: 답할 것에 1:1 도 포함 —
        // 명부에 없는 학부모의 첫 카톡 "선생님 내일 상담 가능할까요?"가 한 줄로 내려가 답할 것에 안 올라갔다).
        // 부르는 말 없이 묻기만 한 것은 그대로 한 줄 — 1:1 처럼 오는 오픈채팅의 "계정 이관 방법이 있을지요?"가 답할 것에 섞이지 않게
        if (conv.items.all { it.room.isEmpty() }) return if (trusted1to1 || joined(conv.room) || roster(conv) || called ||
            conv.items.any { callsTeacher(it.text) && BriefingComposer.asksSomething(it.text) && !isBroadcast(it.text) }) ME else JOINED
        if (roster(conv) || called) return ME
        if (!hasMyNames && BriefingComposer.important(conv, roster)) return ME      // 이름을 모르면 예전 규칙으로
        if (joined(conv.room)) return JOINED
        if (speakers(conv) >= CROWD) return OTHER
        return if (learning || !hasMyNames) JOINED else OTHER
    }

    /** 말한 사람 수(맞장구뿐인 말은 셈) */
    fun speakers(conv: BriefingComposer.Conversation) = conv.items.map { conv.who(it).ifEmpty { it.title.trim() } }.filter { it.isNotEmpty() }.distinct().size

    /**
     * 🙋 답할 것으로 올려도 되는가(2026-11-26 선생님: 답할 것에는 나를 언급한 대화만, 1:1 도 포함): 나에게 온 방(1단계)이고
     *  - 1:1: 묻는 말이 모두 '여러 사람에게 돌린 말'만은 아닐 때
     *  - 단톡방(학부모 방 포함): 나를 부르거나 멘션한 말 자체가 묻거나 부탁할 때. [mine] = 나를 부르는 말 —
     *    학생 이름만 나온 말, 같은 방 다른 사람에게 한 부탁은 🙋 이 아님(내용은 '나에게 온 대화'에 그대로 보임)
     *  - 나를 부르는 말을 아직 안 넣었으면 예전 규칙(학부모 방·돌린 말이 아닌 물음)
     */
    fun replyOk(conv: BriefingComposer.Conversation, level: Int, names: Collection<String>, roster: (BriefingComposer.Conversation) -> Boolean,
                hasMyNames: Boolean = true, mine: Collection<String> = names): Boolean {
        if (level != ME) return false
        val asks = conv.items.filter { BriefingComposer.asksSomething(it.text) }
        if (asks.isEmpty()) return false
        if (conv.items.all { it.room.isEmpty() }) return asks.any { !isBroadcast(it.text) || addressesMe(it.text, names) }
        if (hasMyNames) return asks.any { addressesMe(it.text, mine) }
        // 이름을 모르면 예전처럼: 학부모 방이거나 돌린 말이 아닌 물음
        return asks.any { addressesMe(it.text, names) || !isBroadcast(it.text) }
    }
}

/** 🎯 방마다 직접 정한 거리(방 고르기: 늘 중요 1 / 한 줄 2 / 건수만 3, 없으면 저절로) — 'pkg|방' → 값 */
object RoomTier {
    private fun p(ctx: Context) = ctx.getSharedPreferences("room_tier", Context.MODE_PRIVATE)
    fun key(pkg: String, room: String) = "$pkg|$room"
    fun key(conv: BriefingComposer.Conversation) = key(conv.items.firstOrNull()?.pkg.orEmpty(), conv.room)
    fun all(ctx: Context): Map<String, Int> = p(ctx).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    fun set(ctx: Context, pkg: String, room: String, tier: Int) {
        p(ctx).edit().apply { if (tier in Relevance.ME..Relevance.OTHER) putInt(key(pkg, room), tier) else remove(key(pkg, room)) }.apply()
    }
}

/**
 * 🙋 나를 부르는 말(2026-11-25 선생님: 여러 선생님이 쓰는 앱 — 나를 부르는 말을 등록하게). 내 이름·별명·닉네임('친절한 유쌤').
 * 카톡 알림에 실려 오는 '내 이름'(메시지 목록의 나)은 추천으로 기억해 둔다(5개까지, 내용 X).
 */
object MyNames {
    private fun p(ctx: Context) = ctx.getSharedPreferences("my_names", Context.MODE_PRIVATE)
    fun all(ctx: Context): List<String> = p(ctx).getStringSet("names", emptySet())!!.sorted()
    fun add(ctx: Context, name: String) {
        val n = name.trim().removePrefix("@").trim()
        if (n.length < 2) return
        p(ctx).edit().putStringSet("names", (all(ctx) + n).toSet()).apply()
    }
    fun remove(ctx: Context, name: String) { p(ctx).edit().putStringSet("names", (all(ctx) - name).toSet()).apply() }

    private val NOT_A_NAME = setOf("나", "me", "you", "나에게", "내 채팅")
    /** 카톡 알림의 '나' 이름을 추천으로 기억 */
    fun noteMe(ctx: Context, name: String) {
        val n = name.trim()
        if (n.length !in 2..20 || n.lowercase() in NOT_A_NAME) return
        val seen = p(ctx).getStringSet("seen", emptySet())!!
        if (n in seen) return
        p(ctx).edit().putStringSet("seen", (seen + n).toList().takeLast(5).toSet()).apply()
    }
    /** 추천: 넣은 이름으로 만든 호칭(승현쌤·유 선생님) + 카톡에서 본 내 이름 + ⭐ 키워드(이미 넣은 것은 빼고) */
    fun suggestions(ctx: Context, keywords: Collection<String>): List<String> {
        val mine = all(ctx)
        return (mine.flatMap(::variants) + p(ctx).getStringSet("seen", emptySet())!!.toList() + keywords).map { it.trim() }
            .filter { it.length >= 2 && it !in mine }.distinct()
    }

    /**
     * 사람 이름 → 흔히 부르는 호칭(순수 — 시험): 유승현 → 승현쌤·승현샘·승현 선생님·유쌤·유샘·유 선생님.
     * '유승현 선생님'처럼 이름이 그대로 든 호칭은 이름만으로 이미 알아봐서 뺌. 한글 2~4자 이름만
     */
    fun variants(name: String): List<String> {
        val n = name.trim()
        if (n.length !in 2..4 || !n.all { it in '가'..'힣' }) return emptyList()
        val family = n.take(1); val given = if (n.length >= 3) n.drop(1) else n
        return listOf("${given}쌤", "${given}샘", "$given 선생님", "${family}쌤", "${family}샘", "$family 선생님").filter { it != n && n !in it }.distinct()
    }
    /** 이 기능을 처음 쓴 시각(익히는 기간) */
    fun since(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val t = p(ctx).getLong("since", 0L)
        if (t > 0) return t
        p(ctx).edit().putLong("since", now).apply(); return now
    }
}
