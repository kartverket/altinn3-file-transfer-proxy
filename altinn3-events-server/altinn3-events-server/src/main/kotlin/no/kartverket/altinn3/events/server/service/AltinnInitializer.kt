package no.kartverket.altinn3.events.server.service

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.kartverket.altinn3.client.EventsClient
import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.configuration.AltinnWebhooks
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.Subscription
import no.kartverket.altinn3.models.SubscriptionRequestModel
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.retry.support.RetryTemplate
import java.time.OffsetDateTime
import java.util.*
import kotlin.time.Duration

class EventLoader(
    private val eventsClient: EventsClient,
    private val webhooks: AltinnWebhooks,
    private val retryTemplate: RetryTemplate,
) {
    fun fetchAndMapEventsByResource(recipientOrgNr: String, afterSupplier: (resource: String) -> String = { "0" }) =
        webhooks
            .groupBy({ it.resourceFilter!! }) { it.typeFilter }
            .map { (resource, type) ->
                val typesMaybe =
                    if (type.any { it.isNullOrBlank() }) null
                    else type.filterNotNull().filter { it.isNotBlank() }
                eventsClient.events.loadResourceEventType(
                    resource,
                    typesMaybe,
                    subject = "/organisation/${recipientOrgNr}",
                    afterSupplier = afterSupplier
                )
            }

    fun EventsApi.loadResourceEventType(
        resource: String,
        type: List<String>?,
        pageSize: Int = 50,
        subject: String,
        afterSupplier: (resource: String) -> String = { "0" }
    ) = buildSet {
        var after: String = afterSupplier(resource)
        var page: List<CloudEvent>
        do {
            page = retryTemplate.execute<List<CloudEvent>, IllegalStateException> {
                eventsGet(resource, after, size = pageSize, type = type, subject = subject)
            }
            page.forEach { add(it) }
            if (page.isNotEmpty()) after = page.last().id!!
        } while (page.size == pageSize)
    }
}

