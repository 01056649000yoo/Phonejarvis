package com.jarvis.phone

import java.util.Calendar

/**
 * ✅ 할 일 중심 요약 (2026-11-23 선생님: "좀 더 획기적이면서 요점만 딱 캐치할 방법" — 실험).
 * 방마다 요약하는 대신, 모든 방에서 '내가 할 일'과 '바뀐 것'만 뽑아 마감 순으로 한 장에.
 *  1) 앱이 분류(AI 없이): 부탁(공문 말투 포함)·바뀜(변경·취소·연기)·그 밖(안내·잡담은 건수만)
 *  2) 후보 = (보낸 사람, 방, 문장, 마감) — 이름·방·마감은 앱이 붙임(AI 가 사람을 바꿀 수 없음)
 *  3) AI 는 후보 번호를 골라 같은 일은 합치고 15자 안쪽으로 줄이기만(후보 20개씩 한 번)
 *  4) 화면: ✅ 내가 할 일(마감 순) · 📢 바뀐 것 · 💬 그 밖에(방별 건수)
 */
object ActionDigest {
    /** TASK 할 일 · CHANGE 바뀐 것 · EVENT 일정(시각·날짜가 있는 안내) · NOTE 1:1 연락 요점(할 일이 아닌 것) */
    enum class Kind { TASK, CHANGE, EVENT, NOTE }
    /** direct: 선생님을 직접 부르는 부탁(너는·선생님께서도·쌤…) — AI 가 빼도 꼭 넣음 */
    data class Cand(val kind: Kind, val who: String, val room: String, val text: String, val time: Long, val due: Due?, val direct: Boolean = false)
    private val DIRECT = Regex("(너는|너도|넌 |네가|니가|선생님(께서|께|이|도|은)|쌤|담임 ?선생님|각 반|선생님들)")
    /** 마감: 정렬용 날짜(그날 0시)와 보이는 말 */
    data class Due(val day: Long, val label: String)
    data class Item(val kind: Kind, val text: String, val who: String, val room: String, val due: Due?, val merged: Int)
    data class Result(val text: String, val items: List<Item>, val calls: Int)

    // 부탁(선생님이 할 일): 부탁 말투 + 공문 말투(…하시기 바랍니다·회신 바람·요망·마감·제출) + 1:1 물음
    private val REQUEST = Regex("(주세요|주시면|주실|주시기|부탁|알려 ?주|확인해|내 ?주|올려 ?주|보내 ?주|제출|참석해|도와주|바랍니다|바람|요망|하세요|해야 ?(합니다|해요|함)|회신|마감|챙겨 ?(오|와|주)|가져와|준비해|신청해|작성해|입력해|등록해|줄래|주라|해 ?줘|와 ?줘|사 ?와|갖다 ?줘|달래요|달라고|하래요|하라고|내래요|해야 ?돼|해야 ?해|챙겨 ?오세요)")
    // 일정 안내: 시각·날짜가 있고 '있습니다·예정·열립니다·입니다' 로 끝나는 안내(할 일·바뀐 것이 아닌 것)
    private val EVENT = Regex("(\\d+\\s*시|\\d+\\s*교시|\\d+\\s*월\\s*\\d+\\s*일).{0,40}(있습니다|있어요|예정|열립니다|합니다|입니다|해요|이에요|예요|이야|야|래요|거야)\\s*[.!~]*$")
    private val QUESTION_END = Regex("(\\?|？|까요|나요|가요|인지요|ㄹ까|건가요|되죠|맞죠)\\s*$")
    private val CHANGE = Regex("(변경|바뀌|바꿔|바뀐|취소|연기|당겨|미뤄|미루|늦춰|앞당|→|대신|으로 옮|장소가 .{0,6}로)")
    // 문장 나누기: 마침표·물음표뿐 아니라 마침표 없이 이어지는 카톡 말투('…주세요 반별…')도 부탁 끝말 뒤에서 나눔
    private val SENT = Regex("(?<=[?？.!])\\s+|(?<=(주세요|바랍니다|드려요|드립니다|합니다|해요|있습니다|입니다))\\s+(?=\\S)")
    private val WEEK = mapOf('월' to Calendar.MONDAY, '화' to Calendar.TUESDAY, '수' to Calendar.WEDNESDAY, '목' to Calendar.THURSDAY,
        '금' to Calendar.FRIDAY, '토' to Calendar.SATURDAY, '일' to Calendar.SUNDAY)
    private val WEEK_NAME = mapOf(Calendar.MONDAY to "월", Calendar.TUESDAY to "화", Calendar.WEDNESDAY to "수", Calendar.THURSDAY to "목",
        Calendar.FRIDAY to "금", Calendar.SATURDAY to "토", Calendar.SUNDAY to "일")

