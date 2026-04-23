package com.ispindle.plotter.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON body posted by iSpindel firmware in "Generic HTTP" mode. Fields are
 * optional except angle/temperature/battery because firmware variants differ
 * in what they emit. See universam1/iSpindel — `SenderIf::sendGenericPost`.
 */
@Serializable
data class IspindlePayload(
    val name: String? = null,
    @SerialName("ID") val id: Int? = null,
    val token: String? = null,
    val angle: Double,
    val temperature: Double,
    @SerialName("temp_units") val tempUnits: String = "C",
    val battery: Double,
    val gravity: Double? = null,
    val interval: Int? = null,
    @SerialName("RSSI") val rssi: Int? = null
)
