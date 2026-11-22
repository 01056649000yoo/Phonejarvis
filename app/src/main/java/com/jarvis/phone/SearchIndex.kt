package com.jarvis.phone

/**
 * 🔎 알림 검색 색인 (2026-11-22 선생님: "검색 색인을 넣어 줘", 알림 원문 3개월 보관).
 * 안드로이드 SQLite 의 FTS4 는 띄어쓰기로 낱말을 자르므로 '체험학습'으로 '현장체험학습은'을 못 찾는다.
 * 그래서 글을 두 글자씩 겹쳐 자른 조각(현장 장체 체험 험학 학습 습은)으로 넣고, 찾을 말도 같은 조각의 '이어진 구절'로 찾는다
 * → 글 속 어디에 있든(낱말 가운데여도) 찾고, 기록이 많아도 처음부터 훑지 않는다.
 * 한 글자 낱말(예: '밥')은 조각이 없어 예전처럼 훑어서 찾는다(NotificationDb.search).
 */
object SearchIndex {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** 글자·숫자만 남긴 낱말들(소문자) */
    fun words(s: String): List<String> = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }

    /** 색인에 넣을 글: 낱말마다 두 글자 조각, 한 글자 낱말은 그대로 */
    fun grams(s: String): String = words(s).joinToString(" ") { w ->
        if (w.length < 2) w else (0 until w.length - 1).joinToString(" ") { w.substring(it, it + 2) }
    }

    /**
     * 찾을 말 → FTS MATCH 식("현장 장체" "도시 시락" — 구절마다 모두 들어 있어야). 낱말이 한 글자뿐이거나 없으면 null(훑어 찾기).
     * 찾을 말에 기호가 있으면(예: 3-1반) 기호에서 잘라 조각마다 구절로.
     */
    fun matchQuery(query: String): String? {
        val ws = query.trim().split(Regex("\\s+")).flatMap { words(it) }.take(8)
        if (ws.isEmpty() || ws.any { it.length < 2 }) return null
        return ws.joinToString(" ") { "\"" + grams(it) + "\"" }
    }
}
