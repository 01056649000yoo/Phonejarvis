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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 쌤톡스 색 (2026-11-25 Oura 디자인 반영 — DESIGN.md).
 * 따뜻한 종이 크림 바탕 + 회갈색(Stone) 주색 + 흙빛 보조색(금·흙·올리브).
 * 초록(Action, 로고와 같은 칠판 그린)은 화면마다 가장 중요한 단추 하나에만 쓴다(2026-11-25 파랑 → 칠판 그린).
 */
object Ssam {
    val Action = Color(0xFF2E4A3D)   // 로고(LogoBoard·colors.xml ssam_board)와 같은 칠판 그린: 가장 중요한 단추 하나 (화면마다 하나만), 흰 글자 대비 9.7:1
    val Stone = Color(0xFF4A4741)    // 주색: 단추·고른 칸·진한 요약 카드
    val Canvas = Color(0xFFF7F1E8)   // 종이 크림 바탕
    val Ink = Color(0xFF1C1B1A)      // 본문 글
    val Night = Color(0xFF19191C)    // 어두운 모드 바탕
    val Hairline = Color(0xFFE1DCD3) // 옅은 구분선
    val Clay = Color(0xFF6B443D)     // 흙: 주의·답장 필요
    val Espresso = Color(0xFF39221E)
    val Gold = Color(0xFFAF751B)     // 금: 마감 임박
    val GoldSoft = Color(0xFFDDAA61)
    val GoldPale = Color(0xFFDDBE78) // 진한 카드 위 강조 글
    val Forest = Color(0xFF224228)
    val Olive = Color(0xFF5C6F5D)
    val Sage = Color(0xFF849671)
    val SagePale = Color(0xFFCED4B8) // 고른 칸·완료 표시
    /** 로고(앱 아이콘)만 예전 칠판 그린을 그대로 씀 — res/values/colors.xml 의 ssam_board */
    val LogoBoard = Color(0xFF2E4A3D)
}

/** 화면마다 가장 중요한 단추 하나(예: 📋 지금 정리하기, 처음 설치의 다음 단계)에만 쓰는 초록 단추 색 */
@Composable
fun actionButtonColors() = androidx.compose.material3.ButtonDefaults.buttonColors(
    containerColor = Ssam.Action, contentColor = Color.White)

internal val SsamLight = lightColorScheme(
    primary = Ssam.Stone, onPrimary = Color.White,
    primaryContainer = Ssam.Stone, onPrimaryContainer = Ssam.Canvas,
    secondary = Ssam.Olive, onSecondary = Color.White,
    secondaryContainer = Ssam.SagePale, onSecondaryContainer = Ssam.Forest,
    tertiary = Ssam.Clay, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E2D6), onTertiaryContainer = Ssam.Espresso,
    background = Ssam.Canvas, onBackground = Ssam.Ink,
    surface = Ssam.Canvas, onSurface = Ssam.Ink,
    surfaceVariant = Color(0xFFEEE7DB), onSurfaceVariant = Ssam.Stone,
    // 카드는 크림 바탕 위 흰 종이 + 옅은 선 한 줄
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFDFAF5),
    surfaceContainer = Color(0xFFFBF7F0), surfaceContainerHigh = Color(0xFFFDFAF5),
    surfaceContainerHighest = Color.White,
    outline = Color(0xFF838280), outlineVariant = Ssam.Hairline,
    error = Color(0xFFC2331D), onError = Color.White,
)

