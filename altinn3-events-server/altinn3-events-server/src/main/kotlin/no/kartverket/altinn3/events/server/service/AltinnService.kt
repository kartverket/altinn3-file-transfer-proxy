package no.kartverket.altinn3.events.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.models.FileTransferInitialize
import no.kartverket.altinn3.models.FileTransferStatusNullable
import no.kartverket.altinn3.models.Role
import no.kartverket.altinn3.persistence.AltinnEvent
import no.kartverket.altinn3.persistence.AltinnFil
import no.kartverket.altinn3.persistence.AltinnFilOverview
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.retry.support.RetryTemplate
import java.util.*

private const val ALTINN_ORG_NUMBER_PREFIX = "0192:"

@EnableConfigurationProperties(AltinnServerConfig::class)
class AltinnService(
    private val altinnBrokerClient: BrokerClient,
    private val config: AltinnServerConfig,
    private val altinnTransitService: AltinnTransitService,
    private val retryTemplate: RetryTemplate,
) {
    val logger = LoggerFactory.getLogger(javaClass)

    private val pollMutex = Mutex()

    @Volatile
    private var pollRequested: Boolean = false

    fun sendResponseTilInnsender(fileOverview: AltinnFilOverview, altinnFil: AltinnFil): UUID? {
        if (!config.sendResponse)
            return null.also { logger.debug("Send response to innsender disabled. Skipping...") }

        val innsender = requireNotNull(fileOverview.sender) { "Could not find innsender" }
        val propertyList = fileOverview.jsonPropertyList
            ?.let { jacksonObjectMapper().readValue<Map<Any, Any>>(it) }
            ?: mapOf()

        val fileInfo = FileTransferInitialize(
            resourceId = config.resourceId,
            sender = "$ALTINN_ORG_NUMBER_PREFIX${config.recipientId}",
            recipients = listOf(innsender),
            fileName = requireNotNull(fileOverview.fileName) { "File name must be specified" },
            sendersFileTransferReference = fileOverview.sendersReference.toString(),
            propertyList = propertyList
        )

        val initializedFile = altinnBrokerClient
            .initializeFileTransfer(
                fileInfo
            ).fileTransferId ?: error("Could not initialize file transfer ID")

        altinnBrokerClient.uploadFileToAltinn(
            fileTransferId = initializedFile,
            payload = altinnFil.payload
        )
        logger.info("Successfully uploaded file with transfer ID: {}", initializedFile)
        logger.debug("Initialized file: {}", initializedFile)
        return initializedFile
    }

    suspend fun tryPoll(altinnEvent: AltinnEvent? = null) {
        pollRequested = true

        if (pollMutex.isLocked) {
            logger.debug("Poll already running, queueing next poll")
            return
        }

        pollMutex.withLock {
            while (pollRequested) {
                pollRequested = false
                doPoll(altinnEvent)
            }
        }
    }

    private fun doPoll(altinnEvent: AltinnEvent? = null) {
        val fileTransferIds = altinnBrokerClient.getFileTransfers(
            resourceId = config.resourceId,
            status = FileTransferStatusNullable.Published,
            role = Role.Recipient,
            orderAscending = true
        )
        if (fileTransferIds.isEmpty()) {
            logger.debug("No file transfers to poll from Altinn")
            return
        }
        logger.info("Found {} file transfers to process", fileTransferIds.size)

        val fileOverviews = fileTransferIds.mapNotNull { fileTransferId ->
            retryTemplate.execute<FileOverview, Exception> {
                altinnBrokerClient.getFileOverview(fileTransferId)
            }
        }

        if (fileOverviews.isEmpty()) {
            logger.debug("No file overviews could be retrieved from Altinn")
            return
        }

        val sortedFileOverviews = fileOverviews.sortedBy { it.created }

        sortedFileOverviews.forEach { fileOverview ->
            val fileTransferId = altinnTransitService.prepareForFileTransfer(fileOverview, altinnEvent)

            val fileBytes = retryTemplate.execute<ByteArray, IllegalStateException> {
                altinnBrokerClient.downloadFileBytes(fileTransferId)
            }
            altinnTransitService.startTransfer(fileOverview, fileBytes) {
                retryTemplate.execute<Any, IllegalStateException> {
                    altinnBrokerClient.confirmDownload(fileTransferId)
                }
            }
            logger.info("Successfully uploaded file transfer with id={} to transit-db", fileTransferId)
        }
    }
}
