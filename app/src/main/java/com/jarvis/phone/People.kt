package com.jarvis.phone

import android.content.Context

/**
 * 👥 사람들: 쓰는 사람이 직접 만드는 묶음 (예: 🧒 우리 반 학생, 👪 학부모, 👔 교직원, 🏠 가족).
 * kind: "students"(우리 반 명부와 연결) · "parents"(학부모 명부와 연결) · "custom"(이름만 넣는 묶음)
 * 기본 두 묶음(학생·학부모)도 이름을 바꾸거나 숨길 수 있어서 교사가 아니어도 쓸 수 있음.
 */
data class PeopleGroup(val id: String, val emoji: String, val name: String, val kind: String, val hidden: Boolean = false) {
    val title: String get() = "$emoji $name"
}

object People {
    private const val SEP = "\u0001"
    private fun p(ctx: Context) = ctx.getSharedPreferences("people", Context.MODE_PRIVATE)

    private val DEFAULTS = listOf(
        PeopleGroup("students", "🧒", "우리 반 학생", "students"),
        PeopleGroup("parents", "👪", "학부모", "parents"),
    )
    /** 묶음 만들기 추천 */
    val SUGGESTIONS = listOf("👔" to "교직원", "🏠" to "가족", "🤝" to "친구", "🏫" to "학교 행정", "💼" to "업무 거래처", "⛪" to "모임")
    val EMOJIS = listOf("👔", "🏠", "🤝", "🏫", "💼", "⛪", "⚽", "🎵", "📚", "🩺", "⭐", "👥")

    /** 모든 묶음 (숨긴 것 포함, 순서대로) */
    fun groups(ctx: Context): List<PeopleGroup> {
        val raw = p(ctx).getString("groups", null) ?: return DEFAULTS
        return raw.lines().filter { it.isNotBlank() }.mapNotNull {
            val f = it.split(SEP)
            if (f.size >= 4) PeopleGroup(f[0], f[1], f[2], f[3], f.getOrNull(4) == "1") else null
        }.ifEmpty { DEFAULTS }
    }
    private fun save(ctx: Context, list: List<PeopleGroup>) {
        p(ctx).edit().putString("groups", list.joinToString("\n") {
            listOf(it.id, it.emoji, it.name.replace("\n", " "), it.kind, if (it.hidden) "1" else "0").joinToString(SEP)
        }).apply()
    }
    fun visible(ctx: Context) = groups(ctx).filterNot { it.hidden }
    fun isVisible(ctx: Context, id: String) = groups(ctx).any { it.id == id && !it.hidden }

    fun addGroup(ctx: Context, emoji: String, name: String): PeopleGroup? {
        val n = name.trim().take(20)
        if (n.isEmpty()) return null
        val g = PeopleGroup("g" + System.currentTimeMillis(), emoji.ifBlank { "👥" }, n, "custom")
        save(ctx, groups(ctx) + g)
        return g
    }
    fun rename(ctx: Context, id: String, emoji: String, name: String) {
        val n = name.trim().take(20)
        if (n.isEmpty()) return
        save(ctx, groups(ctx).map { if (it.id == id) it.copy(emoji = emoji.ifBlank { it.emoji }, name = n) else it })
    }
    /** 직접 만든 묶음은 지우고, 기본 묶음(학생·학부모)은 숨김 (이름 기록은 남음) */
    fun remove(ctx: Context, id: String) {
        val g = groups(ctx).firstOrNull { it.id == id } ?: return
        if (g.kind == "custom") {
            save(ctx, groups(ctx).filterNot { it.id == id })
            p(ctx).edit().remove("m_$id").apply()
        } else save(ctx, groups(ctx).map { if (it.id == id) it.copy(hidden = true) else it })
    }
    fun unhide(ctx: Context, id: String) = save(ctx, groups(ctx).map { if (it.id == id) it.copy(hidden = false) else it })

    // ── 묶음 안의 사람 (이름만) ──
    fun members(ctx: Context, id: String): List<String> = p(ctx).getStringSet("m_$id", emptySet())!!.sorted()

    /** 이름 쭉 넣기: 쉼표·줄바꿈·띄어쓰기 아무렇게나. 넣은 이름들을 돌려줌 */
    fun addMembers(ctx: Context, id: String, raw: String): List<String> {
        val names = splitNames(raw)
        if (names.isEmpty()) return emptyList()
        p(ctx).edit().putStringSet("m_$id", members(ctx, id).toSet() + names).apply()
        return names
    }
    fun removeMember(ctx: Context, id: String, name: String) {
        p(ctx).edit().putStringSet("m_$id", members(ctx, id).toSet() - name).apply()
    }

    /** "김교감, 박부장 이선생\n최주무관" → [김교감, 박부장, 이선생, 최주무관]. 숫자만 있는 건(번호) 뺌 */
    fun splitNames(raw: String): List<String> =
        raw.split(Regex("""[,\n;/·、，]+""")).flatMap { piece ->
            val words = piece.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            // "김 교감"처럼 한 글자 조각이 있으면 붙여 쓴 한 이름으로, 아니면 띄어쓰기마다 한 사람
            if (words.any { it.length == 1 }) listOf(words.joinToString("")) else words
        }.map { it.trim('(', ')', '[', ']', '"', '\'', '.', ':') }
            .filter { it.length in 2..20 && it.count { c -> c.isDigit() } < 4 }
            .distinct()

    /** 직접 만든 묶음들의 (묶음, 이름들) — 알림 알아보기용 */
    fun customBook(ctx: Context): List<Pair<PeopleGroup, List<String>>> =
        visible(ctx).filter { it.kind == "custom" }.map { it to members(ctx, it.id) }.filter { it.second.isNotEmpty() }

    /** 규칙(끈 방·광고에서도 놓치지 않기)에 쓸 이름들 */
    fun allNames(ctx: Context): List<String> = customBook(ctx).flatMap { it.second }

    private fun norm(s: String) = s.filterNot { it.isWhitespace() || it in "()[]<>-" }

    /** 이 알림이 어느 묶음의 누구 것인지: 보낸 사람·방 이름에 이름이 있으면(2글자 이상), 없으면 내용에 있으면(3글자 이상) */
    fun find(book: List<Pair<PeopleGroup, List<String>>>, n: NotiItem): Pair<PeopleGroup, String>? {
        if (book.isEmpty()) return null
        val who = listOf(n.title, n.room).filter { it.isNotBlank() }.map(::norm)
        for ((g, names) in book) for (name in names) {
            val m = norm(name)
            if (m.length >= 2 && who.any { it == m || it.contains(m) }) return g to name
        }
        val body = norm(n.text)
        for ((g, names) in book) for (name in names) {
            val m = norm(name)
            if (m.length >= 3 && body.contains(m)) return g to name
        }
        return null
    }

    /** 브리핑용: 묶음별 연락 (규칙, 즉시). 없으면 빈 문자열 */
    fun section(book: List<Pair<PeopleGroup, List<String>>>, items: List<NotiItem>): Pair<String, Set<NotiItem>> {
        val hits = items.mapNotNull { n -> find(book, n)?.let { (g, name) -> Triple(g, name, n) } }
        if (hits.isEmpty()) return "" to emptySet()
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        val text = buildString {
            hits.groupBy { it.first.id }.forEach { (_, list) ->
                val g = list.first().first
                append("${g.title} 연락 (${list.size}건)\n")
                list.distinctBy { it.third.title + it.third.text }.takeLast(8).forEach { (_, name, n) ->
                    val where = if (n.room.isNotBlank() && n.room != n.title) " [${n.room}]" else ""
                    append("- ${fmt.format(n.time)} $name$where: ${n.text.replace('\n', ' ').take(60)}\n")
                }
                append("\n")
            }
        }
        return text to hits.map { it.third }.toSet()
    }
}
