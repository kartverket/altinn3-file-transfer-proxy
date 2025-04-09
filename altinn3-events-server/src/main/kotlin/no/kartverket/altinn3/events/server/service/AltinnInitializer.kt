package no.kartverket.altinn3.events.server.service

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import no.kartverket.altinn3.client.EventsClient
import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.events.infrastructure.ApiClient
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.configuration.AltinnWebhooks
import no.kartverket.altinn3.events.server.domain.*
import no.kartverket.altinn3.events.server.handler.AltinnWarningException
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
import org.springframework.web.client.RestClientResponseException
import java.util.*
import kotlin.time.Duration

class EventLoader(
    private val eventsClient: EventsClient,
    private val webhooks: AltinnWebhooks
) {
    fun fetchAndMapEventsByResource(afterSupplier: (resource: String) -> String = { "0" }) =
        webhooks
            .groupBy({ it.resourceFilter!! }) { it.typeFilter }
            .map { (resource, type) ->
                val typesMaybe =
                    if (type.any { it.isNullOrBlank() }) null
                    else type.filterNotNull().filter { it.isNotBlank() }
                eventsClient.events.loadResourceEventType(
                    resource,
                    typesMaybe,
                    subject = "/organisation/971040238",
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
            page = eventsGet(resource, after, size = pageSize, type = type, subject = subject)
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
    var endEventId: String? = null

    private fun findAllFailedEvents(): Set<CloudEvent> {
        val failedEvents = failedEventRepository.findAll().sortedBy { it.created }
        if (failedEvents.isEmpty()) {
            return emptySet()
        }
        val startId = failedEvents.first().previousEventId

        val failedEventsIds = failedEvents.map { it.altinnId.toString() }

        return buildSet {
            eventLoader.fetchAndMapEventsByResource {
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

        applicationEventPublisher.publishEvent(RecoveryDoneEvent())
    }

    suspend fun sync(startEventId: String = "0") = coroutineScope {
        var lastSuccessfullId = startEventId

        logger.info("Synchronizing events from $startEventId")
        val eventsFromAllResources = eventLoader.fetchAndMapEventsByResource { startEventId }
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
                        if (it is AltinnWarningException) {
                            lastSuccessfullId = eventId
                        } else {
                            logger.debug("Failed to sync event with ID: $eventId")
                            throw HandleSyncEventFailedException(lastSuccessfullId)
                        }
                    }
                )
            }

        applicationEventPublisher.publishEvent(
            AltinnSyncFinishedEvent(lastSuccessfullId)
        )
    }

    suspend fun poll(startEventId: String = "0") = coroutineScope {
        // Nb. Altinn sin rekkefølge ser ikke ut til å være basert på "time"-feltet, så
        // det kan være at det blir overlapping på noen events i sync og poll.
        // Det har liten praktisk betydning for oss så lenge vi sorterer listen,
        // men det kan se litt rart ut i loggen hvis man ikke er klar over det.
        logger.info("Starting polling from eventId $startEventId")
        applicationEventPublisher.publishEvent(PollingStartedEvent())
        val lastSyncedEventPerWebhook = mutableMapOf<String, CloudEvent>()
        var poll = true

        while (poll) {
            val eventsFromAllResources = eventLoader.fetchAndMapEventsByResource { resource ->
                lastSyncedEventPerWebhook[resource]?.id ?: startEventId
            }
                .flatten()
                .sortedBy { it.time }

            // Vi kan bare forkaste resten dersom vi finner endEventId, siden listen er sortert
            val processUpToIndex =
                eventsFromAllResources.find {
                    it.id == endEventId
                }.run {
                    eventsFromAllResources.indexOf(this)
                }.let { index ->
                    if (index == -1) eventsFromAllResources.size else index + 1
                }

            var lastSuccessfullId = startEventId

            eventsFromAllResources
                .subList(0, processUpToIndex)
                .forEach { event ->
                    logger.debug("Polling event with ID: ${event.id}, resourceinstance: ${event.resourceinstance}")
                    if (event.id == endEventId) {
                        logger.info("Reached end-event. Stopping polling")
                        poll = false
                        applicationEventPublisher.publishEvent(PollingReachedEndEvent())
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

private const val API_RETRY_DELAY = 500L

class AltinnWebhookInitializer(
    private val eventsClient: EventsClient,
    private val altinnWebhooks: AltinnWebhooks,
    private val applicationEventPublisher: ApplicationEventPublisher,
) : DisposableBean {
    private lateinit var subscriptions: Set<Subscription>
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private fun SubscriptionApi.setUpSubscriptions(): Flow<Subscription> {
        return altinnWebhooks.map { webhook ->
            retryWrapApiCall {
                subscriptionsPost(
                    SubscriptionRequestModel(
                        endPoint = altinnWebhooks.webhookEndpoint(webhook),
                        resourceFilter = webhook.resourceFilter,
                        typeFilter = webhook.typeFilter
                    )
                )
            }
        }.merge().onEach {
            logger.info("new Altinn subscription: $it")
        }
    }

    private fun SubscriptionApi.deleteAll(): Flow<Int> {
        return subscriptions.map {
            retryWrapApiCall(retries = 5) {
                this.subscriptionsIdDelete(it.id!!)
                it.id!!
            }
        }.merge().onEach { logger.info("Altinn subscription id($it) deleted") }
    }

    private fun <T : ApiClient, R> T.retryWrapApiCall(retries: Long = 2, apiCall: T.() -> R): Flow<R> {
        return flow {
            emit(apiCall())
        }.retry(retries) { t ->
            when (t) {
                is RestClientResponseException -> !t.statusCode.isError
                else -> false
            }.also {
                if (it) delay(API_RETRY_DELAY)
            }
        }
    }

    override fun destroy() = runBlocking {
        if (::subscriptions.isInitialized) eventsClient.subscription.deleteAll().catch {
            logger.error("delete subscriptions error", it)
        }.collect()
    }

    suspend fun setupWebhooks() = coroutineScope {
        subscriptions = eventsClient.subscription.setUpSubscriptions().toCollection(hashSetOf())
        logger.info("setup subscriptions: completed")
        applicationEventPublisher.publishEvent(SetupSubscriptionsDoneEvent())
    }
}
