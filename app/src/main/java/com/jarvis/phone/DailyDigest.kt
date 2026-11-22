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
    fun compose(briefings: List<Briefing>, taskPart: String): String {
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
                    head.startsWith("🙋") -> items.forEach { l -> l.substringBefore(" · ").let { k -> reply.remove(k); reply[k] = l } }
                    head.startsWith("💬") -> items.filterNot { it.startsWith("- 그 밖의 방") }.forEach { l ->
                        val k = l.substringBefore(" (")
                        val n = Regex("\\((\\d+)건\\)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val sum = (talk.remove(k)?.second ?: 0) + n
                        talk[k] = l.replaceFirst(Regex("\\(\\d+건\\)"), "(${sum}건)") to sum
                    }
                    head.startsWith("📦") -> items.forEach { l ->
                        Regex("^- (.+?) (\\d+)건").find(l)?.let { m ->
                            val (app, n) = m.destructured
                            apps[app] = (apps[app] ?: 0) + n.toInt()
                        }
                    }
                    head.startsWith("👪") || head.startsWith("💼") || head.startsWith("⭐") || head.startsWith("👥") ->
                        others.getOrPut(head.substringBefore(" (")) { LinkedHashSet() }.addAll(items)
                    // 🕘 구간·📌 마감(새로 만듦)·⏱ 시간·안내 줄은 넣지 않음
                }
            }
        }
        reply.keys.forEach { talk.remove(it) }
        val count = briefings.sumOf { it.count }
        return buildString {
            append("요약 ${briefings.size}번 · 알림 ${count}건을 합쳤어요.\n\n")
            if (taskPart.isNotBlank()) append(taskPart.trim()).append("\n\n")
            if (reply.isNotEmpty()) {
                append("🙋 답장할 연락 (${reply.size})\n")
                reply.values.reversed().forEach { append(it).append('\n') }
                append('\n')
            }
            others.filterKeys { it.startsWith("👪") }.forEach { (h, l) -> section(h, l.toList().takeLast(10)) }
            if (talk.isNotEmpty()) {
                append("💬 사람에게 온 메시지 (${talk.size}개 방)\n")
                talk.values.reversed().take(15).forEach { append(it.first).append('\n') }
                if (talk.size > 15) append("- 그 밖의 방 ${talk.size - 15}곳\n")
                append('\n')
            }
            others.filterKeys { !it.startsWith("👪") }.forEach { (h, l) -> section(h, l.toList().takeLast(10)) }
            if (apps.isNotEmpty()) {
                val top = apps.entries.sortedByDescending { it.value }
                append("📦 앱 알림 ${apps.values.sum()}건 · ")
                append(top.take(6).joinToString(", ") { "${it.key} ${it.value}" })
                if (top.size > 6) append(" 외 ${top.size - 6}개 앱")
                append('\n')
            }
        }.trim()
    }

    private fun StringBuilder.section(head: String, lines: List<String>) {
        if (lines.isEmpty()) return
        append("$head (${lines.size})\n")
        lines.forEach { append(it).append('\n') }
        append('\n')
    }

    /** 오늘 요약을 합쳐 저장하고 알림 내용을 돌려줌. 오늘 요약이 하나도 없으면 null */
    fun build(ctx: Context, now: Long = System.currentTimeMillis()): Briefings.Outcome? {
        val db = NotificationDb.get(ctx)
        val day = SummarySchedule.startOfDay(now)
        val today = db.briefings(300).filter { it.time >= day && !isDaily(it) }.sortedBy { it.time }
        if (today.isEmpty()) return null
        val text = compose(today, Briefings.taskSection(db.tasks()))
        db.insertBriefing(Briefing(time = now, fromTime = day, label = LABEL, count = 0, text = text))
        val d = SimpleDateFormat("M/d (E)", Locale.KOREA).format(now)
        return Briefings.Outcome("$LABEL · $d", text, now)
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
