package no.kartverket.altinn3.events.server.service

import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.mappers.toAltinnFilOverview
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.persistence.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.function.Supplier

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

    fun prepareForFileTransfer(fileOverview: FileOverview, altinnEvent: AltinnEvent? = null): UUID {
        val fileTransferId = requireNotNull(fileOverview.fileTransferId)

        if (altinnFilOverviewRepository.existsByFileTransferId(fileTransferId)) {
            logger.warn("AltinnFilOverview with fileTransferId: {} already exists", fileTransferId)
        } else {
            altinnFilOverviewRepository.save(fileOverview.toAltinnFilOverview())
            logger.info(
                "Created and prepared file overview for fileTransferId: {} ", fileTransferId
            )
        }

        saveAltinnEvent(altinnEvent, fileTransferId)

        return fileTransferId
    }

    fun findNewestEvent(): String? {
        return altinnEventRepository.findFirstByOrderByTimeDesc()?.altinnId?.toString()
    }

    fun startTransfer(fileOverview: FileOverview, fileBytes: ByteArray, onSuccess: () -> Unit) {
        transactionTemplate.execute {
            saveAltinnFil(fileOverview, fileBytes)
        }
        onSuccess()
    }

    fun completeFileTransfer(altinnFilOverview: AltinnFilOverview) {
        altinnFilOverviewRepository.save(
            altinnFilOverview.copy(
                modified = OffsetDateTime.now(ZoneOffset.UTC),
                sent = OffsetDateTime.now(ZoneOffset.UTC),
                transitStatus = TransitStatus.COMPLETED
            )
        )
    }

    private fun saveAltinnFil(fileOverview: FileOverview, fileBytes: ByteArray) {
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

        if (altinnFilRepository.findByFileOverviewId(altinnFilOverviewId) != null) {
            logger.warn("AltinnFil with overviewId: {} already exists", altinnFilOverviewId)
        } else {
            logger.debug("Trying to save file with fileTransferId: {}", fileTransferId)
            altinnFilRepository.save(incomingFile)
            logger.info("Saved altinn fil with file reference: {} ", fileTransferId)
        }
    }

    fun saveAltinnEvent(altinnEvent: AltinnEvent? = null, fileTransferId: UUID) {
        if (!altinnServerConfig.persistCloudEvent || altinnEvent == null) return

        if (altinnEventRepository.findByResourceinstance(fileTransferId) == null
            && !altinnEventRepository.existsByAltinnId(altinnEvent.altinnId)) {
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
                val filOverviewId = requireNotNull(filOverview.id)
                altinnFilRepository.findByFileOverviewId(filOverviewId)
                    ?: run {
                        Thread.sleep(1000)
                        altinnFilRepository.findByFileOverviewId(filOverviewId)
                    }
                    ?: error("Could not find corresponding file for fileoverview: $filOverviewId")
            }
}
