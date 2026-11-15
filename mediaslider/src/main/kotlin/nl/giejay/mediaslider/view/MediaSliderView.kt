package nl.giejay.mediaslider.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.viewpager.widget.ViewPager
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
// IMPORTANTE: Usamos los recursos de este módulo
import com.zeuskartik.mediaslider.R 
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.adapter.AlignOption
import nl.giejay.mediaslider.adapter.MetaDataAdapter
import nl.giejay.mediaslider.adapter.MetaDataClock
import nl.giejay.mediaslider.adapter.MetaDataMediaCount
import nl.giejay.mediaslider.adapter.ScreenSlidePagerAdapter
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.util.FixedSpeedScroller
import nl.giejay.mediaslider.util.MediaSliderListener
import timber.log.Timber
import java.lang.reflect.Field

class MediaSliderView(context: Context) : ConstraintLayout(context) {
    private var playButton: View
    private var mainHandler: Handler
    private var mPager: ViewPager
    
    // Icono de Favorito
    private var favoriteIcon: ImageView

    // --- Viewer overlay (info pill + info side panel), enabled by the host app ---
    private var viewerOverlayEnabled = false
    private var viewerStyle: ViewerStyle = ViewerStyle()
    private var infoPill: ViewerInfoPill? = null
    private var infoPanel: ViewerInfoPanel? = null
    private var infoPanelOpen = false
    private var infoPanelReservedPx = 0
    private var photoInsetPx = 0
    private var swallowBackUp = false
    private val hideInfoPillRunnable = Runnable { hideInfoPillNow() }

    /** Asked when the "Favorite" button of the info panel is pressed; the host toggles the favorite. */
    var onToggleFavoriteRequested: (() -> Unit)? = null

