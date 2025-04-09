package no.kartverket.altinn3.events.server.domain.state

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import no.kartverket.altinn3.events.server.configuration.Scopes
import no.kartverket.altinn3.events.server.domain.PollingFailedEvent
import no.kartverket.altinn3.events.server.domain.RecoveryFailedEvent
import no.kartverket.altinn3.events.server.domain.SetupWebhooksFailedEvent
import no.kartverket.altinn3.events.server.domain.SyncFailedEvent
import no.kartverket.altinn3.events.server.service.AltinnBrokerSynchronizer
import no.kartverket.altinn3.events.server.service.AltinnWebhookInitializer
import no.kartverket.altinn3.events.server.service.HandlePollEventFailedException
import no.kartverket.altinn3.events.server.service.HandleSyncEventFailedException
import no.kartverket.altinn3.persistence.AltinnFailedEvent
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.statemachine.StateMachine
import org.springframework.statemachine.listener.StateMachineListenerAdapter
import java.util.*
import java.util.function.Supplier
import kotlin.system.exitProcess

private const val RETRY_ATTEMPTS = 3
private const val RETRY_DELAY = 1000L

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
            repeat(RETRY_ATTEMPTS) {
                runCatching {
                    altinnSynchronizer.recoverFailedEvents()
                }.onSuccess {
                    coroutineContext.job.cancelAndJoin()
                }.onFailure {
                    logger.error("Failed to restore previously failed events")
                    logger.error(it.message)
                    logger.error(it.stackTraceToString())
                }
                delay(RETRY_DELAY)
            }
            applicationEventPublisher.publishEvent(RecoveryFailedEvent())
        }
    }

    fun onSyncRequestedEvent() {
        Scopes.altinnScope.launch {
            var syncFromEvent = startEventSupplier.get()
            logger.debug("Starting syncing from event: $syncFromEvent")
            repeat(RETRY_ATTEMPTS) { attempt ->
                try {
                    altinnSynchronizer.sync(syncFromEvent)
                    coroutineContext.job.cancelAndJoin()
                } catch (ex: HandleSyncEventFailedException) {
                    syncFromEvent = ex.lastSuccessfulEventId
                }
                logger.error("Sync failed, attempt: ${attempt + 1}")
                delay(RETRY_DELAY)
            }
            applicationEventPublisher.publishEvent(SyncFailedEvent())
        }
    }

    fun onPollRequestedEvent(
        lastSyncedEvent: String,
        stateMachine: StateMachine<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) {
        Scopes.altinnScope.launch {
            var pollFromEvent = lastSyncedEvent
            var failedEventId: String? = null

            repeat(RETRY_ATTEMPTS) { attempt ->
                try {
                    altinnSynchronizer.poll(pollFromEvent)
                    coroutineContext.job.cancelAndJoin()
                } catch (ex: HandlePollEventFailedException) {
                    pollFromEvent = ex.pollFromEventId
                    failedEventId = ex.failedEventID
                    logger.error("Sync failed, attempt: ${attempt + 1}")
                }
                delay(RETRY_DELAY)
            }
            applicationEventPublisher.publishEvent(
                PollingFailedEvent(
                    AltinnFailedEvent(
                        altinnId = UUID.fromString(failedEventId),
                        previousEventId = UUID.fromString(pollFromEvent),
                        altinnProxyState = stateMachine.state.id.name
                    )
                )
            )
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

    fun onStopPollingRequestedEvent(endEventId: String) {
        altinnSynchronizer.endEventId = endEventId
    }
}
