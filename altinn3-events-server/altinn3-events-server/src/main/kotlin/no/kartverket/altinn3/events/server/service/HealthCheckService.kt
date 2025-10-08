package no.kartverket.altinn3.events.server.service

import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.models.SideEffect
import no.kartverket.altinn3.events.server.routes.HEALTH_CHECK_URL
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationEventPublisherAware
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatusCode
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.client.RestClient

class HealthCheckService(
    private val stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>,
    private val altinnTransitService: AltinnTransitService,
    private val restClient: RestClient
) : ApplicationEventPublisherAware {
    lateinit var publisher: ApplicationEventPublisher
    private val logger = LoggerFactory.getLogger(HealthCheckService::class.java)

    @Profile("!poll")
    @Scheduled(fixedRateString = "#{healthCheckProperties.interval}")
    fun checkHealth() {
        try {
            val response =
                restClient.get().uri(HEALTH_CHECK_URL).retrieve()
                    .onStatus(HttpStatusCode::isError) { _, res ->
                        publishServiceUnavailableEvent("Error checking health check ${res.statusCode} ${res.body}")
                    }
                    .toBodilessEntity()

            if (stateMachine.state == State.Poll && response.statusCode.is2xxSuccessful) {
                publisher.publishEvent(AltinnProxyStateMachineEvent.ServiceAvailable())
            }
        } catch (ex: Exception) {
            publishServiceUnavailableEvent("Exception checking health check ${ex.printStackTrace()}")
        }
    }

    private fun publishServiceUnavailableEvent(errorMessage: String) {
        logger.error(errorMessage)
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
