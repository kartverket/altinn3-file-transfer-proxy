package no.kartverket.altinn3.broker.apis

import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClientResponseException
import java.util.*

@Throws(RestClientResponseException::class)
fun FileTransferApi.downloadFileBytes(fileTransferId: java.util.UUID): ByteArray {
    val request = this.brokerApiV1FiletransferFileTransferIdDownloadGetRequestConfig(fileTransferId)
    val response = this.request<Unit, ByteArray>(request)
    return response.body!!
}

class ConfirmDownloadFailedException(message: String, val statusCode: HttpStatusCode) : RuntimeException(message)

fun FileTransferApi.confirmDownloadWithCheck(fileTransferId: UUID) =
    this.brokerApiV1FiletransferFileTransferIdConfirmdownloadPostWithHttpInfo(fileTransferId = fileTransferId)
        .also {
            if (!it.statusCode.is2xxSuccessful) {
                throw ConfirmDownloadFailedException(
                    "Confirm download unsuccessful",
                    it.statusCode
                )
            }
        }