    private fun startOfDay(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }

    /** 마감 읽기(순수 — 시험): 오늘·내일·모레·이번/다음 주 ○요일·N월 N일·N일. 말한 날(sent) 기준 */
    fun parseDue(text: String, sent: Long): Due? {
        val base = startOfDay(sent)
        fun label(c: Calendar, word: String? = null): Due {
            val m = c.get(Calendar.MONTH) + 1; val d = c.get(Calendar.DAY_OF_MONTH)
            return Due(c.timeInMillis, (word ?: "") + (if (word != null) " " else "") + "$m/$d(${WEEK_NAME[c.get(Calendar.DAY_OF_WEEK)]})")
        }
        // 날짜가 여럿이면 '까지·마감' 바로 앞의 것이 마감(예: '12월 3일 수업 공개 지도안 11월 28일까지' → 11/28)
        val md = Regex("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
        (md.findAll(text).firstOrNull { text.substring(it.range.last + 1).trimStart().let { r -> r.startsWith("까지") || r.startsWith("마감") } } ?: md.find(text))?.let { m ->
            val c = (base.clone() as Calendar).apply { set(Calendar.MONTH, m.groupValues[1].toInt() - 1); set(Calendar.DAY_OF_MONTH, m.groupValues[2].toInt()) }
            if (c.timeInMillis < base.timeInMillis - 60L * 86_400_000) c.add(Calendar.YEAR, 1)
            return label(c)
        }
        when {
            "오늘" in text -> return label(base, "오늘")
            "내일" in text -> return label((base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }, "내일")
            "모레" in text -> return label((base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 2) }, "모레")
        }
        Regex("(다음 ?주|담주|이번 ?주)?\\s*([월화수목금토일])요일").find(text)?.let { m ->
            val want = WEEK[m.groupValues[2][0]]!!
            val c = base.clone() as Calendar
            var add = (want - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (m.groupValues[1].startsWith("다음") || m.groupValues[1].startsWith("담")) add += 7
            c.add(Calendar.DAY_OF_MONTH, add)
            return label(c)
        }
        Regex("(?<![\\d월])(\\d{1,2})\\s*일\\s*(까지|에|마감|오전|오후)").find(text)?.let { m ->
            val c = (base.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, m.groupValues[1].toInt()) }
            if (c.timeInMillis < base.timeInMillis) c.add(Calendar.MONTH, 1)
            return label(c)
        }
        return null
    }

    /** 문장 하나가 부탁·바뀜인가(순수 — 시험) */
    fun kindOf(sentence: String, oneToOne: Boolean): Kind? {
        val s = sentence.trim()
        if (s.length < 8 || BriefingComposer.isFiller(s)) return null
        // 바뀐 것은 무엇이 언제로 바뀌었는지가 있을 때만(잡담 '시간 바뀌면 알려 주세요'는 아님)
        if (CHANGE.containsMatchIn(s) && Regex("\\d+\\s*(시|교시|일|분)|요일|장소|취소|연기").containsMatchIn(s)) return Kind.CHANGE
        if (REQUEST.containsMatchIn(s)) return Kind.TASK
        if (oneToOne && QUESTION_END.containsMatchIn(s)) return Kind.TASK   // 1:1 물음은 답해야 할 일
        if (EVENT.containsMatchIn(s)) return Kind.EVENT
        return null
    }

