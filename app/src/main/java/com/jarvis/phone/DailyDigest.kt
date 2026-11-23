package com.jarvis.phone

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 🗓 하루 정리 (2026-11-22 선생님: "매일 요약본을 바탕으로 저녁 10시 또는 지정된 시간에 일일 브리핑을 한 번 정리해서 자동으로").
 * 그날 나온 요약들을 AI 없이 합친다(폰에 무리 없게, 몇 초):
 *  - 🙋 답장할 연락·💬 사람 메시지는 대화방마다 가장 최근 줄 하나로(💬 건수는 더함), 답장할 방은 💬 에서 뺌
 *  - 👪 학부모·💼 업무·⭐ 키워드·👥 내 묶음은 같은 줄을 한 번만, 📦 앱 알림은 앱별 건수를 더함
 *  - 📌 마감은 그때 할 일 목록에서 새로(내일 마감 미리 보기)
 * 정한 시각 전에 아직 요약 안 한 알림이 있으면 먼저 요약한 뒤 합친다(BriefingService).
 * 저장은 요약 모음(briefing 표)에 '🗓 하루 정리'로 — 다음 요약이 어디부터인지(lastBriefing)에는 넣지 않는다.
 */
object DailyDigest {
    const val LABEL = "🗓 하루 정리"
    const val DEFAULT_MINUTE = 22 * 60

    fun isDaily(b: Briefing) = b.label == LABEL

    /** 요약 글들 → 하루 정리 글. [briefings] 는 그날 요약(오래된 것부터), [taskPart] 는 📌 마감 칸(없으면 "") */
    fun compose(briefings: List<Briefing>, taskPart: String): String = merge(briefings, taskPart).first

