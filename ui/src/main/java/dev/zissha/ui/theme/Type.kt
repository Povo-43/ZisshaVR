package dev.zissha.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tuned for a control surface that mixes dense status text with large
 * emergency buttons. Numbers use tabular-like monospace so FPS / ms values do not
 * jitter while updating.
 */
val AppTypography = Typography()

/** Monospace style for live numeric readouts (FPS, ms, bitrate). */
val MonoReadout = AppTypography.labelMedium.copy(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
)
