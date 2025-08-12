package no.kartverket.altinn3.events.server.handler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.models.FileTransferStatus
import no.kartverket.altinn3.models.RecipientFileTransferStatusDetailsExt
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.retry.support.RetryTemplate
import java.util.*

class CloudEventHandler(
    private val broker: BrokerClient,
    private val altinnTransitService: AltinnTransitService,
    private val retryTemplate: RetryTemplate,
    private val altinnServerConfig: AltinnServerConfig
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    suspend fun <T> tryHandle(
        event: CloudEvent,
        onSuccess: suspend () -> T,
        onFailure: suspend (Throwable) -> T
    ): T = runCatching {
        handle(event)
        onSuccess()
    }.getOrElse { error ->
        logger.error(error.stackTraceToString())
        onFailure(error)
    }

    suspend fun handle(event: CloudEvent) {
        when (AltinnEventType.from(event.type!!)) {
            AltinnEventType.PUBLISHED -> handlePublished(event)
            AltinnEventType.INITIALIZED -> logger.info("Filetransfer initialized")
            AltinnEventType.NEVER_CONFIRMED -> handleNeverConfirmed(event)
            AltinnEventType.UPLOAD_PROCESSING -> logger.info("Upload processing")
            AltinnEventType.DOWNLOAD_CONFIRMED -> logger.info("Download confirmed")
            AltinnEventType.FILE_PURGED -> logger.info("File purged")
            AltinnEventType.ALL_CONFIRMED -> logger.info("All confirmed")
            else -> {
                logger.info("Ignored event: $event")
                throw IllegalArgumentException("Ignored event: $event")
            }
        }
    }

    /**
     *
     * @return true if the file is initialized and ready for download, otherwise false
     * */
    private fun initializeFileTransfer(fileTransferOverview: FileOverview, event: CloudEvent): Boolean {
        logger.debug("Initializing filetransfer on fileTransferId: {}", fileTransferOverview.fileTransferId)

        val fileTransferStatus = requireNotNull(fileTransferOverview.fileTransferStatus)

        // 1 - Når vi synkroniserer vil det være tilfeller av events hvor filen allerede er lastet ned. Disse ignoreres.
        // 2 - Vi får også published-events når vi laster opp filer.
        val isReady =
            fileTransferStatus == FileTransferStatus.Published && isIngoingFile(fileTransferOverview.recipients)

        if (!isReady) {
            logger.debug("Ignoring event with event id ${event.id}.")

            return false
        }

        altinnTransitService.prepareForFileTransfer(event, fileTransferOverview)
        return true
    }

    private suspend fun handleNeverConfirmed(event: CloudEvent) {
        val resourceInstance = UUID.fromString(event.resourceinstance)
        val fileMeta = retryTemplate.execute<FileOverview, IllegalStateException> {
            broker.getFileOverview(resourceInstance)
        }
        logger.error(
            "Recieved file never confirmed event!\nRecipient(s): {}\n fileTransferId: {}",
            fileMeta.recipients,
            fileMeta.fileTransferId
        )
        if (isIngoingFile(fileMeta.recipients))
            handlePublished(event)
    }

    private suspend fun handlePublished(event: CloudEvent) = withContext(Dispatchers.IO) {
        val resourceInstance = UUID.fromString(event.resourceinstance)
        val fileMeta = retryTemplate.execute<FileOverview, IllegalStateException> {
            broker.getFileOverview(resourceInstance)
        }
        if (!initializeFileTransfer(fileMeta, event)) return@withContext

        logger.debug("Starting filetransfer on fileTransferId: {}", fileMeta.fileTransferId)

        val fileBytes = retryTemplate.execute<ByteArray, IllegalStateException> {
            broker.downloadFileBytes(resourceInstance)
        }

        altinnTransitService.startTransfer(fileMeta, fileBytes, event) {
            retryTemplate.execute<Any, IllegalStateException> {
                broker.confirmDownload(resourceInstance)
            }
        }
    }

    private fun isIngoingFile(recipients: List<RecipientFileTransferStatusDetailsExt>?): Boolean {
        val recip = recipients ?: return false

        return recip.any { it.recipient?.contains(altinnServerConfig.recipientId) == true }
    }
}
