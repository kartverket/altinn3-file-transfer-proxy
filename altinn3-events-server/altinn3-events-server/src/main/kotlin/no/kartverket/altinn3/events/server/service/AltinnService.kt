package no.kartverket.altinn3.events.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import org.springframework.scheduling.annotation.Scheduled
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.time.Instant
import java.time.OffsetDateTime
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
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pollRequested: Boolean = false

    fun sendResponseTilInnsender(fileOverview: AltinnFilOverview, altinnFil: AltinnFil): UUID {
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

    @Scheduled(
        fixedDelayString = "\${altinn.poll-altinn-fixed-delay:30s}",
        initialDelayString = "\${altinn.poll-altinn-initial-delay:30s}"
    )
    fun scheduledTryPoll() {
        pollScope.launch {
            tryPoll()
        }
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
            orderAscending = true,
            from = FromUtil.getFromTime(config.pollLookbackDays)
        )
        if (fileTransferIds.isEmpty()) {
            logger.debug("No file transfers to poll from Altinn")
            return
        }
        if (fileTransferIds.size > 99) {
            pollRequested = true // requeue another poll if fetched files is the maximum per transfer.
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

        val sortedFileOverviews = fileOverviews.sortedBy { it.published }

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

    object FromUtil {
        fun getFromTime(lookbackDays: Int, now: Instant = Instant.now()): OffsetDateTime {
            return now.minusSeconds(lookbackDays * 24 * 60 * 60L).atOffset(ZoneOffset.UTC)
        }
    }

    @PreDestroy
    fun shutdown() {
        pollScope.cancel()
    }
}
