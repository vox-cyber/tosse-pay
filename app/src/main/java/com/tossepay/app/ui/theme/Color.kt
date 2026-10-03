// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Tosse Pay

package com.tossepay.app.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────
// Tosse Pay design tokens — dark theme.
//
// Every Compose screen draws from these; inline Color(0x…) literals outside
// this package are a CI failure (see the palette-gate step in build.yml).
// The ramp consolidates the near-duplicate greys that had accreted across
// screens (0x1E1E1E vs 0x1A1A1A, 0x8A8A8A vs 0x888888, …) into one value
// per visual role, so "card grey" or "secondary text" can be changed in
// exactly one place.
// ─────────────────────────────────────────────────────────────────────────

// Surfaces (darkest → lightest)
val TossePayBlack = Color(0xFF000000)

/** Screen background behind cards/lists. */
val TossePaySurfaceDim = Color(0xFF0A0A0A)

/** Card / dialog surface. */
val TossePayDarkGray = Color(0xFF1A1A1A)

/** Elevated surface: input fields, chips, avatars. */
val TossePayMediumGray = Color(0xFF2A2A2A)

/** Borders, dividers, inactive track. */
val TossePayLightGray = Color(0xFF333333)

/** Stronger outline / disabled container. */
val TossePayOutlineGray = Color(0xFF4A4A4A)

/** Disabled content / faint hint. */
val TossePayDisabledGray = Color(0xFF555555)

// Text (dimmest → brightest)
/** Placeholder / hint text. */
val TossePayTextGray = Color(0xFF666666)

/** Secondary text: captions, labels, timestamps. */
val TossePayTextLightGray = Color(0xFF888888)

/** Long-form body text on dark dialogs. */
val TossePayTextPale = Color(0xFFCCCCCC)

val TossePayTextWhite = Color(0xFFFFFFFF)

// Card Colors (light card variant)
val TossePayCardBackground = Color(0xFFE8E8E8)
val TossePayCardText = Color(0xFF000000)
val TossePayCardSubtext = Color(0xFF4A4A4A)

// Accents
val TossePayAccentBlue = Color(0xFF4A90E2)
val TossePayAccentGreen = Color(0xFF4CAF50)

/** Bright green used as the light end of success gradients. */
val TossePayAccentGreenBright = Color(0xFF43E97B)

// ─────────────────────────────────────────────────────────────────────────
// Transaction status palette. One color per outcome, used identically in
// the history list, detail dialog and result screen so a status never
// changes meaning between screens.
// ─────────────────────────────────────────────────────────────────────────

/** SUCCESS — bank confirmed. */
val TossePayStatusSuccess = TossePayAccentGreen

/** FAILED / declined, and destructive actions (delete, clear). */
val TossePayStatusError = Color(0xFFF44336)

/** NEEDS_REVIEW / PENDING — user attention required. */
val TossePayStatusWarning = Color(0xFFFF9800)

/** UNVERIFIED / CANCELLED — outcome unknown or nothing happened. Neutral:
 *  deliberately neither success-green nor failure-red. */
val TossePayStatusNeutral = Color(0xFF9E9E9E)

/**
 * The single mapping from a [com.tossepay.app.data.TransactionStatus] string
 * to its display color. Replaces the byte-identical getStatusColor()
 * functions that had been copy-pasted into multiple screens.
 */
fun statusColor(status: String): Color = when (status.uppercase()) {
    "SUCCESS", "SUCCESSFUL", "COMPLETED" -> TossePayStatusSuccess
    "FAILED", "DECLINED" -> TossePayStatusError
    "PENDING", "NEEDS_REVIEW" -> TossePayStatusWarning
    else -> TossePayStatusNeutral // UNVERIFIED, CANCELLED, unknown
}
