package no.kartverket.altinn3.events.server.handler

import kotlinx.coroutines.launch
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.configuration.Scopes
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.exceptions.HandlePollEventFailedException
import no.kartverket.altinn3.events.server.service.AltinnBrokerSynchronizer
import no.kartverket.altinn3.events.server.service.AltinnWebhookInitializer
import no.kartverket.altinn3.persistence.AltinnFailedEvent
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.*
import java.util.function.Supplier
import kotlin.system.exitProcess
import kotlin.time.Duration

class StateMachineActions(
    private val startEventSupplier: Supplier<String>,
    private val altinnSynchronizer: AltinnBrokerSynchronizer,
    private val altinnWebhookInitializer: AltinnWebhookInitializer,
    private val altinnFailedEventRepository: AltinnFailedEventRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val applicationContext: ApplicationContext,
    private val altinnServerConfig: AltinnServerConfig
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun onCriticalError(state: State, throwable: Throwable? = null) {
        logger.error("CRITICAL ERROR!")
        logger.error("Error in StateMachine: {}", state)
        logger.error(throwable.toString())
        exitProcess(SpringApplication.exit(applicationContext, { 1 }))
    }

    fun onRecoveryRequested() {
        Scopes.altinnProxyScope.launch {
            runCatching {
                altinnSynchronizer.recoverFailedEvents()
            }.onFailure {
                logger.error("Failed to restore previously failed events")
                logger.error(it.message)
                logger.error(it.stackTraceToString())
                applicationEventPublisher.publishEvent(AltinnProxyStateMachineEvent.RecoveryFailed())
            }
        }
    }

    fun onServiceAvailableAfterUnavailability(lastSyncedEvent: String) {
        Scopes.altinnProxyScope.launch {
            launch {
                startPolling(lastSyncedEvent)
            }
            runCatching {
                altinnWebhookInitializer.deleteSubscriptions()
                setupWebhooks()
            }.onFailure {
                logger.error(it.message)
                logger.error(it.stackTraceToString())
                applicationEventPublisher.publishEvent(AltinnProxyStateMachineEvent.RecoveryFailed())
            }
        }
    }

    fun onSyncRequested() {
        Scopes.altinnProxyScope.launch {
            runCatching {
                var syncFromEvent = startEventSupplier.get()
                logger.debug("Starting syncing from event: $syncFromEvent")
                altinnSynchronizer.sync(syncFromEvent)
            }.onFailure {
                applicationEventPublisher.publishEvent(AltinnProxyStateMachineEvent.SyncFailed())
            }
        }
    }

    fun onPollRequestedEvent(
        lastSyncedEvent: String,
    ) {
        Scopes.altinnProxyScope.launch {
            runCatching {
                startPolling(lastSyncedEvent)
            }.onFailure {
                logger.error("")
                logger.error(it.message)
                logger.error(it.stackTraceToString())
            }
        }
    }

    private suspend fun startPolling(lastSyncedEvent: String) {
        runCatching {
            altinnSynchronizer.poll(lastSyncedEvent)
        }.onFailure {
            val eventToPublish = when (it) {
                is HandlePollEventFailedException -> {
                    logger.error(
                        "Could not poll event with id: {}\nWill try to persist error state.",
                        it.failedEventID
                    )
                    AltinnProxyStateMachineEvent.PollingFailed(
                        AltinnFailedEvent(
                            altinnId = UUID.fromString(it.failedEventID),
                            previousEventId = UUID.fromString(it.pollFromEventId),
                        )
                    )
                }

                else -> {
                    logger.error("Something went wrong when polling for event.")
                    logger.error(it.message)
                    AltinnProxyStateMachineEvent.CriticalError(throwable = it)
                }
            }
            applicationEventPublisher.publishEvent(eventToPublish)
        }
    }

    fun onPollingFailed(failedEvent: AltinnFailedEvent) = persistFailedEvent(failedEvent)
    private fun persistFailedEvent(failedEvent: AltinnFailedEvent) {
        logger.error(
            "CRITICAL ERROR! Failed to save event: ${failedEvent.altinnId} in polling. Trying to persist error state"
        )
        runCatching {
            altinnFailedEventRepository.save(
                failedEvent,
            )
        }.onFailure { ex ->
            logger.error("CRITICAL ERROR! Failed to persist failed state for event: ${failedEvent.altinnId}!")
            logger.error(ex.message)
            logger.error(ex.stackTraceToString())
        }
    }

    fun onSetupWebhooksRequested(lastSyncedEvent: String) {
        Scopes.altinnProxyScope.launch {
            launch {
                startPolling(lastSyncedEvent)
            }
            launch {
                altinnWebhookInitializer.deleteSubscriptions()
                setupWebhooks()
            }
        }
    }

    fun onStopPollingRequested(cloudEventTime: OffsetDateTime) {
        altinnSynchronizer.eventRecievedInWebhooksCreatedAt = cloudEventTime
    }

    private suspend fun setupWebhooks() {
        runCatching {
            val delay = Duration.parse(altinnServerConfig.webhookSubscriptionDelay)
            altinnWebhookInitializer.setupWebhooks(delay)
        }.onFailure {
            logger.error("Setup webhooks failed: {}", it.message)
            logger.error(it.stackTraceToString())
            applicationEventPublisher.publishEvent(AltinnProxyStateMachineEvent.WebhookFailed())
        }
    }
}
