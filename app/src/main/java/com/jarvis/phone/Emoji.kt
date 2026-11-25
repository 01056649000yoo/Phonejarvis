package com.jarvis.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 🖋 이모지 → 선 아이콘 (2026-11-25 선생님: "이모지를 다 바꿔 달라").
 * 저장된 요약 글·설정 이름은 칸을 알아보는 표시로 이모지를 그대로 쓰고(하루 정리·위젯·시험이 그것으로 칸을 나눔),
 * 화면·위젯·알림에 보여 줄 때만 바꾼다:
 *  - 글 맨 앞의 앱 이모지 → 선 아이콘(res/drawable/ic_line_*)
 *  - 글 안의 앱 이모지 → 지움
 *  - 앱이 쓰지 않는 이모지(학부모가 보낸 😊 등 메시지 내용)는 그대로
 */
object Emoji {
    /** 맨 앞에 오면 아이콘으로 바꿀 이모지 (긴 것부터 — 👩‍🏫 가 👩 보다 먼저) */
    val ICONS: List<Pair<String, Int>> = listOf(
        "👩‍🏫" to R.drawable.ic_line_person,
        "📋" to R.drawable.ic_line_summary,
        "📄" to R.drawable.ic_line_summary,
        "🧾" to R.drawable.ic_line_summary,
        "📒" to R.drawable.ic_line_summary,
        "📜" to R.drawable.ic_line_summary,
        "🔔" to R.drawable.ic_line_bell,
        "📢" to R.drawable.ic_line_bell,
        "📚" to R.drawable.ic_line_history,
        "🗂" to R.drawable.ic_line_history,
        "🕳" to R.drawable.ic_line_history,
        "⚙" to R.drawable.ic_line_settings,
        "🔧" to R.drawable.ic_line_settings,
        "☰" to R.drawable.ic_line_settings,
        "👥" to R.drawable.ic_line_people,
        "👪" to R.drawable.ic_line_people,
        "🤝" to R.drawable.ic_line_people,
        "👔" to R.drawable.ic_line_people,
        "🏫" to R.drawable.ic_line_people,
        "⛪" to R.drawable.ic_line_people,
        "🍎" to R.drawable.ic_line_people,
        "⚽" to R.drawable.ic_line_people,
        "🏀" to R.drawable.ic_line_people,
        "🎵" to R.drawable.ic_line_people,
        "🎨" to R.drawable.ic_line_people,
        "🩺" to R.drawable.ic_line_people,
        "🍽" to R.drawable.ic_line_people,
        "✈" to R.drawable.ic_line_people,
        "🐶" to R.drawable.ic_line_people,
        "❤" to R.drawable.ic_line_people,
        "🧒" to R.drawable.ic_line_person,
        "👩" to R.drawable.ic_line_person,
        "💬" to R.drawable.ic_line_chat,
        "🗣" to R.drawable.ic_line_chat,
        "🧵" to R.drawable.ic_line_chat,
        "📞" to R.drawable.ic_line_chat,
        "🙋" to R.drawable.ic_line_reply,
        "⭐" to R.drawable.ic_line_star,
        "✨" to R.drawable.ic_line_star,
        "🆕" to R.drawable.ic_line_star,
        "💼" to R.drawable.ic_line_work,
        "📦" to R.drawable.ic_line_box,
        "📥" to R.drawable.ic_line_box,
        "📌" to R.drawable.ic_line_flag,
        "📎" to R.drawable.ic_line_flag,
        "🗓" to R.drawable.ic_line_calendar,
        "📅" to R.drawable.ic_line_calendar,
        "🌱" to R.drawable.ic_line_filter,
        "🔕" to R.drawable.ic_line_quiet,
        "🔇" to R.drawable.ic_line_quiet,
        "📵" to R.drawable.ic_line_quiet,
        "🏠" to R.drawable.ic_line_widget,
        "📱" to R.drawable.ic_line_widget,
        "🤖" to R.drawable.ic_line_chip,
        "💾" to R.drawable.ic_line_restore,
        "📤" to R.drawable.ic_line_restore,
        "🧹" to R.drawable.ic_line_storage,
        "🗑" to R.drawable.ic_line_storage,
        "⏰" to R.drawable.ic_line_clock,
        "⏱" to R.drawable.ic_line_clock,
        "⏳" to R.drawable.ic_line_clock,
        "🕘" to R.drawable.ic_line_clock,
        "🐢" to R.drawable.ic_line_clock,
        "🔒" to R.drawable.ic_line_lock,
        "🔐" to R.drawable.ic_line_lock,
        "🎯" to R.drawable.ic_line_target,
        "🚫" to R.drawable.ic_line_ban,
        "🙅" to R.drawable.ic_line_ban,
        "🙈" to R.drawable.ic_line_ban,
        "⚡" to R.drawable.ic_line_bolt,
        "🚀" to R.drawable.ic_line_bolt,
        "🔋" to R.drawable.ic_line_bolt,
        "🔎" to R.drawable.ic_line_search,
        "🔍" to R.drawable.ic_line_search,
        "👀" to R.drawable.ic_line_search,
        "🧭" to R.drawable.ic_line_search,
        "✉" to R.drawable.ic_line_mail,
        "📨" to R.drawable.ic_line_mail,
        "📧" to R.drawable.ic_line_mail,
        "✅" to R.drawable.ic_line_check,
        "✔" to R.drawable.ic_line_check,
        "✏" to R.drawable.ic_line_edit,
        "📝" to R.drawable.ic_line_edit,
        "⚠" to R.drawable.ic_line_warn,
        "ℹ" to R.drawable.ic_line_warn,
        "🌡" to R.drawable.ic_line_warn,
        "🌅" to R.drawable.ic_line_sun,
        "☀" to R.drawable.ic_line_sun,
        "🌙" to R.drawable.ic_line_moon,
    )