    /** 합친 글과 앱별 건수(모든 앱). 글의 📦 줄은 앞 6개 앱만 적으므로 건수는 여기서 바로 받는다 */
    private fun merge(briefings: List<Briefing>, taskPart: String): Pair<String, Map<String, Int>> {
        val reply = LinkedHashMap<String, String>()      // 방 → 줄 (최근 것이 이김)
        val talk = LinkedHashMap<String, Pair<String, Int>>()   // 방 → (줄, 건수 합)
        val others = linkedMapOf<String, LinkedHashSet<String>>()   // 칸 머리 → 줄들
        val apps = LinkedHashMap<String, Int>()
        for (b in briefings) {
            for (block in b.text.trim().split(Regex("\\n\\s*\\n"))) {
                val lines = block.lines().filter { it.isNotBlank() }
                val head = lines.firstOrNull() ?: continue
                val items = lines.drop(1).filter { it.startsWith("- ") }
                when {
                    // 예전 판은 🙋 칸이 따로, 2026-11-23 부터는 💬 대화 요약 안에 '- 🙋 방 · 시각: …' 줄
                    head.startsWith("🙋") -> items.forEach { l -> l.substringBefore(" · ").removePrefix("- ").let { k -> reply.remove(k); reply[k] = "- 🙋 " + l.removePrefix("- ") } }
                    head.startsWith("💬") -> {
                        items.filter { it.startsWith("- 🙋 ") }.forEach { l ->
                            l.removePrefix("- 🙋 ").substringBefore(" · ").let { k -> reply.remove(k); reply[k] = l }
                        }
                        items.filterNot { it.startsWith("- 그 밖의 방") || it.startsWith("- 🙋 ") }.forEach { l ->
                            val k = l.substringBefore(" (")
                            val n = Regex("\\((\\d+)건\\)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                            val sum = (talk.remove(k)?.second ?: 0) + n
                            talk[k] = l.replaceFirst(Regex("\\(\\d+건\\)"), "(${sum}건)") to sum
                        }
                    }
                    head.startsWith("📦") -> items.forEach { l ->
                        Regex("^- (.+?) (\\d+)건").find(l)?.let { m ->
                            val (app, n) = m.destructured
                            apps[app] = (apps[app] ?: 0) + n.toInt()
                        }
                    }
                    head.startsWith("👪") || head.startsWith("💼") || head.startsWith("⭐") || head.startsWith("👥") || " 연락 (" in head ->
                        others.getOrPut(head.substringBefore(" (")) { LinkedHashSet() }.addAll(items)
                    // 🕘 구간·📌 마감(새로 만듦)·⏱ 시간·안내 줄은 넣지 않음
                }
            }
        }
        reply.keys.forEach { k -> talk.remove("- $k") }
        val count = briefings.sumOf { it.count }
        return buildString {
            append("요약 ${briefings.size}번 · 알림 ${count}건을 합쳤어요.\n\n")
            if (taskPart.isNotBlank()) append(taskPart.trim()).append("\n\n")
            // 읽는 순서(2026-11-23): 👥 사람 묶음(예전 👪 포함) → ⭐ 키워드 → 💼 업무·일정 → 💬 대화 요약(🙋 먼저) → 📦 앱 알림
            fun rank(h: String) = when { h.startsWith("⭐") -> 1; h.startsWith("💼") -> 2; else -> 0 }
            others.entries.sortedBy { rank(it.key) }.forEach { (h, l) -> section(h, l.toList().takeLast(10)) }
            if (reply.isNotEmpty() || talk.isNotEmpty()) {
                append("💬 대화 요약 (${reply.size + talk.size}개 방" + (if (reply.isNotEmpty()) " · 🙋 답장 ${reply.size}" else "") + ")\n")
                reply.values.reversed().forEach { append(it).append('\n') }
                talk.values.reversed().take(15).forEach { append(it.first).append('\n') }
                if (talk.size > 15) append("- 그 밖의 방 ${talk.size - 15}곳\n")
                append('\n')
            }
            if (apps.isNotEmpty()) {
                val top = apps.entries.sortedByDescending { it.value }
                append("📦 앱 알림 ${apps.values.sum()}건 · ")
                append(top.take(6).joinToString(", ") { "${it.key} ${it.value}" })
                if (top.size > 6) append(" 외 ${top.size - 6}개 앱")
                append('\n')
            }
        }.trim() to apps
    }

    private fun StringBuilder.section(head: String, lines: List<String>) {
        if (lines.isEmpty()) return
        append("$head (${lines.size})\n")
        lines.forEach { append(it).append('\n') }
        append('\n')
    }

    // ───────── AI 하루 정리 (2026-11-22 선생님: 길어지지 않게, 우선순위로 거르고 AI가 정리) ─────────

    /** 하루 정리 재료 한 줄. rank 1 = ⭐ 키워드·💼 업무·일정, 2 = 학생·학부모·마감·답장할 연락·사람 묶음, 3 = 그 밖의 대화방 */
    data class Line(val rank: Int, val tag: String, val text: String, val weight: Int = 0)
    data class Material(val lines: List<Line>, val apps: Map<String, Int>, val briefings: Int, val count: Int)

    const val MAX_LINES = 20
    private val RANK_CAP = mapOf(1 to 8, 2 to 8)   // 1·2순위가 많아도 3순위 자리가 조금은 남게

    /** 그날 요약본들 → 순위 매긴 줄들(같은 방·같은 줄은 한 번, 최근 것이 이김) */
    fun material(briefings: List<Briefing>, taskPart: String, students: List<String>): Material {
        // 위의 규칙 합치기로 먼저 중복을 없앰. 앱 건수는 모든 앱을 더한 값(📦 줄은 6개 앱만 적어 '23건'처럼 적게 나오던 것, 2026-11-23)
        val (merged, apps) = merge(briefings, "")
        val lines = mutableListOf<Line>()
        fun body(l: String) = l.removePrefix("- ").trim()
        fun hasStudent(l: String) = students.any { it.length >= 2 && it in l }
        // 예전 요약에 이미 들어간 업체 줄(토스·OO은행·Google …)은 하루 정리에서 뺌 — '오늘 있었던 일'을 차지하던 것(2026-11-23)
        fun business(l: String) = AdFilter.isBusinessName(whoOf(l))
        for (block in merged.split(Regex("\\n\\s*\\n"))) {
            val ls = block.lines().filter { it.isNotBlank() }
            val head = ls.firstOrNull() ?: continue
            val chat = head.startsWith("💬") || head.startsWith("🙋")   // ⭐·💼 는 선생님이 고른 것이라 그대로
            val items = ls.filter { it.startsWith("- ") && !it.startsWith("- 그 밖의 방") && !(chat && business(it)) }
            when {
                head.startsWith("⭐") -> items.forEach { lines += Line(1, "⭐ 키워드", body(it)) }
                head.startsWith("💼") -> items.forEach { lines += Line(1, "💼 업무·일정", body(it)) }
                head.startsWith("🙋") -> items.forEach { lines += Line(2, "🙋 답장할 연락", body(it)) }
                head.startsWith("👪") -> items.forEach { lines += Line(2, "👪 학부모", body(it)) }
                head.startsWith("👥") -> items.forEach { lines += Line(2, head.substringBefore(" ("), body(it)) }
                // 👥 내 묶음(🏠 가족 연락 (2명 · 5건) 처럼 묶음 이모지로 시작)
                " 연락 (" in head && !head.startsWith("💬") -> items.forEach { lines += Line(2, head.substringBefore(" ("), body(it)) }
                head.startsWith("💬") -> items.forEach { l ->
                    // 2026-11-23 부터 🙋 답장할 연락은 💬 대화 요약 안의 '- 🙋 …' 줄
                    if (l.startsWith("- 🙋 ")) { lines += Line(2, "🙋 답장할 연락", body(l).removePrefix("🙋 ")); return@forEach }
                    val n = Regex("\\((\\d+)건\\)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    lines += if (hasStudent(l)) Line(2, "🧒 학생", body(l), n) else Line(3, "💬 대화", body(l), n)
                }
            }
        }
        // 📌 마감은 2순위 맨 뒤에 3줄까지 — 할 일이 많은 날 답장할 연락·학부모 자리를 밀어내지 않게
        taskPart.lines().filter { it.startsWith("- ") }.take(3).forEach { lines += Line(2, "📌 마감", body(it)) }
        return Material(lines, apps, briefings.size, briefings.sumOf { it.count })
    }

    /** 순위대로 MAX_LINES 줄까지(1·2순위는 각 8줄까지, 3순위는 건수 많은 방부터, 남는 자리는 다시 1·2순위로) */
    fun pick(lines: List<Line>): List<Line> {
        val byRank = lines.groupBy { it.rank }.mapValues { (r, l) -> if (r == 3) l.sortedByDescending { it.weight } else l }
        val first = (1..3).flatMap { r -> byRank[r].orEmpty().let { l -> RANK_CAP[r]?.let { l.take(it) } ?: l } }.take(MAX_LINES)
        val rest = (1..2).flatMap { r -> byRank[r].orEmpty() }.filterNot { it in first }
        return (first + rest).take(MAX_LINES).sortedBy { it.rank }
    }

    /** [now] 는 'HH:mm' — 밤에 받는 정리라 이미 지난 일정을 '오늘 꼭 챙길 일'에 넣지 않게(2026-11-23) */
    fun prompt(picked: List<Line>, now: String? = null): String = """
        너는 교사인 사용자의 비서야. 아래는 오늘 사용자 폰에 온 알림을 미리 요약해 둔 줄들이야. [1]이 가장 중요하고 [3]이 덜 중요해.
        ---
        ${picked.joinToString("\n        ") { "[${it.rank}] ${it.tag}: ${it.text}" }}
        ---
        위 줄들만 보고 오늘 하루를 아래 네 칸으로 정리해. 줄에 없는 내용은 지어내지 마. 날짜·시간·숫자·이름은 그대로 옮겨.
        [1] 줄을 먼저 봐. 한 사람·한 일은 네 칸 중 한 곳에만 써. 각 줄은 '- 이름: 내용'(시각이 있으면 '- 이름 · 시각: 내용') 꼴로 40자 안쪽. 별표·꾸밈·다른 말은 쓰지 마.
        캘린더 이름·'동기화' 같은 앱 표시는 내용에 넣지 마.${if (now != null) "\n        지금은 $now 이야. 이미 지난 시각의 일정은 ✅ 가 아니라 📝 에 써." else ""}
        ✅ 오늘 꼭 챙길 일: 아직 안 끝난 일만 3줄까지, 없으면 '- 없음'
        🙋 답장할 연락: 무엇을 답해 달라는지 적어('~관련'으로 끝내지 마) 5줄까지, 없으면 '- 없음'
        📝 오늘 있었던 일: 시각 순서로 5줄까지
        📅 내일 챙길 것: 줄에 '내일'이라고 적힌 것만 2줄까지, 없으면 '- 없음'
    """.trimIndent()

    private val HEADS = listOf("✅" to 3, "🙋" to 5, "📝" to 5, "📅" to 2)
    private val TIME = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")

    /** 줄의 말한 사람/방: '- 🙋 김지우맘 · 08:30: …' → 김지우맘, '- 6학년 동학년방 (3건): …' → 6학년 동학년방. 이름 꼴이 아니면 "" */
    fun whoOf(line: String): String {
        val s = line.removePrefix("- ").removePrefix("🙋 ").trim()
        val cut = listOf(s.indexOf(" · "), s.indexOf(" ("), s.indexOf(": ")).filter { it > 0 }.minOrNull() ?: return ""
        return s.substring(0, cut).trim()
    }

    /** 같은 사람인지 비교하는 열쇠: 첫 낱말('권지혜 선생님' → 권지혜) */
    private fun nameKey(line: String): String? = whoOf(line).substringBefore(' ').takeIf { it.length >= 2 }

    private fun minuteOf(line: String): Int? = TIME.find(line)?.destructured?.let { (h, m) ->
        h.toInt().takeIf { it < 24 }?.let { it * 60 + m.toInt() } }

    /**
     * AI 답 → 칸마다 줄 수를 지켜 다시 씀. 칸이 둘도 안 나오면 null(규칙 정리로).
     * 2026-11-23 하루 정리에서 AI 가 규칙을 안 지킨 것을 앱이 바로잡음:
     *  - 한 사람이 여러 칸에 나옴(김진수 🙋·📝, 권지혜 🙋·📅) → ✅ > 🙋 > 📅 > 📝 순으로 한 곳에만
     *  - 📝 시각이 뒤섞임(11:25 다음 11:01) → 시각 순으로
     *  - 밤 정리인데 ✅ 에 이미 지난 오전 일정 → [nowMinute] 보다 이른 시각은 📝 로
     */
    fun parseAi(reply: String, nowMinute: Int? = null): String? {
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur: String? = null
        reply.replace("*", "").lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
            val h = HEADS.firstOrNull { l.startsWith(it.first) }
            if (h != null) { cur = l.substringBefore(":").trim(); out.getOrPut(cur!!) { mutableListOf() }
                l.substringAfter(":", "").trim().takeIf { it.isNotEmpty() && it != "없음" }?.let { out[cur]!! += "- " + it.removePrefix("- ") } }
            else if (cur != null && l.startsWith("-")) { val t = l.removePrefix("-").trim(); if (t.isNotEmpty() && t != "없음") out[cur]!! += "- $t" }
        }
        if (out.size < 2) return null
        fun sec(e: String) = out.keys.firstOrNull { it.startsWith(e) }
        // 지난 시각의 ✅ → 📝
        if (nowMinute != null) sec("✅")?.let { done ->
            val past = out[done]!!.filter { l -> "내일" !in l && minuteOf(l)?.let { it < nowMinute } == true }
            if (past.isNotEmpty()) {
                out[done]!!.removeAll(past)
                val log = sec("📝") ?: "📝 오늘 있었던 일".also { out[it] = mutableListOf() }
                out[log]!!.addAll(0, past)
            }
        }
        // 한 사람은 한 칸에만: 앞 칸이 이김
        val seen = mutableSetOf<String>()
        listOf("✅", "🙋", "📅", "📝").mapNotNull { sec(it) }.forEach { h ->
            out[h] = out[h]!!.filter { l -> nameKey(l)?.let { seen.add(it) } ?: true }.toMutableList()
        }
        sec("📝")?.let { h -> out[h] = out[h]!!.sortedBy { minuteOf(it) ?: Int.MAX_VALUE }.toMutableList() }
        // 칸 순서는 늘 ✅ 🙋 📝 📅
        val order = HEADS.mapNotNull { (e, _) -> sec(e) }
        return buildString {
            order.forEach { h ->
                val ls = out[h]!!
                val cap = HEADS.first { h.startsWith(it.first) }.second
                val keep = ls.map { BriefingComposer.soften(it) }.distinct().take(cap)
                if (keep.isNotEmpty()) { append(h).append('\n'); keep.forEach { append(it).append('\n') }; append('\n') }
            }
        }.trim().ifEmpty { null }
    }

    /** AI 없이(발열·배터리·실패): 고른 줄을 순위별로 */
    fun plain(picked: List<Line>): String = buildString {
        val names = mapOf(1 to "⭐ 중요 키워드·업무·일정", 2 to "🙋 학생·학부모·마감·답장할 연락", 3 to "💬 대화방")
        picked.groupBy { it.rank }.toSortedMap().forEach { (r, ls) ->
            append(names[r]).append('\n'); ls.forEach { append("- ${it.text}").append('\n') }; append('\n')
        }
    }.trim()

    fun appsLine(apps: Map<String, Int>): String = if (apps.isEmpty()) "" else
        "📦 그 밖에 앱 알림 ${apps.values.sum()}건 · " + apps.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key} ${it.value}" }

