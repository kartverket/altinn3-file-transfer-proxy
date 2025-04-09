@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package no.kartverket.altinn3.models

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.OffsetDateTime
import java.util.*

/**
 *
 *
 * @param fileTransferId
 * @param resourceId
 * @param fileName
 * @param sendersFileTransferReference
 * @param checksum
 * @param useVirusScan
 * @param fileTransferSize
 * @param fileTransferStatus
 * @param fileTransferStatusText
 * @param fileTransferStatusChanged
 * @param created
 * @param expirationTime
 * @param sender
 * @param recipients
 * @param propertyList
 */


data class FileTransferOverviewExt(

    @get:JsonProperty("fileTransferId")
    val fileTransferId: UUID? = null,

    @get:JsonProperty("resourceId")
    val resourceId: String? = null,

    @get:JsonProperty("fileName")
    val fileName: String? = null,

    @get:JsonProperty("sendersFileTransferReference")
    val sendersFileTransferReference: String? = null,

    @get:JsonProperty("checksum")
    val checksum: String? = null,

    @get:JsonProperty("useVirusScan")
    val useVirusScan: Boolean? = null,

    @get:JsonProperty("fileTransferSize")
    val fileTransferSize: Long? = null,

    @get:JsonProperty("fileTransferStatus")
    val fileTransferStatus: FileTransferStatusExt? = null,

    @get:JsonProperty("fileTransferStatusText")
    val fileTransferStatusText: String? = null,

    @get:JsonProperty("fileTransferStatusChanged")
    val fileTransferStatusChanged: OffsetDateTime? = null,

    @get:JsonProperty("created")
    val created: OffsetDateTime? = null,

    @get:JsonProperty("expirationTime")
    val expirationTime: OffsetDateTime? = null,

    @get:JsonProperty("sender")
    val sender: String? = null,

    @get:JsonProperty("recipients")
    val recipients: List<RecipientFileTransferStatusDetailsExt>? = null,

    @get:JsonProperty("propertyList")
    val propertyList: Map<Any, Any>
)