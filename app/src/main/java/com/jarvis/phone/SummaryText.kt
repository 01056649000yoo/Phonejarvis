package com.jarvis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 🧾 요약 글을 읽기 좋은 문서처럼 (2026-11-23 선생님: "전체 인터페이스를 좀 더 고급스럽게").
 * 요약은 글 한 덩어리로 저장되므로(알림·위젯·하루 정리가 같은 글을 씀) 화면에서만 줄의 꼴을 보고 나눠 그린다:
 *  - 칸 제목('🙋 답장할 연락 (2)')  → 굵은 제목 + 건수는 옅게, 화면 읽기에서 '제목'
 *  - 줄('- 김민수 선생님 (3건) · 09:00: 내용') → 사람 이름 굵게 · 건수·시각 옅게 / 다음 줄에 내용
 *  - '(⏱ …)'·'(…)' 안내 → 작고 옅게
 * 규칙은 DESIGN.md(Material 3 점검표: 색은 colorScheme, 글자는 typography, 제목은 heading).
 */
private val TIME = Regex("""\b\d{1,2}:\d{2}\b""")
private val TAGGED = Regex("""^\[([^\]]+)]\s*(.*?)(\s*\(([^()]+)\))?$""")
private val COUNT = Regex("""\((\d+)(건|명|개 방)?[^)]*\)""")

/** 줄 하나: 머리(누가·언제)와 내용. 머리가 없으면 내용만 */
internal data class SummaryLine(val who: String, val meta: String, val body: String)

/** '- …' 줄 나누기(순수 — 시험). 첫 ': ' 앞이 40자 안쪽이면 머리, 머리에서 시각·건수는 meta 로 */
internal fun splitLine(raw: String): SummaryLine {
    val line = raw.removePrefix("-").trim()
    // '[오늘] 회의 자료 확인 (방1)' 같은 마감 줄: 표시는 meta, 끝의 (방)은 누가
    TAGGED.matchEntire(line)?.let { m ->
        return SummaryLine(m.groupValues[4].trim(), m.groupValues[1].trim(), m.groupValues[2].trim())
    }
    val i = line.indexOf(": ")
    if (i <= 0 || i > 40) return SummaryLine("", "", line)
    var head = line.substring(0, i)
    val meta = mutableListOf<String>()
    COUNT.find(head)?.let { meta += it.value.trim('(', ')'); head = head.replace(it.value, " ") }
    TIME.find(head)?.let { meta += it.value; head = head.replace(it.value, " ") }
    val who = head.replace("·", " ").replace(Regex("\\s+"), " ").trim()
    return SummaryLine(who, meta.joinToString(" · "), line.substring(i + 2).trim())
}

/** 🗓 하루 정리의 구간별 요약 줄('  · 09:10~09:40 · 12건: …') — 위 줄 아래에 접어 둠 */
internal fun isDetail(line: String): Boolean = line.startsWith(DailyDigest.DETAIL)

/** 줄 묶기(순수 — 시험): 구간별 요약 줄은 바로 위 '- ' 줄에 딸림. 위에 '- ' 줄이 없으면 보통 줄로 */
internal fun attachDetails(lines: List<String>): List<Pair<String, List<String>>> {
    val out = mutableListOf<Pair<String, MutableList<String>>>()
    for (l in lines) {
        val last = out.lastOrNull()
        if (isDetail(l) && last != null && last.first.startsWith("-")) last.second += l.removePrefix(DailyDigest.DETAIL)
        else out += (if (isDetail(l)) "- " + l.removePrefix(DailyDigest.DETAIL) else l) to mutableListOf()
    }
    return out
}

/** 칸 제목인가: '-'·'('·빈칸으로 시작하지 않고, 첫 글자가 글자·숫자가 아닌 것(이모지) */
internal fun isSectionHead(line: String): Boolean {
    if (isDetail(line)) return false
    val t = line.trimStart()
    if (t.isEmpty() || t[0] == '-' || t[0] == '(' || t[0] == '[') return false
    return !Character.isLetterOrDigit(t.codePointAt(0))
}


/**
 * 🖋 이모지 → 선 아이콘 (2026-11-25 디자인): 저장된 요약 글은 그대로 두고 화면에서만 바꿔 그린다.
 * 앱이 정한 칸 이모지만 바꾸고, 사람이 고른 묶음 이모지(👔 등)는 그대로.
 */
internal val LINE_ICONS = listOf(
    "👥" to R.drawable.ic_line_people, "👪" to R.drawable.ic_line_people, "💬" to R.drawable.ic_line_chat,
    "🙋" to R.drawable.ic_line_reply, "⭐" to R.drawable.ic_line_star, "💼" to R.drawable.ic_line_work,
    "📦" to R.drawable.ic_line_box, "📌" to R.drawable.ic_line_flag, "🗓" to R.drawable.ic_line_calendar,
)
private val INNER_MARKS = listOf("🚫", "🔁") + LINE_ICONS.map { it.first }

