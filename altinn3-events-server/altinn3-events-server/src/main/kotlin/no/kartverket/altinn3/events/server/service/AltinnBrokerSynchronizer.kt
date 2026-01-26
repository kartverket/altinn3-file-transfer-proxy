package no.kartverket.altinn3.events.server.service

import kotlinx.coroutines.coroutineScope
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.*

@EnableConfigurationProperties(AltinnServerConfig::class)
class AltinnBrokerSynchronizer(
    private val eventLoader: EventLoader,
    private val cloudEventHandler: CloudEventHandler,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val altinnConfig: AltinnServerConfig,
    private val failedEventRepository: AltinnFailedEventRepository,
    private val altinnService: AltinnService,
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    var eventRecievedInWebhooksCreatedAt: OffsetDateTime? = null

    private fun findAllFailedEvents(): Set<EventWithFileOverview> {
        val failedEvents = failedEventRepository.findAll().sortedBy { it.created }
        if (failedEvents.isEmpty()) return emptySet()

        val startId = failedEvents.first().previousEventId
        val failedEventsIds = failedEvents.map { it.altinnId.toString() }

        return buildSet {
            eventLoader
                .fetchAndMapEventsByResource(altinnConfig.recipientId) {
                    startId.toString()
                }
                .filter { it.cloudEvent.id in failedEventsIds }
                .forEach { add(it) }
        }
    }

    /**
     * Hovedtanken bak failed events er i et scenario hvor sync er ferdig,
     * og webhook er i ferd med å overta for polling.
     *
     * Dersom webhook prosesserer et event B, og polling feiler på et event A som er sendt mellom sync
     * og når webhook(s) er satt opp, vil polling bevisst ta ned applikasjonen.
     * Når den starter igjen neste gang, vil event B være lagret som det siste eventet.
     *
     * Synkroniseringen benytter dette som startpunkt, og vi har fått et hull hvor vi mangler event A.
     * I stedet lagres nå "failed events", og disse er de første som behandles ved oppstart.
     */
    suspend fun recoverFailedEvents() {
        val previouslyFailedEvents = findAllFailedEvents()
        if (previouslyFailedEvents.isNotEmpty()) {
            logger.info("Found ${previouslyFailedEvents.size} previously failed events")
            previouslyFailedEvents.forEach { handleFailedEvent(it) }
        } else {
            logger.info("No previously failed events found")
        }

        applicationEventPublisher.publishEvent(AltinnProxyStateMachineEvent.RecoverySucceeded())
    }

    private suspend fun handleFailedEvent(event: EventWithFileOverview) {
        logger.info("Inserting event: ${event.cloudEvent.id} from previously failed run")
        cloudEventHandler.handle(event)
        // jdbc støtter ikke egendefinert delete i repo-queries (kan evt. legges til ved å bruke templates)
        failedEventRepository.findDistinctByAltinnIdOrIdNull(UUID.fromString(event.cloudEvent.id))?.let {
            failedEventRepository.deleteById(it.id!!)
            logger.debug("Deleted failed event with id: ${it.id}, altinn id: ${it.altinnId}")
        } ?: error("Failed to find previously failed event")
    }

    suspend fun sync(startEventId: String = "0") = coroutineScope {
        var lastSuccessfullId = startEventId
        logger.info("Synchronizing from broker, event ID ignored: $startEventId")

        altinnService.tryPoll()

        applicationEventPublisher.publishEvent(
            AltinnProxyStateMachineEvent.SyncSucceeded(lastSuccessfullId)
        )
    }

    suspend fun poll() = coroutineScope {
        altinnService.tryPoll()
    }
}