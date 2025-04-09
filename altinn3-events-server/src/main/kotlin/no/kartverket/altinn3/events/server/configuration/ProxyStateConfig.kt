package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.events.server.domain.state.*
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.beans
import org.springframework.core.env.Environment
import org.springframework.statemachine.StateContext
import org.springframework.statemachine.config.EnableStateMachineFactory
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter
import org.springframework.statemachine.config.StateMachineFactory
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer
import org.springframework.statemachine.listener.StateMachineListenerAdapter
import org.springframework.statemachine.state.State

val stateConfig = beans {
    bean<AltinnStateMachineMediator>()
    bean<StateMachineActions>()
    bean<StateMachineHandler>()
    bean {
        ref<StateMachineFactory<AltinnProxyState, AltinnProxyStateMachineEvent>>().stateMachine
    }
}

const val ALTINN_PROXY_STATE_MACHINE_ID = "altinn-proxy"

@Configuration
@EnableStateMachineFactory
class AltinnProxyStateMachineConfig(
    private val environment: Environment,
    private val handler: StateMachineHandler
) : EnumStateMachineConfigurerAdapter<AltinnProxyState, AltinnProxyStateMachineEvent>() {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun configure(
        transitions: StateMachineTransitionConfigurer<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) {
        initialToSyncTransitions(transitions)
            .and()
        syncToPollingTransition(transitions)
            .and()
        pollingToWebhooksTransitions(transitions)
    }

    override fun configure(
        states: StateMachineStateConfigurer<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) {
        states
            .withStates()
            .initial(AltinnProxyState.INITIAL)
            .state(AltinnProxyState.ERROR)
            .state(AltinnProxyState.STARTUP_RECOVERY)
            .state(AltinnProxyState.SYNCHRONIZING)
            .state(AltinnProxyState.POLLING)
            .state(AltinnProxyState.SETUP_WEBHOOKS)
            .state(AltinnProxyState.WEBHOOKS_PENDING_VALIDATION)
            .state(AltinnProxyState.POLL_AND_WEBHOOKS)
            .state(AltinnProxyState.STOP_POLLING)
            .state(AltinnProxyState.WEBHOOKS)
            .state(AltinnProxyState.STOPPING)
    }

    override fun configure(
        config: StateMachineConfigurationConfigurer<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) {
        config
            .withConfiguration()
            .autoStartup(false)
            .machineId(ALTINN_PROXY_STATE_MACHINE_ID)
            .listener(transitionLogger)
            .listener(handler)
    }

    private fun pollingGuard(ctx: StateContext<AltinnProxyState, AltinnProxyStateMachineEvent>) =
        if (ctx.message.headers[AltinnProxyStateMachineHeader.LAST_SYNCED_EVENT.name] != null)
            true
        else {
            logger.error("INVALID TRANSITION: Last synced event not set")
            false
        }

    private fun setupWebhooksGuard(ctx: StateContext<AltinnProxyState, AltinnProxyStateMachineEvent>) =
        if (environment.activeProfiles.contains("poll")) {
            logger.info(
                "Requested transition to: {}. Polling profile selected. Ignoring {} event",
                ctx.target.id,
                ctx.event.name
            )
            false
        } else true

    private fun stopPollingGuard(ctx: StateContext<AltinnProxyState, AltinnProxyStateMachineEvent>) =
        if (ctx.message.headers[AltinnProxyStateMachineHeader.WEBHOOK_EVENT_ID.name] != null)
            true
        else {
            logger.error("INVALID TRANSITION: End event from webhook not set")
            false
        }

    private fun initialToSyncTransitions(
        transitions: StateMachineTransitionConfigurer<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) =
        transitions
            .withExternal()
            .source(AltinnProxyState.INITIAL).target(AltinnProxyState.STARTUP_RECOVERY)
            .event(AltinnProxyStateMachineEvent.START_RECOVERY)
            .and()
            .withExternal()
            .source(AltinnProxyState.STARTUP_RECOVERY).target(AltinnProxyState.ERROR)
            .event(AltinnProxyStateMachineEvent.RECOVERY_FAILED)
            .and()
            .withExternal()
            .source(AltinnProxyState.STARTUP_RECOVERY).target(AltinnProxyState.SYNCHRONIZING)
            .event(AltinnProxyStateMachineEvent.START_SYNC)
            .and()
            .withExternal()
            .source(AltinnProxyState.SYNCHRONIZING).target(AltinnProxyState.ERROR)
            .event(AltinnProxyStateMachineEvent.SYNC_FAILED)

    private fun syncToPollingTransition(
        transitions: StateMachineTransitionConfigurer<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) =
        transitions
            .withExternal()
            .source(AltinnProxyState.SYNCHRONIZING).target(AltinnProxyState.POLLING)
            .event(AltinnProxyStateMachineEvent.SYNC_COMPLETE)
            .guard(::pollingGuard)
            .and()
            .withExternal()
            .source(AltinnProxyState.POLLING).target(AltinnProxyState.ERROR)
            .event(AltinnProxyStateMachineEvent.POLLING_FAILED)

    private fun pollingToWebhooksTransitions(
        transitions: StateMachineTransitionConfigurer<AltinnProxyState, AltinnProxyStateMachineEvent>
    ) =
        transitions
            .withExternal()
            .source(AltinnProxyState.POLLING).target(AltinnProxyState.SETUP_WEBHOOKS)
            .event(AltinnProxyStateMachineEvent.START_WEBHOOKS)
            .guard(::setupWebhooksGuard)
            .and()
            .withExternal()
            .source(AltinnProxyState.SETUP_WEBHOOKS).target(AltinnProxyState.ERROR)
            .event(AltinnProxyStateMachineEvent.POLLING_FAILED)
            .and()
            .withExternal()
            .source(AltinnProxyState.SETUP_WEBHOOKS).target(AltinnProxyState.ERROR)
            .event(AltinnProxyStateMachineEvent.WEBHOOKS_FAILED)
            .and()
            .withExternal()
            .source(AltinnProxyState.SETUP_WEBHOOKS).target(AltinnProxyState.WEBHOOKS_PENDING_VALIDATION)
            .event(AltinnProxyStateMachineEvent.WAIT_FOR_VALIDATION_CLOUD_EVENT)
            .and()
            .withExternal()
            .source(AltinnProxyState.WEBHOOKS_PENDING_VALIDATION).target(AltinnProxyState.ERROR)
            .event(AltinnProxyStateMachineEvent.POLLING_FAILED)
            .and()
            .withExternal()
            .source(AltinnProxyState.WEBHOOKS_PENDING_VALIDATION).target(AltinnProxyState.POLL_AND_WEBHOOKS)
            .event(AltinnProxyStateMachineEvent.WEBHOOK_INITIALIZED)
            .and()
            .withExternal()
            .source(AltinnProxyState.POLL_AND_WEBHOOKS).target(AltinnProxyState.ERROR)
            .event(AltinnProxyStateMachineEvent.POLLING_FAILED)
            .and()
            .withExternal()
            .source(AltinnProxyState.POLL_AND_WEBHOOKS).target(AltinnProxyState.STOP_POLLING)
            .event(AltinnProxyStateMachineEvent.WEBHOOK_READY)
            .guard(::stopPollingGuard)
            .and()
            .withExternal()
            .source(AltinnProxyState.STOP_POLLING).target(AltinnProxyState.WEBHOOKS)
            .event(AltinnProxyStateMachineEvent.WEBHOOK_EVENT_REACHED_IN_POLLING)

    private val transitionLogger =
        object : StateMachineListenerAdapter<AltinnProxyState, AltinnProxyStateMachineEvent>() {
            override fun stateChanged(
                from: State<AltinnProxyState, AltinnProxyStateMachineEvent>?,
                to: State<AltinnProxyState, AltinnProxyStateMachineEvent>
            ) = from?.id.let {
                logger.info(
                    "Changed state from ${it ?: "start"} to ${to.id}"
                )
            }
        }
}
