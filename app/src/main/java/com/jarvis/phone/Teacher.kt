package com.jarvis.phone

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

// ───────────────────────── 🧒 우리 반 명부 + 학생별 기록 ─────────────────────────

/** 학생 한 명에 대한 기록 한 줄 (원래 알림이 지워져도 남음, 기본 1년 · 저장 공간 설정). */
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

    /**
     * 메시지에서 찾을 말: 성까지 쓴 이름, 그리고 세 글자 이름이면 성 뺀 이름("민준이", "민준 어머니").
     * 성 뺀 이름은 앞에 [GIVEN] 표시 — 낱말 안에 든 것은 이름으로 보지 않음(2026-11-24 점검: '지우'가 '지우개'에, '하나'가 '하나도'에 걸려
     * ⭐ 키워드·🙋 답장할 연락·학생 기록에 엉뚱한 알림이 들어감). 흔한 낱말과 같은 이름([COMMON])은 성까지 쓴 이름으로만 찾음.
     */
    const val GIVEN = '\u0001'
    fun terms(name: String): List<String> = listOf(name) +
        if (name.length == 3 && name.substring(1) !in COMMON) listOf(GIVEN + name.substring(1)) else emptyList()
    fun allTerms(names: List<String>): List<String> = names.flatMap(::terms)

    /** 흔한 낱말과 같은 두 글자 이름 */
    private val COMMON = setOf("하나", "나라", "보람", "슬기", "사랑", "가을", "바다", "하늘", "이슬", "한결", "다솜", "소망", "마루", "나래",
        "지혜", "미소", "노을", "우리", "기쁨", "은혜", "봄이", "여름", "겨울", "새봄", "한별", "아름", "다운", "고운", "진주", "보배", "구슬")
    /** 이름 뒤에 오는 말(민준이·민준이가·민준 어머니·민준맘·민준 학생…) — 이것도 아니고 한글이 이어지면 낱말의 일부 */
    private val AFTER = listOf("이", "가", "는", "은", "을", "를", "도", "의", "와", "과", "하고", "랑","한테", "에게", "네", "맘", "엄마", "아빠", "어머니", "어머님", "아버지", "아버님", "학생", "군", "양", "님")
    private fun hangul(c: Char) = c in '가'..'힣'

    /** [term] 이 [text] 에 나오는가 — 성 뺀 이름([GIVEN])은 낱말 안에 든 것은 빼고 */
    fun occurs(term: String, text: String): Boolean {
        if (term.isEmpty()) return false
        if (term[0] != GIVEN) return term in text
        val g = term.substring(1)
        var i = text.indexOf(g)
        while (i >= 0) {
            val before = text.getOrNull(i - 1); val rest = text.substring(i + g.length)
            if ((before == null || !hangul(before)) && (rest.isEmpty() || !hangul(rest[0]) || AFTER.any { rest.startsWith(it) })) return true
            i = text.indexOf(g, i + 1)
        }
        return false
    }

    /** 알림 한 건에 나온 학생들 */
    fun find(names: List<String>, n: NotiItem): List<String> {
        val hay = n.text + " " + n.title + " " + n.room
        return names.filter { name -> terms(name).any { occurs(it, hay) } }
    }

    /** 알림을 학생별 기록에 남김 (수집할 때·이름 추가할 때) */
    fun log(ctx: Context, names: List<String>, n: NotiItem) {
        if (names.isEmpty()) return
        val db = NotificationDb.get(ctx)
        // 이름이 나온 학생 + 학부모 명부로 연결된 학생 (이름이 없어도 "민준 어머님"의 연락이면 민준 기록에)
        val linked = ParentBook.find(ParentBook.links(ctx), n)?.student
        (find(names, n) + listOfNotNull(linked)).distinct().forEach { db.logStudent(it, n) }
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
    /** 수업이 끝날 때 "수업 중 알림 N건" 알림 */
    fun hideInShade(ctx: Context) = hideWhen(ctx) != Hide.NEVER
    /** 모으는 앱 알림을 상태바에서 숨기는 때(2026-11-21: '언제나' 추가). 예전 'hide' 스위치를 끈 사람은 NEVER */
    enum class Hide(val title: String) { QUIET("모아 두는 시간에만"), ALWAYS("언제나"), NEVER("숨기지 않음") }
    /** v1.8.0: 모아서 알림이 켜져 있으면 모으는 때(=늘) 숨김, 꺼져 있으면 숨기지 않음 */
    fun hideWhen(ctx: Context): Hide = Hide.QUIET

    fun update(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(ctx).edit().apply(block).apply()
        tick(ctx)
    }
    fun setPeriods(ctx: Context, list: List<Int>) = update(ctx) { putString("periods", list.sorted().joinToString(",")) }

    /** 교시마다 (시작, 끝) 분. 예전 설정(시작 + 공통 길이)도 읽음 */
    fun ranges(ctx: Context): List<Pair<Int, Int>> =
        prefs(ctx).getString("ranges", null)?.split(",")?.mapNotNull { r ->
            r.split("-").takeIf { it.size == 2 }?.let { (a, b) -> a.toIntOrNull()?.let { s -> b.toIntOrNull()?.let { e -> s to e } } }
        }?.takeIf { it.isNotEmpty() } ?: periods(ctx).map { it to it + periodLen(ctx) }
    fun setRanges(ctx: Context, list: List<Pair<Int, Int>>) =
        update(ctx) { putString("ranges", list.filter { it.second > it.first }.sortedBy { it.first }.joinToString(",") { "${it.first}-${it.second}" }) }

    /** 시간표 버튼: 초등 40분·중학교 45분·고등 50분 (점심시간 빼고) */
    private fun hm(h: Int, m: Int) = h * 60 + m
    val PRESETS: List<Pair<String, List<Pair<Int, Int>>>> = listOf(
        "초등 40분" to listOf(hm(9, 0) to hm(9, 40), hm(9, 50) to hm(10, 30), hm(10, 40) to hm(11, 20), hm(11, 30) to hm(12, 10), hm(13, 0) to hm(13, 40), hm(13, 50) to hm(14, 30)),
        "중학교 45분" to listOf(hm(8, 50) to hm(9, 35), hm(9, 45) to hm(10, 30), hm(10, 40) to hm(11, 25), hm(11, 35) to hm(12, 20), hm(13, 10) to hm(13, 55), hm(14, 5) to hm(14, 50), hm(15, 0) to hm(15, 45)),
        "고등 50분" to listOf(hm(8, 40) to hm(9, 30), hm(9, 40) to hm(10, 30), hm(10, 40) to hm(11, 30), hm(11, 40) to hm(12, 30), hm(13, 20) to hm(14, 10), hm(14, 20) to hm(15, 10), hm(15, 20) to hm(16, 10)),
    )

    private fun minuteOf(c: Calendar) = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)

    /**
     * v1.8.0: '모아서 알림'이 켜져 있으면 늘 모으는 중(AFTER_WORK) — 주말·공휴일 쉬기를 켰으면 그날은 평소처럼.
     * (예전 수업 시간·퇴근 모드는 없앰. 이름 State 는 그대로 둠)
     */
    fun state(ctx: Context, now: Long = System.currentTimeMillis()): State {
        val cfg = SummarySchedule.config(ctx)
        if (!cfg.digest) return State.NONE
        if (cfg.restSkip && RestDays.isRest(ctx, now)) return State.NONE
        return State.AFTER_WORK
    }

    /** 근무 시간 밖(평일 08:00 전·17:00 뒤, 쉬는 날)인지 — 학부모 답장 추천("근무시간 이후라…")에만 씀 */
    fun isAfterWork(ctx: Context, time: Long): Boolean {
        if (RestDays.isRest(ctx, time)) return true
        val m = Calendar.getInstance().apply { timeInMillis = time }.let { minuteOf(it) }
        return m < workStart(ctx) || m >= workEnd(ctx)
    }

    /** 다음 경계 시각(수업 시작·끝, 출근·퇴근, 자정) */
    private fun nextBoundary(ctx: Context, now: Long): Long? {
        // 모아서 알림을 켰으면 자정(쉬는 날이 시작·끝날 때)마다 상태를 다시 봄
        val marks = if (SummarySchedule.config(ctx).digest) listOf(0) else emptyList()
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
        else if (cur != State.NONE) Dnd.upgradeIfOld(ctx)   // 이미 켜져 있던 옛 모드(전화 제한)를 새 모드로

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

        // 🔕 방해 금지: 권한이 있고 '자동 켜기'가 켜져 있을 때만. 쌤톡스 전용 모드를 켜고 끔(선생님이 직접 켠 방해 금지는 건드리지 않음)
        if (cur != State.NONE) Dnd.on(ctx) else Dnd.off(ctx)
        // 모아 두는 시간이 시작되면 그 전에 와 있던 모으는 앱 알림도 상태바에서 정리
        if (cur != State.NONE) NotificationCollectorService.instance?.sweep()

        // 수업이 끝나면: 수업 중 모은 알림 수를 한 번 알려 줌 (AI 없이, 바로)
        // (수업 끝날 때마다 요약을 켰으면 요약이 대신 알려 주므로 건너뜀)
        if (prev == State.CLASS && cur != State.CLASS) {
            val since = p.getLong("since", System.currentTimeMillis() - periodLen(ctx) * 60_000L)
            val rules = AppFilter.rules(ctx)
            val items = NotificationDb.get(ctx).since(since).filter { rules.allows(it) }
            if (items.isNotEmpty()) {
                val parents = ParentAdvisor.contacts(ctx, items)
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

/**
 * 🔕 모아 두는 시간의 방해 금지 (v1.7.0).
 * 안드로이드 '모드'(AutomaticZenRule)로 "쌤톡스 모아 두기" 모드를 만들어 켜고 끈다 — 메시지·알림 소리만 끄고
 * 전화는 손대지 않는다(v1.8.1, 2026-11-22 선생님: "긴급 전화도 있으니 전화는 이 앱에서 제어하지 말자, 메시지가 포인트").
 * 그래서 전화를 막을 수 있는 폰 방해 금지 통째로 켜기(예전 대신 쓰던 길)는 없앴다. 권한은 사용자가 직접 한 번 허용해야 한다.
 */
object Dnd {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quiet", Context.MODE_PRIVATE)
    private val CONDITION = android.net.Uri.parse("condition://com.jarvis.phone/collect")
    private const val RULE_V = 2   // 2 = 전화 모두 허용(v1.8.1)
    fun autoOn(ctx: Context) = prefs(ctx).getBoolean("dnd_auto", true)
    fun granted(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun ruleId(ctx: Context, nm: NotificationManager): String? {
        prefs(ctx).getString("zen_rule", null)?.let { id ->
            // v1.8.1 전에 만든 모드는 전화를 즐겨찾기만 받게 돼 있어 지우고 새로 만든다
            if (prefs(ctx).getInt("zen_rule_v", 1) < RULE_V) { runCatching { nm.removeAutomaticZenRule(id) }; prefs(ctx).edit().remove("zen_rule").apply() }
            else if (runCatching { nm.getAutomaticZenRule(id) }.getOrNull() != null) return id
        }
        return runCatching {
            val policy = android.service.notification.ZenPolicy.Builder()
                .allowAlarms(true).allowMedia(true)
                .allowCalls(android.service.notification.ZenPolicy.PEOPLE_TYPE_ANYONE).allowRepeatCallers(true)   // 📞 전화는 모두 그대로
                .allowMessages(android.service.notification.ZenPolicy.PEOPLE_TYPE_NONE)
                .allowConversations(android.service.notification.ZenPolicy.CONVERSATION_SENDERS_NONE)
                .allowReminders(false).allowEvents(false).allowSystem(false).build()
            val rule = android.app.AutomaticZenRule("쌤톡스 모아 두기", null,
                android.content.ComponentName(ctx, MainActivity::class.java), CONDITION, policy,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
            nm.addAutomaticZenRule(rule).also { prefs(ctx).edit().putString("zen_rule", it).putInt("zen_rule_v", RULE_V).apply() }
        }.getOrNull()
    }

    fun on(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!autoOn(ctx) || !nm.isNotificationPolicyAccessGranted) return
        val id = ruleId(ctx, nm)
        // 모드를 못 만들면 소리 끄기는 건너뜀(알림은 그래도 숨기고 모음) — 폰 방해 금지를 통째로 켜면 전화까지 막힐 수 있어서
        if (id != null) runCatching {
            nm.setAutomaticZenRuleState(id, android.service.notification.Condition(CONDITION, "모아 두는 중", android.service.notification.Condition.STATE_TRUE))
        }
    }

    fun off(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        prefs(ctx).getString("zen_rule", null)?.let { id ->
            runCatching { nm.setAutomaticZenRuleState(id, android.service.notification.Condition(CONDITION, "꺼짐", android.service.notification.Condition.STATE_FALSE)) }
        }
        if (prefs(ctx).getBoolean("we_set_dnd", false)) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            prefs(ctx).edit().putBoolean("we_set_dnd", false).apply()
        }
    }

    /** v1.8.1 전에 만든 모드(즐겨찾기 전화만 울림)가 남아 있으면 지우고 새 모드로 다시 켬 */
    fun upgradeIfOld(ctx: Context) {
        if (prefs(ctx).getString("zen_rule", null) != null && prefs(ctx).getInt("zen_rule_v", 1) < RULE_V) on(ctx)
    }

    /** 자동 켜기 스위치. 끄면 지금 켜 둔 모드도 끔 */
    fun setAuto(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("dnd_auto", enabled).apply()
        if (enabled && QuietMode.state(ctx) != QuietMode.State.NONE) on(ctx) else if (!enabled) off(ctx)
    }
}

/** 수업·퇴근 경계 시각에 깨어나 상태를 반영하고 다음 경계를 예약. */
class QuietReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = QuietMode.tick(ctx)
}

// ───────────────────────── 🚫 광고 자동 거르기 ─────────────────────────

/**
 * 문자·카톡의 광고성 메시지를 규칙으로 판단.
 * - 확실한 표시: (광고), [광고], 무료수신거부, 수신거부, 080 번호 → 광고
 * - 광고 단어(할인·쿠폰·이벤트·특가·적립·최대 N%·무료배송 등)가 2개 이상 → 광고
 * 학부모 방·중요 키워드·학생 이름이 든 메시지는 거르지 않음 (AppFilter.Rules에서 처리).
 */
object AdFilter {
    private val STRONG = listOf("(광고)", "[광고]", "(AD)", "[AD]", "무료수신거부", "무료 수신거부", "수신거부", "수신 거부", "080-", "080)")
    private val WEAK = listOf(
        "할인", "쿠폰", "이벤트", "특가", "적립", "혜택", "무료배송", "세일", "SALE", "핫딜", "오픈기념", "선착순",
        "최저가", "구매하기", "지금 바로", "가입하면", "포인트", "당첨", "경품", "증정", "프로모션", "한정 수량",
    )
    private val PERCENT = Regex("""최대\s*\d{1,2}\s*%|\d{1,2}\s*%\s*(할인|OFF|off)""")

    fun isAd(title: String, text: String): Boolean {
        val t = "$title $text"
        if (STRONG.any { it in t }) return true
        val weak = WEAK.count { it in t } + if (PERCENT.containsMatchIn(t)) 1 else 0
        return weak >= 2
    }

    /**
     * 보낸 사람이 번호인데 휴대폰 번호(01…)가 아님 — 15xx·16xx·080·02 같은 대표번호, 짧은 번호(업체·기관 문자).
     * 저장한 연락처 이름으로 온 문자는 해당 없음(2026-11-23 선생님: 광고 문자가 답장할 연락에 들어감).
     */
    /**
     * 보낸 사람 이름이 은행·카드·핀테크·큰 회사(2026-11-23 하루 정리: 토스·IBK기업은행·뱅크샐러드·Google Gemini 가
     * 이름으로 와서 대표번호 거르기를 지나 '오늘 있었던 일'을 차지함). 사람 이름과 겹치지 않게 끝말·정확한 상호만 본다.
     */
    private val BUSINESS_SUFFIX = Regex("(은행|뱅크|카드|증권|보험|캐피탈|생명|화재|페이|저축|금고|신협|농협|우체국|택배|공사)$")
    private val BUSINESS_NAME = Regex("^(토스|toss|뱅크샐러드|카카오뱅크|케이뱅크|쿠팡|배달의민족|요기요|당근마켓|네이버|카카오페이|삼성카드|현대카드|" +
        "Google|Gemini|YouTube|Apple|Samsung|Microsoft|Netflix|LG U\\+|KT|SKT|SK텔레콤|" +
        // 2026-11-24: 서비스가 보내는 자동 메일(GitHub 빌드 실패 메일이 💼 업무 요약에)
        "GitHub|GitLab|Notion|Slack|Zoom|Figma|Canva|Vercel|Netlify|Discord|Facebook|Instagram|LinkedIn|Amazon|Naver|Kakao|카카오계정|Dropbox|Adobe|OpenAI|Anthropic)(\\b|\\s|$)", RegexOption.IGNORE_CASE)

    fun isBusinessName(sender: String): Boolean {
        val s = sender.trim().removePrefix("(광고)").removePrefix("[광고]").trim()
        if (s.isEmpty()) return false
        return BUSINESS_NAME.containsMatchIn(s) || BUSINESS_SUFFIX.containsMatchIn(s)
    }

    fun isBusinessNumber(sender: String): Boolean {
        val s = sender.trim()
        if (s.isEmpty() || !s.all { it.isDigit() || it in "+-() #" }) return false
        val d = s.filter { it.isDigit() }.let { if (it.startsWith("82")) "0" + it.drop(2) else it }
        return d.length >= 3 && !d.startsWith("01")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("ad_filter", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("ad_filter", on).apply()

    /** 오늘 거른 광고 수 (날짜가 바뀌면 0부터) */
    fun countToday(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getLong("ad_day", 0) == NotificationDb.startOfToday()) p.getInt("ad_count", 0) else 0
    }
    fun addOne(ctx: Context) {
        val today = NotificationDb.startOfToday()
        prefs(ctx).edit().putLong("ad_day", today).putInt("ad_count", countToday(ctx) + 1).apply()
    }
}

// ───────────────────────── 👪 학부모 명부 (학생 ↔ 학부모 이름·번호) ─────────────────────────

/**
 * 학부모 한 명: student(학생 이름, 학생을 모르면 학부모 이름), relation(어머님/아버님/보호자, 모르면 ""),
 * match(알림에서 알아볼 이름·번호).
 */
data class ParentLink(val student: String, val relation: String, val match: String) {
    /** 화면·브리핑에 보일 이름. 세 글자 학생은 성 빼고 "민준 어머님", 관계를 모르면 이름만 */
    val label: String get() = if (relation.isBlank()) student
        else (if (student.length == 3) student.substring(1) else student) + " " + relation
}

/**
 * 학부모 명부.
 * 연결해 둔 이름·번호로 문자·카톡·밴드·하이클래스·학교종이 어디서 온 연락이든 그 학생의 학부모 연락으로 정리.
 * 번호는 숫자만 비교(뒤 8자리), 이름은 띄어쓰기 없이 비교.
 */
object ParentBook {
    private const val SEP = "\u0001"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun links(ctx: Context): List<ParentLink> =
        prefs(ctx).getStringSet("parent_links", emptySet())!!.mapNotNull {
            val p = it.split(SEP); if (p.size == 3) ParentLink(p[0], p[1], p[2]) else null
        }.sortedWith(compareBy({ it.student }, { it.relation }))

    fun add(ctx: Context, link: ParentLink) {
        if (link.match.isBlank()) return
        val s = prefs(ctx).getStringSet("parent_links", emptySet())!!.toMutableSet()
        s.add(listOf(link.student, link.relation, link.match.trim()).joinToString(SEP))
        prefs(ctx).edit().putStringSet("parent_links", s).apply()
    }
    fun remove(ctx: Context, link: ParentLink) {
        val s = prefs(ctx).getStringSet("parent_links", emptySet())!!.toMutableSet()
        s.remove(listOf(link.student, link.relation, link.match).joinToString(SEP))
        prefs(ctx).edit().putStringSet("parent_links", s).apply()
    }

    // ── 이름 쭉 넣기: 쉼표·줄바꿈·띄어쓰기 상관없이 알아서 나눔 ──
    private val REL_WORDS = listOf(
        "어머님" to "어머님", "어머니" to "어머님", "엄마" to "어머님", "맘" to "어머님",
        "아버님" to "아버님", "아버지" to "아버님", "아빠" to "아버님",
        "보호자" to "보호자", "할머니" to "보호자", "할아버지" to "보호자", "학부모님" to "", "학부모" to "",
    )
    private fun isPhone(w: String) = w.count { it.isDigit() } >= 8

    /** "민준맘" → ("민준", "어머님"), "이서윤" → ("이서윤", null) */
    private fun splitRelation(w: String): Pair<String, String?> {
        REL_WORDS.forEach { (k, r) -> if (w.endsWith(k) && w.length > k.length) return w.removeSuffix(k) to r }
        return w to null
    }

    /**
     * 아무렇게나 넣은 이름 목록을 학부모 연결로 바꿈.
     * 예) "민준맘, 이서윤 아버님 / 박지호 김영희" →
     *   민준(명부의 김민준) 어머님 [민준맘] · 이서윤 아버님 [이서윤 아버님] · 박지호 [박지호] · 김영희 [김영희]
     * 명부에 있는 학생 이름(성 빼고도)과 맞으면 그 학생의 학부모로, 아니면 넣은 이름 그대로 한 사람으로.
     */
    fun parse(raw: String, students: List<String>): List<ParentLink> {
        class Entry(val words: MutableList<String>, var rel: String?, val phones: MutableList<String>)
        val out = mutableListOf<ParentLink>()
        raw.split(Regex("""[,\n;/·、，]+""")).forEach { piece ->
            val entries = mutableListOf<Entry>()
            piece.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }.forEach { w0 ->
                val w = w0.trim('(', ')', '[', ']', '"', '\'', '.', ':')
                if (w.isEmpty()) return@forEach
                val exactRel = REL_WORDS.firstOrNull { it.first == w }?.second
                when {
                    isPhone(w) -> {}   // 이름만 받음 (번호는 넣지 않음)
                    exactRel != null && entries.isNotEmpty() -> entries.last().let { it.rel = exactRel; it.words.add(w) }
                    exactRel != null -> {}   // 맨 앞의 "학부모" 같은 말은 건너뜀
                    else -> { val (_, r) = splitRelation(w); entries.add(Entry(mutableListOf(w), r, mutableListOf())) }
                }
            }
            entries.forEach { e ->
                val text = e.words.joinToString(" ")
                if (text.isEmpty() && e.phones.isEmpty()) return@forEach
                val base = e.words.firstOrNull()?.let { splitRelation(it).first } ?: ""
                // 명부의 학생과 맞추기: 성까지 같거나, 성 뺀 이름이 같으면
                val student = students.firstOrNull { it == base || (it.length == 3 && it.substring(1) == base) }
                val who = student ?: base.ifEmpty { e.phones.first() }
                val rel = e.rel ?: ""
                val matches = listOfNotNull(text.ifEmpty { null }) + e.phones
                matches.forEach { out.add(ParentLink(who, rel, it)) }
            }
        }
        return out.distinct()
    }

    /** 비교용: 번호면 숫자 뒤 8자리, 이름이면 띄어쓰기·괄호 뺀 글자 */
    fun norm(s: String): String {
        val digits = s.filter { it.isDigit() }
        return if (digits.length >= 8 && digits.length * 2 >= s.count { !it.isWhitespace() }) digits.takeLast(8)
        else canon(s.filterNot { it.isWhitespace() || it in "()[]<>-" })
    }
    /** "엄마·어머니·맘" → 어머님, "아빠·아버지" → 아버님 으로 통일 ("민준맘" = "민준 어머님") */
    private fun canon(s: String): String = s
        .replace("어머니", "어머님").replace("엄마", "어머님").replace("맘", "어머님")
        .replace("아버지", "아버님").replace("아빠", "아버님")
    /** 이 연결로 알아볼 글자들: 그대로, 그리고 "이서윤아버님"이면 성 뺀 "서윤아버님"도 */
    private fun forms(l: ParentLink): List<String> {
        val m = norm(l.match)
        return if (l.student.length == 3 && m.startsWith(l.student) && m.length > 3) listOf(m, m.substring(1)) else listOf(m)
    }

    /** 이 알림을 보낸 사람(제목)·방이 연결된 학부모인지 */
    /**
     * 이 알림이 명부의 어느 학부모 것인지 (어떤 앱이든).
     * ① 보낸 사람·방 이름에 이름·번호가 있으면 (2글자 이상) ② 없으면 내용에 들어 있는지 (3글자 이상, 번호는 뒤 8자리)
     */
    fun find(links: List<ParentLink>, n: NotiItem): ParentLink? {
        if (links.isEmpty()) return null
        val who = listOf(n.title, n.room).filter { it.isNotBlank() }.map(::norm)
        links.firstOrNull { l -> forms(l).any { m -> m.length >= 2 && who.any { it == m || (m.length >= 3 && m in it) } } }
            ?.let { return it }
        if (n.text.isBlank()) return null
        val body = canon(n.text.filterNot { it.isWhitespace() || it in "()[]<>-" })
        return links.firstOrNull { l -> forms(l).any { m -> m.length >= 3 && m in body } }
    }

    // 학급 소통 앱: 여기서 온 연락에 학부모 호칭이나 우리 반 학생 이름이 있으면 학부모 연락으로 봄
    private val SCHOOL_APPS = listOf("하이클래스", "hiclass", "클래스팅", "classting", "학교종이", "알리미", "밴드", "band", "e알리미")
    private val PARENT_WORDS = listOf("어머님", "아버님", "어머니", "아버지", "학부모", "보호자", "맘", "엄마", "아빠")
    fun isSchoolApp(n: NotiItem) = (n.app + " " + n.pkg).lowercase().let { k -> SCHOOL_APPS.any { it.lowercase() in k } }
    fun looksParentInSchoolApp(n: NotiItem, studentNames: List<String>): Boolean =
        isSchoolApp(n) && (PARENT_WORDS.any { it in n.title || it in n.room } || Students.find(studentNames, n).isNotEmpty())

    /** 최근 연락한 사람 (문자·1:1 대화의 보낸 사람) — "최근 연락에서 고르기"용 */
    fun recentSenders(items: List<NotiItem>, links: List<ParentLink>): List<String> {
        val linked = links.map { norm(it.match) }.toSet()
        return items.asReversed()
            .filter { it.room.isEmpty() && it.title.isNotBlank() && AppAdvisor.isMessaging(AppStat(it.pkg, it.app, 0, emptyList())) }
            .map { it.title }
            .distinct()
            .filter { norm(it) !in linked && it.count { c -> c.isDigit() } < 4 }   // 이름만 (번호로 뜨는 건 빼기)
            .take(12)
    }
}
