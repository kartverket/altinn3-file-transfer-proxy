package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.configuration.AltinnHealthCheckProperties
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.models.SideEffect
import no.kartverket.altinn3.events.server.service.AltinnHealthCheckService
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.events.server.service.StateMachine
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.*
import kotlin.test.Test

class AltinnHealthCheckServiceTest {

    private lateinit var brokerClient: BrokerClient
    private lateinit var applicationEventPublisher: ApplicationEventPublisher
    private lateinit var altinnTransitService: AltinnTransitService
    private lateinit var altinnHealthCheck: AltinnHealthCheckService

    private lateinit var altinnHealthCheckProperties: AltinnHealthCheckProperties
    private lateinit var altinnServerConfig: AltinnServerConfig
    private lateinit var stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>

    @BeforeEach
    fun setUp() {
        brokerClient = mockk()
        applicationEventPublisher = mockk(relaxed = true)
        every { applicationEventPublisher.publishEvent(any()) } just Runs
        altinnTransitService = mockk()
        every { altinnTransitService.findNewestEvent() } returns "12345"

        altinnHealthCheckProperties = mockk()
        altinnServerConfig = mockk(relaxed = true)
        stateMachine = mockk(relaxed = true)

        altinnHealthCheck = AltinnHealthCheckService(
            altinnServerConfig,
            stateMachine,
            altinnTransitService,
            brokerClient
        )
        altinnHealthCheck.setApplicationEventPublisher(applicationEventPublisher)
    }

    @Test
    fun `AltinnCheckHealth should publish ServiceAvailable event when health check is successful`() {
        every { stateMachine.state } returns State.Poll

        val responseEntity = ResponseEntity<List<UUID>>(HttpStatus.OK)
        every { brokerClient.healthCheckViaFileTransfer(any<String>()) } returns responseEntity

        altinnHealthCheck.checkAltinnHealth()

        verify {
            altinnHealthCheck.publisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.ServiceAvailable::class)
            )
        }
    }

    @Test
    fun `AltinnCheckHealth should publish WaitForConnection event when health check is HttpError`() {
        every { stateMachine.state } returns State.PollAndWebhook

        val responseEntity = ResponseEntity<List<UUID>>(HttpStatus.BAD_REQUEST)
        every { brokerClient.healthCheckViaFileTransfer(any<String>()) } returns responseEntity

        altinnHealthCheck.checkAltinnHealth()

        verify {
            altinnHealthCheck.publisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.WaitForConnection::class)
            )
        }
    }

    @Test
    fun `checkHealth should publish WaitForConnection event when an exception occurs`() {
        every { brokerClient.healthCheckViaFileTransfer(any<String>()) } throws Exception()

        altinnHealthCheck.checkAltinnHealth()

        verify {
            applicationEventPublisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.WaitForConnection::class)
            )
        }
    }
}
