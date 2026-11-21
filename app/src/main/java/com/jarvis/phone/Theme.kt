package com.jarvis.phone

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 쌤톡스 브랜드 색 (로고 디자인과 같음, res/values/colors.xml 과도 맞춤) */
object Ssam {
    val Board = Color(0xFF2E4A3D)    // 칠판 그린
    val Chalk = Color(0xFFF6F1E4)    // 분필 크림
    val Sprout = Color(0xFF9BD18B)   // 새싹
    val Blush = Color(0xFFF28C6B)    // 볼터치
    val Ink = Color(0xFF1F2A24)
    val Night = Color(0xFF1B211E)
}

private val Light = lightColorScheme(
    primary = Ssam.Board, onPrimary = Ssam.Chalk,
    primaryContainer = Ssam.Board, onPrimaryContainer = Ssam.Chalk,
    secondary = Color(0xFF4F7A45), onSecondary = Color.White,
    // 하단 메뉴의 고른 칸·칩: 기본 보라 대신 새싹 연두 (2026-11-22 깔끔하고 눈에 띄게)
    secondaryContainer = Color(0xFFD6EDCB), onSecondaryContainer = Color(0xFF1C3A22),
    tertiary = Color(0xFFB4553A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE0D6), onTertiaryContainer = Color(0xFF5A2312),
    background = Color(0xFFF3EDDF), onBackground = Ssam.Ink,
    surface = Color(0xFFF3EDDF), onSurface = Ssam.Ink,
    surfaceVariant = Color(0xFFEDE7D8), onSurfaceVariant = Color(0xFF4A5A51),
    // 카드는 크림 바탕 위 흰 종이처럼 (예전 베이지 카드는 바탕과 섞여 흐릿했음)
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFDF8),
    surfaceContainer = Color(0xFFFFFCF4), surfaceContainerHigh = Color(0xFFFFFDF8),
    surfaceContainerHighest = Color(0xFFFFFEFA),
    outline = Color(0xFF8A9690), outlineVariant = Color(0xFFE3DCCB),
)

private val Dark = darkColorScheme(
    primary = Ssam.Sprout, onPrimary = Color(0xFF10301F),
    primaryContainer = Ssam.Board, onPrimaryContainer = Ssam.Chalk,
    secondary = Color(0xFFB9D9AE), onSecondary = Color(0xFF1E3A1A),
    secondaryContainer = Color(0xFF345A3C), onSecondaryContainer = Color(0xFFD6EDCB),
    tertiary = Ssam.Blush, onTertiary = Color(0xFF4A1A0C),
    tertiaryContainer = Color(0xFF5A2E22), onTertiaryContainer = Color(0xFFFBE0D6),
    background = Ssam.Night, onBackground = Color(0xFFE6ECE8),
    surface = Ssam.Night, onSurface = Color(0xFFE6ECE8),
    surfaceVariant = Color(0xFF2A322E), onSurfaceVariant = Color(0xFFB9C6BE),
    surfaceContainerLowest = Color(0xFF151A17), surfaceContainerLow = Color(0xFF1F2622),
    surfaceContainer = Color(0xFF232A26), surfaceContainerHigh = Color(0xFF29312D),
    surfaceContainerHighest = Color(0xFF2B332F),
    outline = Color(0xFF7D8A83), outlineVariant = Color(0xFF39433E),
)

/**
 * 🔤 쌤톡스 글자 단계 (앱 전체가 이것만 씀). 기본 Material보다 한 단계씩 크게, 한글이 답답하지 않게 줄 간격 넉넉히.
 *   화면 큰 제목 headlineMedium 28 · 세부 화면 제목 headlineSmall 24 · 카드 제목 titleMedium 19 굵게
 *   본문 bodyLarge 18 · 보통 bodyMedium 16 · 설명 bodySmall 14 · 버튼·칩 labelLarge 16
 * 폰 설정의 글자 크기를 키우면 그만큼 같이 커짐.
 */
private val SsamType = Typography().run {
    fun TextStyle.k(size: Int, line: Int, weight: FontWeight? = null) =
        copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight ?: fontWeight, letterSpacing = 0.sp)
    Typography(
        displayMedium = displayMedium.k(40, 48, FontWeight.Bold),
        headlineMedium = headlineMedium.k(28, 36, FontWeight.Bold),
        headlineSmall = headlineSmall.k(24, 32, FontWeight.Bold),
        titleLarge = titleLarge.k(22, 30, FontWeight.Bold),
        titleMedium = titleMedium.k(19, 27, FontWeight.Bold),
        titleSmall = titleSmall.k(17, 24, FontWeight.SemiBold),
        bodyLarge = bodyLarge.k(18, 27),
        bodyMedium = bodyMedium.k(16, 24),
        bodySmall = bodySmall.k(14, 21),
        labelLarge = labelLarge.k(16, 22, FontWeight.SemiBold),
        labelMedium = labelMedium.k(14, 20, FontWeight.Medium),
        labelSmall = labelSmall.k(13, 18, FontWeight.Medium),
    )
}

/** 모서리: 카드 20, 창 28 — 둥글고 부드럽게 */
private val SsamShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** 폰 설정의 다크 모드를 따라 밝은(분필 크림)/어두운(밤 칠판) 테마. */
@Composable
fun SsamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = SsamType, shapes = SsamShapes, content = content)
}

/**
 * 쌤톡스 카드 (2026-11-22): 흰 종이 + 옅은 테두리 한 줄. 이 패키지의 `Card(...)` 는 모두 이것을 씀
 * (같은 패키지 함수가 material3.* 보다 먼저 잡힘). 색을 따로 준 카드(진한 요약 카드 등)는 테두리 없이.
 */
@Composable
fun Card(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    colors: androidx.compose.material3.CardColors = androidx.compose.material3.CardDefaults.cardColors(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val plain = colors == androidx.compose.material3.CardDefaults.cardColors()
    androidx.compose.material3.Card(
        modifier, shape = MaterialTheme.shapes.medium, colors = colors,
        border = if (plain) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        content = content,
    )
}
