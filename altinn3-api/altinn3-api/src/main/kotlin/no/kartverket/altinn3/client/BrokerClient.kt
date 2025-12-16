package no.kartverket.altinn3.client

import no.kartverket.altinn3.broker.apis.FileTransferApi
import no.kartverket.altinn3.models.*
import org.springframework.http.HttpStatusCode
import java.time.OffsetDateTime
import java.util.*

class ConfirmDownloadFailedException(message: String, val statusCode: HttpStatusCode) : RuntimeException(message)

class BrokerClient(
    private val file: FileTransferApi
) {
    fun confirmDownload(fileTransferId: UUID): Unit {
        file.brokerApiV1FiletransferFileTransferIdConfirmdownloadPostWithHttpInfo(fileTransferId = fileTransferId)
            .also {
                if (!it.statusCode.is2xxSuccessful) {
                    throw ConfirmDownloadFailedException(
                        "Confirm download unsuccessful",
                        it.statusCode
                    )
                }
            }
    }

    fun downloadFileBytes(fileTransferId: UUID): ByteArray =
        file.brokerApiV1FiletransferFileTransferIdDownloadGetRequestConfig(fileTransferId).let { request ->
            val response = file.request<Unit, ByteArray>(request)
            return response.body ?: byteArrayOf()
        }

    fun getFileOverview(fileTransferId: UUID): FileOverview =
        file.brokerApiV1FiletransferFileTransferIdGet(fileTransferId = fileTransferId)

    fun uploadFileToAltinn(fileTransferId: UUID, payload: ByteArray) =
        file.brokerApiV1FiletransferFileTransferIdUploadPost(
            fileTransferId = fileTransferId,
            body = payload
        )

    fun initializeFileTransfer(fileTransferInitialize: FileTransferInitialize) =
        file.brokerApiV1FiletransferPost(fileTransferInitialize)

    fun healthCheckViaFileTransfer(resourceId: String) =
        file.brokerApiV1FiletransferGetWithHttpInfo(resourceId = resourceId)

    fun getFileTransfers(
        resourceId: String? = null,
        status: FileTransferStatusNullable? = null,
        recipientStatus: RecipientFileTransferStatusNullable? = null,
        from: OffsetDateTime? = null,
        to: OffsetDateTime? = null,
        orderAscending: Boolean? = null,
        role: Role? = null,
    ): List<UUID> =
        file.brokerApiV1FiletransferGet(
            resourceId = resourceId,
            status = status,
            recipientStatus = recipientStatus,
            from = from,
            to = to,
            orderAscending = orderAscending,
            role = role,
        )
}

