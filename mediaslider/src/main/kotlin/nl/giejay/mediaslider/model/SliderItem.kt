package nl.giejay.mediaslider.model

import java.io.Serializable

/**
 * Modelo único y limpio para el Slider.
 * Implementa Serializable para pasar entre fragmentos.
 */
data class SliderItem(
    val id: String,
    val url: String?,
    val type: SliderItemType,
    val metaData: Map<MetaDataType, String?> = emptyMap(),
    val thumbnailUrl: String? = null, // Nombre corregido para que coincida con el resto de la app
    var isFavorite: Boolean = false   // Nuestro campo nuevo para favoritos
) : Serializable {

    // Esta función es necesaria porque MetaDataAdapter la usa
    fun get(key: MetaDataType): String? {
        return metaData[key]
    }
}
