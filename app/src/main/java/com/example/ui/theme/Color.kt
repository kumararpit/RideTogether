package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// RideTogether design tokens — "premium dark" (approved 14-screen reference).
//
// These values are the single source of truth. Screens must use the semantic
// names below (or the MaterialTheme scheme built from them), never raw hex.
// ─────────────────────────────────────────────────────────────────────────────

// Backgrounds and surfaces
val SlateDark900 = Color(0xFF0B0F12) // background — main app canvas
val SlateDark800 = Color(0xFF11171C) // surface — cards and sheets
val SlateDark700 = Color(0xFF192128) // surfaceElevated — raised cards / controls
val SlateDark600 = Color(0xFF232D35) // surfacePressed — pressed / selected secondary
val SlateDark500 = Color(0xFF29333B) // border — subtle card outlines

// Brand accent — restrained amber, primary actions only
val AmberPrimary = Color(0xFFFF9F2F)
val AmberLight = Color(0xFFFFB65C)
val AmberDark = Color(0xFFE9891D)
val AmberGlow = Color(0x33FF9F2F)

// Semantic status colours
val StatusRidingGreen = Color(0xFF35C982)
val StatusRidingGreenDark = Color(0xFF1F9D63)
val StatusRidingGlow = Color(0x3335C982)

val StatusStoppedAmber = Color(0xFFF5B544)
val StatusStoppedAmberDark = Color(0xFFD89A2E)
val StatusStoppedGlow = Color(0x33F5B544)

val StatusEmergencyRed = Color(0xFFF05D59)
val StatusEmergencyRedDark = Color(0xFFC94A46)
val StatusEmergencyGlow = Color(0x44F05D59)

val StatusOfflineGray = Color(0xFF6F7A83)
val StatusOfflineGlow = Color(0x226F7A83)

// Text
val TextPrimary = Color(0xFFF5F7F8)
val TextSecondary = Color(0xFFA4ADB5)
val TextTertiary = Color(0xFF6F7A83)

// Component aliases (kept for the existing call sites)
val SurfaceCard = SlateDark800
val SurfaceCardElevated = SlateDark700
val DividerColor = SlateDark500

// Optional navigation emphasis (alternate routes / route line)
val RouteBlue = Color(0xFF2587FF)
// The live-guidance banner. The reference paints the maneuver card deep green
// with white text; it is the one surface that is deliberately not charcoal,
// because it must be readable in a single glance at riding speed.
val RideGuidanceGreen = Color(0xFF0E6B3F)
val RideGuidanceGreenDark = Color(0xFF0A5632)