    /** 앱이 글에 쓰는 이모지 전부(소스의 글자들에서 모음). 이것만 지우고, 나머지 이모지는 메시지 내용이라 그대로 */
    private val APP: List<String> = listOf("👩‍🏫", "🎵", "👔", "✏", "💾", "🙈", "🏀", "🚀", "✈", "🔤", "🧒", "🌙", "🔐", "📚", "👀", "🆕", "➕", "🔕", "⚙", "✉", "🔋", "👥", "🕘", "🌱", "ℹ", "⚠", "🐢", "⏳", "🔒", "☰", "🔍", "⚽", "📎", "🧭", "📨", "🤝", "🧹", "⛪", "📒", "📵", "🕳", "🎨", "📶", "🧾", "📝", "🔁", "🌡", "🏠", "🌅", "🔔", "📅", "⭐", "🔎", "☀", "🐶", "📋", "📦", "⏱", "⚡", "🗂", "🚫", "🔇", "🧵", "📞", "❤", "📌", "📢", "✔", "⏰", "📧", "👩", "🙋", "🔧", "💬", "🗓", "🎯", "📜", "🤖", "🩺", "🍎", "🗣", "🙅", "📤", "📥", "🗑", "📄", "💼", "📱", "🏫", "✅", "✨", "👪", "🍽")

    private const val VS = "\uFE0F"

    /** 맨 앞 앱 이모지를 떼어 낸다(순수 — 시험): (아이콘, 나머지 글). 앞이 앱 이모지가 아니면 (null, 글) */
    fun lead(text: String): Pair<Int?, String> {
        val t = text.trimStart()
        val noVs = t.replace(VS, "")
        val hit = ICONS.firstOrNull { noVs.startsWith(it.first) } ?: run {
            val other = APP.firstOrNull { noVs.startsWith(it) } ?: return null to strip(text)
            return null to strip(noVs.removePrefix(other))
        }
        return hit.second to strip(noVs.removePrefix(hit.first))
    }

    /** 글 안의 앱 이모지를 모두 지운다(순수 — 시험). '✔'는 '✓'로, 빈칸 두 개는 하나로 */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        var s = text.replace("✔$VS", "✓").replace("✔", "✓").replace(VS, "")
        for (e in APP) if (e != "✔" && e in s) s = s.replace("$e ", "").replace(e, "")
        return s.replace(Regex("(?<=\\S) {2,}"), " ").trimStart()
    }
}

/**
 * 화면 글자는 모두 이것으로 — 같은 패키지 함수라 `import androidx.compose.material3.*` 의 Text 보다 먼저 잡힌다(Card 와 같은 방식).
 * 맨 앞 앱 이모지는 글자 크기에 맞춘 선 아이콘으로, 글 안의 앱 이모지는 지움.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val (icon, rest) = Emoji.lead(text)
    val body = rest
    val plain = @Composable { m: Modifier ->
        androidx.compose.material3.Text(body, m, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
            textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style)
    }
    if (icon == null) { plain(modifier); return }
    val size = (if (fontSize.isSpecified) fontSize else if (style.fontSize.isSpecified) style.fontSize else 16.sp) * 1.15f
    val tint = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (textAlign == TextAlign.Center) Arrangement.Center else Arrangement.Start) {
        Icon(painterResource(icon), contentDescription = null, tint = tint,
            modifier = Modifier.padding(end = 6.dp).size(with(LocalDensity.current) { size.toDp() }))
        plain(Modifier)
    }
}