class HandleSyncEventFailedException(val lastSuccessfulEventId: String) : RuntimeException()
class HandlePollEventFailedException(
    val pollFromEventId: String,
    val failedEventID: String
) : RuntimeException()

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

    private fun findAllFailedEvents(): Set<CloudEvent> {
        val failedEvents = failedEventRepository.findAll().sortedBy { it.created }
        if (failedEvents.isEmpty()) {
            return emptySet()
        }
        val startId = failedEvents.first().previousEventId

        val failedEventsIds = failedEvents.map { it.altinnId.toString() }

        return buildSet {
            eventLoader.fetchAndMapEventsByResource(altinnConfig.recipientId) {
                startId.toString()
            }
                .flatten()
                .forEach { event ->
                    if (failedEventsIds.contains(event.id)) {
                        add(event)
                    }
                }
        }.toSet()
    }

    // Hovedtanken bak failed events er i et scenario hvor sync er ferdig, og webhook er i ferd med å overta for polling.
    // Dersom webhook prosesserer et event B, og polling feiler på et event A som er sendt mellom sync og når webhook(s) er satt opp, vil
    // polling bevisst ta ned applikasjonen. Når den starter igjen neste gang, vil event B være lagret som det siste eventet.
    // Synkroniseringen benytter dette som startpunkt, og vi har fått et hull hvor vi mangler event A.
    // I stedet lagres nå "failed events", og disse er de første som behandles ved oppstart.
    suspend fun recoverFailedEvents() {
        val previouslyFailedEvents = findAllFailedEvents()
        if (previouslyFailedEvents.size > 0) {
            logger.info("Found {} previously failed events", previouslyFailedEvents.size)
            previouslyFailedEvents.forEach {
                logger.info("Inserting event: {} from previously failed run", it.id)
                cloudEventHandler.handle(it)
                // jdbc støtter ikke egendefinert delete i repo-queries (kan evt. legges til ved å bruke templates)
                failedEventRepository.findDistinctByAltinnIdOrIdNull(UUID.fromString(it.id))?.let {
                    failedEventRepository.deleteById(it.id!!)
                    logger.debug("Deleted failed event with id: {}, altinn id: {}", it.id, it.altinnId)
                } ?: error("Failed to find previously failed event")
            }
        } else {
            logger.info("No previously failed events found")
        }

        applicationEventPublisher.publishEvent(AltinnProxyStateMachineEvent.RecoverySucceeded())
    }

    suspend fun sync(startEventId: String = "0") = coroutineScope {
        var lastSuccessfullId = startEventId

        logger.info("Synchronizing events from $startEventId")
        val eventsFromAllResources = eventLoader.fetchAndMapEventsByResource(altinnConfig.recipientId) { startEventId }
            .flatten()
            .sortedBy { it.time }

        eventsFromAllResources
            .forEach { event ->
                val eventId = requireNotNull(event.id)
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
                .flatten()
                .sortedBy { it.time }

            // Vi kan bare forkaste resten dersom time er større eller
            // lik eventRecievedInWebhooksCreatedAt, siden listen er sortert
            val lastIndex = eventsFromAllResources.indexOfFirst {
                eventRecievedInWebhooksCreatedAt != null && it.time!! >= eventRecievedInWebhooksCreatedAt
            }
                .takeUnless { it == -1 }
                ?: eventsFromAllResources.lastIndex

            var lastSuccessfullId = startEventId

            eventsFromAllResources
                .withIndex()
                .take(lastIndex + 1)
                .forEach { (_, event) ->
                    logger.debug("Polling event with ID: ${event.id}, resourceinstance: ${event.resourceinstance}")
                    if (eventRecievedInWebhooksCreatedAt != null && event.time!! >= eventRecievedInWebhooksCreatedAt) {
                        logger.info("Polling now replaced by webhook. Stopping polling")
                        poll = false
                        applicationEventPublisher.publishEvent(
                            AltinnProxyStateMachineEvent.PollingSucceeded()
                        )
                    } else {
                        val eventId = requireNotNull(event.id)
                        cloudEventHandler.tryHandle(
                            event,
                            onSuccess = {
                                requireNotNull(event.resource).let {
                                    lastSuccessfullId = eventId
                                    lastSyncedEventPerWebhook[it] = event
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

class AltinnWebhookInitializer(
    private val eventsClient: EventsClient,
    private val altinnWebhooks: AltinnWebhooks,
    private val retryTemplate: RetryTemplate,
) : DisposableBean {
    private lateinit var subscriptions: Set<Subscription>
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private fun SubscriptionApi.setUpSubscriptions(): Collection<Subscription> {
        return altinnWebhooks.map { webhook ->
            retryTemplate.execute<Subscription, IllegalStateException> {
                subscriptionsPost(
                    SubscriptionRequestModel(
                        endPoint = altinnWebhooks.webhookEndpoint(webhook),
                        resourceFilter = webhook.resourceFilter,
                        typeFilter = webhook.typeFilter,
                        subjectFilter = webhook.subjectFilter,
                    )
                )
            }
        }.onEach {
            logger.info("new Altinn subscription: $it")
        }
    }

    private fun SubscriptionApi.deleteAll(): Collection<Int> {
        return subscriptions.map { subscription ->
            retryTemplate.execute<Unit, IllegalStateException> { this.subscriptionsIdDelete(subscription.id!!) }
            subscription.id!!
        }.onEach { logger.info("Altinn subscription id($it) deleted") }
    }

    override fun destroy() = runBlocking {
        deleteSubscriptions()
    }

    fun deleteSubscriptions() {
        if (::subscriptions.isInitialized) runCatching {
            eventsClient.subscription.deleteAll()
        }.onFailure {
            logger.error("delete subscriptions error", it)
        }
    }

    suspend fun setupWebhooks(delay: Duration) = coroutineScope {
        delay(delay)
        subscriptions = eventsClient.subscription.setUpSubscriptions().toCollection(hashSetOf())
        logger.info("setup subscriptions: completed")
    }
}
