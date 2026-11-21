package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class AccentPreset(
    val label: String,
    val primary: Color,
    val secondary: Color
) {
    ELECTRIC_COSMOS("Electric Cosmos", ElectricBlue, NeonViolet),
    NEON_PLASMA("Neon Plasma", NeonViolet, PlasmaMagenta),
    CYBER_TITANIUM("Cyber Titanium", CyberBlue, SilverWhite)
}

data class DskMotionConfig(
    val animations4D: Boolean = true,
    val reducedMotion: Boolean = false
) {
    val isAnimated: Boolean get() = animations4D && !reducedMotion
}

val LocalDskMotion = staticCompositionLocalOf { DskMotionConfig() }
val LocalDskAccent = staticCompositionLocalOf { AccentPreset.ELECTRIC_COSMOS }

@Composable
fun DskLockerTheme(
    darkTheme: Boolean = true,
    accentPreset: AccentPreset = AccentPreset.ELECTRIC_COSMOS,
    motionConfig: DskMotionConfig = DskMotionConfig(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = accentPreset.primary,
            onPrimary = VoidBlack,
            primaryContainer = RoyalIndigo,
            onPrimaryContainer = SilverWhite,
            secondary = accentPreset.secondary,
            onSecondary = VoidBlack,
            secondaryContainer = MidnightPurple,
            onSecondaryContainer = SilverWhite,
            tertiary = PlasmaMagenta,
            onTertiary = VoidBlack,
            background = VoidBlack,
            onBackground = SilverWhite,
            surface = DeepObsidian,
            onSurface = SilverWhite,
            surfaceVariant = MidnightPurple,
            onSurfaceVariant = MetallicSilver,
            outline = NeonViolet.copy(alpha = 0.45f),
            outlineVariant = Color(0xFF2B224A),
            error = CrimsonAlert,
            onError = SilverWhite
        )
    } else {
        lightColorScheme(
            primary = CyberBlue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE7FF),
            onPrimaryContainer = LightInk,
            secondary = NeonViolet,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEDE2FF),
            onSecondaryContainer = LightInk,
            tertiary = PlasmaMagenta,
            background = LightSpaceBg,
            onBackground = LightInk,
            surface = LightGlassSurface,
            onSurface = LightInk,
            surfaceVariant = Color(0xFFE5E8F5),
            onSurfaceVariant = LightMuted,
            outline = NeonViolet.copy(alpha = 0.4f),
            outlineVariant = Color(0xFFCBD1E6),
            error = CrimsonAlert,
            onError = Color.White
        )
    }

    CompositionLocalProvider(
        LocalDskMotion provides motionConfig,
        LocalDskAccent provides accentPreset
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DskTypography,
            content = content
        )
    }
}
