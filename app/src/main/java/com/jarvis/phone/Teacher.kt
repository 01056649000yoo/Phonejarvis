package com.jarvis.phone

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

// ───────────────────────── 🧒 우리 반 명부 + 학생별 기록 ─────────────────────────

/** 학생 한 명에 대한 기록 한 줄 (원래 알림이 지워져도 1년 보관). */
data class StudentEntry(val student: String, val time: Long, val app: String, val room: String, val sender: String, val text: String)

object Students {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun names(ctx: Context): List<String> = prefs(ctx).getStringSet("students", emptySet())!!.sorted()

    /** "김민준, 이서윤 박지호" 처럼 여러 명을 한 번에 붙여 넣어도 됨. 추가된 이름 수를 돌려줌. */
    fun add(ctx: Context, raw: String): List<String> {
        val new = raw.split(Regex("""[,\s/·]+""")).map { it.trim() }.filter { it.length in 2..5 }
        val all = (names(ctx) + new).toSet()
        prefs(ctx).edit().putStringSet("students", all).apply()
        return new
    }
    fun remove(ctx: Context, name: String) {
        prefs(ctx).edit().putStringSet("students", names(ctx).toSet() - name).apply()
    }

    /** 메시지에서 찾을 말: 성까지 쓴 이름, 그리고 세 글자 이름이면 성 뺀 이름("민준이", "민준 어머니"). */
    fun terms(name: String): List<String> = listOf(name) + if (name.length == 3) listOf(name.substring(1)) else emptyList()
    fun allTerms(names: List<String>): List<String> = names.flatMap(::terms)

    /** 알림 한 건에 나온 학생들 */
    fun find(names: List<String>, n: NotiItem): List<String> {
        val hay = n.text + " " + n.title + " " + n.room
        return names.filter { name -> terms(name).any { it in hay } }
    }

    /** 알림을 학생별 기록에 남김 (수집할 때·이름 추가할 때) */
    fun log(ctx: Context, names: List<String>, n: NotiItem) {
        if (names.isEmpty()) return
        val db = NotificationDb.get(ctx)
        find(names, n).forEach { db.logStudent(it, n) }
    }
}

// ───────────────────────── 📌 마감·할 일 ─────────────────────────

data class Task(
    val id: Long = 0,
    val title: String,
    val room: String,
    /** 마감 시각. 시간이 없으면 그날 23:59 */
    val due: Long,
    val hasTime: Boolean,
    val sourceTime: Long,
    val done: Boolean = false,
    val dedupe: String = "$room|$title",
) {
    /** "오늘", "D-1", "지남" 같은 표시 */
    fun dLabel(now: Long = System.currentTimeMillis()): String {
        val d = TaskExtractor.daysBetween(now, due)
        return when {
            d < 0 || (d == 0 && hasTime && due < now) -> "지남"
            d == 0 -> "오늘"
            else -> "D-$d"
        }
    }
}

/**
 * 알림 문장에서 할 일·마감을 뽑음 (규칙, 즉시).
 * "내일까지 제출", "금요일까지", "3/15 마감", "3월 15일", "15일까지", "회의 14:30", "오후 3시", "준비물" 등.
 * 날짜를 알 수 없으면 만들지 않음 (준비물은 내일로).
 */
object TaskExtractor {
    private val TRIGGERS = listOf("까지", "마감", "제출", "준비물", "회의", "협의회", "연수", "가져", "챙겨", "행사", "시험", "회신")
    private val DAYS = mapOf('월' to Calendar.MONDAY, '화' to Calendar.TUESDAY, '수' to Calendar.WEDNESDAY,
        '목' to Calendar.THURSDAY, '금' to Calendar.FRIDAY, '토' to Calendar.SATURDAY, '일' to Calendar.SUNDAY)