    /** 재료 → 하루 정리 글. ask 가 null 이면 AI 없이 */
    suspend fun write(m: Material, now: Long? = null, ask: (suspend (String) -> String)? = null): Pair<String, Boolean> {
        val picked = pick(m.lines)
        val hm = now?.let { SimpleDateFormat("HH:mm", Locale.KOREA).format(it) }
        val nowMinute = hm?.let { minuteOf(it) }
        val ai = if (ask != null && picked.isNotEmpty()) runCatching { parseAi(ask(prompt(picked, hm)), nowMinute) }.getOrNull() else null
        val left = m.lines.size - picked.size
        val body = (ai ?: plain(picked)).ifBlank { "오늘은 따로 챙길 알림이 없었어요." }
        val tail = listOfNotNull(appsLine(m.apps).ifEmpty { null },
            if (left > 0) "(덜 중요한 ${left}줄은 뺐어요 · 📚 지난 요약에 그날 요약이 모두 있어요)" else null)
        return ("요약 ${m.briefings}번 · 알림 ${m.count}건을 중요한 것부터 정리했어요.\n\n" + body +
            (if (tail.isNotEmpty()) "\n\n" + tail.joinToString("\n") else "")) to (ai != null)
    }

    /** 오늘 요약을 정리해 저장하고 알림 내용을 돌려줌. 오늘 요약이 하나도 없으면 null */
    suspend fun build(ctx: Context, ask: (suspend (String) -> String)?, now: Long = System.currentTimeMillis()): Briefings.Outcome? {
        val db = NotificationDb.get(ctx)
        val day = SummarySchedule.startOfDay(now)
        val today = db.briefings(300).filter { it.time >= day && !isDaily(it) }.sortedBy { it.time }
        if (today.isEmpty()) return null
        val m = material(today, Briefings.taskSection(db.tasks()), Students.names(ctx))
        val (text, byAi) = write(m, now, ask)
        db.insertBriefing(Briefing(time = now, fromTime = day, label = LABEL, count = 0, text = text))
        val d = SimpleDateFormat("M/d (E)", Locale.KOREA).format(now)
        return Briefings.Outcome("$LABEL · $d" + if (byAi) "" else " (AI 없이)", text, now)
    }

    /** 다음 하루 정리 시각. 끄면 null. 모아서 알림의 '주말·공휴일엔 쉬기'를 켰으면 쉬는 날은 건너뜀 */
    fun next(now: Long, cfg: SummarySchedule.Config, isRest: (Long) -> Boolean): Long? {
        if (!cfg.dailyOn) return null
        val start = SummarySchedule.startOfDay(now)
        for (d in 0..10) {
            val dayStart = SummarySchedule.addDays(start, d)
            if (cfg.digest && cfg.restSkip && isRest(dayStart)) continue
            val at = dayStart + cfg.dailyMinute * 60_000L
            if (at > now + 5_000) return at
        }
        return null
    }
}
