package no.kartverket.altinn3.events.server.handler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import no.kartverket.altinn3.broker.apis.confirmDownloadWithCheck
import no.kartverket.altinn3.broker.apis.downloadFileBytes
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.domain.AltinnEventType.*
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

interface AltinnWarningException

class CloudEventHandler(
    private val broker: BrokerClient,
    private val altinnTransitService: AltinnTransitService,
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    suspend fun <T> tryHandle(
        event: CloudEvent,
        onSuccess: suspend () -> T,
        onFailure: suspend (Throwable) -> T
    ): T = runCatching {
        handle(event)
    }.onFailure { e ->
        when (e) {
            is AltinnWarningException ->
                logger.warn(e.message)

            else ->
                logger.error(e.stackTraceToString())
        }
    }.fold({ onSuccess() }) { e ->
        onFailure(e)
    }

    suspend fun handle(event: CloudEvent) {
        when (AltinnEventType.from(event.type!!)) {
            PUBLISHED -> handlePublished(event)
            INITIALIZED -> handleInitialized(event)
            NEVER_CONFIRMED -> logger.warn("Never confirmed!")
            UPLOAD_PROCESSING -> logger.info("Upload processing")
            DOWNLOAD_CONFIRMED -> logger.info("Download confirmed")
            FILE_PURGED -> logger.info("File purged")
            ALL_CONFIRMED -> logger.info("All confirmed")
            null -> {
                logger.info("Unknown event: $event")
                throw IllegalArgumentException("Unknown event: $event")
            }

            else -> {
                logger.info("Ignored event: $event")
                throw IllegalArgumentException("Ignored event: $event")
            }
        }
    }

    private suspend fun handleInitialized(event: CloudEvent) = coroutineScope {
        withContext(Dispatchers.IO) {
            val fileDetails = broker.file.brokerApiV1FiletransferFileTransferIdDetailsGet(
                UUID.fromString(event.resourceinstance)
            )
            // TODO: Operation.test er lagt til for å ikke få en uendelig løkke når vi p.t. er både sender og mottaker
            if (fileDetails.propertyList["operation"] == "test") {
                logger.debug("TEST OPERATION")
                logger.info("Wont persist")
                return@withContext
            }
            // Historikken kan ha fått flere elementer enn 'Initialized' innen vi prosesserer eventet,
            // men det er kun dersom det har gått lenger enn published at vi vet med sikkerhet
            // at vi allerede har behandlet det når vi synkroniserer.
            val fileTransferHistory = requireNotNull(fileDetails.fileTransferStatusHistory)
            val invalidHistoryElements = listOf(
                FileStatus.AllConfirmedDownloaded,
                FileStatus.Purged,
                FileStatus.Cancelled,
                FileStatus.Failed
            )
            val shouldNotSave = fileTransferHistory.any {
                invalidHistoryElements.contains(it.fileTransferStatus)
            }

            if (shouldNotSave) {
                logger.debug("Ignoring initialized event with event id ${event.id}.")
                return@withContext
            }
            altinnTransitService.prepareForFileTransfer(event, fileDetails)
        }
    }

    private suspend fun handlePublished(event: CloudEvent) = withContext(Dispatchers.IO) {
        val resourceInstance = UUID.fromString(event.resourceinstance)
        val fileMeta = broker.file.brokerApiV1FiletransferFileTransferIdGet(resourceInstance)

        // For gamle events som hentes via sync/poll.
        // bør ikke være noe som slår ut i prod når et fornuftig start-event er satt
        if (fileMeta.fileTransferStatus != FileStatus.Published) {
            logger.debug("fileTransferStatus: ${fileMeta.fileTransferStatus}")
            logger.debug("filetransferStatus is not 'Published'")
            logger.debug("Won't handle file transfer status: {}", fileMeta.fileTransferStatus)
            return@withContext
        }

        // TODO: Operation.test workaround mot uendelig løkke når vi er avsender og mottaker av fil.
        if (fileMeta.propertyList["operation"] == "test") {
            logger.debug("TEST OPERATION")
            logger.info("Wont persist")
            return@withContext
        }

        val fileBytes = broker.file.downloadFileBytes(resourceInstance)

        altinnTransitService.startTransfer(fileMeta, fileBytes, event) {
            broker.file.confirmDownloadWithCheck(resourceInstance)
        }
    }
}
