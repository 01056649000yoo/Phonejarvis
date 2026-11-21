package com.jarvis.phone

import android.content.Context

/** 최근 며칠 동안 알림을 보낸 앱 하나의 통계. */
data class AppStat(val pkg: String, val app: String, val count: Int, val samples: List<String>)

/** 앱 하나에 대한 추천: 모으기(keep) / 빼기. */
data class AppRec(val stat: AppStat, val keep: Boolean, val reason: String, val byAi: Boolean)

/** 사용자가 뺀 앱(패키지) 목록과, 이미 살펴본 앱 목록. 수집·요약 모두 이 목록을 따른다. */
object AppFilter {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun excluded(ctx: Context): Set<String> = prefs(ctx).getStringSet("excluded", emptySet())!!.toSet()
    fun reviewed(ctx: Context): Set<String> = prefs(ctx).getStringSet("reviewed", emptySet())!!.toSet()

    fun setKeep(ctx: Context, pkg: String, keep: Boolean) {
        val ex = excluded(ctx).toMutableSet()
        if (keep) ex.remove(pkg) else ex.add(pkg)
        prefs(ctx).edit().putStringSet("excluded", ex).apply()
        markReviewed(ctx, listOf(pkg))
    }

    /** 추천 한꺼번에 적용: 빼기 추천 앱은 제외 목록에, 모으기 추천 앱은 제외 목록에서 뺌. */
    fun apply(ctx: Context, recs: List<AppRec>) {
        val ex = excluded(ctx).toMutableSet()
        recs.forEach { if (it.keep) ex.remove(it.stat.pkg) else ex.add(it.stat.pkg) }
        prefs(ctx).edit().putStringSet("excluded", ex).apply()
        markReviewed(ctx, recs.map { it.stat.pkg })
    }

    fun markReviewed(ctx: Context, pkgs: Collection<String>) {
        prefs(ctx).edit().putStringSet("reviewed", reviewed(ctx) + pkgs).apply()
    }

    // ── 방 고르기: 앱 안에서 뺀 방 ("패키지|방 이름") ──
    private fun roomKey(pkg: String, room: String) = "$pkg|$room"
    fun excludedRooms(ctx: Context): Set<String> = prefs(ctx).getStringSet("excluded_rooms", emptySet())!!.toSet()
    fun isRoomExcluded(set: Set<String>, pkg: String, room: String) = roomKey(pkg, room) in set
    fun setRoomKeep(ctx: Context, pkg: String, room: String, keep: Boolean) {
        val s = excludedRooms(ctx).toMutableSet()
        if (keep) s.remove(roomKey(pkg, room)) else s.add(roomKey(pkg, room))
        prefs(ctx).edit().putStringSet("excluded_rooms", s).apply()
    }
    fun setAllRooms(ctx: Context, pkg: String, rooms: List<String>, keep: Boolean) {
        val s = excludedRooms(ctx).toMutableSet()
        rooms.forEach { if (keep) s.remove(roomKey(pkg, it)) else s.add(roomKey(pkg, it)) }
        prefs(ctx).edit().putStringSet("excluded_rooms", s).apply()
    }

