package no.kartverket.altinn3.events.server.handler

import kotlinx.coroutines.launch
import no.kartverket.altinn3.events.server.configuration.Scopes
import no.kartverket.altinn3.events.server.domain.*
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyState
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.service.AltinnBrokerSynchronizer
import no.kartverket.altinn3.events.server.service.AltinnWebhookInitializer
import no.kartverket.altinn3.events.server.service.HandlePollEventFailedException
import no.kartverket.altinn3.persistence.AltinnFailedEvent
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.statemachine.StateMachine
import org.springframework.statemachine.listener.StateMachineListenerAdapter
import java.time.OffsetDateTime
import java.util.*
import java.util.function.Supplier
import kotlin.system.exitProcess

class StateMachineActions(
    private val startEventSupplier: Supplier<String>,
    private val altinnSynchronizer: AltinnBrokerSynchronizer,
    private val altinnWebhookInitializer: AltinnWebhookInitializer,
    private val altinnFailedEventRepository: AltinnFailedEventRepository,
    private val applicationEventPublisher: ApplicationEventPublisher
) : StateMachineListenerAdapter<AltinnProxyState, AltinnProxyStateMachineEvent>() {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun onCriticalError(stateMachine: StateMachine<AltinnProxyState, AltinnProxyStateMachineEvent>) {
        logger.error("CRITICAL ERROR!")
        logger.error("Error in StateMachine: {}", stateMachine)
        exitProcess(1)
    }

    fun onRecoveryRequested() {
        Scopes.altinnScope.launch {
            runCatching {
                altinnSynchronizer.recoverFailedEvents()
            }.onFailure {
                logger.error("Failed to restore previously failed events")
                logger.error(it.message)
                logger.error(it.stackTraceToString())
                applicationEventPublisher.publishEvent(RecoveryFailedEvent())
            }
        }
    }


    fun onSyncRequestedEvent() {
        Scopes.altinnScope.launch {
            runCatching {
                var syncFromEvent = startEventSupplier.get()
                logger.debug("Starting syncing from event: $syncFromEvent")
                altinnSynchronizer.sync(syncFromEvent)
            }.onFailure {
                applicationEventPublisher.publishEvent(SyncFailedEvent())
            }
        }
    }

    fun onPollRequestedEvent(
        lastSyncedEvent: String,
        stateMachine: StateMachine<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) {
        Scopes.altinnScope.launch {
            runCatching {
                altinnSynchronizer.poll(lastSyncedEvent)
            }.onFailure {
                val eventToPublish = when (it) {
                    is HandlePollEventFailedException -> {
                        logger.error(
                            "Could not poll event with id: {}\nWill try to persist error state.",
                            it.failedEventID
                        )
                        PollingFailedEvent(
                            AltinnFailedEvent(
                                altinnId = UUID.fromString(it.failedEventID),
                                previousEventId = UUID.fromString(it.pollFromEventId),
                                altinnProxyState = stateMachine.state.id.name
                            )
                        )
                    }

                    else -> {
                        logger.error("Something went wrong when polling for event.")
                        logger.error(it.message)
                        FatalErrorEvent()
                    }
                }
                applicationEventPublisher.publishEvent(eventToPublish)
            }
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

    fun onSetupWebhooksRequestedEvent() {
        Scopes.altinnScope.launch {
            runCatching {
                altinnWebhookInitializer.setupWebhooks()
            }.onFailure {
                logger.error("Setup webhooks failed: {}", it.message)
                logger.error(it.stackTraceToString())
                applicationEventPublisher.publishEvent(SetupWebhooksFailedEvent())
            }
        }
    }

    fun onStopPollingRequestedEvent(cloudEventTime: OffsetDateTime) {
        altinnSynchronizer.eventRecievedInWebhooksCreatedAt = cloudEventTime
    }
}
