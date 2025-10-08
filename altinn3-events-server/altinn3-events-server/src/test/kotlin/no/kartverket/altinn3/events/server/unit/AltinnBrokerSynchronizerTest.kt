package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import kotlinx.coroutines.test.runTest
import no.kartverket.altinn3.events.server.Helpers.createCloudEvent
import no.kartverket.altinn3.events.server.Helpers.createEventWithFileOverview
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.exceptions.HandlePollEventFailedException
import no.kartverket.altinn3.events.server.exceptions.HandleSyncEventFailedException
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.events.server.service.AltinnBrokerSynchronizer
import no.kartverket.altinn3.events.server.service.EventLoader
import no.kartverket.altinn3.persistence.AltinnFailedEvent
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.util.*

class AltinnBrokerSynchronizerTest {
    private val eventLoader = mockk<EventLoader>()
    private val cloudEventHandler = mockk<CloudEventHandler>()
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val altinnConfig = mockk<AltinnServerConfig>(relaxed = true)
    private val failedEventRepository = mockk<AltinnFailedEventRepository>(relaxed = true)
    private val synchronizer = AltinnBrokerSynchronizer(
        eventLoader,
        cloudEventHandler,
        publisher,
        altinnConfig,
        failedEventRepository,
    )

    @BeforeEach
    fun restoreMocks() {
        coEvery {
            cloudEventHandler.tryHandle(
                any<EventWithFileOverview>(),
                any<suspend () -> Unit>(),
                any<suspend (Throwable) -> Unit>()
            )
        } answers { callOriginal() }
    }

    @Test
    fun `recoverFailedEvents() - calls failedEventsRepo and publishes RecoveryDoneEvent`() = runTest {
        val event1 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()
        val event2 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()
        val failedEvent1 = AltinnFailedEvent(
            UUID.fromString(event1.cloudEvent.id),
            UUID.randomUUID(),
            State.PollAndWebhook::class.simpleName
        )
        val failedEvent2 = AltinnFailedEvent(
            UUID.fromString(event2.cloudEvent.id),
            UUID.randomUUID(),
            State.PollAndWebhook::class.simpleName
        )
        every { failedEventRepository.findAll() } returns listOf(failedEvent1, failedEvent2)
        every { eventLoader.fetchAndMapEventsByResource(any(), any()) } returns listOf(event1, event2)
        coEvery { cloudEventHandler.handle(any()) } just Runs
        synchronizer.recoverFailedEvents()
        val eventSlot = slot<AltinnProxyStateMachineEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(eventSlot)) }
        verify { failedEventRepository.deleteById(any()) }
        verify(exactly = 1) { failedEventRepository.findAll() }
        coVerify(exactly = 1) { eventLoader.fetchAndMapEventsByResource(any(), any()) }
    }

    @Test
    fun `sync() - calls fetchAndMapEventsByResource and publishes AltinnSyncFinishedEvent`() = runTest {
        val event1 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()
        val event2 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()

        every { eventLoader.fetchAndMapEventsByResource(any(), any()) } returns listOf(event1, event2)

        coEvery { cloudEventHandler.handle(any()) } just runs
        synchronizer.sync()

        coVerify(exactly = 1) { cloudEventHandler.tryHandle<Unit>(event1, any(), any()) }
        coVerify(exactly = 1) { cloudEventHandler.tryHandle<Unit>(event2, any(), any()) }

        val eventSlot = slot<AltinnProxyStateMachineEvent.SyncSucceeded>()
        verify { publisher.publishEvent(capture(eventSlot)) }
        assertEquals(event2.cloudEvent.id, eventSlot.captured.lastSyncedEvent)
    }

    @Test
    fun `poll() - runs until end event is reached and publishes PollingStartedEvent and PollingReachedEndEvent`() =
        runTest {
            val cloudEvent1 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()
            val cloudEvent2 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()

            every { eventLoader.fetchAndMapEventsByResource(any(), any()) } returns listOf(
                cloudEvent1,
                cloudEvent2
            )
            every { altinnConfig.pollAltinnInterval } returns "1s"

            coEvery {
                cloudEventHandler.handle(any())
            } returns Unit

            synchronizer.eventRecievedInWebhooksCreatedAt = cloudEvent2.cloudEvent.time
            synchronizer.poll()

            coVerify(exactly = 1) { cloudEventHandler.tryHandle<Unit>(cloudEvent1, any(), any()) }
            coVerify(exactly = 0) { cloudEventHandler.tryHandle<Unit>(cloudEvent2, any(), any()) }

            val pollingEndEvent = slot<AltinnProxyStateMachineEvent.PollingSucceeded>()

            verify {
                publisher.publishEvent(capture(pollingEndEvent))
            }
        }

    @Test
    fun `sync() - onError triggers HandleSyncEventFailedException with correct eventId`() = runTest {
        val event: EventWithFileOverview =
            createCloudEvent(AltinnEventType.PUBLISHED).copy(id = "someEventId").createEventWithFileOverview()

        every { eventLoader.fetchAndMapEventsByResource(any(), any()) } returns listOf(event)

        coEvery {
            cloudEventHandler.tryHandle(
                any<EventWithFileOverview>(),
                any<suspend () -> Unit>(),
                any<suspend (Throwable) -> Unit>()
            )
        } coAnswers {
            val onErrorLambda = thirdArg<suspend (Throwable) -> Unit>()
            onErrorLambda.invoke(RuntimeException("Simulated sync failure"))
        }

        assertThrows<HandleSyncEventFailedException> {
            synchronizer.sync("startEventId123")
        }.also { ex ->
            assertEquals("startEventId123", ex.lastSuccessfulEventId)
        }
    }

    @Test
    fun `poll() - onError triggers HandlePollEventFailedException with correct eventId`() = runTest {
        val event: EventWithFileOverview =
            createCloudEvent(AltinnEventType.PUBLISHED).copy(id = "pollEventId").createEventWithFileOverview()

        every { eventLoader.fetchAndMapEventsByResource(any(), any()) } returns listOf(event)
        every { altinnConfig.pollAltinnInterval } returns "1s"

        coEvery {
            cloudEventHandler.tryHandle(
                any<EventWithFileOverview>(),
                any<suspend () -> Unit>(),
                any<suspend (Throwable) -> Unit>()
            )
        } coAnswers {
            val onErrorLambda = thirdArg<suspend (Exception) -> Unit>()
            onErrorLambda(RuntimeException("Simulated poll failure"))
        }

        assertThrows<HandlePollEventFailedException> {
            synchronizer.poll("myPollStartId")
        }.also { ex ->
            assertEquals("myPollStartId", ex.pollFromEventId)
        }
    }
}