    // ── 중요 키워드 ──
    val SUGGESTED_KEYWORDS = listOf("조퇴", "결석", "상담", "준비물", "마감", "회의", "체험학습", "급식", "병원", "공문")
    fun keywords(ctx: Context): List<String> =
        prefs(ctx).getStringSet("keywords", emptySet())!!.sorted()
    fun setKeywords(ctx: Context, words: Collection<String>) {
        prefs(ctx).edit().putStringSet("keywords", words.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
    }
    fun hasKeyword(words: Collection<String>, vararg texts: String): Boolean =
        words.isNotEmpty() && texts.any { t -> words.any { it in t } }

    /**
     * 이 알림을 모으고 요약에 넣을지: 뺀 앱은 무조건 제외,
     * 뺀 방이라도 중요 키워드가 들어 있으면 포함 (놓치면 안 되는 연락).
     */
    class Rules(val apps: Set<String>, val rooms: Set<String>, val keywords: List<String>) {
        fun allows(pkg: String, room: String, title: String, text: String): Boolean {
            if (pkg in apps) return false
            if (isRoomExcluded(rooms, pkg, room.ifEmpty { title })) return hasKeyword(keywords, title, text, room)
            return true
        }
        fun allows(n: NotiItem) = allows(n.pkg, n.room, n.title, n.text)
    }
    fun rules(ctx: Context) = Rules(excluded(ctx), excludedRooms(ctx), keywords(ctx))
}

/**
 * 알림 앱 정리 추천.
 * 1) 규칙: 앱 이름으로 학교·연락 앱은 모으기, 쇼핑·광고·게임·금융·시스템 앱은 빼기 (즉시)
 * 2) AI: 모델이 있으면 앱 이름 + 알림 제목 예시를 보고 다시 판단 (폰 안에서, 한 번 호출)
 */
object AppAdvisor {
    private val KEEP = listOf(
        "카카오톡", "kakao.talk", "클래스", "class", "알리미", "학교", "school", "밴드", "band",
        "메신저", "messenger", "메일", "mail", "문자", "메시지", "messag", "sms", "캘린더", "calendar",
        "teams", "zoom", "줌", "업무", "나이스", "neis", "교육", "edu", "학급", "e알리미",
    )
    private val DROP = listOf(
        "쿠팡", "coupang", "11번가", "g마켓", "gmarket", "옥션", "무신사", "배민", "배달", "요기요",
        "당근", "쇼핑", "shopping", "스토어", "store", "유튜브", "youtube", "넷플릭스", "netflix",
        "게임", "game", "뉴스", "news", "날씨", "weather", "은행", "bank", "카드", "card", "페이", "pay",
        "증권", "토스", "toss", "인스타", "instagram", "틱톡", "tiktok", "스포티파이", "spotify", "멜론",
        "melon", "갤러리", "gallery", "클라우드", "cloud", "업데이트", "update", "배터리", "디바이스",
        "보안", "security", "백신", "광고",
    )

    /** 메시지·SNS 앱 (카톡, 문자, 메신저, 밴드, 학급 소통 앱, SNS 등). 추천 목록에서 맨 위에 모아 보여 줌. */
    private val MESSAGING = listOf(
        "카카오톡", "kakao.talk", "카카오워크", "문자", "메시지", "messag", "sms", "메신저", "messenger",
        "밴드", "band", "라인", "jp.naver.line", "텔레그램", "telegram", "whatsapp", "왓츠앱", "디스코드", "discord",
        "slack", "슬랙", "teams", "팀즈", "인스타", "instagram", "페이스북", "facebook", "스레드", "threads",
        "트위터", "twitter", "com.twitter", "하이클래스", "hiclass", "클래스팅", "classting", "알리미", "학교종이",
    )

    /** 교사들이 많이 쓰는 메시지 앱. 설치돼 있는데 알림이 0건이면 "알림이 안 들어와요"로 알려 줌. */
    val KNOWN_MESSAGING = linkedMapOf(
        "com.kakao.talk" to "카카오톡",
        "com.samsung.android.messaging" to "메시지(삼성)",
        "com.google.android.apps.messaging" to "메시지(구글)",
        "com.nhn.android.band" to "네이버 밴드",
        "jp.naver.line.android" to "라인",
        "org.telegram.messenger" to "텔레그램",
        "com.microsoft.teams" to "팀즈",
    )

