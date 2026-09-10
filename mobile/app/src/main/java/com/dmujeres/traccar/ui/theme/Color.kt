package com.dmujeres.traccar.ui.theme

import androidx.compose.ui.graphics.Color

val Primary = Color(0xFFEB0045)
val PrimaryDark = Color(0xFFC4003B)
val Accent = Color(0xFFFF1464)
val White = Color(0xFFFFFFFF)
val Background = Color(0xFFF6F6F6)
val Ink = Color(0xFF181818)

val StatusOk = Color(0xFF2E7D32)
// NOTA (3 colores estricto): la UI de jornada debe usar JourneyColors
// (rojo #EB0045 / blanco / verde #2E7D32). Los antiguos StatusWarn,
// StatusIdle, StatusOffline y StatusError se eliminaron por no pertenecer
// a la paleta. Excepción: ámbar solo en DiagnosticsActivity.kt con literal
// local. StatusOk se mantiene (== JourneyColors.Verde) para UI fuera de
// jornada como OnboardingActivity.
