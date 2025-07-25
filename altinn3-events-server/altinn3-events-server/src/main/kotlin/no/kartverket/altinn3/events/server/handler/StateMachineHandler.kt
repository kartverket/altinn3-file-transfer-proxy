package no.kartverket.altinn3.events.server.handler

import no.kartverket.altinn3.events.server.domain.state.AltinnProxyState
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.persistence.AltinnFailedEvent
import org.springframework.statemachine.StateContext
import org.springframework.statemachine.listener.StateMachineListenerAdapter
import java.time.OffsetDateTime

class StateMachineHandler(private val actions: StateMachineActions) :
    StateMachineListenerAdapter<AltinnProxyState, AltinnProxyStateMachineEvent>() {
    private inline fun <reified T> StateContext<*, *>.findHeader(key: AltinnProxyStateMachineHeader): T? =
        this.getMessageHeader(key.name) as? T

    fun handleTransition(stateContext: StateContext<AltinnProxyState, AltinnProxyStateMachineEvent>) {
        val sm = stateContext.stateMachine
        val event = stateContext.event
        when (event) {
            AltinnProxyStateMachineEvent.START_RECOVERY -> actions.onRecoveryRequested()
            AltinnProxyStateMachineEvent.RECOVERY_FAILED -> actions.onCriticalError(sm)
            AltinnProxyStateMachineEvent.START_SYNC -> actions.onSyncRequestedEvent()
            AltinnProxyStateMachineEvent.SYNC_COMPLETE -> {
                stateContext.findHeader<String>(AltinnProxyStateMachineHeader.LAST_SYNCED_EVENT)?.let { eventId ->
                    actions.onPollRequestedEvent(eventId, sm)
                }
            }

            AltinnProxyStateMachineEvent.SYNC_FAILED -> actions.onCriticalError(sm)
            AltinnProxyStateMachineEvent.START_WEBHOOKS -> actions.onSetupWebhooksRequestedEvent()
            AltinnProxyStateMachineEvent.POLLING_FAILED -> {
                stateContext.findHeader<AltinnFailedEvent>(AltinnProxyStateMachineHeader.POLLING_FAILED_EVENT)
                    ?.let { altinnFailedEvent ->
                        actions.onPollingFailed(altinnFailedEvent)
                    }
            }

            AltinnProxyStateMachineEvent.WEBHOOKS_FAILED -> actions.onCriticalError(sm)
            AltinnProxyStateMachineEvent.WEBHOOK_READY -> {
                stateContext.findHeader<OffsetDateTime>(AltinnProxyStateMachineHeader.WEBHOOK_CLOUD_EVENT_TIME)
                    ?.let { cloudEventTimestamp ->
                        actions.onStopPollingRequestedEvent(cloudEventTimestamp)
                    }
            }

            AltinnProxyStateMachineEvent.FATAL_ERROR -> actions.onCriticalError(sm)
            else -> return
        }
    }

    override fun stateContext(stateContext: StateContext<AltinnProxyState, AltinnProxyStateMachineEvent>?) {
        val stage = stateContext?.stage

        when (stage) {
            StateContext.Stage.TRANSITION -> handleTransition(stateContext)
            else -> return
        }
    }
}