    /** 설치돼 있지만 최근 알림이 하나도 없는 메시지 앱 (패키지 → 이름). */
    fun silentMessagingApps(ctx: Context, stats: List<AppStat>): List<Pair<String, String>> {
        val seen = stats.map { it.pkg }.toSet()
        return KNOWN_MESSAGING.filter { (pkg, _) ->
            pkg !in seen && runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }.map { (pkg, label) ->
            // 폰에 표시되는 실제 이름이 있으면 그걸 씀
            val shown = runCatching {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(label)
            pkg to shown
        }
    }

    fun isMessaging(s: AppStat): Boolean = (s.app + " " + s.pkg).lowercase().let { k -> MESSAGING.any { it in k } }

    /** 메시지·SNS 앱 먼저, 그 안에서는 알림 많은 순. */
    fun sortForDisplay(recs: List<AppRec>): List<AppRec> =
        recs.sortedWith(compareByDescending<AppRec> { isMessaging(it.stat) }.thenByDescending { it.stat.count })

    fun byRules(stats: List<AppStat>): List<AppRec> = stats.map { s ->
        val key = (s.app + " " + s.pkg).lowercase()
        when {
            DROP.any { it in key } -> AppRec(s, false, "쇼핑·광고·금융·시스템 알림", false)
            KEEP.any { it in key } -> AppRec(s, true, "학교·연락 관련", false)
            else -> AppRec(s, true, "판단이 어려워 일단 모아 둬요", false)
        }
    }

    /** AI 추천. 실패하거나 답 형식이 틀린 앱은 규칙 추천을 그대로 씀. */
    suspend fun byAi(ctx: Context, rules: List<AppRec>): List<AppRec> {
        val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel() || rules.isEmpty()) return rules
        val list = rules.take(MAX_APPS)
        val prompt = buildString {
            append(PROMPT)
            list.forEachIndexed { i, r ->
                append("${i + 1}. ${r.stat.app} (7일 ${r.stat.count}건)")
                if (r.stat.samples.isNotEmpty()) append(" 예: ${r.stat.samples.joinToString(" / ") { it.take(20) }}")
                append("\n")
            }
        }
        val answer = runCatching { summarizer.complete(prompt) }.getOrNull() ?: return rules
        val parsed = mutableMapOf<Int, Pair<Boolean, String>>()
        LINE.findAll(answer).forEach { m ->
            val idx = m.groupValues[1].toInt() - 1
            parsed[idx] = (m.groupValues[2] == "모으기") to m.groupValues[3].trim().take(30)
        }
        return rules.mapIndexed { i, r ->
            parsed[i]?.let { (keep, why) -> r.copy(keep = keep, reason = why.ifEmpty { r.reason }, byAi = true) } ?: r
        }
    }

    private const val MAX_APPS = 25
    private val LINE = Regex("""(?m)^\s*(\d+)\s*[|｜]\s*(모으기|빼기)\s*[|｜]\s*(.*)$""")
    private val PROMPT = """
        너는 교사의 알림 정리 도우미야. 아래는 최근 7일 동안 휴대폰에 알림을 보낸 앱 목록이야.
        학교 업무, 학부모·학생 연락, 학교 일정과 관련 있을 수 있는 앱은 '모으기',
        쇼핑·광고·게임·뉴스·금융·시스템처럼 관련 없는 앱은 '빼기'로 골라.
        앱마다 한 줄씩, 정확히 이 형식으로만 답해. 다른 말은 쓰지 마.
        번호|모으기 또는 빼기|이유(15자 이내)

        앱 목록:

    """.trimIndent()
}

/**
 * 중요 키워드 추천.
 * 1) 규칙: 교사 업무에 자주 나오는 단어가 최근 알림에 몇 번 나왔는지 세서 많은 순
 * 2) AI: 최근 알림 문장을 보고 놓치면 안 될 단어를 더 고름 (실제로 알림에 나온 단어만 남김)
 */
object KeywordAdvisor {
    private val CANDIDATES = listOf(
        "조퇴", "결석", "지각", "병원", "아파서", "상담", "면담", "준비물", "숙제", "과제", "마감", "제출", "기한",
        "회의", "협의", "공문", "보고", "결재", "체험학습", "현장학습", "동의서", "가정통신문", "급식", "알레르기",
        "방과후", "돌봄", "시험", "평가", "성적", "생활기록부", "출장", "연수", "학부모", "어머님", "아버님",
        "긴급", "확인 부탁", "회신", "변경", "취소", "안전", "사고", "다쳤", "민원",
    )