internal val SsamDark = darkColorScheme(
    primary = Ssam.Canvas, onPrimary = Ssam.Stone,
    primaryContainer = Ssam.Stone, onPrimaryContainer = Ssam.Canvas,
    secondary = Ssam.SagePale, onSecondary = Ssam.Forest,
    secondaryContainer = Ssam.Olive, onSecondaryContainer = Color(0xFFEEF0E4),
    tertiary = Ssam.GoldSoft, onTertiary = Ssam.Espresso,
    tertiaryContainer = Color(0xFF4A3520), onTertiaryContainer = Ssam.GoldPale,
    background = Ssam.Night, onBackground = Ssam.Canvas,
    surface = Ssam.Night, onSurface = Ssam.Canvas,
    surfaceVariant = Color(0xFF2A292C), onSurfaceVariant = Color(0xFFA8A5A0),
    surfaceContainerLowest = Color(0xFF141416), surfaceContainerLow = Color(0xFF1F1F22),
    surfaceContainer = Color(0xFF232326), surfaceContainerHigh = Color(0xFF29292C),
    surfaceContainerHighest = Color(0xFF2C2B2F),
    outline = Color(0xFF838280), outlineVariant = Color(0xFF38373A),
    error = Color(0xFFFF8A73), onError = Color(0xFF4A1006),
)

/**
 * 🔤 쌤톡스 글자 단계 (앱 전체가 이것만 씀). 기본 Material보다 한 단계씩 크게, 한글이 답답하지 않게 줄 간격 넉넉히.
 *   화면 큰 제목 headlineMedium 28 · 세부 화면 제목 headlineSmall 24 · 카드 제목 titleMedium 19
 *   제목은 Bold 대신 SemiBold — 굵기보다 크기로 위아래를 나눔(Oura, 2026-11-25)
 *   본문 bodyLarge 18 · 보통 bodyMedium 16 · 설명 bodySmall 14 · 버튼·칩 labelLarge 16
 * 폰 설정의 글자 크기를 키우면 그만큼 같이 커짐.
 */
private val SsamType = Typography().run {
    fun TextStyle.k(size: Int, line: Int, weight: FontWeight? = null) =
        copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight ?: fontWeight, letterSpacing = 0.sp)
    Typography(
        displayMedium = displayMedium.k(40, 48, FontWeight.SemiBold),
        headlineMedium = headlineMedium.k(28, 36, FontWeight.SemiBold),
        headlineSmall = headlineSmall.k(24, 32, FontWeight.SemiBold),
        titleLarge = titleLarge.k(22, 30, FontWeight.SemiBold),
        titleMedium = titleMedium.k(19, 27, FontWeight.SemiBold),
        titleSmall = titleSmall.k(17, 24, FontWeight.SemiBold),
        bodyLarge = bodyLarge.k(18, 27),
        bodyMedium = bodyMedium.k(16, 24),
        bodySmall = bodySmall.k(14, 21),
        labelLarge = labelLarge.k(16, 22, FontWeight.SemiBold),
        labelMedium = labelMedium.k(14, 20, FontWeight.Medium),
        labelSmall = labelSmall.k(13, 18, FontWeight.Medium),
    )
}

/**
 * 📏 간격 단계(2026-11-23 디자인 규칙 — DESIGN.md): 4 · 8 · 12 · 16 · 24 · 32 만 씀(10·14·6 같은 값 쓰지 않기).
 * 카드 안쪽 l(16)·20, 칸 사이 l, 줄 사이 s, 화면 양옆 xl 안팎.
 */
object Space {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val card = 20.dp; val xl = 24.dp; val xxl = 32.dp
}

/** 칸·카드 제목: 화면 읽기(TalkBack)가 '제목'으로 읽어 칸 사이를 건너뛸 수 있게 (DESIGN.md 접근성) */
fun androidx.compose.ui.Modifier.asHeading() = this.then(androidx.compose.ui.Modifier.semantics { heading() })

/** 모서리: 칩 8 · 입력칸 12 · 카드 24 · 창 28 (Oura). 단추는 Material 3 기본대로 알약(완전히 둥글게). */
private val SsamShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** 폰 설정의 다크 모드를 따라 밝은(종이 크림)/어두운(먹색) 테마. */
@Composable
fun SsamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) SsamDark else SsamLight, typography = SsamType, shapes = SsamShapes, content = content)
}

/**
 * 쌤톡스 카드 (2026-11-22): 흰 종이 + 옅은 테두리 한 줄(Oura hairline). 이 패키지의 `Card(...)` 는 모두 이것을 씀
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
