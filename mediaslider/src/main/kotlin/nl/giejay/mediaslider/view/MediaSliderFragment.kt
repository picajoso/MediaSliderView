package nl.giejay.mediaslider.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.datasource.DefaultHttpDataSource
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemViewHolder

open class MediaSliderFragment : Fragment() {

    // --- NUEVO: Listener para que la Activity se suscriba ---
    var onAssetFavoriteChanged: ((SliderItemViewHolder, Boolean) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val mediaView = MediaSliderView(requireContext())
        
        // --- CONECTAMOS EL CABLE ---
        // Cuando la View avisa, nosotros avisamos hacia arriba (Activity)
        mediaView.onFavoriteStateChanged = { item, isFavorite ->
            onAssetFavoriteChanged?.invoke(item, isFavorite)
        }
        
        return mediaView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestFocus()
    }

    override fun onPause() {
        destroyMediaPlayer()
        super.onPause()
    }

    private fun destroyMediaPlayer() {
        view.onDestroy()
    }

    override fun getView(): MediaSliderView {
        return super.getView() as MediaSliderView
    }

    fun loadMediaSliderView(config: MediaSliderConfiguration) {
        view.loadMediaSliderView(config)
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        view.setDefaultExoFactory(defaultExoFactory)
    }
}