    /** 최근 알림에서 후보 단어가 나온 횟수 (많은 순, 최대 10개). */
    fun byRules(items: List<NotiItem>): List<Pair<String, Int>> =
        CANDIDATES.map { w -> w to items.count { w in it.text || w in it.title || w in it.room } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(10)

    suspend fun byAi(ctx: Context, items: List<NotiItem>, rules: List<Pair<String, Int>>): List<Pair<String, Int>> {
        val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel() || items.isEmpty()) return rules
        val sample = items.takeLast(40).joinToString("\n") { "- ${it.roomOrTitle}: ${it.text.replace('\n', ' ').take(60)}" }
        val answer = runCatching { summarizer.complete(PROMPT + sample) }.getOrNull() ?: return rules
        val aiWords = answer.lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("""^\d+[.)]\s*"""), "").trim('"', '\'', ' ') }
            .filter { it.length in 2..8 && it.count { c -> c == ' ' } <= 1 }
            .distinct()
        val counted = aiWords.map { w -> w to items.count { w in it.text || w in it.title || w in it.room } }
            .filter { it.second > 0 }   // 실제로 나온 단어만 (AI가 지어낸 단어 거르기)
        return (counted + rules).distinctBy { it.first }.sortedByDescending { it.second }.take(10)
    }

    private val PROMPT = """
        너는 교사의 알림 비서야. 아래는 최근 휴대폰에 온 알림이야.
        교사가 놓치면 안 되는 연락(학부모 요청, 출결, 마감, 회의, 안전 등)을 잡아낼 짧은 키워드를 6개 골라.
        키워드는 2~5글자 낱말로, 아래 알림에 실제로 나온 말이어야 해.
        한 줄에 키워드 하나만 써. 다른 말은 쓰지 마.

        알림:

    """.trimIndent()
}

/** 학부모 연락 한 건 (오늘 한 방에서 온 메시지 묶음). */
data class ParentContact(
    val pkg: String,
    val app: String,
    val room: String,
    val messages: List<NotiItem>,
    val category: ParentAdvisor.Category,
) {
    val last: NotiItem get() = messages.last()
    /** 답장함 표시에 쓰는 키: 마지막 메시지가 바뀌면 다시 '답장 필요' */
    val key: String get() = "$pkg|$room|${last.time}"
    /** 호칭: 방 이름으로 어머님/아버님 추정, 모르면 학부모님 */
    val honorific: String get() = when {
        "아버" in room -> "아버님"
        "어머" in room || "맘" in room -> "어머님"
        else -> "학부모님"
    }
}

/**
 * 학부모 지정·인식·답장 추천.
 * - 방 고르기에서 "학부모"로 지정한 방(패키지|방)의 연락만 학부모 연락으로 봄
 * - 방 이름에 어머님·아버님·학부모 등이 있으면 지정을 추천
 * - 메시지 내용으로 출결/상담/문의/확인·감사 분류 (규칙, 즉시)
 * - 답장 추천: AI가 정중한 답장 2~3개 (모델이 없으면 기본 문장)
 */
object ParentAdvisor {
    enum class Category(val label: String, val needsReply: Boolean) {
        ATTEND("출결", true), CONSULT("상담", true), ASK("문의", true), THANKS("확인·감사", false), OTHER("기타", true)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    // ── 학부모 방 지정 ("패키지|방") ──
    fun parentRooms(ctx: Context): Set<String> = prefs(ctx).getStringSet("parent_rooms", emptySet())!!.toSet()
    fun setParent(ctx: Context, pkg: String, room: String, on: Boolean) {
        val s = parentRooms(ctx).toMutableSet()
        if (on) s.add("$pkg|$room") else s.remove("$pkg|$room")
        prefs(ctx).edit().putStringSet("parent_rooms", s).apply()
    }
    fun isParent(set: Set<String>, pkg: String, room: String) = "$pkg|$room" in set

    private val PARENT_HINTS = listOf("어머님", "아버님", "어머니", "아버지", "학부모", "보호자", "맘)", "엄마")
    /** 방 이름만 보고 학부모 방일 것 같은지 (지정 추천용) */
    fun looksParent(room: String) = PARENT_HINTS.any { it in room }

