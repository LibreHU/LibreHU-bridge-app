package org.librehu.bridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** LibreHU's blue (the launcher's default accent): light tone on dark, deeper one on light. */
private val Dark =
    darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF0B1F3A),
        primaryContainer = Color(0xFF1E3A66),
        onPrimaryContainer = Color(0xFFD6E3FF),
        secondary = Color(0xFF78D9EC),
        tertiary = Color(0xFF81C995),
        background = Color(0xFF0E1116),
        surface = Color(0xFF0E1116),
        surfaceContainer = Color(0xFF181C23),
        surfaceContainerHigh = Color(0xFF20252E),
        surfaceContainerHighest = Color(0xFF2A303A),
    )

private val Light =
    lightColorScheme(
        primary = Color(0xFF1A73E8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E3FF),
        onPrimaryContainer = Color(0xFF0B1F3A),
        secondary = Color(0xFF12858F),
        tertiary = Color(0xFF188038),
        background = Color(0xFFF6F8FC),
        surface = Color(0xFFF6F8FC),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFEEF2F8),
        surfaceContainerHighest = Color(0xFFE3E8F0),
    )

private val Type =
    Typography().run {
        copy(
            headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        )
    }

private val Shape =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
    )

/** LibreHU look; the phone's own colours (Material You, Android 12+) when [dynamic]. */
@Composable
fun BridgeTheme(
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme =
        when {
            dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> Dark
            else -> Light
        }
    MaterialTheme(colorScheme = scheme, typography = Type, shapes = Shape, content = content)
}
