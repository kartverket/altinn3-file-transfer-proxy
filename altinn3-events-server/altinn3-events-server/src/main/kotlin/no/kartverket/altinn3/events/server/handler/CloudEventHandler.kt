package no.kartverket.altinn3.events.server.handler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.events.server.service.AltinnTransitService
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
        event: EventWithFileOverview,
        onSuccess: suspend () -> T,
        onFailure: suspend (Throwable) -> T
    ): T = runCatching {
        handle(event)
        onSuccess()
    }.getOrElse { error ->
        logger.error(error.stackTraceToString())
        onFailure(error)
    }

    suspend fun handle(event: EventWithFileOverview) {
        when (AltinnEventType.from(event.cloudEvent.type!!)) {
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
    private fun initializeFileTransfer(event: EventWithFileOverview): Boolean {
        logger.debug("Initializing filetransfer on fileTransferId: {}", event.fileOverview.fileTransferId)

        val fileTransferStatus = requireNotNull(event.fileOverview.fileTransferStatus)

        // 1 - Når vi synkroniserer vil det være tilfeller av events hvor filen allerede er lastet ned. Disse ignoreres.
        // 2 - Vi får også published-events når vi laster opp filer.
        val isReady =
            fileTransferStatus == FileTransferStatus.Published && isIngoingFile(event.fileOverview.recipients)

        if (!isReady) {
            logger.debug("Ignoring event with event id ${event.cloudEvent.id}.")
            return false
        }

        altinnTransitService.prepareForFileTransfer(event)
        return true
    }

    private suspend fun handleNeverConfirmed(event: EventWithFileOverview) {
        val fileMeta = event.fileOverview
        logger.error(
            "Recieved file never confirmed event!\nRecipient(s): {}\n fileTransferId: {}",
            fileMeta.recipients,
            fileMeta.fileTransferId
        )
        if (isIngoingFile(fileMeta.recipients))
            handlePublished(event)
    }

    private suspend fun handlePublished(event: EventWithFileOverview) = withContext(Dispatchers.IO) {
        val resourceInstance = UUID.fromString(event.cloudEvent.resourceinstance)
        val fileMeta = event.fileOverview

        if (!initializeFileTransfer(event)) return@withContext

        logger.debug("Starting filetransfer on fileTransferId: {}", fileMeta.fileTransferId)

        val fileBytes = retryTemplate.execute<ByteArray, IllegalStateException> {
            broker.downloadFileBytes(resourceInstance)
        }

        altinnTransitService.startTransfer(fileMeta, fileBytes) {
            retryTemplate.execute<Any, IllegalStateException> {
                broker.confirmDownload(resourceInstance)
            }
        }
    }

    private fun isIngoingFile(recipients: List<RecipientFileTransferStatusDetailsExt>?): Boolean {
        return recipients?.any { it.recipient?.contains(altinnServerConfig.recipientId) == true } ?: false
    }
}
