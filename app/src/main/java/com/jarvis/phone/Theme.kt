package com.jarvis.phone

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    tertiary = Color(0xFFB4553A), onTertiary = Color.White,
    background = Ssam.Chalk, onBackground = Ssam.Ink,
    surface = Ssam.Chalk, onSurface = Ssam.Ink,
    surfaceVariant = Color(0xFFEAE3D2), onSurfaceVariant = Color(0xFF4A5A51),
    surfaceContainer = Color(0xFFFFFCF4), surfaceContainerHighest = Color(0xFFEAE3D2),
    outline = Color(0xFF8A9690),
)

private val Dark = darkColorScheme(
    primary = Ssam.Sprout, onPrimary = Color(0xFF10301F),
    primaryContainer = Ssam.Board, onPrimaryContainer = Ssam.Chalk,
    secondary = Color(0xFFB9D9AE), onSecondary = Color(0xFF1E3A1A),
    tertiary = Ssam.Blush, onTertiary = Color(0xFF4A1A0C),
    background = Ssam.Night, onBackground = Color(0xFFE6ECE8),
    surface = Ssam.Night, onSurface = Color(0xFFE6ECE8),
    surfaceVariant = Color(0xFF2A322E), onSurfaceVariant = Color(0xFFB9C6BE),
    surfaceContainer = Color(0xFF232A26), surfaceContainerHighest = Color(0xFF2E3632),
    outline = Color(0xFF7D8A83),
)

/** 폰 설정의 다크 모드를 따라 밝은(분필 크림)/어두운(밤 칠판) 테마. */
@Composable
fun SsamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
