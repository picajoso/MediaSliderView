package nl.giejay.mediaslider.model

import java.io.Serializable

/**
 * Raw details of an asset shown in the viewer pill / info panel. Everything is optional;
 * formatting (units, locale) is done by the viewer.
 */
data class SliderItemDetails(
    val dateMillis: Long? = null,
    val city: String? = null,
    val country: String? = null,
    val cameraName: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: Double? = null,
    val fNumber: Double? = null,
    /** as delivered by the server, e.g. "1/1600" */
    val exposureTime: String? = null,
    val iso: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fileSizeBytes: Long? = null,
    /** IANA id of the photo's own timezone; device timezone when null/unknown */
    val timeZoneId: String? = null
) : Serializable
