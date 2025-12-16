package no.kartverket.altinn3.events.server.handler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.mappers.toAltinnEventEntity
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.events.server.service.AltinnService
import no.kartverket.altinn3.models.FileTransferStatus
import no.kartverket.altinn3.models.RecipientFileTransferStatusDetailsExt
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class CloudEventHandler(
    private val altinnServerConfig: AltinnServerConfig,
    private val altinnService: AltinnService,
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

    private suspend fun handleNeverConfirmed(event: EventWithFileOverview) {
        val fileMeta = event.fileOverview
        logger.error(
            "Received file never confirmed event!\nRecipient(s): {}\n fileTransferId: {}",
            fileMeta.recipients,
            fileMeta.fileTransferId
        )
        if (isIngoingFile(fileMeta.recipients))
            handlePublished(event)
    }

    private suspend fun handlePublished(event: EventWithFileOverview) = withContext(Dispatchers.IO) {
        val fileTransferStatus = requireNotNull(event.fileOverview.fileTransferStatus)

        val isFileReadyForDownload = fileTransferStatus == FileTransferStatus.Published && isIngoingFile(event.fileOverview.recipients)

        if (!isFileReadyForDownload) {
            logger.debug("Ignoring event with eventId={}", event.cloudEvent.id)
            return@withContext
        }

        logger.debug(
            "Received published event for ingoing file with event id={} and fileTransferId={}",
            event.cloudEvent.id,
            event.fileOverview.fileTransferId
        )

        altinnService.tryPoll(event.cloudEvent.toAltinnEventEntity())
    }

    private fun isIngoingFile(recipients: List<RecipientFileTransferStatusDetailsExt>?): Boolean {
        return recipients?.any { it.recipient?.contains(altinnServerConfig.recipientId) == true } ?: false
    }
}
