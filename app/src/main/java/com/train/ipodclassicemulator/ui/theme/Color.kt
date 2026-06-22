package com.train.ipodclassicemulator.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Una singola "skin" colore per l'emulatore iPod Classic.
 * bodyXxx = scocca esterna, screenXxx = LCD, wheelXxx = click wheel.
 */
data class IPodColorPalette(
    val bodyPrimary: Color,
    val bodySecondary: Color,
    val bodyEdge: Color,
    val screenBackground: Color,
    val screenText: Color,
    val screenAccent: Color,
    val screenSecondary: Color,
    val screenSelectedBg: Color,
    val screenSelectedText: Color,
    val wheelBase: Color,
    val wheelHighlight: Color,
    val wheelText: Color,
    val centerButton: Color,
    val centerButtonPressed: Color
)

object IPodPalettes {

    // 🤍 iPod Classic bianco originale (Schermo a colori)
    val ClassicWhite = IPodColorPalette(
        bodyPrimary = Color(0xFFF6F6F2),
        bodySecondary = Color(0xFFE6E6E0),
        bodyEdge = Color(0xFFC4C4BC),
        screenBackground = Color(0xFFFFFFFF),
        screenText = Color(0xFF000000),
        screenAccent = Color(0xFF0066CC),
        screenSecondary = Color(0xFFF2F2F7),
        screenSelectedBg = Color(0xFF0066CC),
        screenSelectedText = Color(0xFFFFFFFF),
        wheelBase = Color(0xFFE6E6E0),
        wheelHighlight = Color(0xFFFFFFFF),
        wheelText = Color(0xFF6B6B6B),
        centerButton = Color(0xFFF6F6F2),
        centerButtonPressed = Color(0xFFD6D6CC)
    )

    // ⚫ iPod Classic nero (Schermo a colori)
    val ClassicBlack = IPodColorPalette(
        bodyPrimary = Color(0xFF2B2B2B),
        bodySecondary = Color(0xFF1A1A1A),
        bodyEdge = Color(0xFF0D0D0D),
        screenBackground = Color(0xFFFFFFFF),
        screenText = Color(0xFF000000),
        screenAccent = Color(0xFF0066CC),
        screenSecondary = Color(0xFFF2F2F7),
        screenSelectedBg = Color(0xFF0066CC),
        screenSelectedText = Color(0xFFFFFFFF),
        wheelBase = Color(0xFF3A3A3A),
        wheelHighlight = Color(0xFF565656),
        wheelText = Color(0xFFC0C0C0),
        centerButton = Color(0xFF2B2B2B),
        centerButtonPressed = Color(0xFF161616)
    )

    // ⬜ Silver / Acciaio spazzolato (Schermo a colori)
    val Silver = IPodColorPalette(
        bodyPrimary = Color(0xFFD8DADC),
        bodySecondary = Color(0xFFC2C5C8),
        bodyEdge = Color(0xFF9EA2A6),
        screenBackground = Color(0xFFFFFFFF),
        screenText = Color(0xFF000000),
        screenAccent = Color(0xFF0066CC),
        screenSecondary = Color(0xFFF2F2F7),
        screenSelectedBg = Color(0xFF0066CC),
        screenSelectedText = Color(0xFFFFFFFF),
        wheelBase = Color(0xFFC2C5C8),
        wheelHighlight = Color(0xFFF1F2F3),
        wheelText = Color(0xFF54585B),
        centerButton = Color(0xFFD8DADC),
        centerButtonPressed = Color(0xFFAEB2B6)
    )

    // ❤️ Product (RED) Special Edition (Schermo a colori)
    val ProductRed = IPodColorPalette(
        bodyPrimary = Color(0xFFB8121C),
        bodySecondary = Color(0xFF8E0E16),
        bodyEdge = Color(0xFF5C0A0F),
        screenBackground = Color(0xFFFFFFFF),
        screenText = Color(0xFF000000),
        screenAccent = Color(0xFFB8121C),
        screenSecondary = Color(0xFFF2F2F7),
        screenSelectedBg = Color(0xFFB8121C),
        screenSelectedText = Color(0xFFFFFFFF),
        wheelBase = Color(0xFF2B2B2B),
        wheelHighlight = Color(0xFF4F4F4F),
        wheelText = Color(0xFFE0E0E0),
        centerButton = Color(0xFFECECEC),
        centerButtonPressed = Color(0xFFC8C8C8)
    )
}

enum class IPodThemeType(val displayName: String) {
    CLASSIC_WHITE("Classic White"),
    CLASSIC_BLACK("Classic Black"),
    SILVER("Silver"),
    PRODUCT_RED("Product (RED)");

    fun palette(): IPodColorPalette = when (this) {
        CLASSIC_WHITE -> IPodPalettes.ClassicWhite
        CLASSIC_BLACK -> IPodPalettes.ClassicBlack
        SILVER -> IPodPalettes.Silver
        PRODUCT_RED -> IPodPalettes.ProductRed
    }
}