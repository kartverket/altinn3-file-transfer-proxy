@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package no.kartverket.altinn3.models

import com.fasterxml.jackson.annotation.JsonProperty

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
 * @param fileTransferStatusHistory
 * @param recipientFileTransferStatusHistory
 */


data class FileTransferStatusDetailsExt(

    @get:JsonProperty("fileTransferId")
    val fileTransferId: java.util.UUID? = null,

    @get:JsonProperty("resourceId")
    val resourceId: kotlin.String? = null,

    @get:JsonProperty("fileName")
    val fileName: kotlin.String? = null,

    @get:JsonProperty("sendersFileTransferReference")
    val sendersFileTransferReference: kotlin.String? = null,

    @get:JsonProperty("checksum")
    val checksum: kotlin.String? = null,

    @get:JsonProperty("useVirusScan")
    val useVirusScan: kotlin.Boolean? = null,

    @get:JsonProperty("fileTransferSize")
    val fileTransferSize: kotlin.Long? = null,

    @get:JsonProperty("fileTransferStatus")
    val fileTransferStatus: FileTransferStatusExt? = null,

    @get:JsonProperty("fileTransferStatusText")
    val fileTransferStatusText: kotlin.String? = null,

    @get:JsonProperty("fileTransferStatusChanged")
    val fileTransferStatusChanged: java.time.OffsetDateTime? = null,

    @get:JsonProperty("created")
    val created: java.time.OffsetDateTime? = null,

    @get:JsonProperty("expirationTime")
    val expirationTime: java.time.OffsetDateTime? = null,

    @get:JsonProperty("sender")
    val sender: kotlin.String? = null,

    @get:JsonProperty("recipients")
    val recipients: kotlin.collections.List<RecipientFileTransferStatusDetailsExt>? = null,

    @get:JsonProperty("propertyList")
    val propertyList: Map<Any, Any>,

    @get:JsonProperty("fileTransferStatusHistory")
    val fileTransferStatusHistory: kotlin.collections.List<FileTransferStatusEventExt>? = null,

    @get:JsonProperty("recipientFileTransferStatusHistory")
    val recipientFileTransferStatusHistory: kotlin.collections.List<RecipientFileTransferStatusEventExt>? = null
)
