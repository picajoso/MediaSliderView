package nl.giejay.mediaslider.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
// Resources of this module
import com.zeuskartik.mediaslider.R
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemDetails
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.dpToPx(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

private fun TextView.applyFont(style: ViewerStyle, title: Boolean) {
    val tf = if (title) style.titleTypeface else style.bodyTypeface
    if (tf != null) typeface = tf
}

/** Locale aware date/time formatting used by the pill and the panel. */
internal object ViewerFormat {
    private fun locale(): Locale = Locale.getDefault(Locale.Category.FORMAT)

    private fun zone(id: String?): java.util.TimeZone? =
        if (id != null && java.util.TimeZone.getAvailableIDs().contains(id)) java.util.TimeZone.getTimeZone(id) else null

    private fun format(skeleton: String, millis: Long, tz: String?): String {
        val locale = locale()
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        val f = SimpleDateFormat(pattern, locale)
        zone(tz)?.let { f.timeZone = it }
        return f.format(Date(millis))
    }

    /** "25 Oct 2026" */
    fun shortDate(millis: Long, tz: String?): String = format("dMMMyyyy", millis, tz)

    /** "Oct 25, 2026" */
    fun date(millis: Long, tz: String?): String = format("MMMdyyyy", millis, tz)

    /** "Saturday" */
    fun weekday(millis: Long, tz: String?): String = format("EEEE", millis, tz)

    /** "12:14" (respects the 24h setting of the device) */
    fun time(context: Context, millis: Long, tz: String?): String {
        val f = DateFormat.getTimeFormat(context)
        zone(tz)?.let { f.timeZone = it }
        return f.format(Date(millis))
    }

    private fun trimNumber(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    /** "24 mm · ƒ/1.7 · 1/1600 s · ISO 50", null when nothing is known */
    fun exposure(d: SliderItemDetails): String? {
        val parts = listOfNotNull(
            d.focalLengthMm?.let { "${trimNumber(it)} mm" },
            d.fNumber?.let { "ƒ/${trimNumber(it)}" },
            d.exposureTime?.takeIf { it.isNotBlank() }?.let { "$it s" },
            d.iso?.let { "ISO $it" }
        )
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** "4080 × 3072" */
    fun dimensions(d: SliderItemDetails): String? {
        val w = d.width
        val h = d.height
        return if (w != null && h != null && w > 0 && h > 0) "$w × $h" else null
    }

    /** "50 MP · 4080 × 3072 · 6,2 MB" */
    fun sizeLineWithDimensions(context: Context, d: SliderItemDetails): String? {
        val w = d.width
        val h = d.height
        val known = w != null && h != null && w > 0 && h > 0
        val parts = listOfNotNull(
            if (known) "${maxOf(1L, Math.round(w!!.toLong() * h!! / 1_000_000.0))} MP" else null,
            if (known) "$w × $h" else null,
            d.fileSizeBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
        )
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** "12 MP · 6.2 MB" */
    fun sizeLine(context: Context, d: SliderItemDetails): String? {
        val w = d.width
        val h = d.height
        val parts = listOfNotNull(
            if (w != null && h != null && w > 0 && h > 0) "${maxOf(1L, Math.round(w.toLong() * h / 1_000_000.0))} MP" else null,
            d.fileSizeBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
        )
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}

/**
 * Small pill at the bottom center of the viewer: [heart if favorite] place  ·  date.
 */
class ViewerInfoPill(context: Context, private val style: ViewerStyle) : LinearLayout(context) {
    private val heart = ImageView(context)
    private val place = TextView(context)
    private val separator = TextView(context)
    private val date = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        isClickable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpToPx(500f).toFloat()
            setColor(style.pillBg)
            setStroke(context.dpToPx(0.5f).coerceAtLeast(1), style.pillStroke)
        }
        setPadding(context.dpToPx(11f), context.dpToPx(style.pillPadVDp), context.dpToPx(14f), context.dpToPx(style.pillPadVDp))

        heart.setImageResource(R.drawable.ic_favorite_white_filled_24dp)
        addView(heart, LayoutParams(context.dpToPx(14f), context.dpToPx(14f)).apply {
            marginEnd = context.dpToPx(8f)
        })

        place.textSize = style.pillTextSp
        place.setTextColor(style.pillText)
        place.applyFont(style, true)
        place.setSingleLine(true)
        addView(place, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        separator.text = "·"
        separator.textSize = style.pillTextSp
        separator.setTextColor(style.pillSeparator)
        separator.applyFont(style, false)
        addView(separator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dpToPx(8f)
            marginEnd = context.dpToPx(8f)
        })

        date.textSize = style.pillTextSp
        date.setTextColor(style.pillTextDim)
        date.applyFont(style, false)
        date.setSingleLine(true)
        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        alpha = 0f
    }

    /** Fills the pill from [item]. Returns false when there is nothing to show. */
    fun bind(item: SliderItem): Boolean {
        val details = item.details
        val placeText = (details?.city ?: item.metaData[MetaDataType.CITY])?.takeIf { it.isNotBlank() }
            ?: (details?.country ?: item.metaData[MetaDataType.COUNTRY])?.takeIf { it.isNotBlank() }
        val dateText = details?.dateMillis?.let { ViewerFormat.shortDate(it, details?.timeZoneId) }
            ?: item.metaData[MetaDataType.DATE]?.takeIf { it.isNotBlank() }

        heart.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
        place.visibility = if (placeText != null) View.VISIBLE else View.GONE
        place.text = placeText
        date.visibility = if (dateText != null) View.VISIBLE else View.GONE
        date.text = dateText
        separator.visibility = if (style.pillShowSeparator && placeText != null && dateText != null) View.VISIBLE else View.GONE
        // without the dot, the date needs its own gap to the place
        (date.layoutParams as? LayoutParams)?.marginStart = if (!style.pillShowSeparator && placeText != null) context.dpToPx(9f) else 0
        return item.isFavorite || placeText != null || dateText != null
    }
}

/**
 * Info side panel of the viewer: date + time, place, camera, exposure, size, and the buttons
 * "Favorite" (toggle) and "Slideshow".
 */
class ViewerInfoPanel(context: Context, private val style: ViewerStyle) : LinearLayout(context) {
    var onFavoriteClick: (() -> Unit)? = null
    var onSlideshowClick: (() -> Unit)? = null

    private val blocks = LinearLayout(context)
    private val favoriteButton = PanelButton(context, style)
    private val slideshowButton = PanelButton(context, style)

    init {
        orientation = VERTICAL
        isFocusable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpToPx(style.panelCornerDp).toFloat()
            setColor(style.panelBg)
            if (style.panelStroke != 0) setStroke(context.dpToPx(0.5f).coerceAtLeast(1), style.panelStroke)
        }
        setPadding(
            context.dpToPx(style.panelPaddingHDp.toFloat()),
            context.dpToPx(style.panelPaddingVDp.toFloat()),
            context.dpToPx(style.panelPaddingHDp.toFloat()),
            context.dpToPx(style.panelPaddingVDp.toFloat())
        )

        if (style.panelHeading) {
            val heading = TextView(context)
            heading.text = "Details"
            heading.textSize = 19f
            heading.setTextColor(style.text)
            heading.applyFont(style, true)
            addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dpToPx(10f)
            })
        }

        blocks.orientation = VERTICAL
        addView(blocks, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // buttons keep room for their focus ring outside the fill: pull them out by that much so
        // the fills line up with the panel content (mockup: 12px between the two fills)
        val ring = if (style.focusRing != 0) context.dpToPx(2f) else 0
        val gap = context.dpToPx(6f)
        addView(favoriteButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(-ring, 0, -ring, gap - 2 * ring)
        })
        addView(slideshowButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(-ring, 0, -ring, -ring)
        })
        clipChildren = false
        clipToPadding = false

        favoriteButton.setOnClickListener { onFavoriteClick?.invoke() }
        slideshowButton.setOnClickListener { onSlideshowClick?.invoke() }
        slideshowButton.set(R.drawable.ic_viewer_play, "Start slideshow", primary = false)

        // keep focus navigation inside the two buttons
        favoriteButton.id = View.generateViewId()
        slideshowButton.id = View.generateViewId()
        favoriteButton.nextFocusDownId = slideshowButton.id
        slideshowButton.nextFocusUpId = favoriteButton.id
        slideshowButton.nextFocusDownId = slideshowButton.id
    }

    fun focusFirstButton() {
        favoriteButton.requestFocus()
    }

    fun isFirstButtonFocused(): Boolean = favoriteButton.isFocused

    fun isAnyButtonFocused(): Boolean = favoriteButton.isFocused || slideshowButton.isFocused

    /** Refreshes the content for [item]. */
    fun bind(item: SliderItem) {
        val ctx = context
        favoriteButton.set(
            if (item.isFavorite) R.drawable.ic_favorite_white_filled_24dp else R.drawable.ic_viewer_heart,
            if (item.isFavorite) "In favorites" else "Favorite",
            primary = item.isFavorite
        )

        blocks.removeAllViews()
        val d = item.details
        val millis = d?.dateMillis
        val tz = d?.timeZoneId

        // date + time
        if (millis != null) {
            if (style.cardBlocks) {
                addBlock(
                    R.drawable.ic_viewer_calendar,
                    "${ViewerFormat.weekday(millis, tz)}, ${ViewerFormat.date(millis, tz)}",
                    listOf(ViewerFormat.time(ctx, millis, tz))
                )
            } else {
                addDateHeader(ViewerFormat.weekday(millis, tz), "${ViewerFormat.date(millis, tz)} · ${ViewerFormat.time(ctx, millis, tz)}")
            }
        } else {
            item.metaData[MetaDataType.DATE]?.takeIf { it.isNotBlank() }?.let {
                addBlock(R.drawable.ic_viewer_calendar, it, emptyList())
            }
        }

        // place
        val city = (d?.city ?: item.metaData[MetaDataType.CITY])?.takeIf { it.isNotBlank() }
        val country = (d?.country ?: item.metaData[MetaDataType.COUNTRY])?.takeIf { it.isNotBlank() }
        if (city != null) {
            addBlock(R.drawable.ic_viewer_pin, city, listOfNotNull(country))
        } else if (country != null) {
            addBlock(R.drawable.ic_viewer_pin, country, emptyList())
        }

        // camera + exposure (+ size in the Apple layout, which has no separate size block)
        if (d != null) {
            val camera = d.cameraName?.takeIf { it.isNotBlank() }
            val exposure = ViewerFormat.exposure(d)
            val dimensions = ViewerFormat.dimensions(d)
            val sizeLine = ViewerFormat.sizeLine(ctx, d)
            if (style.cardBlocks) {
                if (camera != null) {
                    addBlock(R.drawable.ic_viewer_camera, camera, listOfNotNull(exposure))
                } else if (exposure != null) {
                    addBlock(R.drawable.ic_viewer_camera, exposure, emptyList())
                }
                if (dimensions != null) {
                    addBlock(R.drawable.ic_viewer_image, dimensions, listOfNotNull(sizeLine))
                } else if (sizeLine != null) {
                    addBlock(R.drawable.ic_viewer_image, sizeLine, emptyList())
                }
            } else {
                // "50 MP · 4080 × 3072 · 6,2 MB"
                val size = ViewerFormat.sizeLineWithDimensions(ctx, d)
                if (camera != null) {
                    addBlock(R.drawable.ic_viewer_camera, camera, listOfNotNull(exposure, size), titleSp = 12f, lineSp = 10.5f)
                } else if (exposure != null || size != null) {
                    val lines = listOfNotNull(exposure, size)
                    addBlock(R.drawable.ic_viewer_camera, lines.first(), lines.drop(1), titleSp = 12f, lineSp = 10.5f)
                }
            }
        }
    }

    private fun addDateHeader(weekday: String, dateTime: String) {
        val ctx = context
        val column = LinearLayout(ctx)
        column.orientation = VERTICAL
        val top = TextView(ctx)
        top.text = weekday
        top.textSize = 10.5f
        top.setTextColor(style.dim)
        top.applyFont(style, false)
        column.addView(top)
        val main = TextView(ctx)
        main.text = dateTime
        main.textSize = 18f
        main.setTextColor(style.text)
        main.applyFont(style, true)
        column.addView(main)
        blocks.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ctx.dpToPx(15f)
        })
    }

    private fun addBlock(icon: Int, title: String, lines: List<String>, titleSp: Float = if (style.cardBlocks) 13.5f else 13f, lineSp: Float = if (style.cardBlocks) 12f else 11f) {
        val ctx = context
        val row = LinearLayout(ctx)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.TOP
        if (style.cardBlocks) {
            row.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dpToPx(style.blockCornerDp).toFloat()
                setColor(style.blockBg)
            }
            row.setPadding(ctx.dpToPx(12f), ctx.dpToPx(10f), ctx.dpToPx(12f), ctx.dpToPx(10f))
        }

        val iconView = ImageView(ctx)
        iconView.setImageResource(icon)
        iconView.setColorFilter(style.iconTint)
        row.addView(iconView, LayoutParams(ctx.dpToPx(14f), ctx.dpToPx(14f)).apply {
            topMargin = ctx.dpToPx(2f)
            marginEnd = ctx.dpToPx(if (style.cardBlocks) 11f else 8f)
        })

        val column = LinearLayout(ctx)
        column.orientation = VERTICAL
        val titleView = TextView(ctx)
        titleView.text = title
        titleView.textSize = titleSp
        titleView.setTextColor(style.text)
        titleView.applyFont(style, true)
        column.addView(titleView)
        lines.forEach { line ->
            val lineView = TextView(ctx)
            lineView.text = line
            lineView.textSize = lineSp
            lineView.setTextColor(style.dim)
            lineView.applyFont(style, false)
            column.addView(lineView)
        }
        row.addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        blocks.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ctx.dpToPx(if (style.cardBlocks) 6f else 15f)
        })
    }
}

