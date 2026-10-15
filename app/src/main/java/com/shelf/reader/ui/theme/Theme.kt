package com.shelf.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val ShelfDark = darkColorScheme(
    primary = ShelfColors.Rose,
    onPrimary = ShelfColors.OnAccent,
    primaryContainer = ShelfColors.Raised,
    onPrimaryContainer = ShelfColors.Text,
    secondary = ShelfColors.Jade,
    onSecondary = ShelfColors.OnAccent,
    tertiary = ShelfColors.Amber,
    onTertiary = ShelfColors.OnAccent,
    background = ShelfColors.Ink,
    onBackground = ShelfColors.Text,
    surface = ShelfColors.Panel,
    onSurface = ShelfColors.Text,
    surfaceVariant = ShelfColors.Raised,
    onSurfaceVariant = ShelfColors.Muted,
    surfaceContainer = ShelfColors.Panel,
    surfaceContainerHigh = ShelfColors.Raised,
    surfaceContainerHighest = ShelfColors.RaisedHover,
    surfaceContainerLow = ShelfColors.Panel,
    surfaceContainerLowest = ShelfColors.Ink,
    outline = ShelfColors.Hair,
    outlineVariant = ShelfColors.Hair,
    error = ShelfColors.Danger,
    onError = ShelfColors.OnAccent,
    scrim = ShelfColors.Scrim,
)

private val ShelfShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val ShelfTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 0.6.sp,
    ),
)

@Composable
fun ShelfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShelfDark,
        typography = ShelfTypography,
        shapes = ShelfShapes,
        content = content,
    )
}
