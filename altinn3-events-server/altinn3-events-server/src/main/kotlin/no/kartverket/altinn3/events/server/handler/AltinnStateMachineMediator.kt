package no.kartverket.altinn3.events.server.handler

import no.kartverket.altinn3.events.server.configuration.WebhookAvailabilityStatus
import no.kartverket.altinn3.events.server.domain.*
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyState
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.SmartLifecycle.DEFAULT_PHASE
import org.springframework.context.event.EventListener
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.statemachine.StateMachine
import reactor.core.publisher.Mono

enum class AltinnProxyStateMachineHeader {
    LAST_SYNCED_EVENT, WEBHOOK_CLOUD_EVENT_TIME, POLLING_FAILED_EVENT
}

private const val PHASE_BEFORE_NETTY = DEFAULT_PHASE - 2056

class AltinnStateMachineMediator(
    private val stateMachine: StateMachine<AltinnProxyState, AltinnProxyStateMachineEvent>
) :
    SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var running = false

    override fun start() {
        logger.info("Started statemachine")
        stateMachine.startReactively().block()

        stateMachine.sendEvent(
            stateMachine.createMessage(AltinnProxyStateMachineEvent.START_RECOVERY)
        ).subscribe()

        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean {
        return running
    }

    override fun getPhase(): Int {
        return PHASE_BEFORE_NETTY
    }

    @EventListener
    fun onApplicationEvent(event: AltinnProxyApplicationEvent) {
        when (event) {
            is RecoveryDoneEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.START_SYNC)

            is AltinnSyncFinishedEvent -> {
                val lastSyncedEvent = event.latestEventId
                stateMachine.sendEvent(
                    stateMachine.createMessage(
                        AltinnProxyStateMachineEvent.SYNC_COMPLETE,
                        AltinnProxyStateMachineHeader.LAST_SYNCED_EVENT,
                        lastSyncedEvent
                    )
                ).subscribe()
            }

            is PollingStartedEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.START_WEBHOOKS)

            is SetupSubscriptionsDoneEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.WAIT_FOR_VALIDATION_CLOUD_EVENT)

            is SubscriptionValidatedEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.WEBHOOK_INITIALIZED)

            is WebhookHandlerReadyEvent -> {
                stateMachine.sendEvent(
                    stateMachine.createMessage(
                        AltinnProxyStateMachineEvent.WEBHOOK_READY,
                        AltinnProxyStateMachineHeader.WEBHOOK_CLOUD_EVENT_TIME,
                        event.cloudEventTime
                    )
                ).subscribe()
            }

            is PollingReachedEndEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.WEBHOOK_EVENT_REACHED_IN_POLLING)

            is RecoveryFailedEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.RECOVERY_FAILED)

            is PollingFailedEvent ->
                stateMachine.sendEvent(
                    stateMachine.createMessage(
                        AltinnProxyStateMachineEvent.POLLING_FAILED,
                        AltinnProxyStateMachineHeader.POLLING_FAILED_EVENT,
                        event.altinnFailedEvent
                    )
                ).subscribe()

            is SyncFailedEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.SYNC_FAILED)

            is SetupWebhooksFailedEvent ->
                stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.WEBHOOKS_FAILED)

            is FatalErrorEvent -> stateMachine.sendSimpleEvent(AltinnProxyStateMachineEvent.FATAL_ERROR)
        }
    }
}

private fun StateMachine<AltinnProxyState, AltinnProxyStateMachineEvent>.createMessage(
    payload: AltinnProxyStateMachineEvent,
    header: AltinnProxyStateMachineHeader? = null,
    headerValue: Any? = null
): Mono<Message<AltinnProxyStateMachineEvent>> {
    val builder = MessageBuilder.withPayload(payload)
    if (header != null)
        builder.setHeader(header.name, headerValue)
    return Mono.just(builder.build())
}

fun StateMachine<AltinnProxyState, AltinnProxyStateMachineEvent>.sendSimpleEvent(
    event: AltinnProxyStateMachineEvent
) = this.sendEvent(createMessage(event)).subscribe()

class StateMachineWebhookAvailabilityStatus(
    private val stateMachine: StateMachine<AltinnProxyState, AltinnProxyStateMachineEvent>
) : WebhookAvailabilityStatus {
    override fun isAvailable(): Boolean {
        val statesOpenForRequests =
            listOf(AltinnProxyState.WEBHOOKS, AltinnProxyState.POLL_AND_WEBHOOKS, AltinnProxyState.STOP_POLLING)

        return statesOpenForRequests.contains(stateMachine.state.id)
    }
}