    /** 후보 뽑기: 메시지마다 부탁·바뀜 문장 마지막 2개, 긴 문장은 끝을 살림, 같은 말은 한 번 */
    fun candidates(items: List<NotiItem>): List<Cand> = items.filterNot { BriefingComposer.isFiller(it.text) }.flatMap { n ->
        val room = n.roomOrTitle
        val oneToOne = n.room.isEmpty()
        n.text.replace('\n', ' ').split(SENT).map { it.trim() }.mapNotNull { s -> kindOf(s, oneToOne)?.let { k -> k to s } }
            .takeLast(2).map { (k, s) ->
                val t = if (s.length > 140) "… " + s.takeLast(140) else s
                Cand(k, n.title.trim(), room, t, n.time, parseDue(s, n.time), DIRECT.containsMatchIn(s))
            }
    }.distinctBy { it.text.replace(Regex("[\\d()\\[\\]\\s]|차 ?안내"), "").take(30) + it.who }

    fun prompt(cands: List<Cand>): String =
        "너는 교사인 사용자의 비서야. 아래는 사용자가 받은 메시지에서 앱이 뽑은 '할 일·바뀐 것' 후보야. 번호. [방] 보낸 사람: 문장\n" +
        cands.mapIndexed { i, c -> "${i + 1}. [${c.room}] ${c.who}: ${c.text}" }.joinToString("\n") + "\n" +
        "정확히 아래 꼴로만 답해. 다른 말, 꾸밈, 별표는 쓰지 마.\n" +
        "할일: 사용자(선생님)가 직접 해야 하는 일만. 같은 일을 여러 번 말한 후보는 번호를 +로 묶어. 줄마다 '번호(+번호)=15자 안쪽으로 줄인 말'.\n" +
        "바뀜: 일정·장소·시간이 바뀐 것만. 줄마다 '번호=15자 안쪽으로 줄인 말'(예: 상담 2시→4시).\n" +
        "없으면 그 칸은 '없음'.\n" +
        "예시:\n할일: 2+5=학예회 명단 올리기\n할일: 7=도시락 여부 답장\n바뀜: 4=상담 2시→4시"

    private val LINE = Regex("""(할\s*일|바뀜)\s*:\s*(.+)""")
    private val PICK = Regex("""([\d+\s]+)=\s*(.+)""")

    /** AI 답 → 할 일·바뀜(이름·방·마감은 후보에서). AI 가 쓴 줄인 말이 후보 원문과 너무 다르면 원문을 줄여 씀 */
    fun parse(reply: String, cands: List<Cand>): List<Item> {
        val out = mutableListOf<Item>()
        reply.replace("*", "").lines().forEach { raw ->
            val m = LINE.find(raw.trim()) ?: return@forEach
            val kind = if (m.groupValues[1].startsWith("바")) Kind.CHANGE else Kind.TASK
            m.groupValues[2].split(Regex(",\\s*(?=\\d)")).forEach { part ->
                val p = PICK.find(part.trim()) ?: return@forEach
                val idx = p.groupValues[1].split("+").mapNotNull { it.trim().toIntOrNull()?.minus(1) }.filter { it in cands.indices }
                if (idx.isEmpty()) return@forEach
                val group = idx.map { cands[it] }
                val first = group.first()
                var text = p.groupValues[2].trim().trimEnd('.', ',')
                // 번호 확인: 줄인 말이 그 후보 원문과 닮지 않았으면(번호를 잘못 적음) 원문을 줄여 씀
                if (group.none { BriefingComposer.overlapPublic(text, it.text) >= 0.3 }) text = shorten(first.text)
                else if (text.length > 30 || Regex("^(너는|너도|넌|선생님께서도)\\s").containsMatchIn(text)) text = shorten(text)   // AI 가 원문을 그대로 옮겼으면 앱이 줄임
                val due = group.mapNotNull { it.due }.minByOrNull { it.day }
                out += Item(kind, text, first.who, first.room, due, group.size)
            }
        }
        return out
    }