/** 설정 목록·설정 화면 제목의 이모지 → 선 아이콘 */
internal val MENU_ICONS = listOf(
    "🌱" to R.drawable.ic_line_filter, "🔕" to R.drawable.ic_line_quiet, "🏠" to R.drawable.ic_line_widget,
    "🤖" to R.drawable.ic_line_chip, "💾" to R.drawable.ic_line_restore, "🧹" to R.drawable.ic_line_storage,
    "👥" to R.drawable.ic_line_people, "⭐" to R.drawable.ic_line_star,
)

/** 메뉴 이름 앞 이모지를 아이콘으로(순수 — 시험): (아이콘, 이모지 뗀 이름). 모르는 이모지(사람이 고른 묶음 등)면 (null, 그대로) */
internal fun menuIcon(title: String): Pair<Int?, String> {
    val t = title.replace("\uFE0F", "")
    val hit = MENU_ICONS.firstOrNull { t.startsWith(it.first) } ?: return null to title
    return hit.second to t.removePrefix(hit.first).trimStart()
}

/** 메뉴 이름: 아는 이모지는 선 아이콘으로, 나머지는 글 그대로 */
@Composable
fun MenuTitle(title: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
              iconSize: androidx.compose.ui.unit.Dp = 20.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val (icon, name) = menuIcon(title)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = tint,
            modifier = Modifier.padding(end = Space.m).size(iconSize))
        Text(name, style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** 칸 제목 앞 이모지를 떼어 낸다(순수 — 시험): (앞 이모지, 나머지 글). 글 사이 칸 표시 이모지도 지움. 모르는 이모지면 (null, 그대로) */
internal fun splitIcon(line: String): Pair<String?, String> {
    val t = line.trimStart().replace("\uFE0F", "")
    val key = LINE_ICONS.firstOrNull { t.startsWith(it.first) }?.first ?: return null to line
    var rest = t.removePrefix(key).trimStart()
    INNER_MARKS.forEach { rest = rest.replace("$it ", "").replace(it, "") }
    return key to rest
}

/** 줄에 🙋(답할 것) 표시가 있으면 떼어 내고 true(순수 — 시험) */
internal fun splitAsk(line: String): Pair<Boolean, String> =
    if ("🙋" in line) true to line.replace("🙋 ", "").replace("🙋", "") else false to line

private fun iconRes(key: String): Int = LINE_ICONS.first { it.first == key }.second

@Composable
fun SummaryText(text: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    val base = LocalContentColor.current
    val muted = base.copy(alpha = 0.68f)
    val blocks = remember(text) {
        text.trim().split(Regex("\\n\\s*\\n")).map { b -> b.lines().map { it.trimEnd() }.filter { it.isNotBlank() } }.filter { it.isNotEmpty() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        blocks.forEach { lines ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                attachDetails(lines).forEach { (line, details) ->
                    when {
                        isSectionHead(line) -> {
                            val (icon, plain) = splitIcon(line)
                            val count = Regex("""\s*\(([^)]*)\)\s*$""").find(plain)
                            val title = count?.let { plain.removeRange(it.range) } ?: plain
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) Icon(painterResource(iconRes(icon)), contentDescription = null, tint = accent,
                                modifier = Modifier.padding(end = Space.s).size(18.dp))
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(title.substringBefore(" — ")) }
                                count?.let { withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Normal)) { append("  ${it.groupValues[1]}") } }
                                if (" — " in title) withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Normal)) { append(" — " + title.substringAfter(" — ")) }
                            }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            }
                        }
                        line.startsWith("-") -> {
                            val (ask, clean) = splitAsk(line)
                            val l = splitLine(clean)
                            Row(verticalAlignment = Alignment.Top) {
                                // 🙋 답할 것은 점 대신 답장 화살표
                                if (ask) Icon(painterResource(R.drawable.ic_line_reply), contentDescription = "답할 것", tint = accent,
                                    modifier = Modifier.padding(top = 2.dp, end = Space.s).size(16.dp))
                                else Box(Modifier.padding(top = 9.dp, end = Space.m).size(5.dp).background(accent.copy(alpha = 0.7f), CircleShape))
                                Column {
                                    if (l.who.isNotEmpty() || l.meta.isNotEmpty()) Text(buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(l.who) }
                                        if (l.meta.isNotEmpty()) withStyle(SpanStyle(color = muted)) { append(if (l.who.isEmpty()) l.meta else "  ${l.meta}") }
                                    }, style = MaterialTheme.typography.labelLarge)
                                    Text(l.body, style = MaterialTheme.typography.bodyMedium)
                                    // 🗓 구간별 요약(3층): 눌러서 펼침
                                    if (details.isNotEmpty()) {
                                        var open by rememberSaveable(line) { mutableStateOf(false) }
                                        Text(if (open) "▾ 구간별 요약 접기" else "▸ 구간별 요약 ${details.size}개",
                                            style = MaterialTheme.typography.labelMedium, color = accent,
                                            modifier = Modifier.clickable { open = !open }.padding(vertical = 2.dp))
                                        if (open) details.forEach { d -> Text(d, style = MaterialTheme.typography.bodySmall, color = muted) }
                                    }
                                }
                            }
                        }
                        line.startsWith("(") -> Text(line, style = MaterialTheme.typography.labelMedium, color = muted)
                        else -> Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }
