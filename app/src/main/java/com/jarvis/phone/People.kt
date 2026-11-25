package com.jarvis.phone

import android.content.Context

/**
 * 👥 사람 묶음: 쓰는 사람이 직접 만드는 목록 (예: 👔 교직원, 🏠 가족, 👪 학부모).
 * kind: "custom"(이름만 넣는 묶음) · 예전 판에 있던 "students"(우리 반 명부)·"parents"(학부모 명부)
 * 2026-11-21 선생님: 학생·학부모를 고정으로 두지 않는다 — 처음엔 빈 목록, 내 맘대로 만들고 꾸미고 지운다.
 * 예전 판의 학생·학부모 묶음은 이름이 들어 있으면 남겨 두고(지우기 가능), 비어 있으면 저절로 없앤다.
 */
data class PeopleGroup(val id: String, val emoji: String, val name: String, val kind: String, val hidden: Boolean = false) {
    val title: String get() = "$emoji $name"
}

object People {
    private const val SEP = "\u0001"
    private fun p(ctx: Context) = ctx.getSharedPreferences("people", Context.MODE_PRIVATE)

    private val DEFAULTS = emptyList<PeopleGroup>()
    /** 묶음 만들기 추천 */
    val SUGGESTIONS = listOf("👔" to "교직원", "👪" to "학부모", "🏠" to "가족", "🤝" to "친구", "🏫" to "학교 행정", "📚" to "동학년", "💼" to "업무 거래처", "⛪" to "모임")
    val EMOJIS = listOf("👔", "👪", "🏠", "🤝", "🏫", "📚", "💼", "⛪", "🧒", "👩‍🏫", "🍎", "✏️",
        "⚽", "🏀", "🎵", "🎨", "🩺", "🍽️", "✈️", "🐶", "🌱", "⭐", "❤️", "👥")

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
    /** 묶음 지우기 — 어떤 묶음이든 넣어 둔 이름까지 지운다(학생 묶음은 학생 이름, 학부모 묶음은 연결해 둔 학부모). 모은 알림은 남음 */
    fun remove(ctx: Context, id: String) {
        val g = groups(ctx).firstOrNull { it.id == id } ?: return
        save(ctx, groups(ctx).filterNot { it.id == id })
        p(ctx).edit().remove("m_$id").apply()
        val apps = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE).edit()
        when (g.kind) {
            "students" -> apps.remove("students").apply()
            "parents" -> apps.remove("parent_links").apply()
        }
    }

    /** 예전 판의 학생·학부모 묶음이 비어 있으면 없앤다(앱을 켤 때). 이름이 들어 있으면 그대로 둔다 */
    fun dropEmptyBuiltIns(ctx: Context) {
        // 2026-11-24 선생님: '학부모'라는 고정 칸은 없앰 — 예전 판 👪 학부모 명부는 넣어 둔 이름 그대로 보통 묶음(🤝 지인)으로.
        // 이름·이모지는 사람들 탭에서 바꿀 수 있음. 연결 정보(ParentBook)는 지우지 않아 중요한 연락 알아보기에 그대로 쓰임
        groups(ctx).firstOrNull { it.kind == "parents" }?.let { old ->
            val names = parentNames(ctx)
            if (names.isNotEmpty()) p(ctx).edit().putStringSet("m_${old.id}", members(ctx, old.id).toSet() + names).apply()
            save(ctx, groups(ctx).map { g ->
                if (g.id != old.id) g else g.copy(kind = "custom",
                    emoji = if (g.emoji == "👪") "🤝" else g.emoji, name = if (g.name == "학부모" || g.name == "학부모 명부") "지인" else g.name)
            }.filterNot { it.id == old.id && names.isEmpty() && members(ctx, old.id).isEmpty() })
        }
        val list = groups(ctx)
        val keep = list.filterNot { g ->
            (g.kind == "students" && Students.names(ctx).isEmpty()) || (g.kind == "parents" && ParentBook.links(ctx).isEmpty())
        }
        if (keep.size != list.size) save(ctx, keep)
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
        visible(ctx).filter { it.kind == "custom" || it.kind == "parents" }
            .map { it to if (it.kind == "parents") parentNames(ctx) else members(ctx, it.id) }.filter { it.second.isNotEmpty() }

    /**
     * 예전 판 👪 학부모 명부도 다른 묶음과 똑같이(2026-11-23 선생님: 학부모 전용 칸 없이 내가 만든 사람 묶음으로).
     * 연결해 둔 이름(번호는 빼고) — 보낸 사람·방 이름으로 알아봄
     */
    private fun parentNames(ctx: Context): List<String> =
        ParentBook.links(ctx).map { it.match.trim() }.filter { it.length >= 2 && it.count { c -> c.isDigit() } < 4 }.distinct()

    /** 규칙(끈 방·광고에서도 놓치지 않기)에 쓸 이름들 */
    fun allNames(ctx: Context): List<String> = customBook(ctx).flatMap { it.second }

    private fun norm(s: String) = s.filterNot { it.isWhitespace() || it in "()[]<>-" }

    /** 이 알림이 어느 묶음의 누구 것인지: 보낸 사람·방 이름에 이름이 있으면(2글자 이상), 없으면 내용에 있으면(3글자 이상) */
    /** bodyToo=false: 보낸 사람·방 이름으로만(요약에서 사람별로 묶을 때 — 내용에 이름이 나온 것은 그 사람이 보낸 게 아님) */
    fun find(book: List<Pair<PeopleGroup, List<String>>>, n: NotiItem, bodyToo: Boolean = true): Pair<PeopleGroup, String>? {
        if (book.isEmpty()) return null
        val who = listOf(n.title, n.room).filter { it.isNotBlank() }.map(::norm)
        for ((g, names) in book) for (name in names) {
            val m = norm(name)
            if (m.length >= 2 && who.any { it == m || it.contains(m) }) return g to name
        }
        if (!bodyToo) return null
        val body = norm(n.text)
        for ((g, names) in book) for (name in names) {
            val m = norm(name)
            if (m.length >= 3 && body.contains(m)) return g to name
        }
        return null
    }
}
