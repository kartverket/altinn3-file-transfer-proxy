package no.kartverket.altinn3.client

import no.kartverket.altinn3.broker.apis.FileTransferApi
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.models.FileTransferInitialize
import org.springframework.http.HttpStatusCode
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

    fun healthCheckViaFileTranser(resourceId: String) =
        file.brokerApiV1FiletransferGetWithHttpInfo(resourceId = resourceId)
}