    // ── 답장함 표시 ──
    fun handled(ctx: Context): Set<String> = prefs(ctx).getStringSet("handled", emptySet())!!.toSet()
    fun markHandled(ctx: Context, key: String) {
        // 오래된 키가 쌓이지 않게 최근 300개만
        val s = (handled(ctx) + key).toList().takeLast(300).toSet()
        prefs(ctx).edit().putStringSet("handled", s).apply()
    }

    // ── 분류 (규칙) ──
    private val ATTEND = listOf("조퇴", "결석", "지각", "병원", "아파", "아프", "열이", "감기", "체험학습", "늦게", "못 가", "못가", "쉬")
    private val CONSULT = listOf("상담", "면담", "통화", "여쭤", "말씀드릴", "뵙고", "걱정")
    private val ASK = listOf("?", "궁금", "문의", "언제", "어떻게", "될까요", "되나요", "인가요", "있나요", "맞나요", "알려")
    private val THANKS = listOf("감사", "고맙", "확인했", "알겠", "수고", "좋은 하루")

    fun classify(messages: List<NotiItem>): Category {
        val t = messages.takeLast(5).joinToString(" ") { it.text }
        return when {
            ATTEND.any { it in t } -> Category.ATTEND
            CONSULT.any { it in t } -> Category.CONSULT
            ASK.any { it in t } -> Category.ASK
            THANKS.any { it in t } -> Category.THANKS
            else -> Category.OTHER
        }
    }

    /** 오늘 알림에서 학부모 연락 묶기 (최근 연락 먼저). */
    fun contacts(items: List<NotiItem>, parentRooms: Set<String>): List<ParentContact> =
        items.filter { isParent(parentRooms, it.pkg, it.roomOrTitle) }
            .groupBy { it.pkg to it.roomOrTitle }
            .map { (k, list) -> ParentContact(k.first, list.first().app, k.second, list, classify(list)) }
            .sortedByDescending { it.last.time }

    // ── 답장 추천 ──
    fun templates(c: ParentContact): List<String> {
        val h = c.honorific
        return when (c.category) {
            Category.ATTEND -> listOf(
                "네 $h, 말씀 잘 받았습니다. 학교에서 잘 챙기겠습니다.",
                "네 확인했습니다. 아이가 얼른 나아지길 바랄게요.",
            )
            Category.CONSULT -> listOf(
                "네 $h, 상담 가능합니다. 편하신 요일과 시간을 알려 주시면 맞춰 보겠습니다.",
                "연락 주셔서 감사합니다. 내일 중으로 전화드려도 괜찮으실까요?",
            )
            Category.ASK -> listOf(
                "네 $h, 확인해 보고 다시 연락드리겠습니다.",
                "문의 주셔서 감사합니다. 알아본 뒤 오늘 중으로 답변드릴게요.",
            )
            Category.THANKS -> listOf("네 감사합니다 $h. 좋은 하루 보내세요 :)")
            Category.OTHER -> listOf("네 $h, 메시지 잘 받았습니다. 확인 후 연락드리겠습니다.")
        }
    }

    suspend fun drafts(ctx: Context, c: ParentContact): List<String> {
        val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel()) return templates(c)
        val conv = c.messages.takeLast(6).joinToString("\n") { "${it.title}: ${it.text.replace('\n', ' ').take(150)}" }
        val prompt = """
            너는 초등학교 담임교사야. 아래는 학부모가 보낸 메시지야 (분류: ${c.category.label}).
            교사가 보낼 답장을 3개 써. 각 답장은 1~2문장, 정중한 존댓말, 호칭은 '${c.honorific}'.
            약속하지 않은 일(날짜·시간·결정)을 지어내지 마. 한 줄에 답장 하나씩, 번호 없이 답장만 써.

            메시지:
            $conv
        """.trimIndent()
        val answer = runCatching { summarizer.complete(prompt) }.getOrNull() ?: return templates(c)
        val lines = answer.lines()
            .map { it.trim().trimStart('-', '*', '•').replace(Regex("""^\d+[.)]\s*"""), "").trim().trim('"') }
            .filter { it.length in 5..200 }
            .distinct()
            .take(3)
        return lines.ifEmpty { templates(c) }
    }
}
