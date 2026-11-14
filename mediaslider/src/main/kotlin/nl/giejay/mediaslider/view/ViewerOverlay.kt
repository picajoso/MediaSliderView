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

    private fun format(skeleton: String, millis: Long): String {
        val locale = locale()
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(Date(millis))
    }

    /** "25 Oct 2026" */
    fun shortDate(millis: Long): String = format("dMMMyyyy", millis)

    /** "Oct 25, 2026" */
    fun date(millis: Long): String = format("MMMdyyyy", millis)

    /** "Saturday" */
    fun weekday(millis: Long): String = format("EEEE", millis)

    /** "12:14" (respects the 24h setting of the device) */
    fun time(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

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
        setPadding(context.dpToPx(11f), context.dpToPx(7f), context.dpToPx(14f), context.dpToPx(7f))

        heart.setImageResource(R.drawable.ic_favorite_white_filled_24dp)
        addView(heart, LayoutParams(context.dpToPx(14f), context.dpToPx(14f)).apply {
            marginEnd = context.dpToPx(8f)
        })

        place.textSize = 13f
        place.setTextColor(style.pillText)
        place.applyFont(style, true)
        place.setSingleLine(true)
        addView(place, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        separator.text = "·"
        separator.textSize = 13f
        separator.setTextColor(style.pillSeparator)
        separator.applyFont(style, false)
        addView(separator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dpToPx(8f)
            marginEnd = context.dpToPx(8f)
        })

        date.textSize = 13f
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
        val dateText = details?.dateMillis?.let { ViewerFormat.shortDate(it) }
            ?: item.metaData[MetaDataType.DATE]?.takeIf { it.isNotBlank() }

        heart.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
        place.visibility = if (placeText != null) View.VISIBLE else View.GONE
        place.text = placeText
        date.visibility = if (dateText != null) View.VISIBLE else View.GONE
        date.text = dateText
        separator.visibility = if (placeText != null && dateText != null) View.VISIBLE else View.GONE
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

        val gap = context.dpToPx(6f)
        addView(favoriteButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = gap
        })
        addView(slideshowButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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

        // date + time
        if (millis != null) {
            if (style.cardBlocks) {
                addBlock(
                    R.drawable.ic_viewer_calendar,
                    "${ViewerFormat.weekday(millis)}, ${ViewerFormat.date(millis)}",
                    listOf(ViewerFormat.time(ctx, millis))
                )
            } else {
                addDateHeader(ViewerFormat.weekday(millis), "${ViewerFormat.date(millis)} · ${ViewerFormat.time(ctx, millis)}")
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

        // camera + exposure
        if (d != null) {
            val camera = d.cameraName?.takeIf { it.isNotBlank() }
            val exposure = ViewerFormat.exposure(d)
            if (camera != null) {
                addBlock(R.drawable.ic_viewer_camera, camera, listOfNotNull(d.lensModel?.takeIf { it.isNotBlank() }, exposure))
            } else if (exposure != null) {
                addBlock(R.drawable.ic_viewer_camera, exposure, emptyList())
            }

            // size
            val dimensions = ViewerFormat.dimensions(d)
            val sizeLine = ViewerFormat.sizeLine(ctx, d)
            if (dimensions != null) {
                addBlock(R.drawable.ic_viewer_image, dimensions, listOfNotNull(sizeLine))
            } else if (sizeLine != null) {
                addBlock(R.drawable.ic_viewer_image, sizeLine, emptyList())
            }
        }
    }

    private fun addDateHeader(weekday: String, dateTime: String) {
        val ctx = context
        val column = LinearLayout(ctx)
        column.orientation = VERTICAL
        val top = TextView(ctx)
        top.text = weekday
        top.textSize = 13f
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

    private fun addBlock(icon: Int, title: String, lines: List<String>) {
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
        row.addView(iconView, LayoutParams(ctx.dpToPx(16f), ctx.dpToPx(16f)).apply {
            topMargin = ctx.dpToPx(1f)
            marginEnd = ctx.dpToPx(10f)
        })

        val column = LinearLayout(ctx)
        column.orientation = VERTICAL
        val titleView = TextView(ctx)
        titleView.text = title
        titleView.textSize = 14f
        titleView.setTextColor(style.text)
        titleView.applyFont(style, true)
        column.addView(titleView)
        lines.forEach { line ->
            val lineView = TextView(ctx)
            lineView.text = line
            lineView.textSize = 13f
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
        label.textSize = 13f
        label.setSingleLine(true)
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun set(iconRes: Int, text: String, primary: Boolean) {
        val fg = if (primary) style.primaryText else style.secondaryText
        val bg = if (primary) style.primaryBg else style.secondaryBg
        icon.setImageResource(iconRes)
        icon.setColorFilter(fg)
        label.text = text
        label.setTextColor(fg)
        label.applyFont(style, primary)

        val radius = context.dpToPx(style.buttonCornerDp).toFloat()
        fun shape(stroke: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bg)
            if (stroke) setStroke(context.dpToPx(2f), style.focusRing)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(), shape(false))
        }
    }
}