/** Focusable button of the info panel: icon + label, filled when [primary]. Focus shows a ring. */
internal class PanelButton(context: Context, private val style: ViewerStyle) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setPadding(context.dpToPx(11f), context.dpToPx(9f), context.dpToPx(11f), context.dpToPx(9f))

        addView(icon, LayoutParams(context.dpToPx(14f), context.dpToPx(14f)).apply {
            marginEnd = context.dpToPx(8f)
        })
        label.textSize = 11.5f
        label.setSingleLine(true)
        // the button (this view) takes focus; icon and label follow its state for their colors
        icon.isDuplicateParentStateEnabled = true
        label.isDuplicateParentStateEnabled = true
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun set(iconRes: Int, text: String, primary: Boolean) {
        // tvOS / Google TV convention: the FOCUSED button is the filled one; the favourite state
        // is told by the label and the (filled) heart, see ViewerInfoPanel.bind
        val colors = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(style.primaryText, style.secondaryText)
        )
        icon.setImageResource(iconRes)
        icon.imageTintList = colors
        label.text = text
        label.setTextColor(colors)
        label.applyFont(style, primary)

        val radius = context.dpToPx(style.buttonCornerDp).toFloat()
        val ring = if (style.focusRing != 0) context.dpToPx(2f) else 0
        fun fill(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
        val focused = if (ring > 0) {
            // ring outside the fill (CSS box-shadow 0 0 0 4px): the view keeps [ring] of room
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius + ring
                setColor(style.focusRing)
            }
            android.graphics.drawable.LayerDrawable(arrayOf(outer, fill(style.primaryBg))).apply {
                setLayerInset(1, ring, ring, ring, ring)
            }
        } else fill(style.primaryBg)
        val normal = android.graphics.drawable.InsetDrawable(fill(style.secondaryBg), ring)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        setPadding(context.dpToPx(11f) + ring, context.dpToPx(9f) + ring, context.dpToPx(11f) + ring, context.dpToPx(9f) + ring)
        if (style.buttonHeightDp > 0) minimumHeight = context.dpToPx(style.buttonHeightDp.toFloat()) + 2 * ring
    }
}
