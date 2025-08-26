package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.handler.StateMachineActions
import no.kartverket.altinn3.events.server.service.StateMachine
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.SmartLifecycle.DEFAULT_PHASE
import org.springframework.context.event.EventListener
import org.springframework.context.support.beans
import org.springframework.core.env.Environment

val stateConfig = beans {
    bean<StateMachineActions>()
    bean {
        initializeStateMachine(ref(), ref())
    }
    bean<StateMachineHandler>()
}

class StateMachineWebhookAvailabilityStatus(
    private val stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>
) : WebhookAvailabilityStatus {
    override fun isAvailable(): Boolean {
        val statesOpenForRequests =
            listOf(State.Webhook, State.PollAndWebhook)

        return statesOpenForRequests.contains(stateMachine.state)
    }
}

sealed class SideEffect {
    object RecoveryRequested : SideEffect()
    object RecoveryFailed : SideEffect()
    object SyncRequested : SideEffect()
    object SyncFailed : SideEffect()
    object PollRequested : SideEffect()
    object PollFailed : SideEffect()
    object WebhookRequested : SideEffect()
    object WebbhookFailed : SideEffect()
    object StopPollRequested : SideEffect()
    object CriticalError : SideEffect()
    object ServiceUnavailable : SideEffect()
    object ServiceAvailableAgainAfterUnavailability : SideEffect()
}

private const val PHASE_BEFORE_NETTY = DEFAULT_PHASE - 2056

class StateMachineHandler(private val sm: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>) :
    SmartLifecycle {
    private var started = false

    @EventListener
    fun onApplicationEvent(event: AltinnProxyStateMachineEvent) {
        sm.transition(event)
    }

    override fun start() {
        sm.transition(AltinnProxyStateMachineEvent.StartRecovery())
        started = true
    }

    override fun stop() {
        started = false
    }

    override fun getPhase(): Int {
        return PHASE_BEFORE_NETTY
    }

    override fun isRunning(): Boolean = started
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun initializeStateMachine(
    actions: StateMachineActions,
    environment: Environment
): StateMachine<State, AltinnProxyStateMachineEvent, SideEffect> = StateMachine.create {
    initialState(State.Initial)
    state<State.Initial> {
        on<AltinnProxyStateMachineEvent.StartRecovery> {
            transitionTo(
                State.StartupRecovery,
                SideEffect.RecoveryRequested
            )
        }
    }
    state<State.StartupRecovery> {
        on<AltinnProxyStateMachineEvent.RecoveryFailed> {
            transitionTo(
                State.StartupRecovery,
                SideEffect.RecoveryFailed
            )
        }
        on<AltinnProxyStateMachineEvent.RecoverySucceeded> {
            transitionTo(
                State.Synchronize,
                SideEffect.SyncRequested
            )
        }
    }
    state<State.Synchronize> {
        on<AltinnProxyStateMachineEvent.SyncFailed> {
            transitionTo(State.Error, SideEffect.SyncFailed)
        }
        on<AltinnProxyStateMachineEvent.SyncSucceeded> {
            if (!environment.activeProfiles.contains("poll")) {
                transitionTo(
                    State.SetupWebhook,
                    SideEffect.WebhookRequested
                )
            } else {
                transitionTo(State.Poll, SideEffect.PollRequested)
            }
        }
    }
    state<State.Poll> {
        on<AltinnProxyStateMachineEvent.PollingFailed> {
            transitionTo(State.Error, SideEffect.PollFailed)
        }
        on<AltinnProxyStateMachineEvent.ServiceAvailable> {
            transitionTo(State.SetupWebhook, SideEffect.WebhookRequested)
        }
    }
    state<State.SetupWebhook> {
        on<AltinnProxyStateMachineEvent.WebhookFailed> {
            transitionTo(State.Error, SideEffect.WebbhookFailed)
        }
        on<AltinnProxyStateMachineEvent.WebhookValidated> {
            transitionTo(State.PollAndWebhook)
        }
        on<AltinnProxyStateMachineEvent.PollingFailed> {
            transitionTo(State.Error, SideEffect.PollFailed)
        }
    }
    state<State.PollAndWebhook> {
        on<AltinnProxyStateMachineEvent.WebhookReady> {
            transitionTo(
                State.Webhook,
                SideEffect.StopPollRequested
            )
        }
        on<AltinnProxyStateMachineEvent.PollingFailed> {
            transitionTo(State.Error, SideEffect.PollFailed)
        }
        on<AltinnProxyStateMachineEvent.ServiceUnavailable> {
            transitionTo(
                State.Poll,
                SideEffect.ServiceUnavailable
            )
        }
    }
    state<State.Webhook> {
        on<AltinnProxyStateMachineEvent.ServiceUnavailable> {
            transitionTo(
                State.Poll,
                SideEffect.ServiceUnavailable
            )
        }
    }
    // Transition to Error state from all states
    // when recieving Event.CriticalError
    State::class.sealedSubclasses
        .mapNotNull { it.objectInstance }
        .forEach { s ->
            state(s) {
                on<AltinnProxyStateMachineEvent.CriticalError> {
                    transitionTo(
                        State.Error,
                        SideEffect.CriticalError
                    )
                }
            }
        }

    onTransition {
        val transition = it as? StateMachine.Transition.Valid ?: return@onTransition
        val logger = LoggerFactory.getLogger(javaClass)
        logger.info(
            "Transition from state: ${it.fromState.toString().uppercase()} to ${
                it.toState.toString().uppercase()
            } on event: ${it.event.toString().uppercase()}"
        )
        val sideEffect = transition.sideEffect ?: return@onTransition


        when (sideEffect) {
            SideEffect.RecoveryRequested -> actions.onRecoveryRequested()
            SideEffect.RecoveryFailed -> actions.onCriticalError(it.fromState)
            SideEffect.SyncRequested -> actions.onSyncRequested()
            SideEffect.SyncFailed -> actions.onCriticalError(it.fromState)
            SideEffect.ServiceUnavailable -> {
                val altinnProxyStateMachineEvent = it.event as AltinnProxyStateMachineEvent.ServiceUnavailable
                actions.onPollRequestedEvent(altinnProxyStateMachineEvent.lastSyncedEvent)
            }

            SideEffect.ServiceAvailableAgainAfterUnavailability -> {
                actions.onServiceAvailableAfterUnavailability()
            }

            SideEffect.PollRequested -> {
                val altinnProxyStateMachineEvent = it.event as AltinnProxyStateMachineEvent.SyncSucceeded
                actions.onPollRequestedEvent(altinnProxyStateMachineEvent.lastSyncedEvent)
            }

            SideEffect.PollFailed -> {
                val altinnProxyStateMachineEvent = it.event as AltinnProxyStateMachineEvent.PollingFailed
                actions.onPollingFailed(altinnProxyStateMachineEvent.failedEvent)
            }

            SideEffect.WebhookRequested -> {
                val altinnProxyStateMachineEvent = it.event as AltinnProxyStateMachineEvent.SyncSucceeded
                actions.onSetupWebhooksRequested(altinnProxyStateMachineEvent.lastSyncedEvent)
            }

            SideEffect.WebbhookFailed -> actions.onCriticalError(it.fromState)
            SideEffect.StopPollRequested -> {
                val altinnProxyStateMachineEvent = it.event as AltinnProxyStateMachineEvent.WebhookReady
                actions.onStopPollingRequested(altinnProxyStateMachineEvent.cloudEventTime)
            }

            SideEffect.CriticalError -> actions.onCriticalError(it.fromState)
        }
    }
}

