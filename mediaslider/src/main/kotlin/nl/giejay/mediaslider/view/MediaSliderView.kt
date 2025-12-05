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
import android.view.Gravity
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
    
    // --- NUEVO: Icono de Favorito ---
    private var favoriteIcon: ImageView
    // --------------------------------

    // --- NUEVO: Callback para avisar al exterior ---
    // Recibe el item modificado y el nuevo estado booleano
    var onFavoriteStateChanged: ((SliderItemViewHolder, Boolean) -> Unit)? = null
    // -----------------------------------------------

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
        // Usamos la estrella del sistema, igual que en el grid
        favoriteIcon.setImageResource(android.R.drawable.btn_star_big_on)
        favoriteIcon.visibility = GONE // Oculto por defecto
        
        // Lo añadimos al layout
        val params = LayoutParams(60, 60) // Tamaño
        // Lo pondremos arriba a la derecha mediante Constraints programáticas
        addView(favoriteIcon, params)

        // --- FIX: ASIGNAR IDs A VISTAS QUE NO TENGAN (DEL XML INFLADO) ---
        // ConstraintSet requiere que TODOS los hijos tengan ID. Si el XML slider.xml
        // tiene algún View o Spacer sin ID, esto lo arregla automáticamente.
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
        // -------------------------------
    
        // --- NUEVO: EVENTO CLICK EN LA ESTRELLA ---
        favoriteIcon.setOnClickListener {
            // 1. Obtenemos el item actual
            val current = currentItem()
            val item = if (current.hasSecondaryItem()) current.mainItem else current.mainItem 
            // Nota: Ajusta la lógica de arriba si secondaryItem es relevante para favoritos, 
            // normalmente el favorito va ligado al mainItem.

            // 2. Cambiamos el estado local (para feedback visual instantáneo)
            item.isFavorite = !item.isFavorite
            // updateFavoriteIcon()

            // 3. ¡AVISAMOS AL FRAGMENTO!
            onFavoriteStateChanged?.invoke(current, item.isFavorite)
        }
        // ------------------------------------------

        // --- Actualización inicial ---
        updateFavoriteIcon()

    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(volumeReceiver, filter)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(volumeReceiver)
    }

    @OptIn(UnstableApi::class)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                    goToPreviousAsset()
                    return false
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return if (itemType == SliderItemType.IMAGE) false else super.dispatchKeyEvent(event)
    }

    private fun goToPreviousAsset() {
        mPager.setCurrentItem((if (0 == mPager.currentItem) mPager.adapter!!.count else mPager.currentItem) - 1,
            config.enableSlideAnimation)
    }

    fun loadMediaSliderView(config: MediaSliderConfiguration) {
        this.config = config

        // ... (resto de adaptadores de metadatos igual) ...
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
        
        // --- ACTUALIZAR ICONO AL CARGAR ---
        updateFavoriteIcon()
    }
    
    // --- MÉTODO PÚBLICO PARA ACTUALIZAR ICONO ---
    fun updateFavoriteIcon() {
    
        // Si la config no está lista, no hacemos nada (y no crasheamos)
        if (!::config.isInitialized) return
        
        val item = currentItem().mainItem // Cogemos la principal
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
                // --- ACTUALIZAR ICONO AL DESLIZAR ---
                updateFavoriteIcon()
                // ------------------------------------
                
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
    
    // ... (resto de métodos onDestroy, clearKeepScreenOnFlags, etc igual que antes) ...
    fun onDestroy() {
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
        if (slideShowPlaying && currentItemType() == SliderItemType.IMAGE) {
            startTimerNextAsset()
        }
    }

    private fun currentItem(): SliderItemViewHolder = config.items[mPager.currentItem]
    private fun currentItemType(): SliderItemType = config.items[mPager.currentItem].type
    
    fun getCurrentIndex(): Int {
        return mPager.currentItem
    }

    @OptIn(UnstableApi::class)
    fun isControllerVisible(): Boolean {
        return currentPlayerView?.isControllerFullyVisible == true
    }

    companion object {
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
