package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import kotlinx.coroutines.test.runTest
import no.kartverket.altinn3.events.server.Helpers.createCloudEvent
import no.kartverket.altinn3.events.server.Helpers.createEventWithFileOverview
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.mappers.toAltinnEventEntity
import no.kartverket.altinn3.events.server.service.AltinnService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudEventHandlerTest {
    private val altinnService = mockk<AltinnService>(relaxed = true)
    private val config = mockk<AltinnServerConfig>(relaxed = true) {
        every { recipientId } returns "123456789"
    }
    private val cloudEventHandler = CloudEventHandler(config, altinnService)

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `tryHandle - success path calls onSuccess`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()

        coEvery { altinnService.tryPoll(event.cloudEvent.toAltinnEventEntity()) } just Runs

        val onSuccess = mockk<suspend () -> String>()
        val onError = mockk<suspend (Throwable) -> String>()
        coEvery { onSuccess.invoke() } returns "OK"

        val result = cloudEventHandler.tryHandle(event, onSuccess, onError)

        assertEquals("OK", result)
        coVerify(exactly = 1) { onSuccess.invoke() }
        coVerify(exactly = 0) { onError.invoke(any()) }
        coVerify(exactly = 1) { altinnService.tryPoll(any()) }
    }

    @Test
    fun `tryHandle - error path calls onError`() = runTest {
        val event = createCloudEvent(AltinnEventType.UPLOAD_FAILED).createEventWithFileOverview()
        val errorMsg = "Simulated error"
        val ex = IllegalArgumentException(errorMsg)

        val onSuccess = mockk<suspend () -> String>()
        val onError = mockk<suspend (Throwable) -> String>()

        coEvery { onError.invoke(any()) } returns ex.message!!

        val result = cloudEventHandler.tryHandle(event, onSuccess, onError)

        assertEquals(errorMsg, result)

        val slot = slot<Exception>()
        coVerify(exactly = 0) { onSuccess.invoke() }
        coVerify(exactly = 1) {
            onError.invoke(capture(slot))
        }
        coVerify(exactly = 0) { altinnService.tryPoll() }
        assertTrue(slot.captured is IllegalArgumentException)
    }

    @Test
    fun `handle - published event calls tryPoll`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileOverview()

        coEvery { altinnService.tryPoll(event.cloudEvent.toAltinnEventEntity()) } just Runs

        cloudEventHandler.handle(event)

        coVerify(exactly = 1) { altinnService.tryPoll(any()) }
    }

    @Test
    fun `handle - an ignored event type throws`() = runTest {
        val event = createCloudEvent(AltinnEventType.VALIDATE_SUBSCRIPTION).createEventWithFileOverview()

        var thrown: Throwable? = null
        try {
            cloudEventHandler.handle(event)
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(thrown is IllegalArgumentException)
        assertTrue(requireNotNull(thrown).message!!.contains("Ignored event"))
        coVerify(exactly = 0) { altinnService.tryPoll() }
    }

    @Test
    fun `handlePublished - when fileTransferStatus != Published, it should return early`() = runTest {
        val event = createCloudEvent(AltinnEventType.ALL_CONFIRMED).createEventWithFileOverview()

        cloudEventHandler.handle(event)

        coVerify(exactly = 0) { altinnService.tryPoll() }
    }
}
