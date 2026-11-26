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
    fun compose(briefings: List<Briefing>, taskPart: String, replied: Map<String, Long> = emptyMap()): String = merge(briefings, taskPart, replied).first

    /** 합친 글과 앱별 건수(모든 앱). 글의 📦 줄은 앞 6개 앱만 적으므로 건수는 여기서 바로 받는다 */
    private fun merge(briefings: List<Briefing>, taskPart: String, replied: Map<String, Long> = emptyMap()): Pair<String, Map<String, Int>> {
        val reply = LinkedHashMap<String, String>()      // 방 → 줄 (최근 것이 이김)
        val talk = LinkedHashMap<String, Pair<String, Int>>()   // 방 → (줄, 건수 합)
        val others = linkedMapOf<String, LinkedHashMap<String, Pair<String, Int>>>()   // 칸 머리 → (사람·앱 → (줄, 건수 합))
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
                            l.removePrefix("- 🙋 ").substringBefore(" · ").let { k ->
                                reply.remove(k)
                                // ✔ 이 요약 뒤에 내가 그 방에 답했으면 답할 연락에서 내림(2026-11-24)
                                if ((replied[k] ?: 0L) > b.time) talk[k] = ("- " + l.removePrefix("- 🙋 ") + " · ✔ 답함") to 1 else reply[k] = l
                            }
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
                    head.startsWith("👪") || head.startsWith("💼") || head.startsWith("⭐") || head.startsWith("👥") || " 연락 (" in head -> {
                        val sec = others.getOrPut(head.substringBefore(" (")) { LinkedHashMap() }
                        // 👥 사람·💼 앱 줄 '- 이름 (N건) · 시각: …' 은 사람·앱마다 하나로(최근 줄, 건수 합) — 요약마다 건수·시각이 달라
                        // 같은 사람이 여러 번 쌓이던 것(2026-11-24 점검). 건수 없는 줄(⭐ 등)은 같은 줄만 한 번
                        items.forEach { l ->
                            val n = Regex("^- .+? \\((\\d+)건\\)").find(l)?.groupValues?.get(1)?.toIntOrNull()
                            if (n == null) { sec.remove(l); sec[l] = l to 0; return@forEach }
                            val k = l.substringBefore(" (")
                            val sum = (sec.remove(k)?.second ?: 0) + n
                            sec[k] = l.replaceFirst(Regex("\\(\\d+건\\)"), "(${sum}건)") to sum
                        }
                    }
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
            others.entries.sortedBy { rank(it.key) }.forEach { (h, l) -> section(h, l.values.map { it.first }.takeLast(10)) }
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
    data class Material(val lines: List<Line>, val apps: Map<String, Int>, val briefings: Int, val count: Int,
                        /** 그날 요약들이 덮은 가장 이른 시각(월요일 아침 요약은 주말부터 덮음) */ val from: Long = 0L)

    const val MAX_LINES = 20
    private val RANK_CAP = mapOf(1 to 8, 2 to 8)   // 1·2순위가 많아도 3순위 자리가 조금은 남게

    /** 그날 요약본들 → 순위 매긴 줄들(같은 방·같은 줄은 한 번, 최근 것이 이김) */
    fun material(briefings: List<Briefing>, taskPart: String, students: List<String>, replied: Map<String, Long> = emptyMap()): Material {
        // 위의 규칙 합치기로 먼저 중복을 없앰. 앱 건수는 모든 앱을 더한 값(📦 줄은 6개 앱만 적어 '23건'처럼 적게 나오던 것, 2026-11-23)
        val (merged, apps) = merge(briefings, "", replied)
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
        return Material(lines, apps, briefings.size, briefings.sumOf { it.count }, briefings.minOfOrNull { it.fromTime } ?: 0L)
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
        // 그날 첫 요약이 전날(주말)부터 덮었으면 어디서부터인지 밝힘 — 월요일 '오늘 있었던 일'에 주말 일이 섞여 보이던 것(2026-11-24 점검)
        val since = if (now != null && m.from in 1 until SummarySchedule.startOfDay(now))
            " (${SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA).format(m.from)}부터)" else ""
        return ("요약 ${m.briefings}번 · 알림 ${m.count}건${since}을 중요한 것부터 정리했어요.\n\n" + body +
            (if (tail.isNotEmpty()) "\n\n" + tail.joinToString("\n") else "")) to (ai != null)
    }

    /** 오늘 요약을 정리해 저장하고 알림 내용을 돌려줌. 오늘 요약이 하나도 없으면 null */
    suspend fun build(ctx: Context, ask: (suspend (String) -> String)?, now: Long = System.currentTimeMillis()): Briefings.Outcome? {
        val db = NotificationDb.get(ctx)
        val day = SummarySchedule.startOfDay(now)
        val today = db.briefings(300).filter { it.time >= day && !isDaily(it) }.sortedBy { it.time }
        if (today.isEmpty()) return null
        val d = SimpleDateFormat("M/d (E)", Locale.KOREA).format(now)
        val entries = db.roomDays(day)
        // 🧭 '나에게 한 말' 가중치 다듬기는 밤에 폰을 안 쓸 때 저절로 돎(BriefingScheduler → BriefingService 의 learn).
        //    여기서는 이틀 밤 내리 못 돌았을 때만 예비로(폰을 밤새 쓰거나 꺼 두는 경우)
        val learned = runCatching { ToMe.learn(ctx, now, ask, minGapHours = 44) }.getOrDefault(0)
        val learnNote = if (learned > 0) "\n\n(🧭 오늘 대화 ${learned}건으로 '나에게 한 말' 알아보기를 다듬었어요 — 쓸수록 정확해져요)" else ""
        // 🗓 총정리(방별 하루 기록이 있는 날) — 예전 판에서 요약한 날은 아래 예전 방식으로
        if (entries.isNotEmpty()) {
            val t = total(Day(entries, keywordLines(today), "",   // 📌 마감 칸은 없앰(2026-11-25 선생님)
                today.size, today.sumOf { it.count },
                today.minOf { it.fromTime }), ReplyLog.all(ctx), now, ask)
            // 💡 쓰면서 쌓인 기록으로 추천(놓치기 싫은 방·건수만 볼 방·꼭 받을 사람) — 권하기만 하고 적용은 눌러서
            val recs = runCatching { ToMe.recommend(ctx, now) }.getOrDefault("")
            db.insertBriefing(Briefing(time = now, fromTime = day, label = LABEL, count = 0, text = t.full + (if (recs.isNotEmpty()) "\n\n$recs" else "") + learnNote))
            return Briefings.Outcome("$LABEL · $d" + if (t.byAi) "" else " (AI 없이)", t.short, now)
        }
        val m = material(today, "", Students.names(ctx), ReplyLog.all(ctx))
        val (text, byAi) = write(m, now, ask)
        db.insertBriefing(Briefing(time = now, fromTime = day, label = LABEL, count = 0, text = text))
        return Briefings.Outcome("$LABEL · $d" + if (byAi) "" else " (AI 없이)", text, now)
    }

    // ───────── 🗓 총정리 (2026-11-24 선생님: "하루 종일 온 알림을 전체적으로 총정리 — 하루 500개를 가정") ─────────
    //  1층 ✨ 오늘의 핵심(AI 5줄) · 📅 내일   ← 알림창에는 여기와 🙋 까지
    //  2층 🙋 답할 것(상한 없이 모두) · 📌 마감 · ⭐ 키워드 · 👥 묶음 · 💼 업무 · 💬 방별 하루(내용 있는 방 모두, 나머지는 이름·건수) · 📦 앱 건수
    //  3층 방마다 구간별 요약(화면에서 펼쳐 봄 — '  · ' 줄)
    // 원문은 다시 읽지 않는다(폰 AI 작업 공간 4096): 낮 동안 요약하며 쌓은 방별 하루 기록(RoomDay)만 모은다.

    data class Day(val entries: List<RoomDay.Entry>, val keywords: List<String>, val taskPart: String,
                   val briefings: Int, val count: Int, val from: Long = 0L)
    /** full: 저장·화면용 전체, short: 알림창용(1층 + 🙋), byAi: 핵심을 AI 가 썼는가 */
    data class Total(val full: String, val short: String, val byAi: Boolean)

    const val DETAIL = "  · "          // 3층 구간별 요약 줄의 머리(화면에서 접어 둠)
    const val MAX_SEGS = 3             // 방별 하루 한 줄에 넣는 조각
    const val MAX_PICK_ROOMS = 12      // 조각이 많은 방 중 AI 로 고르는 방(건수 많은 순) — 나머지는 규칙으로
    const val PICK_DEADLINE_MS = 4 * 60_000L
    const val MAX_OTHER_NAMES = 12     // 하루 정리의 '그 밖의 방' 이름(건수 많은 순) — 30곳이면 그 줄만 500자
    private const val CORE_TOKENS = 2000   // 핵심 질문에 넣는 줄의 토큰 어림 상한(폰 작업 공간 4096)

    /** 그날 요약들의 ⭐ 키워드 줄(같은 줄은 한 번, 최근 것부터 10개) */
    fun keywordLines(briefings: List<Briefing>): List<String> = briefings.flatMap { b ->
        b.text.split(Regex("\\n\\s*\\n")).filter { it.trimStart().startsWith("⭐") }
            .flatMap { blk -> blk.lines().filter { it.startsWith("- ") } }
    }.distinct().takeLast(10).reversed()

    private val SPEAKER = Regex("^([^:/]{1,24}): ")
    /** 방의 구간 요약들 → 조각('보낸 사람: 말'). 같은 사람이 이어져 이름을 뗀 조각은 앞 이름을 다시 붙임 */
    fun segments(parts: List<RoomDay.Part>): List<String> = parts.flatMap { p ->
        val segs = p.point.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
        var who: String? = SPEAKER.find(segs.firstOrNull().orEmpty())?.groupValues?.get(1)
        segs.map { s ->
            val w = SPEAKER.find(s)?.groupValues?.get(1)
            if (w != null) { who = w; s } else if (who != null) "$who: $s" else s
        }
    }.map { BriefingComposer.clip(it, 70) }.distinct()

    private val KEEP = Regex("(까지|오늘|내일|모레|[월화수목금토일]요일|\\d{1,2}일|\\d{1,2}시|\\d{1,2}:\\d{2}|\\d+교시|\\?|주세요|부탁)")
    /** AI 없이 고르기: 날짜·시간·부탁이 든 조각(최근 것)과 마지막 조각, 3개까지 시간 순서로 */
    fun rulePick(segs: List<String>): List<Int> {
        if (segs.size <= MAX_SEGS) return segs.indices.toList()
        val keep = segs.indices.filter { KEEP.containsMatchIn(segs[it]) }.takeLast(MAX_SEGS - 1)
        return (keep + segs.lastIndex).distinct().sorted().takeLast(MAX_SEGS)
    }

    fun pickPrompt(room: String, segs: List<String>): String =
        "너는 교사인 사용자의 비서야. '$room'에서 오늘 하루 나온 말을 정리해 둔 줄들이야.\n" +
        segs.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n") + "\n" +
        "오늘 이 방에서 있었던 일을 가장 잘 보여 주는 줄 번호를 ${MAX_SEGS}개까지 쉼표로만 답해. 다른 말은 쓰지 마. " +
        "약속·결정·날짜·시간·장소·부탁이 든 줄을 먼저, 맞장구·감탄은 고르지 마.\n예시: 2, 5"

    /** AI 답 → 고른 번호(시간 순서). 못 읽으면 null(규칙으로) */
    fun parsePick(reply: String, size: Int): List<Int>? = Regex("\\d{1,3}").findAll(reply)
        .mapNotNull { it.value.toIntOrNull()?.minus(1) }.filter { it in 0 until size }.distinct().take(MAX_SEGS).toList()
        .sorted().ifEmpty { null }

    /** 고른 조각을 한 줄로 — 바로 앞과 같은 사람이면 이름은 한 번 */
    fun joinSegs(segs: List<String>): String {
        var last: String? = null
        return segs.joinToString(" / ") { s ->
            val w = SPEAKER.find(s)?.groupValues?.get(1)
            (if (w != null && w == last) s.removePrefix("$w: ") else s).also { last = w }
        }
    }

    /** 이 방에 아직 답하지 않은 🙋 (내가 그 방에 답한 시각보다 뒤에 온 것) */
    fun openAsks(e: RoomDay.Entry, replied: Map<String, Long>): List<RoomDay.Ask> {
        val at = replied[e.room] ?: 0L
        return e.asks.filter { it.time >= at }
    }

    /** 방별 하루 줄에 쓸 구간: 아직 답할 🙋 이 있으면 그 내용(🙋 칸에 있음)은 빼고 */
    fun lineParts(e: RoomDay.Entry, replied: Map<String, Long>): List<RoomDay.Part> =
        if (openAsks(e, replied).isEmpty()) e.parts else e.parts.filterNot { it.asked }

    /**
     * 하루 정리에서 이 방을 어느 칸에 놓을지와 그 줄의 재료(순수 — 시험).
     * 방의 거리는 하루 중 가장 가까웠던 때를 따르는데(RoomDay.merge), 예전엔 그 방의 하루치 구간을 모두 '나에게 온 대화'에 넣었다 —
     * 큰 단톡방에서 내가 한마디 했거나 한 번 불렸을 뿐인데 그 방의 하루 잡담 전체가 구간별 요약까지 달고 맨 위 칸을 차지했다.
     * 이제 구간마다 적어 둔 거리로: 나에게 온 구간이 남아 있으면 그 구간만 '나에게 온 대화'에, 없으면(이미 🙋 로 올라갔거나) 나머지를 '내가 속한 방' 한 줄로.
     * '그 밖'이었던 구간은 줄을 쓰지 않음. 거리를 모르는 예전 기록은 방의 거리 그대로
     */
    fun place(e: RoomDay.Entry, replied: Map<String, Long>): Pair<Int, List<RoomDay.Part>> {
        val parts = lineParts(e, replied)
        if (e.kind != RoomDay.TALK || parts.none { it.level != 0 }) return e.level to parts
        fun lv(p: RoomDay.Part) = if (p.level == 0) e.level else p.level
        val shown = parts.filter { lv(it) != Relevance.OTHER }
        val best = shown.minOfOrNull(::lv) ?: return (if (e.level == Relevance.OTHER) Relevance.OTHER else Relevance.JOINED) to emptyList()
        return best to shown.filter { lv(it) == best }
    }

    /** 재료 → 총정리 글. [ask] 가 null 이면 AI 없이(핵심 칸 없이 방별로만) */
    suspend fun total(d: Day, replied: Map<String, Long>, now: Long, ask: (suspend (String) -> String)?): Total {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val span = SimpleDateFormat("HH:mm", Locale.KOREA)
        val apps = d.entries.filter { it.kind == RoomDay.APP }
        val rooms = d.entries.filter { it.kind != RoomDay.APP }
        // 방마다 하루 한 줄: 조각이 많은 방은 AI 가 고르고(건수 많은 순, 시간 안에서), 나머지는 규칙
        val placed = rooms.associate { it.key to place(it, replied) }
        fun lvOf(e: RoomDay.Entry) = placed[e.key]?.first ?: e.level
        val segsOf = rooms.associate { it.key to segments(placed[it.key]!!.second) }
        // AI 로 고르는 방은 가까운 방(나에게 → 내가 속한 방)부터, 같으면 건수 많은 순. 그 밖의 방은 줄을 쓰지 않음
        val needPick = rooms.filter { lvOf(it) != Relevance.OTHER && (segsOf[it.key]?.size ?: 0) > MAX_SEGS }
            .sortedWith(compareBy<RoomDay.Entry> { lvOf(it) }.thenByDescending { it.count })
        val picks = mutableMapOf<String, List<Int>>()
        var usedAi = false
        if (ask != null) {
            val until = System.currentTimeMillis() + PICK_DEADLINE_MS
            for (e in needPick.take(MAX_PICK_ROOMS)) {
                if (System.currentTimeMillis() > until) break
                val segs = segsOf[e.key]!!
                runCatching { parsePick(ask(pickPrompt(e.room, segs)), segs.size) }.getOrNull()?.let { picks[e.key] = it; usedAi = true }
            }
        }
        fun line(e: RoomDay.Entry): String? {
            if (lvOf(e) == Relevance.OTHER) return null   // 🎯 그 밖의 방은 이름과 건수만
            val segs = segsOf[e.key].orEmpty()
            if (segs.isEmpty()) return null
            val idx = picks[e.key] ?: rulePick(segs)
            val done = if (e.asks.isNotEmpty() && openAsks(e, replied).isEmpty()) " · ✔ 답함" else ""
            return joinSegs(idx.map { segs[it] }) + done
        }
        /** 3층: 구간이 둘 넘는 방은 구간별 요약을 접어 둠 */
        fun details(e: RoomDay.Entry): List<String> = placed[e.key]!!.second.takeIf { it.size >= 2 }.orEmpty()
            .map { p -> "$DETAIL${span.format(p.from)}~${span.format(p.to)} · ${p.count}건: ${BriefingComposer.clip(p.point, 160)}" }

        // 🙋 답할 것 — 상한 없이 모두(최근 것부터)
        val asks = rooms.flatMap { e -> openAsks(e, replied).map { e to it } }.sortedByDescending { it.second.time }
        val askLines = asks.map { (e, a) -> "- ${e.room} · ${fmt.format(a.time)}: ${a.text}" }

        // 🎯 나와의 거리(2026-11-25 선생님: 나와 관련된 것만): 나에게 온 대화(내용 + 구간별 요약) · 내가 속한 방(한 줄) · 그 밖의 방(이름·건수)
        val talk = rooms.filter { it.kind == RoomDay.TALK }
        val talkLines = talk.mapNotNull { e -> line(e)?.let { e to it } }
            .sortedWith(compareBy<Pair<RoomDay.Entry, String>> { lvOf(it.first) }.thenByDescending { it.first.count })
        val meLines = talkLines.filter { lvOf(it.first) == Relevance.ME }
        // 🗞 하루 정리는 나와 관련된 것만(2026-11-26 선생님): 내가 속한 방은 줄을 쓰지 않고 이름·건수만
        val shownKeys = meLines.map { it.first.key }.toSet() + asks.map { it.first.key }
        val joinedRooms = talk.filter { it.key !in shownKeys && lvOf(it) == Relevance.JOINED }.sortedByDescending { it.count }
        val others = talk.filter { it.key !in shownKeys && lvOf(it) != Relevance.JOINED }.sortedByDescending { it.count }

        val body = StringBuilder()
        fun section(head: String, lines: List<String>) {
            if (lines.isEmpty()) return
            body.append(head).append('\n'); lines.forEach { body.append(it).append('\n') }; body.append('\n')
        }
        // 👥 묶음 · 💼 업무
        val grouped = rooms.filter { it.kind == RoomDay.GROUP || it.kind == RoomDay.WORK }
        val groupBlocks = grouped.groupBy { it.app }.entries
            .sortedBy { if (it.key == BriefingComposer.WORK_TITLE) 1 else 0 }
            .map { (title, list) ->
                val shown = list.mapNotNull { e -> line(e)?.let { e to it } }.sortedByDescending { it.first.last }
                val rest = list.filter { e -> shown.none { it.first.key == e.key } && asks.none { it.first.key == e.key } }
                val n = list.sumOf { it.count }
                val head = if (title == BriefingComposer.WORK_TITLE) "$title (앱 ${list.size}개 · ${n}건)" else "$title 연락 (${list.size}명 · ${n}건)"
                head to (shown.flatMap { (e, l) -> listOf("- ${e.room} (${e.count}건) · ${fmt.format(e.last)}: $l") + details(e) } +
                    (if (rest.isNotEmpty()) listOf("- 그 밖에: " + rest.joinToString(", ") { "${it.room} ${it.count}건" }) else emptyList()))
            }

        // ✨ 오늘의 핵심 · 📅 내일 — AI 한 번
        var core: Pair<List<String>, List<String>>? = null
        if (ask != null) {
            val material = coreMaterial(asks.map { (e, a) -> "[답할 것] ${e.room}: ${a.text}" }, d.keywords.map { "[키워드] " + it.removePrefix("- ") },
                groupBlocks.flatMap { (h, ls) -> ls.filter { it.startsWith("- ") && !it.startsWith("- 그 밖에") }.map { "[${h.substringBefore(" (")}] " + it.removePrefix("- ") } },
                meLines.map { (e, l) -> "[대화] ${e.room} (${e.count}건): $l" })
            if (material.isNotEmpty()) core = runCatching { parseCore(ask(corePrompt(material)), material.joinToString(" ")) }.getOrNull()
            if (core != null) usedAi = true
        }

        val since = if (d.from in 1 until SummarySchedule.startOfDay(now)) " (${SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA).format(d.from)}부터)" else ""
        val meRooms = (meLines.map { it.first.key } + asks.filter { it.first.kind == RoomDay.TALK }.map { it.first.key }).distinct().size
        val intro = "요약 ${d.briefings}번 · 알림 ${d.count}건${since}을 정리했어요. 대화방 ${talk.size}곳 중 " +
            "나에게 온 대화 ${meRooms}곳" + (if (joinedRooms.isNotEmpty()) " · 내가 속한 방 ${joinedRooms.size}곳" else "") + " · 그 밖의 방 ${others.size}곳."
        val top = StringBuilder()
        core?.let { (c, t) ->
            if (c.isNotEmpty()) { top.append("✨ 오늘의 핵심\n"); c.forEach { top.append(it).append('\n') }; top.append('\n') }
            if (t.isNotEmpty()) { top.append("📅 내일 챙길 것\n"); t.forEach { top.append(it).append('\n') }; top.append('\n') }
        }
        val askBlock = if (askLines.isEmpty()) "" else "🙋 답할 것 (${askLines.size})\n" + askLines.joinToString("\n") + "\n\n"

        if (d.taskPart.isNotBlank()) body.append(d.taskPart.trim()).append("\n\n")
        section("⭐ 키워드 알림 (${d.keywords.size})", d.keywords)
        groupBlocks.forEach { (h, ls) -> section(h, ls) }
        section("💬 나에게 온 대화 (${meLines.size}곳 · ${meLines.sumOf { it.first.count }}건)",
            meLines.flatMap { (e, l) -> listOf("- ${e.room} (${e.count}건): $l") + details(e) })
        fun names(l: List<RoomDay.Entry>) = if (l.isEmpty()) emptyList() else listOf("- " + l.take(MAX_OTHER_NAMES).joinToString(", ") { "${it.room} ${it.count}건" } +
            (if (l.size > MAX_OTHER_NAMES) " 외 ${l.size - MAX_OTHER_NAMES}곳" else ""))
        section("💬 내가 속한 방 (${joinedRooms.size}곳 · ${joinedRooms.sumOf { it.count }}건)", names(joinedRooms))   // 이름·건수만
        section("💬 그 밖의 방 (${others.size}곳 · ${others.sumOf { it.count }}건)", names(others))
        if (apps.isNotEmpty()) {
            val top6 = apps.sortedByDescending { it.count }
            body.append("📦 앱 알림 ${apps.sumOf { it.count }}건 · " + top6.take(8).joinToString(", ") { "${it.app} ${it.count}" } +
                (if (top6.size > 8) " 외 ${top6.size - 8}개 앱" else "")).append("\n\n")
        }
        val noAi = if (ask == null) "(🌡️ 폰 상태 때문에 AI 없이 정리했어요 — 핵심 줄 없이 방별로만)\n\n" else ""
        val full = (intro + "\n\n" + top + askBlock + body + noAi).trim()
        val hint = "(📱 눌러서 열면 " + listOfNotNull(
            if (meLines.isNotEmpty()) "나에게 온 대화 ${meLines.size}곳" else null,
            if (joinedRooms.isNotEmpty()) "내가 속한 방 ${joinedRooms.size}곳" else null,
            if (groupBlocks.isNotEmpty()) "묶음·업무" else null,
            if (apps.isNotEmpty()) "앱 알림" else null,
        ).joinToString(" · ").ifEmpty { "전체" } + "까지 모두 볼 수 있어요)"
        val short = (intro + "\n\n" + top + askBlock + (if (top.isEmpty() && askBlock.isEmpty()) "오늘은 따로 답할 연락이 없어요.\n\n" else "") + hint).trim()
        return Total(full, short, usedAi)
    }

    /** 핵심 질문에 넣는 줄: 답할 것 → 키워드 → 묶음·업무 → 대화(중요·건수 순), 토큰 어림 상한까지 */
    fun coreMaterial(vararg groups: List<String>): List<String> {
        val out = mutableListOf<String>(); var tokens = 0
        for (l in groups.flatMap { it }.map { BriefingComposer.clip(it, 120) }) {
            val t = BriefingComposer.estTokens(l) + 2
            if (tokens + t > CORE_TOKENS) break
            out += l; tokens += t
        }
        return out
    }

    fun corePrompt(material: List<String>): String = """
        너는 교사인 사용자의 비서야. 아래는 오늘 사용자 폰에 온 알림을 방마다 정리해 둔 줄들이야. 위에 있는 줄일수록 중요해.
        ---
        ${material.joinToString("\n        ")}
        ---
        위 줄들만 보고 아래 두 칸으로 답해. 줄에 없는 내용은 지어내지 마. 날짜·시간·숫자·이름은 그대로 옮겨. 별표·꾸밈·다른 말은 쓰지 마.
        한 사람의 말을 다른 사람 말처럼 쓰지 말고, 서로 다른 이야기는 다른 줄에 써. 줄에 없는 감정·까닭은 붙이지 마.
        ✨ 오늘의 핵심: 오늘 하루에서 꼭 알아야 할 일을 중요한 것부터 5줄까지. 각 줄은 '- 이름: 내용' 꼴로 40자 안쪽.
        📅 내일 챙길 것: 줄에 '내일'이라고 적힌 것만 2줄까지, 없으면 '- 없음'
    """.trimIndent()

    /** AI 답 → (핵심 5줄, 내일 2줄). 원문에 없는 숫자·감정이 든 줄은 버림. 핵심을 못 읽으면 null */
    fun parseCore(reply: String, source: String): Pair<List<String>, List<String>>? {
        val core = mutableListOf<String>(); val tomorrow = mutableListOf<String>()
        var cur: MutableList<String>? = null
        reply.replace("*", "").lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
            when {
                l.startsWith("✨") || l.contains("오늘의 핵심") -> { cur = core; l.substringAfter(":", "").trim().takeIf { it.isNotEmpty() }?.let { core += it } }
                l.startsWith("📅") || l.contains("내일 챙길") -> { cur = tomorrow; l.substringAfter(":", "").trim().takeIf { it.isNotEmpty() }?.let { tomorrow += it } }
                l.startsWith("-") -> cur?.add(l.removePrefix("-").trim())
            }
        }
        fun clean(ls: List<String>, cap: Int) = ls.map { it.removePrefix("- ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("없음") && BriefingComposer.grounded(it, source) }
            .map { "- " + BriefingComposer.soften(it) }.distinct().take(cap)
        val c = clean(core, 5)
        if (c.isEmpty()) return null
        return c to clean(tomorrow, 2).filter { "내일" in it || "내일" in source }
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
