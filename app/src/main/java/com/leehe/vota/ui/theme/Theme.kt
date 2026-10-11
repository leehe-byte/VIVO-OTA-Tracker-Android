package com.leehe.vota.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 深色底：液态玻璃要衬得出来，背景必须够暗、够有对比 */
val InkBlack = Color(0xFF06070C)
val GlassWhite = Color(0xFFFFFFFF)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7CC7FF),
    onPrimary = Color(0xFF00131F),
    secondary = Color(0xFFB9C7FF),
    background = InkBlack,
    onBackground = Color(0xFFF2F4F8),
    surface = InkBlack,
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF14161F),
    onSurfaceVariant = Color(0xFFC6CAD6),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun VotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
