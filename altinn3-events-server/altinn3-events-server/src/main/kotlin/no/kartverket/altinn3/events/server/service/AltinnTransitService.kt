package no.kartverket.altinn3.events.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.persistence.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.*
import java.util.function.Supplier

fun CloudEvent.toAltinnEventEntity() = AltinnEvent(
    specVersion = specversion,
    altinnId = UUID.fromString(id),
    type = type,
    time = time?.toLocalDateTime(),
    resource = resource,
    resourceinstance = UUID.fromString(resourceinstance),
    source = source.toString()
)

private fun FileOverview.toAltinnFilOverview() = AltinnFilOverview(
    fileName = this.fileName,
    checksum = this.checksum,
    sendersReference = this.sendersFileTransferReference,
    sender = this.sender,
    created = this.created?.toLocalDateTime(),
    received = LocalDateTime.now(),
    fileTransferId = this.fileTransferId,
    resourceId = this.resourceId,
    transitStatus = TransitStatus.NEW,
    direction = Direction.IN,
    jsonPropertyList = jacksonObjectMapper().writeValueAsString(this.propertyList)
)

/**
 * @return the event from which the synchronization should start at, in the following priority:
 * 1. App configuration
 * 2. The most recent event stored in the database
 * 3. From first event (0).
 **/
fun createAltinnStartEventSupplier(
    altinnEventRepository: AltinnEventRepository,
    altinnServerConfig: AltinnServerConfig
): Supplier<String> {
    val configuredEventId =
        altinnServerConfig.startEvent
            ?: altinnEventRepository.findFirstByOrderByTimeDesc()?.altinnId?.toString()
            ?: "0"

    return Supplier { configuredEventId }
}

@EnableConfigurationProperties(AltinnServerConfig::class)
open class AltinnTransitService(
    private val altinnFilOverviewRepository: AltinnFilOverviewRepository,
    private val altinnEventRepository: AltinnEventRepository,
    private val altinnFilRepository: AltinnFilRepository,
    private val altinnServerConfig: AltinnServerConfig,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    fun prepareForFileTransfer(cloudEvent: CloudEvent, fileDetails: FileOverview) {
        transactionTemplate.execute {
            val altinnEvent = cloudEvent.toAltinnEventEntity()
            val overview = fileDetails.toAltinnFilOverview()
            val fileTransferId = requireNotNull(overview.fileTransferId)

            if (!altinnFilOverviewRepository.existsByFileTransferId(fileTransferId)) {
                altinnFilOverviewRepository.save(overview)
                logger.info(
                    "Created and prepared file overview for file reference: {} ",
                    cloudEvent.resourceinstance
                )
            } else {
                logger.warn("AltinnFilOverview with fileReference: {} already exists", overview.fileTransferId)
            }
            saveAltinnEvent(altinnEvent)
        }
    }

    fun startTransfer(fileOverview: FileOverview, fileBytes: ByteArray, onSuccess: () -> Unit) {
        transactionTemplate.execute {
            saveAltinnFil(fileOverview, fileBytes)
            onSuccess()
        }
    }

    fun completeFileTransfer(altinnFilOverview: AltinnFilOverview) {
        altinnFilOverviewRepository.save(
            altinnFilOverview.copy(
                modified = LocalDateTime.now(),
                sent = LocalDateTime.now(),
                transitStatus = TransitStatus.COMPLETED
            )
        )
    }

    private fun saveAltinnFil(fileOverview: FileOverview, fileBytes: ByteArray) {
        if (!altinnServerConfig.persistAltinnFile) {
            logger.info(
                """
                |Persisting files disabled. 
                |Won't save file with fileTransferId: {}
                """.trimMargin(),
                fileOverview.fileTransferId
            )
            return
        }
        val fileTransferId =
            requireNotNull(fileOverview.fileTransferId) { "fileTransferId is required in the file overview" }

        val altinnFilOverviewId = altinnFilOverviewRepository
            .findByFileTransferId(fileTransferId).run {
                requireNotNull(this?.id) { "Altinn fil hasn't been initialized" }
            }

        val incomingFile = AltinnFil(
            payload = fileBytes,
            fileOverviewId = altinnFilOverviewId
        )

        logger.debug("Trying to save file with fileTransferId: {}", fileTransferId)
        altinnFilRepository.save(incomingFile)
        logger.info("Saved altinn fil with file reference: {} ", fileTransferId)
    }

    fun saveAltinnEvent(altinnEvent: AltinnEvent) {
        if (!altinnServerConfig.persistCloudEvent)
            return

        if (!altinnEventRepository.existsByAltinnId(altinnEvent.altinnId)) {
            altinnEventRepository.save(altinnEvent).also { savedResult ->
                logger.info(
                    "Saved altinn event id: {} with resourceinstance: {}",
                    savedResult.altinnId,
                    savedResult.resourceinstance
                )
            }
        } else {
            logger.warn("Event with AltinnId: {} already exists!", altinnEvent.altinnId)
        }
    }

    fun findNewUtgaendeFiler(): Map<AltinnFilOverview, AltinnFil> =
        altinnFilOverviewRepository.findAllByTransitStatus(TransitStatus.NEW, direction = Direction.OUT)
            .sortedBy { it.created }
            .associateWith { filOverview ->
                altinnFilRepository.findByFileOverviewId(requireNotNull(filOverview.id))
                    ?: error("Could not find corresponding file for fileoverview: ${filOverview.id}")
            }
}