    private val MD_KOR = Regex("""(\d{1,2})월\s*(\d{1,2})일""")
    private val MD_SLASH = Regex("""(?<![\d:])(\d{1,2})/(\d{1,2})(?![\d:])""")
    private val D_ONLY = Regex("""(?<![\d월])(\d{1,2})일\s*(?=까지|마감|에|\(|오전|오후)""")
    private val WEEKDAY = Regex("""(다음\s*주|담주|이번\s*주)?\s*([월화수목금토일])요일""")
    private val HHMM = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?!\d)""")
    private val KOR_TIME = Regex("""(오전|오후)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분|\s*(반))?""")

    fun extract(n: NotiItem): Task? {
        val t = n.text
        if (TRIGGERS.none { it in t }) return null
        val base = Calendar.getInstance().apply { timeInMillis = n.time }
        var date: Calendar? = null

        // 1) 날짜
        MD_KOR.find(t)?.let { m -> date = monthDay(base, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        if (date == null) MD_SLASH.find(t)?.let { m -> date = monthDay(base, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        if (date == null) D_ONLY.find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) date = monthDay(base, base.get(Calendar.MONTH) + 1, d)
        }
        if (date == null) WEEKDAY.find(t)?.let { m ->
            val target = DAYS[m.groupValues[2][0]] ?: return@let
            val c = startOfDay(base)
            var add = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (m.groupValues[1].replace(" ", "").let { it == "다음주" || it == "담주" }) add += if (add == 0) 7 else 7
            c.add(Calendar.DAY_OF_YEAR, add)
            date = c
        }
        if (date == null) when {
            "모레" in t -> date = startOfDay(base).apply { add(Calendar.DAY_OF_YEAR, 2) }
            "내일" in t -> date = startOfDay(base).apply { add(Calendar.DAY_OF_YEAR, 1) }
            "오늘" in t -> date = startOfDay(base)
        }

        // 2) 시각
        var hour = -1; var minute = 0
        HHMM.find(t)?.let { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (h in 0..23 && mi in 0..59) { hour = h; minute = mi }
        }
        if (hour < 0) KOR_TIME.find(t)?.let { m ->
            var h = m.groupValues[2].toInt()
            if (h !in 1..12) return@let
            val ampm = m.groupValues[1]
            if (ampm == "오후" && h < 12) h += 12
            if (ampm.isEmpty() && h in 1..6) h += 12          // 학교에서 "3시 회의"는 보통 오후
            hour = h
            minute = when { m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt(); m.groupValues[4].isNotEmpty() -> 30; else -> 0 }
        }

        // 3) 날짜가 없을 때: 시각만 있으면 오늘(지났으면 내일), 준비물은 내일, 그 밖에는 만들지 않음
        if (date == null) {
            date = when {
                hour >= 0 -> startOfDay(base).apply {
                    if (hour * 60 + minute <= base.get(Calendar.HOUR_OF_DAY) * 60 + base.get(Calendar.MINUTE)) add(Calendar.DAY_OF_YEAR, 1)
                }
                "준비물" in t || "가져" in t || "챙겨" in t -> startOfDay(base).apply { add(Calendar.DAY_OF_YEAR, 1) }
                else -> return null
            }
        }
        val due = date!!.apply {
            if (hour >= 0) { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
            else { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
        }.timeInMillis

        return Task(title = titleOf(t), room = n.roomOrTitle, due = due, hasTime = hour >= 0, sourceTime = n.time)
    }

    /** 할 일 제목: 트리거 단어가 있는 문장, 60자까지 */
    private fun titleOf(t: String): String {
        val sentences = t.replace('\n', ' ').split(Regex("""(?<=[.!?。])\s+"""))
        val s = sentences.firstOrNull { st -> TRIGGERS.any { it in st } } ?: t
        return s.trim().take(60)
    }

    private fun startOfDay(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** 월/일 → 날짜. 메시지보다 두 달 넘게 이전이면 내년으로 봄. */
    private fun monthDay(base: Calendar, month: Int, day: Int): Calendar? {
        if (month !in 1..12 || day !in 1..31) return null
        val c = startOfDay(base).apply { set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, day) }
        if (c.timeInMillis < base.timeInMillis - 60L * 24 * 3600 * 1000) c.add(Calendar.YEAR, 1)
        return c
    }

    /** 두 시각 사이의 날짜 차이 (달력 기준) */
    fun daysBetween(from: Long, to: Long): Int {
        val a = startOfDay(Calendar.getInstance().apply { timeInMillis = from }).timeInMillis
        val b = startOfDay(Calendar.getInstance().apply { timeInMillis = to }).timeInMillis
        return Math.round((b - a) / 86_400_000.0).toInt()
    }

    /** 알림에서 할 일을 뽑아 저장 (같은 방·같은 문장은 한 번만) */
    fun save(ctx: Context, n: NotiItem) { extract(n)?.let { NotificationDb.get(ctx).insertTask(it) } }
}

// ───────────────────────── 🏫 수업 중 모드 · 🌙 퇴근 모드 ─────────────────────────

/**
 * 정한 시간(수업 시간, 퇴근 후·주말)에는 모은 메시지 알림을 상태바에서 숨기고,
 * 방해 금지 권한이 있으면 소리까지 끔. 수업이 끝나면 "수업 중 알림 N건"을 한 번 알려 줌.
 */
object QuietMode {
    enum class State { NONE, CLASS, AFTER_WORK }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quiet", Context.MODE_PRIVATE)

    /** 초등 기본 시간표: 1~6교시 시작 시각 (분) */
    val DEFAULT_PERIODS = listOf(9 * 60, 9 * 60 + 50, 10 * 60 + 50, 11 * 60 + 40, 13 * 60 + 10, 14 * 60)

    fun classOn(ctx: Context) = prefs(ctx).getBoolean("class_on", false)
    fun periods(ctx: Context): List<Int> =
        prefs(ctx).getString("periods", null)?.split(",")?.mapNotNull { it.toIntOrNull() } ?: DEFAULT_PERIODS
    fun periodLen(ctx: Context) = prefs(ctx).getInt("period_len", 40)
    fun afterWorkOn(ctx: Context) = prefs(ctx).getBoolean("after_on", false)
    fun workStart(ctx: Context) = prefs(ctx).getInt("work_start", 8 * 60)
    fun workEnd(ctx: Context) = prefs(ctx).getInt("work_end", 17 * 60)
    fun weekendQuiet(ctx: Context) = prefs(ctx).getBoolean("weekend", true)
    /** 수업이 끝날 때 "수업 중 알림 N건" 알림 */
    fun hideInShade(ctx: Context) = prefs(ctx).getBoolean("hide", true)

    fun update(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(ctx).edit().apply(block).apply()
        tick(ctx)
    }
    fun setPeriods(ctx: Context, list: List<Int>) = update(ctx) { putString("periods", list.sorted().joinToString(",")) }

    private fun isWeekend(c: Calendar) = c.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
    private fun minuteOf(c: Calendar) = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)

    fun state(ctx: Context, now: Long = System.currentTimeMillis()): State {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val m = minuteOf(c)
        if (classOn(ctx) && !isWeekend(c) && periods(ctx).any { m >= it && m < it + periodLen(ctx) }) return State.CLASS
        if (afterWorkOn(ctx) && (isWeekend(c) && weekendQuiet(ctx) || (!isWeekend(c) && (m < workStart(ctx) || m >= workEnd(ctx)))))
            return State.AFTER_WORK
        return State.NONE
    }

    /** 이 시각이 퇴근 후(또는 주말)인지: 학부모 답장 추천에 씀 */
    fun isAfterWork(ctx: Context, time: Long): Boolean {
        if (!afterWorkOn(ctx)) return false
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val m = minuteOf(c)
        return if (isWeekend(c)) weekendQuiet(ctx) else m < workStart(ctx) || m >= workEnd(ctx)
    }

    /** 다음 경계 시각(수업 시작·끝, 출근·퇴근, 자정) */
    private fun nextBoundary(ctx: Context, now: Long): Long? {
        val marks = buildList {
            if (classOn(ctx)) periods(ctx).forEach { add(it); add(it + periodLen(ctx)) }
            if (afterWorkOn(ctx)) { add(workStart(ctx)); add(workEnd(ctx)); add(0) }
        }.distinct()
        if (marks.isEmpty()) return null
        val day = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (0..1).flatMap { d -> marks.map { day + d * 86_400_000L + it * 60_000L } }.filter { it > now + 1000 }.minOrNull()
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, QuietReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 지금 상태를 반영(방해 금지·수업 끝 알림)하고 다음 경계에 다시 깨어나도록 예약. 설정 변경·부팅·앱 열 때 호출. */
    fun tick(ctx: Context) {
        val p = prefs(ctx)
        val prev = runCatching { State.valueOf(p.getString("last", "NONE")!!) }.getOrDefault(State.NONE)
        val cur = state(ctx)
        if (prev != cur) onChange(ctx, prev, cur)

        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx))
        nextBoundary(ctx, System.currentTimeMillis())?.let { at ->
            if (BriefingScheduler.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        }
    }

    private fun onChange(ctx: Context, prev: State, cur: State) {
        val p = prefs(ctx)
        p.edit().putString("last", cur.name).apply {
            if (cur != State.NONE && prev == State.NONE) putLong("since", System.currentTimeMillis())
        }.apply()

        // 방해 금지: 권한이 있을 때만. 선생님이 직접 켠 방해 금지는 건드리지 않음.
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            if (cur != State.NONE && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                p.edit().putBoolean("we_set_dnd", true).apply()
            } else if (cur == State.NONE && p.getBoolean("we_set_dnd", false)) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                p.edit().putBoolean("we_set_dnd", false).apply()
            }
        }

        // 수업이 끝나면: 수업 중 모은 알림 수를 한 번 알려 줌 (AI 없이, 바로)
        if (prev == State.CLASS && cur != State.CLASS) {
            val since = p.getLong("since", System.currentTimeMillis() - periodLen(ctx) * 60_000L)
            val rules = AppFilter.rules(ctx)
            val items = NotificationDb.get(ctx).since(since).filter { rules.allows(it) }
            if (items.isNotEmpty()) {
                val parents = ParentAdvisor.contacts(items, ParentAdvisor.parentRooms(ctx))
                val rooms = items.map { it.roomOrTitle }.distinct()
                Notifications.ensureChannels(ctx)
                Notifications.notify(ctx, Notifications.ID_QUIET, Notifications.result(ctx,
                    "수업 중 알림 ${items.size}건" + if (parents.isNotEmpty()) " (학부모 ${parents.size})" else "",
                    rooms.take(4).joinToString(", ") + if (rooms.size > 4) " 외 ${rooms.size - 4}곳" else ""))
            }
            if (cur == State.NONE) p.edit().remove("since").apply()
        }
    }
}

/** 수업·퇴근 경계 시각에 깨어나 상태를 반영하고 다음 경계를 예약. */
class QuietReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = QuietMode.tick(ctx)
}