    /** 원문을 AI 없이 짧게: 물음·부탁 끝말을 바꾸고 40자 안쪽에서 낱말 단위로 자름 */
    fun shorten(text: String): String {
        var s = text.removePrefix("… ").trim().replace(Regex("\\(\\s*\\d+\\s*차\\s*안내\\s*\\)|\\[\\s*\\d+\\s*번째 안내\\s*]"), "").trim().replace(Regex("^(너는|너도|넌|네가|니가|선생님께서도|선생님께서는|선생님도)\\s+"), "")
        // 공문 끝말: '…하시기 바랍니다' → '…하라고 함', '꼭 …바랍니다' 도
        s = s.replace(Regex("하시기\\s*바랍니다[.!]*$"), "하라고 함").replace(Regex("(\\S+)\\s*바랍니다[.!]*$"), "$1 하라고 함")
        // 쉼표 앞 사정 설명은 떼고 본론만('회사 일 때문에, 4시로 바꿔도 될까요' → 뒤만) — 남는 게 10자 넘을 때
        s.lastIndexOf(", ").takeIf { it > 0 && s.length - it > 12 && Regex("(때문에|해서|라서|는데|어서)\\s*$").containsMatchIn(s.substring(0, it)) }?.let { s = s.substring(it + 2) }
        // 마감은 앞의 [날짜]에 있으니 본문에서는 뺌
        s = s.replace(Regex("\\s*(\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|(이번 ?주|다음 ?주)?\\s*[월화수목금토일]요일|내일|오늘|모레)\\s*까지"), "").trim()
        val c = BriefingComposer.condense(s)
        if (c.length <= 40) return c
        val cut = c.take(40); val sp = cut.lastIndexOf(' ')
        return (if (sp > 20) cut.take(sp) else cut) + "…"
    }

    /** 규칙만으로(AI 없음·AI 답을 못 읽음): 후보를 줄여 쓰고 같은 일은 합침 */
    fun byRules(cands: List<Cand>): List<Item> = cands.fold(mutableListOf<Item>()) { acc, c ->
        val short = shorten(c.text)
        val same = acc.indexOfFirst { it.kind == c.kind && BriefingComposer.overlapPublic(short, it.text) >= 0.5 }
        if (same >= 0) acc[same] = acc[same].copy(merged = acc[same].merged + 1, due = listOfNotNull(acc[same].due, c.due).minByOrNull { it.day })
        else acc += Item(c.kind, short, c.who, c.room, c.due, 1)
        acc
    }

