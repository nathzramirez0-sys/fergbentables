package com.blushdesk.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.StockStatus

/**
 * The brand colors as plain 0xRRGGBB integers. Compose, the PDF canvas and the Excel styles all
 * need the same values but each wants a different color type, so the raw numbers live here once
 * and each consumer converts them (Compose through [ShowroomColors]).
 */
object BrandPalette {
    const val SOFT_PINK = 0xFCE4EC
    const val VIBRANT_ROSE = 0xE91E63
    const val DEEP_MAGENTA = 0x880E4F
    const val LAVENDER_BLUSH = 0xFFF0F5
    const val WHITE = 0xFFFFFF
    const val DARK_TEXT = 0x212121
    const val MUTED_TEXT = 0x757575

    /** Muted text on pink or blush backgrounds, where #757575 falls below 4.5:1 contrast. */
    const val MUTED_TEXT_ON_TINT = 0x666666

    /** Second stop of the brand gradient (operator card), between rose and magenta. */
    const val ROSE_DEEP = 0xC2185B
    const val ROSE_TINT = 0xF8D7E3
    const val OUTLINE = 0x9A6A7F
    const val OUTLINE_SOFT = 0xF3C7D6
    const val ERROR = 0xBA1A1A

    /** Foreground and background of a status badge; every pair is at least 4.5:1. */
    data class Tone(val foreground: Int, val background: Int)

    private val BLUE = Tone(0x0D47A1, 0xE3F2FD)
    private val AMBER = Tone(0x8A4B00, 0xFFF1D6)
    private val GREEN = Tone(0x1B5E20, 0xE3F4E5)
    private val RED = Tone(0xB3261E, 0xFDE7E5)

    fun tone(status: FulfillmentStatus): Tone = when (status) {
        FulfillmentStatus.PROCESSING -> BLUE
        FulfillmentStatus.PREPARING -> AMBER
        FulfillmentStatus.DELIVERED -> GREEN
    }

    fun tone(status: PaymentStatus): Tone = when (status) {
        PaymentStatus.UNPAID -> RED
        PaymentStatus.PENDING -> AMBER
        PaymentStatus.PAID -> GREEN
    }

    /** Products withdrawn from sale (unavailable, discontinued, archived) are neutral gray. */
    private val GRAY = Tone(0x5F5F5F, 0xEEEEEE)

    fun tone(status: StockStatus): Tone = when (status) {
        StockStatus.IN_STOCK -> GREEN
        StockStatus.LOW_STOCK -> AMBER
        StockStatus.OUT_OF_STOCK -> RED
        StockStatus.UNAVAILABLE, StockStatus.DISCONTINUED, StockStatus.ARCHIVED -> GRAY
    }
}

/**
 * The palette as Compose colors. Composables read these through MaterialTheme or [ShowroomTheme].
 *
 * Initialization note: this object must not depend on any other top-level value of the theme
 * package. An earlier version called a top-level helper here, which initialized
 * [DefaultExtendedColors] while this object was still half built, so every extended color came
 * out transparent. ThemeColorsTest guards against that.
 */
object ShowroomColors {
    /** An opaque Compose color from a 0xRRGGBB value. */
    fun rgb(value: Int) = Color(0xFF000000.toInt() or value)

    val SoftPink = rgb(BrandPalette.SOFT_PINK)
    val VibrantRose = rgb(BrandPalette.VIBRANT_ROSE)
    val DeepMagenta = rgb(BrandPalette.DEEP_MAGENTA)
    val LavenderBlush = rgb(BrandPalette.LAVENDER_BLUSH)
    val White = rgb(BrandPalette.WHITE)
    val DarkText = rgb(BrandPalette.DARK_TEXT)
    val MutedText = rgb(BrandPalette.MUTED_TEXT)
    val MutedTextOnTint = rgb(BrandPalette.MUTED_TEXT_ON_TINT)
    val RoseDeep = rgb(BrandPalette.ROSE_DEEP)
    val RoseTint = rgb(BrandPalette.ROSE_TINT)
    val Outline = rgb(BrandPalette.OUTLINE)
    val OutlineSoft = rgb(BrandPalette.OUTLINE_SOFT)
    val Error = rgb(BrandPalette.ERROR)
}

/** A badge's text and fill. */
@Immutable
data class ToneColors(val foreground: Color, val background: Color)

fun BrandPalette.Tone.toColors() = ToneColors(ShowroomColors.rgb(foreground), ShowroomColors.rgb(background))

/**
 * Brand colors Material's color scheme has no slot for. Provided by [ShowroomPinkTheme] and read
 * with `ShowroomTheme.colors`.
 */
@Immutable
data class ShowroomExtendedColors(
    /** Secondary text placed on SoftPink / LavenderBlush surfaces. */
    val mutedOnTint: Color,
    /** Operator card gradient and the text drawn on it. */
    val brandGradientStart: Color,
    val brandGradientEnd: Color,
    val onBrand: Color,
    val onBrandMuted: Color,
    val brandGlow: Color,
    /** Neutral badges (date, payment mode). */
    val neutralBadge: ToneColors,
) {
    fun tone(status: FulfillmentStatus): ToneColors = BrandPalette.tone(status).toColors()
    fun tone(status: PaymentStatus): ToneColors = BrandPalette.tone(status).toColors()
    fun tone(status: StockStatus): ToneColors = BrandPalette.tone(status).toColors()

    /** Amber and green for states that are not an order or stock status (a backup that is due or fresh). */
    val warning: ToneColors get() = BrandPalette.tone(PaymentStatus.PENDING).toColors()
    val success: ToneColors get() = BrandPalette.tone(PaymentStatus.PAID).toColors()
}

/** Built on first use, after [ShowroomColors] is complete, never during its initialization. */
internal val DefaultExtendedColors: ShowroomExtendedColors by lazy { buildExtendedColors() }

private fun buildExtendedColors() = ShowroomExtendedColors(
    mutedOnTint = ShowroomColors.MutedTextOnTint,
    brandGradientStart = ShowroomColors.DeepMagenta,
    brandGradientEnd = ShowroomColors.RoseDeep,
    onBrand = ShowroomColors.White,
    onBrandMuted = ShowroomColors.White.copy(alpha = 0.9f),
    brandGlow = ShowroomColors.White.copy(alpha = 0.08f),
    neutralBadge = ToneColors(ShowroomColors.MutedTextOnTint, ShowroomColors.SoftPink),
)
