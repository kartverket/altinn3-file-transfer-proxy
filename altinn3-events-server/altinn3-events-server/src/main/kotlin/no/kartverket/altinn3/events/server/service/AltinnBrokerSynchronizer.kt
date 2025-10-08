package no.kartverket.altinn3.events.server.service

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.exceptions.HandlePollEventFailedException
import no.kartverket.altinn3.events.server.exceptions.HandleSyncEventFailedException
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.*
import kotlin.time.Duration

@EnableConfigurationProperties(AltinnServerConfig::class)
class AltinnBrokerSynchronizer(
    private val eventLoader: EventLoader,
    private val cloudEventHandler: CloudEventHandler,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val altinnConfig: AltinnServerConfig,
    private val failedEventRepository: AltinnFailedEventRepository,
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    var eventRecievedInWebhooksCreatedAt: OffsetDateTime? = null

    private fun findAllFailedEvents(): Set<EventWithFileOverview> {
        val failedEvents = failedEventRepository.findAll().sortedBy { it.created }
        if (failedEvents.isEmpty()) return emptySet()

        val startId = failedEvents.first().previousEventId
        val failedEventsIds = failedEvents.map { it.altinnId.toString() }

        return buildSet<EventWithFileOverview> {
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
        logger.info("Synchronizing events from $startEventId")

        val eventsFromAllResources = eventLoader.fetchAndMapEventsByResource(altinnConfig.recipientId) { startEventId }
            .sortedBy { it.fileOverview.created }

        eventsFromAllResources
            .forEach { event ->
                val eventId = requireNotNull(event.cloudEvent.id)
                logger.debug("Syncing event with ID: $eventId")

                cloudEventHandler.tryHandle(
                    event,
                    onSuccess = {
                        lastSuccessfullId = eventId
                    },
                    onFailure = {
                        logger.debug("Failed to sync event with ID: $eventId")
                        throw HandleSyncEventFailedException(lastSuccessfullId)
                    }
                )
            }

        applicationEventPublisher.publishEvent(
            AltinnProxyStateMachineEvent.SyncSucceeded(lastSuccessfullId)
        )
    }

    suspend fun poll(startEventId: String = "0") = coroutineScope {
        // Nb. Altinn sin rekkefølge ser ikke ut til å være basert på "time"-feltet, så
        // det kan være at det blir overlapping på noen events i sync og poll.
        // Det har liten praktisk betydning for oss så lenge vi sorterer listen,
        // men det kan se litt rart ut i loggen hvis man ikke er klar over det.
        logger.info("Starting polling from eventId $startEventId")
        val lastSyncedEventPerWebhook = mutableMapOf<String, CloudEvent>()
        var poll = true

        while (poll) {
            logger.debug("Polling Altinn")
            val eventsFromAllResources = eventLoader.fetchAndMapEventsByResource(altinnConfig.recipientId) { resource ->
                lastSyncedEventPerWebhook[resource]?.id ?: startEventId
            }
                .sortedBy { it.fileOverview.created }

            // Hvis staten er Webhook og det ikke er flere events å prosessere, stopper polling
            if (eventRecievedInWebhooksCreatedAt != null && eventsFromAllResources.isEmpty()) {
                logger.info("Webhook ready and no more events to process. Stopping polling.")
                poll = false
                applicationEventPublisher.publishEvent(
                    AltinnProxyStateMachineEvent.PollingSucceeded()
                )
                continue
            }

            // Vi kan bare forkaste resten dersom time er større eller
            // lik eventRecievedInWebhooksCreatedAt, siden listen er sortert
            val lastIndex = eventsFromAllResources.indexOfFirst {
                eventRecievedInWebhooksCreatedAt != null && it.cloudEvent.time!! >= eventRecievedInWebhooksCreatedAt
            }
                .takeUnless { it == -1 }
                ?: eventsFromAllResources.lastIndex

            var lastSuccessfullId = startEventId

            eventsFromAllResources
                .withIndex()
                .take(lastIndex + 1)
                .forEach { (_, event) ->
                    logger.debug(
                        "Polling event with ID: {}, resourceinstance: {} ",
                        event.cloudEvent.id, event.cloudEvent.resourceinstance
                    )

                    if (
                        eventRecievedInWebhooksCreatedAt != null &&
                        event.cloudEvent.time!! >= eventRecievedInWebhooksCreatedAt
                    ) {
                        logger.info("Polling now replaced by webhook. Stopping polling")
                        poll = false
                        applicationEventPublisher.publishEvent(
                            AltinnProxyStateMachineEvent.PollingSucceeded()
                        )
                    } else {
                        val eventId = requireNotNull(event.cloudEvent.id)
                        cloudEventHandler.tryHandle(
                            event,
                            onSuccess = {
                                requireNotNull(event.cloudEvent.resource).let {
                                    lastSuccessfullId = eventId
                                    lastSyncedEventPerWebhook[it] = event.cloudEvent
                                }
                            },
                            onFailure = {
                                throw HandlePollEventFailedException(
                                    pollFromEventId = lastSuccessfullId,
                                    failedEventID = eventId,
                                )
                            }
                        )
                    }
                }
            delay(Duration.parse(altinnConfig.pollAltinnInterval))
        }
    }
}