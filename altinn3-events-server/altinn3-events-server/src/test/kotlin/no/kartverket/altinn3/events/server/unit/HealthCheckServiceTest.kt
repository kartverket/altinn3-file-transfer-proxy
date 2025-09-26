package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.configuration.HealthCheckProperties
import no.kartverket.altinn3.events.server.configuration.SideEffect
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.events.server.service.HealthCheckService
import no.kartverket.altinn3.events.server.service.StateMachine
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import kotlin.test.Test

class HealthCheckServiceTest {

    private lateinit var restClient: RestClient
    private lateinit var applicationEventPublisher: ApplicationEventPublisher
    private lateinit var altinnTransitService: AltinnTransitService
    private lateinit var healthCheck: HealthCheckService

    private lateinit var properties: HealthCheckProperties
    private lateinit var altinnServerConfig: AltinnServerConfig
    private lateinit var stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>

    @BeforeEach
    fun setUp() {
        restClient = mockk()
        applicationEventPublisher = mockk(relaxed = true)
        every { applicationEventPublisher.publishEvent(any()) } just Runs
        altinnTransitService = mockk()
        every { altinnTransitService.findNewestEvent() } returns "12345"

        properties = mockk()
        altinnServerConfig = mockk()
        every { altinnServerConfig.webhookExternalUrl } returns "http://mocked-url.com"
        stateMachine = mockk(relaxed = true)

        healthCheck = HealthCheckService(
            stateMachine,
            altinnTransitService,
            restClient
        )
        healthCheck.setApplicationEventPublisher(applicationEventPublisher)
    }

    @Test
    fun `checkHealth should publish ServiceAvailable event when health check is successful`() {
        every { stateMachine.state } returns State.Poll

        val res = mockk<RestClient.ResponseSpec>(relaxed = true)
        every { restClient.get().uri(any<String>()).retrieve() } returns res
        every { res.onStatus(any(), any()) } returns res

        val responseEntity = ResponseEntity<Void>(HttpStatus.OK)
        every { res.toBodilessEntity() } returns responseEntity

        healthCheck.checkHealth()

        verify {
            healthCheck.publisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.ServiceAvailable::class)
            )
        }
    }

    @Test
    fun `checkHealth should publish WaitForConnection event when an exception occurs`() {
        val res = mockk<RestClient.ResponseSpec>(relaxed = true)
        every { restClient.get().uri(any<String>()).retrieve() } returns res
        every { res.onStatus(any(), any()) } answers {
            // Simulate that the status matches and the error handler is called
            val errorHandler = secondArg<RestClient.ResponseSpec.ErrorHandler>()
            errorHandler.handle(mockk(relaxed = true), mockk(relaxed = true))
            res
        }

        val responseEntity =
            ResponseEntity<Void>(HttpStatus.NOT_FOUND)
        every { res.toBodilessEntity() } returns responseEntity

        val lastEventId = "event-id"
        every { altinnTransitService.findNewestEvent() } returns lastEventId

        healthCheck.checkHealth()

        verify {
            applicationEventPublisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.WaitForConnection::class)
            )
        }
    }
}
