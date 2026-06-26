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
 * @param published
 */


data class FileTransferOverviewExt(

    @field:JsonProperty("fileTransferId")
    @param:JsonProperty("fileTransferId")
    val fileTransferId: UUID? = null,

    @field:JsonProperty("resourceId")
    @param:JsonProperty("resourceId")
    val resourceId: String? = null,

    @field:JsonProperty("fileName")
    @param:JsonProperty("fileName")
    val fileName: String? = null,

    @field:JsonProperty("sendersFileTransferReference")
    @param:JsonProperty("sendersFileTransferReference")
    val sendersFileTransferReference: String? = null,

    @field:JsonProperty("checksum")
    @param:JsonProperty("checksum")
    val checksum: String? = null,

    @field:JsonProperty("useVirusScan")
    @param:JsonProperty("useVirusScan")
    val useVirusScan: Boolean? = null,

    @field:JsonProperty("fileTransferSize")
    @param:JsonProperty("fileTransferSize")
    val fileTransferSize: Long? = null,

    @field:JsonProperty("fileTransferStatus")
    @param:JsonProperty("fileTransferStatus")
    val fileTransferStatus: FileTransferStatusExt? = null,

    @field:JsonProperty("fileTransferStatusText")
    @param:JsonProperty("fileTransferStatusText")
    val fileTransferStatusText: String? = null,

    @field:JsonProperty("fileTransferStatusChanged")
    @param:JsonProperty("fileTransferStatusChanged")
    val fileTransferStatusChanged: OffsetDateTime? = null,

    @field:JsonProperty("created")
    @param:JsonProperty("created")
    val created: OffsetDateTime? = null,

    @field:JsonProperty("expirationTime")
    @param:JsonProperty("expirationTime")
    val expirationTime: OffsetDateTime? = null,

    @field:JsonProperty("sender")
    @param:JsonProperty("sender")
    val sender: String? = null,

    @field:JsonProperty("recipients")
    @param:JsonProperty("recipients")
    val recipients: List<RecipientFileTransferStatusDetailsExt>? = null,

    @field:JsonProperty("propertyList")
    @param:JsonProperty("propertyList")
    val propertyList: Map<String, Any> = emptyMap(),

    @field:JsonProperty("published")
    @param:JsonProperty("published")
    val published: OffsetDateTime? = null,
)