    suspend fun compose(items: List<NotiItem>, ask: suspend (String) -> String): Result {
        val people = items.filter { BriefingComposer.isPerson(it) }
        // 1:1 은 앞뒤 맥락이 중요해 예전처럼 대화째 요약(채점 20/21) → 부탁이 있으면 할 일, 없으면 요점(NOTE). 단톡방만 할 일 뽑기
        val (ones, groups) = people.partition { it.room.isEmpty() }
        val oneItems = mutableListOf<Item>(); var oneCalls = 0
        for (conv in BriefingComposer.conversations(ones)) {
            val r = BriefingComposer.direct(conv) ?: run { oneCalls++; BriefingComposer.parse(conv, ask(BriefingComposer.prompt(conv))) }
            if (r.point.isBlank() && r.ask.isBlank()) continue
            // 1:1 마감은 '까지·마감·오늘·내일'이 든 말에서만('안 되면 목요일도 괜찮아요'의 목요일은 마감이 아님)
            val due = conv.items.asReversed().filter { Regex("까지|마감|오늘|내일").containsMatchIn(it.text) }.firstNotNullOfOrNull { parseDue(it.text, it.time) }
            val ask1 = shorten(r.ask); val point1 = shorten(r.point)
            // 요점이 부탁과 다르면 괄호로 맥락을 붙임('도시락 싸야 하는지 물음 (내일 체험학습)')
            val withCtx = if (r.point.isNotBlank() && BriefingComposer.overlapPublic(point1, ask1) < 0.5 && BriefingComposer.overlapPublic(ask1, point1) < 0.5) "$ask1 ($point1)" else ask1
            oneItems += if (r.ask.isNotBlank()) Item(Kind.TASK, withCtx, conv.room, conv.room, due, 1)
                        else Item(Kind.NOTE, shorten(r.point), conv.room, conv.room, null, 1)
        }
        val cands = candidates(groups)
        var calls = oneCalls
        val found: List<Item> = cands.filter { it.kind != Kind.EVENT }.chunked(20).flatMap { batch ->
            calls++
            val r = runCatching { parse(ask(prompt(batch)), batch) }.getOrDefault(emptyList())
            r.ifEmpty { byRules(batch) }
        }
        // 🔒 꼭 넣을 것(AI 가 빼도): 1:1 로 온 물음·부탁(답해야 함), 마감이 있는 부탁, 바뀐 것 — AI 는 줄이고 합치기만(2026-11-23 채점: AI 가 고르면 학부모 물음을 뺌)
        val covered = { c: Cand -> found.any { it.kind == c.kind && it.who == c.who && (BriefingComposer.overlapPublic(it.text, c.text) >= 0.3 || BriefingComposer.overlapPublic(shorten(c.text), it.text) >= 0.5) } }
        val must = cands.filter { c -> c.kind != Kind.EVENT && (c.room == c.who || c.due != null || c.direct || c.kind == Kind.CHANGE) && !covered(c) }
        val events = byRules(cands.filter { it.kind == Kind.EVENT })
        val all = oneItems + found + byRules(must) + events
        // 묶음(20개)끼리도 같은 일은 한 번
        val merged = all.fold(mutableListOf<Item>()) { acc, it ->
            val same = acc.indexOfFirst { o -> o.kind == it.kind && (BriefingComposer.overlapPublic(it.text, o.text) >= 0.6 ||
                (o.who == it.who && o.room == it.room && (o.due == null || it.due == null) && maxOf(BriefingComposer.overlapPublic(it.text, o.text), BriefingComposer.overlapPublic(o.text, it.text)) >= 0.4)) }
            if (same >= 0) acc[same] = acc[same].copy(merged = acc[same].merged + it.merged) else acc += it
            acc
        }
        // 같은 사람의 같은 일이 할 일에도 있으면 바뀐 것에서는 뺌(예: '상담 4시로 바꿔도 될까요' — 답할 일이 먼저)
        val final = merged.filterNot { c -> c.kind == Kind.CHANGE && c.room == c.who && merged.any { t -> t.kind == Kind.TASK && t.who == c.who &&
            (BriefingComposer.overlapPublic(c.text, t.text) >= 0.4 || BriefingComposer.overlapPublic(t.text, c.text) >= 0.4) } }
        return Result(render(final, people, cands), final, calls)
    }

    fun render(list: List<Item>, people: List<NotiItem>, cands: List<Cand>): String = buildString {
        val tasks = list.filter { it.kind == Kind.TASK }.sortedWith(compareBy<Item> { it.due?.day ?: Long.MAX_VALUE })
        val changes = list.filter { it.kind == Kind.CHANGE }
        if (tasks.isNotEmpty()) {
            append("✅ 내가 할 일 (${tasks.size})\n")
            tasks.forEach { t ->
                append("- ${t.due?.let { "[${it.label}] " } ?: ""}${t.text} — ${t.who}${if (t.room != t.who) " · ${t.room}" else ""}${if (t.merged > 1) " (같은 안내 ${t.merged}번)" else ""}\n")
            }
            append("\n")
        }
        val events = list.filter { it.kind == Kind.EVENT }.sortedWith(compareBy<Item> { it.due?.day ?: Long.MAX_VALUE })
        if (events.isNotEmpty()) {
            append("📅 일정 (${events.size})\n")
            events.forEach { e -> append("- ${e.text} — ${e.who}${if (e.room != e.who) " · ${e.room}" else ""}\n") }
            append("\n")
        }
        val notes = list.filter { it.kind == Kind.NOTE }
        if (notes.isNotEmpty()) {
            append("💬 연락 (${notes.size})\n")
            notes.forEach { n -> append("- ${n.who}: ${n.text}\n") }
            append("\n")
        }
        if (changes.isNotEmpty()) {
            append("📢 바뀐 것 (${changes.size})\n")
            changes.forEach { c -> append("- ${c.text} — ${c.who}${if (c.room != c.who) " · ${c.room}" else ""}\n") }
            append("\n")
        }
        val byRoom = people.groupBy { it.roomOrTitle }.map { (room, l) -> "$room ${l.size}건" }
        if (byRoom.isNotEmpty()) append("💬 그 밖에: " + byRoom.joinToString(", ") + "\n")
    }.trimEnd()
}
