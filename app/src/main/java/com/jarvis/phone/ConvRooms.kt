package com.jarvis.phone

import android.content.Context

/**
 * 💬 방 이름이 없는 단톡방 알아보기 (2026-11-26 선생님 캡처: 같은 단톡방의 말이 사람마다 1:1 연락처럼 올라오고,
 * 한 사람의 서로 다른 톡방 말이 한 줄로 섞임 — '윤현정 교감선생님: 투표가 30분 뒤 종료됩니다 / 시지정 연구학교의 아버지').
 *
 * 카톡은 이름을 정하지 않은 단톡방의 알림에 방 이름을 넣어 주지 않아, 제목(보낸 사람)만 보고는 1:1 과 구분할 수 없었다.
 * 안드로이드는 대화마다 고유한 표시(대화 바로가기 id)를 알림에 붙여 주므로 그것으로 '같은 대화'를 알아본다:
 *  - 같은 대화에서 보낸 사람이 둘 이상 나오면 단톡방 → 그때부터 그 대화의 방 이름을 '김희엽·최성우 단톡방'으로(한 번 정하면 그대로)
 *  - 한 사람만 나오는 대화는 지금처럼 1:1
 * 저장하는 것은 대화 표시와 보낸 사람 이름뿐(내용 X), 이 폰 안에만.
 */
object ConvRooms {
    const val MAX_SENDERS = 6
    private const val SEP = '\u0001'
    private fun p(ctx: Context) = ctx.getSharedPreferences("conv_rooms", Context.MODE_PRIVATE)

    /** 대화 하나: 지금까지 본 보낸 사람들, 단톡방으로 정해졌으면 그 이름("" = 아직 1:1 로 봄) */
    data class Entry(val senders: List<String> = emptyList(), val label: String = "")

    /**
     * 새로 본 보낸 사람을 더하고, 단톡방인지 정함(순수 — 시험).
     * [groupFlag]: 알림이 '여럿이 있는 대화'라고 알려 줌 — 한 사람만 봤어도 단톡방으로
     */
    fun note(old: Entry?, senders: List<String>, groupFlag: Boolean = false): Entry {
        val all = ((old?.senders.orEmpty()) + senders.map { it.trim() }.filter { it.isNotEmpty() }).distinct().take(MAX_SENDERS)
        val label = old?.label.orEmpty().ifEmpty {
            when {
                all.size >= 2 -> "${all[0]}·${all[1]} 단톡방"
                groupFlag && all.isNotEmpty() -> "${all[0]} 외 단톡방"
                else -> ""
            }
        }
        return Entry(all, label.take(100))
    }

    private fun parse(s: String?): Entry? = s?.split(SEP)?.let { v -> Entry(v.drop(1).filter { it.isNotEmpty() }, v.firstOrNull().orEmpty()) }

    /** 이 대화의 방 이름("" = 1:1). [learn] 이면 보낸 사람을 기억함 */
    fun roomFor(ctx: Context, convId: String, senders: List<String>, groupFlag: Boolean, learn: Boolean): String {
        val old = parse(p(ctx).getString(convId, null))
        if (!learn) return old?.label.orEmpty()
        val now = note(old, senders, groupFlag)
        if (now != old) p(ctx).edit().apply {
            putString(convId, (listOf(now.label) + now.senders).joinToString(SEP.toString()))
            if (p(ctx).all.size > 600) p(ctx).all.keys.take(100).forEach { remove(it) }   // 끝없이 쌓이지 않게
        }.apply()
        return now.label
    }
}
