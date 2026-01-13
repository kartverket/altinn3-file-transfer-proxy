package no.kartverket.altinn3.events.server.service

import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.models.SideEffect
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationEventPublisherAware
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.client.RestClient

class HealthCheckService(
    private val stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>,
    private val altinnTransitService: AltinnTransitService,
    private val restClient: RestClient
) : ApplicationEventPublisherAware {
    lateinit var publisher: ApplicationEventPublisher
    private val logger = LoggerFactory.getLogger(HealthCheckService::class.java)
    private var consecutiveUpCount: Int = 0

    companion object {
        private const val STATUS_UP = "UP"
        private const val STATUS_DOWN = "DOWN"
        private const val REQUIRED_CONSECUTIVE_UPS = 3
    }

    @Profile("!poll")
    @Scheduled(fixedRateString = "#{healthCheckProperties.interval}")
    fun checkHealth() {
        val status = try {
            restClient.get()
                .retrieve()
                .body(DigibokHealthResponse::class.java)
                ?.altinn3Proxy
                ?.status
        } catch (e: Exception) {
            logger.error("Exception while calling digibok health endpoint: {}", e.message, e)
            consecutiveUpCount = 0
            return
        }

        when (status) {
            STATUS_DOWN -> handleDown()
            STATUS_UP -> handleUp()
            else -> {
                logger.warn("Unknown or missing digibok health status: {}", status)
                consecutiveUpCount = 0
            }
        }
    }

    private fun handleDown() {
        consecutiveUpCount = 0
        logger.error("Digibok health reports DOWN, switching to polling-state if not already polling")

        if (stateMachine.state != State.Poll) {
            publisher.publishEvent(AltinnProxyStateMachineEvent.WaitForConnection(getLastEventId()))
        }
    }

    private fun handleUp() {
        if (stateMachine.state != State.Poll) {
            consecutiveUpCount = 0
            return
        }

        consecutiveUpCount++
        if (consecutiveUpCount >= REQUIRED_CONSECUTIVE_UPS) {
            logger.info(
                "Digibok health reported {} consecutive UP statuses, switching to setupwebhook-state",
                consecutiveUpCount
            )
            consecutiveUpCount = 0
            publisher.publishEvent(AltinnProxyStateMachineEvent.ServiceAvailable(getLastEventId()))
        }
    }

    private fun getLastEventId(): String {
        return requireNotNull(altinnTransitService.findNewestEvent())
    }

    override fun setApplicationEventPublisher(applicationEventPublisher: ApplicationEventPublisher) {
        this.publisher = applicationEventPublisher
    }
}

data class DigibokHealthResponse(
    val altinn3Proxy: Altinn3ProxyStatus
)

data class Altinn3ProxyStatus(
    val status: String
)