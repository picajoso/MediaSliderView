package nl.giejay.mediaslider.view

import android.graphics.Typeface

/**
 * Look of the viewer overlay (info pill + info panel). The host app maps its own design tokens to
 * this; the defaults are the "Apple" look. Colors are ARGB ints, sizes dp/sp.
 */
data class ViewerStyle(
    // pill
    val pillBg: Int = 0x9E1C1C1E.toInt(),
    val pillStroke: Int = 0x1FFFFFFF,
    val pillText: Int = 0xFFF5F5F7.toInt(),
    val pillTextDim: Int = 0xBFF5F5F7.toInt(),
    val pillSeparator: Int = 0x73F5F5F7,

    // panel
    val panelBg: Int = 0xFF1C1C1E.toInt(),
    val panelStroke: Int = 0x14FFFFFF,
    val panelWidthDp: Int = 260,
    /** outer margin of the panel (floating card in Apple, 0 = flush with the screen edge) */
    val panelMarginDp: Int = 16,
    val panelCornerDp: Float = 13f,
    val panelPaddingHDp: Int = 20,
    val panelPaddingVDp: Int = 22,
    /** "Details" heading above the blocks (Google TV) */
    val panelHeading: Boolean = false,

    val text: Int = 0xFFF5F5F7.toInt(),
    val dim: Int = 0xFFA1A1A6.toInt(),
    val iconTint: Int = 0xFFA1A1A6.toInt(),

    /** every info block sits on its own rounded surface (Google TV) */
    val cardBlocks: Boolean = false,
    val blockBg: Int = 0xFF2B2C31.toInt(),
    val blockCornerDp: Float = 9f,

    // buttons
    val primaryBg: Int = 0xFFF5F5F7.toInt(),
    val primaryText: Int = 0xFF000000.toInt(),
    val secondaryBg: Int = 0x14FFFFFF,
    val secondaryText: Int = 0xFFF5F5F7.toInt(),
    val focusRing: Int = 0xFFFFFFFF.toInt(),
    val buttonCornerDp: Float = 8f,

    val titleTypeface: Typeface? = null,
    val bodyTypeface: Typeface? = null
)
