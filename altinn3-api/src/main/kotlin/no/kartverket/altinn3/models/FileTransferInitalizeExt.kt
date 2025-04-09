package no.kartverket.altinn3.models


import com.fasterxml.jackson.annotation.JsonProperty

/**
 *
 *
 * @param fileName
 * @param resourceId
 * @param sender
 * @param recipients
 * @param sendersFileTransferReference
 * @param propertyList
 * @param checksum
 * @param disableVirusScan
 */


data class FileTransferInitalizeExt(

    @get:JsonProperty("fileName")
    val fileName: String,

    @get:JsonProperty("resourceId")
    val resourceId: String,

    @get:JsonProperty("sender")
    val sender: String,

    @get:JsonProperty("recipients")
    val recipients: List<String>,

    @get:JsonProperty("sendersFileTransferReference")
    val sendersFileTransferReference: String? = null,

    @get:JsonProperty("propertyList")
    val propertyList: Map<Any, Any>,

    @get:JsonProperty("checksum")
    val checksum: String? = null,

    @get:JsonProperty("disableVirusScan")
    val disableVirusScan: Boolean? = null
)