    // Callback para avisar al exterior
    var onFavoriteStateChanged: ((SliderItemViewHolder, Boolean) -> Unit)? = null

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                if(currentItemType() == SliderItemType.VIDEO && currentPlayerInScope?.volume == 0f){
                    currentPlayerView?.findViewById<ImageButton>(R.id.exo_mute)?.performClick()
                }
            }
        }
    }

    private lateinit var config: MediaSliderConfiguration
    private lateinit var metaDataLeftAdapter: MetaDataAdapter
    private lateinit var metaDataRightAdapter: MetaDataAdapter

    private var currentPlayerInScope: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null
    private var defaultExoFactory = DefaultHttpDataSource.Factory()
    private var slideShowPlaying = false
    private val goToNextAssetRunnable = Runnable { this.goToNextAsset() }
    private var pagerAdapter: ScreenSlidePagerAdapter? = null
    private var loading = false
    private val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private val transformResults = mutableMapOf<Int, String>()
    private var currentToast: Toast? = null

    var onLongPressCenterListener: (() -> Unit)? = null
    private var isCenterKeyDown = false
    private var isLongPressTriggered = false
    private val longPressTimeout = 500L
    private val longPressRunnable = Runnable {
        isLongPressTriggered = true
        onLongPressCenterListener?.invoke()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        inflate(getContext(), R.layout.slider, this)
        playButton = findViewById(R.id.playPause)
        playButton.setOnClickListener { toggleSlideshow(true) }
        mPager = findViewById(R.id.pager)
        mainHandler = Handler(Looper.getMainLooper())

        // --- INYECTAR ICONO FAVORITO ---
        favoriteIcon = ImageView(context)
        favoriteIcon.id = View.generateViewId()
        // CORRECCIÓN: Usamos el corazón BLANCO RELLENO
        favoriteIcon.setImageResource(R.drawable.ic_favorite_white_filled_24dp)
        favoriteIcon.visibility = GONE 
        
        val params = LayoutParams(60, 60)
        addView(favoriteIcon, params)

        // Asignar IDs a hijos sin ID para que ConstraintSet funcione
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.id == View.NO_ID) {
                child.id = View.generateViewId()
            }
        }
        
        val set = ConstraintSet()
        set.clone(this)
        set.connect(favoriteIcon.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 40)
        set.connect(favoriteIcon.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 40)
        set.applyTo(this)
    
        // Click en el corazón
        favoriteIcon.setOnClickListener {
            val current = currentItem()
            val item = if (current.hasSecondaryItem()) current.mainItem else current.mainItem 
            item.isFavorite = !item.isFavorite
            // Avisamos al fragmento
            onFavoriteStateChanged?.invoke(current, item.isFavorite)
            // Actualizamos icono visualmente
            updateFavoriteIcon() 
        }

        updateFavoriteIcon()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(volumeReceiver, filter)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(hideInfoPillRunnable)
        context.unregisterReceiver(volumeReceiver)
    }

    @OptIn(UnstableApi::class)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewerOverlayEnabled) {
            if (event.action == KeyEvent.ACTION_DOWN && !infoPanelOpen) {
                // any key press brings the info pill back for 3 seconds
                showInfoPill()
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && swallowBackUp) {
                // the matching DOWN closed the info panel, do not let the UP leave the viewer
                swallowBackUp = false
                return true
            }
            if (infoPanelOpen && handleInfoPanelKey(event)) {
                return true
            }
            if (!infoPanelOpen && event.action == KeyEvent.ACTION_DOWN && ::config.isInitialized &&
                mPager.adapter != null && config.items.isNotEmpty() && mPager.currentItem in config.items.indices) {
                val isInfoKey = event.keyCode == KeyEvent.KEYCODE_INFO
                val isDownOnImage = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        currentItemType() == SliderItemType.IMAGE && !slideShowPlaying
                if (isInfoKey || isDownOnImage) {
                    openInfoPanel()
                    return true
                }
            }
        }
        if (infoPanelOpen) {
            // while the panel is open only the panel buttons take center / up / down
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isCenterKeyDown = true
                    isLongPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                }
                return true
            } 
            else if (event.action == KeyEvent.ACTION_UP) {
                isCenterKeyDown = false
                mainHandler.removeCallbacks(longPressRunnable)
                if (isLongPressTriggered) {
                    isLongPressTriggered = false
                    return true
                }
                if (currentItemType() == SliderItemType.IMAGE) {
                    toggleSlideshow(true)
                } else if (currentPlayerView != null) {
                     if (currentPlayerInScope?.isPlaying == true) currentPlayerInScope?.pause()
                     else currentPlayerInScope?.play()
                }
                return true
            }
        }

        if (mPager.adapter == null) {
            return super.dispatchKeyEvent(event)
        }
        val itemType = currentItemType()
        
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (context is MediaSliderListener && (context as MediaSliderListener).onButtonPressed(event)) {
                return false
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && itemType == SliderItemType.VIDEO && currentPlayerView != null) {
                currentPlayerView!!.useController = true
                currentPlayerView!!.showController()
                return super.dispatchKeyEvent(event)
            } else if (event.keyCode == KeyEvent.KEYCODE_BACK && itemType == SliderItemType.VIDEO && currentPlayerView != null && currentPlayerView!!.isControllerFullyVisible) {
                currentPlayerView!!.hideController()
                return true
            } else if (slideShowPlaying && itemType == SliderItemType.IMAGE) {
                if (event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    toggleSlideshow(true)
                } else {
                    mainHandler.removeCallbacks(goToNextAssetRunnable)
                    goToNextAsset()
                    return false
                }
                return super.dispatchKeyEvent(event)
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (itemType == SliderItemType.IMAGE || currentPlayerView?.isControllerFullyVisible == false) {
                    goToNextAsset()
                } else if (currentPlayerView?.isControllerFullyVisible == true) {
                    return super.dispatchKeyEvent(event)
                }
                return false
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (itemType == SliderItemType.IMAGE || currentPlayerView?.isControllerFullyVisible == false) {
                    Timber.tag("SLIDER").d("DPAD_LEFT. Index: ${mPager.currentItem}. LoadPrevious: ${config.loadPrevious}")
                    if (mPager.currentItem == 0 && config.loadPrevious != null) {
                        Timber.tag("SLIDER").d("Triggering handleLoadPrevious()")
                        handleLoadPrevious()
                        return true
                    }
                    goToPreviousAsset()
                    return false
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return if (itemType == SliderItemType.IMAGE) false else super.dispatchKeyEvent(event)
    }

    private fun handleLoadPrevious() {
        val loadPrevious = config.loadPrevious ?: return
        
        ioScope.launch {
            val newItems = loadPrevious()
            Timber.d("SLIDER: Loaded ${newItems.size} previous items.")
            
            if (newItems.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    // Update Configuration Items
                    val currentItems = config.items
                    val combinedItems = newItems + currentItems
                    config.items = combinedItems
                    
                    Timber.d("SLIDER: Merged items. New Size: ${combinedItems.size}.")

                    // Update View
                    setItems(combinedItems)
                    
                    val newIndex = newItems.size - 1
                    Timber.d("SLIDER: Setting new index to $newIndex")
                    mPager.setCurrentItem(newIndex, false) // False to avoid animation during jump
                    
                    Toast.makeText(context, "Cargando mes anterior...", Toast.LENGTH_SHORT).show()
                }
            } else {
                 withContext(Dispatchers.Main) {
                     Toast.makeText(context, "No hay más fotos recientes", Toast.LENGTH_SHORT).show()
                 }
            }
        }
    }

    private fun goToPreviousAsset() {
        mPager.setCurrentItem((if (0 == mPager.currentItem) mPager.adapter!!.count else mPager.currentItem) - 1,
            config.enableSlideAnimation)
    }

    fun loadMediaSliderView(config: MediaSliderConfiguration) {
        this.config = config

        val listViewRight = findViewById<ListView>(R.id.metadata_view_right)
        metaDataRightAdapter = MetaDataAdapter(context,
            config.metaDataConfig.filter { it.align == AlignOption.RIGHT },
            config.metaDataConfig.map { it.withAlign(align = AlignOption.RIGHT) }.distinct(),
            { metaData, sliderItem, textView ->
                metaData.updateView(textView, sliderItem, mPager.currentItem, config.items.size)
            },
            { if (currentItem().hasSecondaryItem()) currentItem().secondaryItem!! else currentItem().mainItem },
            { currentItem().hasSecondaryItem() })
        listViewRight.divider = null
        listViewRight.adapter = metaDataRightAdapter

        val listViewLeft = findViewById<ListView>(R.id.metadata_view_left)
        metaDataLeftAdapter = MetaDataAdapter(context,
            config.metaDataConfig.filter { it.align == AlignOption.LEFT },
            config.metaDataConfig.filterNot { it is MetaDataClock || it is MetaDataMediaCount }
                .map { it.withAlign(align = AlignOption.LEFT) }.distinct(),
            { metaData, sliderItem, textView ->
                metaData.updateView(textView, sliderItem, mPager.currentItem, config.items.size)
            },
            { currentItem().mainItem },
            { currentItem().hasSecondaryItem() })
        listViewLeft.divider = null
        listViewLeft.adapter = metaDataLeftAdapter

        val listener: Player.Listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && slideShowPlaying) {
                    goToNextAsset()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val playPauseButton = findViewById<ImageButton>(R.id.exo_pause)
                playPauseButton?.setImageResource(if (isPlaying) R.drawable.exo_legacy_controls_pause else R.drawable.exo_legacy_controls_play)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (slideShowPlaying) {
                    goToNextAsset()
                }
            }
        }
        initViewsAndSetAdapter(listener)
        updateFavoriteIcon()
        if (viewerOverlayEnabled) {
            showInfoPill()
        }
    }

    /**
     * Turns the viewer into "photo only" with a transient info pill (heart if favorite, place,
     * date) and an info side panel (down / Info key). The classic metadata lists and the corner
     * heart are hidden. Call before [loadMediaSliderView].
     */
    fun enableViewerOverlay(style: ViewerStyle) {
        viewerStyle = style
        viewerOverlayEnabled = true
        val density = resources.displayMetrics.density

        findViewById<LinearLayout>(R.id.meta_data_holder)?.visibility = GONE
        favoriteIcon.visibility = GONE

        infoPill?.let { removeView(it) }
        infoPanel?.let { removeView(it) }

        val pill = ViewerInfoPill(context, style)
        pill.elevation = 12 * density
        val pillParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        pillParams.bottomToBottom = LayoutParams.PARENT_ID
        pillParams.startToStart = LayoutParams.PARENT_ID
        pillParams.endToEnd = LayoutParams.PARENT_ID
        pillParams.bottomMargin = (24 * density).toInt()
        addView(pill, pillParams)
        infoPill = pill

        val panel = ViewerInfoPanel(context, style)
        panel.elevation = 14 * density
        panel.visibility = GONE
        val margin = (style.panelMarginDp * density).toInt()
        val panelParams = LayoutParams((style.panelWidthDp * density).toInt(), 0)
        panelParams.topToTop = LayoutParams.PARENT_ID
        panelParams.bottomToBottom = LayoutParams.PARENT_ID
        panelParams.endToEnd = LayoutParams.PARENT_ID
        panelParams.setMargins(0, margin, margin, margin)
        addView(panel, panelParams)
        panel.onFavoriteClick = { onToggleFavoriteRequested?.invoke() }
        panel.onSlideshowClick = {
            closeInfoPanel()
            toggleSlideshow(true)
        }
        infoPanel = panel
        // photo area keeps a small gap to the panel
        infoPanelReservedPx = ((style.panelWidthDp + style.panelMarginDp + 8) * density).toInt()
        photoInsetPx = (style.photoInsetDp * density).toInt()
    }

    private fun showInfoPill() {
        val pill = infoPill ?: return
        if (!::config.isInitialized || mPager.adapter == null || infoPanelOpen) return
        if (config.items.isEmpty() || mPager.currentItem !in config.items.indices) return
        if (!pill.bind(currentItem().mainItem)) {
            hideInfoPillNow()
            return
        }
        mainHandler.removeCallbacks(hideInfoPillRunnable)
        pill.animate().cancel()
        pill.animate().alpha(1f).setDuration(150).start()
        mainHandler.postDelayed(hideInfoPillRunnable, INFO_PILL_VISIBLE_MS)
    }

    private fun hideInfoPillNow() {
        mainHandler.removeCallbacks(hideInfoPillRunnable)
        val pill = infoPill ?: return
        pill.animate().cancel()
        pill.animate().alpha(0f).setDuration(300).start()
    }

    private fun openInfoPanel() {
        val panel = infoPanel ?: return
        if (infoPanelOpen) return
        if (slideShowPlaying) {
            toggleSlideshow(false)
        }
        infoPanelOpen = true
        mainHandler.removeCallbacks(hideInfoPillRunnable)
        infoPill?.animate()?.cancel()
        infoPill?.alpha = 0f
        panel.bind(currentItem().mainItem)
        panel.visibility = VISIBLE
        panel.translationX = infoPanelReservedPx.toFloat()
        panel.animate().translationX(0f).setDuration(180).start()
        // re-layout the photo to the left so the panel never covers it
        setPagerMargins(photoInsetPx, infoPanelReservedPx + photoInsetPx)
        panel.focusFirstButton()
    }

    private fun closeInfoPanel() {
        val panel = infoPanel ?: return
        if (!infoPanelOpen) return
        infoPanelOpen = false
        // hand focus back to the pager first, otherwise key events stop reaching this view
        mPager.requestFocus()
        panel.animate().cancel()
        panel.animate().translationX(infoPanelReservedPx.toFloat()).setDuration(150).withEndAction {
            if (!infoPanelOpen) panel.visibility = GONE
        }.start()
        setPagerMargins(0, 0)
        showInfoPill()
    }

    /** Re-lays the photo out: [inset] on the start/top/bottom, [end] on the panel side. */
    private fun setPagerMargins(inset: Int, end: Int) {
        val lp = mPager.layoutParams as? LayoutParams ?: return
        if (lp.marginEnd == end && lp.marginStart == inset && lp.topMargin == inset) return
        lp.marginStart = inset
        lp.topMargin = inset
        lp.bottomMargin = inset
        lp.marginEnd = end
        mPager.layoutParams = lp
    }

    /** Key handling while the info panel is open. Returns true when the event was consumed here. */
    private fun handleInfoPanelKey(event: KeyEvent): Boolean {
        val panel = infoPanel ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) {
            // swallow the UP of keys we consume on DOWN
            return event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                swallowBackUp = true
                closeInfoPanel()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                closeInfoPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (panel.isFirstButtonFocused() || !panel.isAnyButtonFocused()) {
                    closeInfoPanel()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                goToPreviousAsset()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                goToNextAsset()
                return true
            }
        }
        return false
    }

    fun updateFavoriteIcon() {
        updateFavoriteIcon(showPill = true)
    }

    private fun updateFavoriteIcon(showPill: Boolean) {
        if (!::config.isInitialized) return
        val item = currentItem().mainItem
        if (viewerOverlayEnabled) {
            // the corner heart is replaced by the pill / panel
            favoriteIcon.visibility = GONE
            if (infoPanelOpen) {
                infoPanel?.bind(item)
            } else if (showPill) {
                showInfoPill()
            }
            return
        }
        if (item.isFavorite) {
            favoriteIcon.visibility = VISIBLE
        } else {
            favoriteIcon.visibility = GONE
        }
    }

    private fun setStartPosition() {
        if (config.startPosition >= 0) {
            if (config.startPosition > config.items.size) {
                mPager.currentItem = (config.items.size - 1)
                return
            }
            mPager.currentItem = config.startPosition
        } else {
            mPager.currentItem = 0
        }
        mPager.offscreenPageLimit = 1
    }

    fun toggleSlideshow(togglePlayButton: Boolean) {
        slideShowPlaying = !slideShowPlaying
        if (slideShowPlaying) {
            if (currentItemType() == SliderItemType.IMAGE) {
                startTimerNextAsset()
            }
            if (context is Activity) {
                (context as Activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } else {
            clearKeepScreenOnFlags()
            mainHandler.removeCallbacks(goToNextAssetRunnable)
        }
        if (togglePlayButton) {
            togglePlayButton()
        }
    }

    private fun togglePlayButton() {
        playButton.visibility = VISIBLE
        playButton.setBackgroundResource(if (slideShowPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
        mainHandler.postDelayed({
            playButton.visibility = GONE
        }, 2000)
    }

    private fun startTimerNextAsset() {
        mainHandler.removeCallbacks(goToNextAssetRunnable)
        mainHandler.postDelayed(goToNextAssetRunnable, (config.interval * 1000).toLong())
    }

    private fun goToNextAsset() {
        if (mPager.currentItem < mPager.adapter!!.count - 1) {
            mPager.setCurrentItem(mPager.currentItem + 1, config.enableSlideAnimation)
        } else {
            mPager.setCurrentItem(0, config.enableSlideAnimation)
        }
    }

    private fun initViewsAndSetAdapter(listener: Player.Listener) {
        pagerAdapter = ScreenSlidePagerAdapter(
            context, config.items,
            defaultExoFactory,
            config,
            { result, position -> transformResults[position] = result },
            {
                when (it) {
                    R.id.exo_rewind -> goToPreviousAsset()
                    R.id.exo_forward -> goToNextAsset()
                }
            }
        )

        try {
            if (config.enableSlideAnimation && config.animationSpeedMillis > 0) {
                val mScroller: Field = ViewPager::class.java.getDeclaredField("mScroller")
                mScroller.isAccessible = true
                val scroller = FixedSpeedScroller(mPager.context, DecelerateInterpolator(0.75F), config.animationSpeedMillis)
                mScroller.set(mPager, scroller)
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not set scroller")
        }

        mPager.setAdapter(pagerAdapter)
        setStartPosition()
        mPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(i: Int, v: Float, i1: Int) {
                stopPlayer()
                if (i != mPager.currentItem) {
                    return
                }
                updateFavoriteIcon()
                
                val sliderItem = config.items[i]
                config.onAssetSelected(sliderItem)
                metaDataRightAdapter.notifyDataSetChanged()
                metaDataLeftAdapter.notifyDataSetChanged()
                currentToast?.cancel()
                if (!sliderItem.hasSecondaryItem() && config.debugEnabled && transformResults.contains(i)) {
                    currentToast = Toast.makeText(context, transformResults[i], Toast.LENGTH_LONG)
                    currentToast!!.show()
                    transformResults.remove(i)
                }

                if (config.loadMore != null && mPager.currentItem > config.items.size - 10 && !loading) {
                    loading = true
                    ioScope.launch {
                        val nextItems = config.loadMore!!.invoke()
                        addItemsMain(nextItems)
                        loading = nextItems.isEmpty()
                    }
                }

                val statusLayoutLeft = findViewById<LinearLayout>(R.id.meta_data_holder)
                if (sliderItem.type == SliderItemType.VIDEO) {
                    if (config.isGradiantOverlayVisible) {
                        statusLayoutLeft.background = null
                    }
                    val viewTag = mPager.findViewWithTag<View>("view$i") ?: return
                    currentPlayerView = viewTag.findViewById(R.id.video_view)
                    if (currentPlayerView!!.player != null) {
                        currentPlayerInScope = currentPlayerView!!.player as ExoPlayer?
                        currentPlayerInScope!!.seekTo(0, 0)
                        if (currentPlayerInScope!!.playbackState == Player.STATE_IDLE && sliderItem.url != null) {
                            prepareMedia(sliderItem.url!!,
                                currentPlayerInScope!!, defaultExoFactory)
                        }
                        if (!config.isVideoSoundEnable) {
                            currentPlayerView!!.player!!.volume = 0f
                        }
                        currentPlayerInScope!!.addListener(listener)
                        currentPlayerInScope!!.playWhenReady = true
                    }
                } else {
                    if (config.isGradiantOverlayVisible) {
                        statusLayoutLeft.setBackgroundResource(R.drawable.gradient_overlay)
                    }
                    if (slideShowPlaying) {
                        startTimerNextAsset()
                    }
                    stopPlayer()
                }
            }

            override fun onPageSelected(i: Int) {
                metaDataRightAdapter.notifyDataSetChanged()
                metaDataLeftAdapter.notifyDataSetChanged()
            }

            override fun onPageScrollStateChanged(i: Int) {
            }
        })
    }
    
    fun onDestroy() {
        mainHandler.removeCallbacks(hideInfoPillRunnable)
        if (currentPlayerInScope != null) {
            currentPlayerInScope!!.release()
        }
        clearKeepScreenOnFlags()
        mainHandler.removeCallbacks(goToNextAssetRunnable)
    }

    private fun clearKeepScreenOnFlags() {
        if (context is Activity) {
            val window = (context as Activity).window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun stopPlayer() {
        if (currentPlayerInScope != null && (currentPlayerInScope!!.isPlaying || currentPlayerInScope!!.isLoading)) {
            currentPlayerInScope!!.stop()
        }
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        this.defaultExoFactory = defaultExoFactory
    }

    suspend fun addItemsMain(items: List<SliderItemViewHolder>) = withContext(Dispatchers.Main) {
        addItems(items)
    }

    private fun addItems(items: List<SliderItemViewHolder>) {
        setItems(Lists.newArrayList(Iterables.concat(config.items, items)).distinct())
    }

    fun setItems(items: List<SliderItemViewHolder>) {
        if (items.isEmpty()) {
            Toast.makeText(context, "No items received to show in slideshow", Toast.LENGTH_SHORT).show()
            return
        }
        if (slideShowPlaying) {
            mainHandler.removeCallbacks(goToNextAssetRunnable)
        }
        config.items = items
        pagerAdapter!!.setItems(items)

        // Ensure current index is still valid
        if (mPager.currentItem >= items.size) {
            mPager.setCurrentItem(items.size - 1, false)
        } else {
            // Force refresh of metadata and state for the current item
            // even if the index didn't change (the data at this index did)
            updateFavoriteIcon(showPill = false)
            val sliderItem = items[mPager.currentItem]
            config.onAssetSelected(sliderItem)
            metaDataRightAdapter.notifyDataSetChanged()
            metaDataLeftAdapter.notifyDataSetChanged()
        }

        if (slideShowPlaying && currentItemType() == SliderItemType.IMAGE) {
            startTimerNextAsset()
        }
    }

    private fun currentItem(): SliderItemViewHolder = config.items[mPager.currentItem]
    private fun currentItemType(): SliderItemType = config.items[mPager.currentItem].type
    
    fun getCurrentIndex(): Int {
        return mPager.currentItem
    }

    fun setCurrentIndex(index: Int) {
        mPager.currentItem = index
    }

    @OptIn(UnstableApi::class)
    fun isControllerVisible(): Boolean {
        return currentPlayerView?.isControllerFullyVisible == true
    }

    companion object {
        private const val INFO_PILL_VISIBLE_MS = 3000L

        @SuppressLint("UnsafeOptInUsageError")
        fun prepareMedia(mediaUrl: String, player: ExoPlayer, factory: DefaultHttpDataSource.Factory) {
            val mediaUri = Uri.parse(mediaUrl)
            val mediaItem = MediaItem.fromUri(mediaUri)
            val mediaSource = ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, 0L)
            player.prepare()
        }
    }
}
