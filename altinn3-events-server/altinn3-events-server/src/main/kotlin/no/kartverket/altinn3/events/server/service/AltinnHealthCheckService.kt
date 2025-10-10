package no.kartverket.altinn3.events.server.service

import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.models.SideEffect
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationEventPublisherAware
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled

class AltinnHealthCheckService(
    private val altinnServerConfig: AltinnServerConfig,
    private val stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>,
    private val altinnTransitService: AltinnTransitService,
    private val brokerClient: BrokerClient
) : ApplicationEventPublisherAware {
    private val logger = LoggerFactory.getLogger(AltinnHealthCheckService::class.java)
    lateinit var publisher: ApplicationEventPublisher

    @Profile("!poll")
    @Scheduled(fixedRateString = "#{altinnHealthCheckProperties.interval}", initialDelay = 60000)
    fun checkAltinnHealth() {
        if (stateMachine.state == State.Initial || stateMachine.state == State.Synchronize) {
            return
        }
        try {
            val response =
                brokerClient.healthCheckViaFileTranser(resourceId = altinnServerConfig.resourceId)
            if (stateMachine.state == State.Poll && response.statusCode.is2xxSuccessful) {
                val lastEventId =
                    // TODO: Hva skjer hvis denne får null, og spinnes det ny schedulering opp på en ny tråd? Shutdown?
                    requireNotNull(
                        altinnTransitService.findNewestEvent()
                    )
                publisher.publishEvent(AltinnProxyStateMachineEvent.ServiceAvailable(lastEventId))
            }
            if (response.statusCode.is4xxClientError || response.statusCode.is5xxServerError) {
                publishServiceUnavailableEvent("$${response.statusCode} ${response.body}")
            }
        } catch (ex: Exception) {
            publishServiceUnavailableEvent(ex.stackTraceToString())
        }
    }

    private fun publishServiceUnavailableEvent(errorMessage: String) {
        logger.error("Could not health check Altinn: $errorMessage")
        if (stateMachine.state != State.Poll) {
            val lastEventId =
                // TODO: Hva skjer hvis denne får null, og spinnes det ny schedulering opp på en ny tråd? Shutdown?
                requireNotNull(
                    altinnTransitService.findNewestEvent()
                )
            publisher.publishEvent(
                AltinnProxyStateMachineEvent.WaitForConnection(
                    lastEventId
                )
            )
        }
    }

    override fun setApplicationEventPublisher(applicationEventPublisher: ApplicationEventPublisher) {
        this.publisher = applicationEventPublisher
    }
}
