package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.models.SideEffect
import no.kartverket.altinn3.events.server.service.*
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.client.RestClient
import kotlin.test.Test

class HealthCheckServiceTest {

    private lateinit var restClient: RestClient
    private lateinit var applicationEventPublisher: ApplicationEventPublisher
    private lateinit var altinnTransitService: AltinnTransitService
    private lateinit var healthCheck: HealthCheckService

    private lateinit var stateMachine: StateMachine<State, AltinnProxyStateMachineEvent, SideEffect>

    @BeforeEach
    fun setUp() {
        restClient = mockk()
        applicationEventPublisher = mockk(relaxed = true)
        every { applicationEventPublisher.publishEvent(any()) } just Runs
        altinnTransitService = mockk()
        every { altinnTransitService.findNewestEvent() } returns "12345"

        stateMachine = mockk(relaxed = true)

        healthCheck = HealthCheckService(
            stateMachine,
            altinnTransitService,
            restClient
        )
        healthCheck.setApplicationEventPublisher(applicationEventPublisher)
    }

    @Test
    fun `checkHealth should publish ServiceAvailable event when health check is successful and up-count is 3`() {
        every { stateMachine.state } returns State.Poll

        val res = mockk<RestClient.ResponseSpec>()
        every { restClient.get() } returns mockk {
            every { retrieve() } returns res
        }
        every { res.body(DigibokHealthResponse::class.java) } returns DigibokHealthResponse(
            Altinn3ProxyStatus("UP")
        )

        repeat(3) { healthCheck.checkHealth() }

        verify {
            applicationEventPublisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.ServiceAvailable::class)
            )
        }
    }

    @Test
    fun `checkHealth should publish WaitForConnection event when status is DOWN and state is not Poll`() {
        every { stateMachine.state } returns State.SetupWebhook

        val res = mockk<RestClient.ResponseSpec>()
        every { restClient.get() } returns mockk {
            every { retrieve() } returns res
        }
        every { res.body(DigibokHealthResponse::class.java) } returns DigibokHealthResponse(
            Altinn3ProxyStatus("DOWN")
        )

        val lastEventId = "event-id"
        every { altinnTransitService.findNewestEvent() } returns lastEventId

        healthCheck.checkHealth()

        verify {
            applicationEventPublisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.WaitForConnection::class)
            )
        }
    }

    @Test
    fun `checkHealth should not publish ServiceAvailable before 3 consecutive UP statuses`() {
        every { stateMachine.state } returns State.Poll

        val res = mockk<RestClient.ResponseSpec>()
        every { restClient.get() } returns mockk {
            every { retrieve() } returns res
        }
        every { res.body(DigibokHealthResponse::class.java) } returns DigibokHealthResponse(
            Altinn3ProxyStatus("UP")
        )

        repeat(2) { healthCheck.checkHealth() }

        verify(exactly = 0) {
            applicationEventPublisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.ServiceAvailable::class)
            )
        }
    }

    @Test
    fun `checkHealth should not publish ServiceAvailable when status is UP but state is not Poll`() {
        every { stateMachine.state } returns State.SetupWebhook

        val res = mockk<RestClient.ResponseSpec>()
        every { restClient.get() } returns mockk {
            every { retrieve() } returns res
        }
        every { res.body(DigibokHealthResponse::class.java) } returns DigibokHealthResponse(
            Altinn3ProxyStatus("UP")
        )

        repeat(5) { healthCheck.checkHealth() }

        verify(exactly = 0) {
            applicationEventPublisher.publishEvent(
                ofType(AltinnProxyStateMachineEvent.ServiceAvailable::class)
            )
        }
    }

    @Test
    fun `checkHealth should not publish any events when an exception occurs`() {
        every { stateMachine.state } returns State.Poll
        every { restClient.get() } throws RuntimeException("Failed")

        healthCheck.checkHealth()

        verify(exactly = 0) {
            applicationEventPublisher.publishEvent(any())
        }
    }
}