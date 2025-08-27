package no.kartverket.altinn3.events.server.service

import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.configuration.HealthCheckProperties
import no.kartverket.altinn3.events.server.configuration.SideEffect
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.routes.HEALTH_CHECK_URL
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationEventPublisherAware
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatusCode
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.client.RestClient

class HealthCheckService(
    private val properties: HealthCheckProperties,
    private val altinnServerConfig: AltinnServerConfig,
    private val stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>,
    private val altinnTransitService: AltinnTransitService,
    private val restClient: RestClient
) : ApplicationEventPublisherAware {
    lateinit var publisher: ApplicationEventPublisher
    private val logger = LoggerFactory.getLogger(HealthCheckService::class.java)

    @Profile("!poll")
    @Scheduled(fixedRateString = "#{@healthCheckProperties.interval}")
    fun checkHealth() {
        try {
            logger.debug("Checking health of altinn3-proxy")
            val response =
                restClient.get().uri(HEALTH_CHECK_URL).retrieve()
                    .onStatus(HttpStatusCode::isError) { _, res ->
                        logger.error("Error checking health check ${res.statusCode} ${res.body}")
                        publishServiceUnavailableEvent()
                    }
                    .toBodilessEntity()

            if (stateMachine.state == State.Poll && response.statusCode.is2xxSuccessful) {
                publisher.publishEvent(AltinnProxyStateMachineEvent.ServiceAvailable())
            }
        } catch (ex: Exception) {
            logger.error("Exception checking health check ${ex.printStackTrace()}")
            publishServiceUnavailableEvent()
        }
    }

    private fun publishServiceUnavailableEvent() {
        if (stateMachine.state != State.Poll) {
            val lastEventId =
                // TODO: Hva skjer hvis denne får null, og spinnes det ny schedulering opp på en ny tråd? Bør man shutdown?
                requireNotNull(
                    altinnTransitService.findNewestEvent()
                )
            publisher.publishEvent(
                AltinnProxyStateMachineEvent.ServiceUnavailable(
                    lastEventId
                )
            )
        }
    }

    override fun setApplicationEventPublisher(applicationEventPublisher: ApplicationEventPublisher) {
        this.publisher = applicationEventPublisher
    }
}
