package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import kotlinx.coroutines.test.runTest
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.Helpers.createCloudEvent
import no.kartverket.altinn3.events.server.Helpers.createFileOverviewFromEvent
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.models.FileStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.retry.support.RetryTemplate
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.*

class CloudEventHandlerTest {
    private val broker = mockk<BrokerClient>(relaxed = true)
    private val transactionTemplate = mockk<TransactionTemplate>(relaxed = true)
    private val retryTemplate = RetryTemplate.builder().maxAttempts(1).build()
    private val altinnTransitService = mockk<AltinnTransitService>(relaxed = true)
    private val cloudEventHandler = CloudEventHandler(broker, altinnTransitService, retryTemplate)

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @BeforeEach
    fun setup() {
        every { transactionTemplate.execute(any<TransactionCallback<Any?>>()) } answers {
            val callback = firstArg<TransactionCallback<Any?>>()
            val dummyStatus = mockk<TransactionStatus>(relaxed = true)
            callback.doInTransaction(dummyStatus)
        }
    }

    @Test
    fun `tryHandle - success path calls onSuccess`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED)

        every {
            broker.getFileOverview(UUID.fromString(event.resourceinstance))
        } returns createFileOverviewFromEvent(
            event,
            FileStatus.Published
        )
        every { broker.downloadFileBytes(any()) } returns "<OK />".encodeToByteArray()
        every {
            broker.confirmDownload(any())
        } just Awaits
        val onSuccess = mockk<suspend () -> String>()
        val onError = mockk<suspend (Throwable) -> String>()
        coEvery { onSuccess.invoke() } returns "OK"

        val result = cloudEventHandler.tryHandle(event, onSuccess, onError)

        assertEquals("OK", result)
        coVerify(exactly = 1) { onSuccess.invoke() }
        coVerify(exactly = 0) { onError.invoke(any()) }
    }

    @Test
    fun `tryHandle - error path calls onError`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED)
        val errorMsg = "Simulated error"
        val ex = IllegalArgumentException(errorMsg)

        every { broker.getFileOverview(any()) } throws ex

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
        assertTrue(slot.captured is IllegalArgumentException)
        assertEquals(errorMsg, slot.captured.message)
    }

    @Test
    fun `handle - published event calls handlePublished`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED)

        val spyHandler = spyk(cloudEventHandler, recordPrivateCalls = true)

        spyHandler.handle(event)

        coVerify(exactly = 1) {
            spyHandler["handlePublished"](eq(event)) // using the private function name
        }
    }


    @Test
    fun `handle - an ignored event type throws`() = runTest {
        val event = createCloudEvent(AltinnEventType.VALIDATE_SUBSCRIPTION)

        assertThrows<IllegalArgumentException> {
            cloudEventHandler.handle(event)
        }.run {
            requireNotNull(this.message)
        }.also {
            assertTrue(it.contains("Ignored event"))
        }
    }

    @Test
    fun `handlePublished - when fileTransferStatus != Published, it should return early`() = runTest {
        val event = createCloudEvent(AltinnEventType.ALL_CONFIRMED)

        coEvery { broker.getFileOverview(any()) } returns createFileOverviewFromEvent(
            event,
            FileStatus.AllConfirmedDownloaded
        )

        cloudEventHandler.handle(event)

        verify(exactly = 0) { transactionTemplate.execute(any()) }
    }

    @Test
    fun `handlePublished - when fileTransferStatus is Published it downloads and confirms download`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED)
        val fileOverview = createFileOverviewFromEvent(event = event, status = FileStatus.Published)

        every {
            altinnTransitService.startTransfer(any(), any(), any(), captureLambda<() -> Unit>())
        } answers {
            lambda<() -> Unit>().invoke()
        }

        every { broker.getFileOverview(any()) } returns fileOverview
        every { broker.downloadFileBytes(any()) } returns "<OK />".encodeToByteArray()
        every { broker.confirmDownload(any()) } just Runs

        cloudEventHandler.handle(event)

        verify(exactly = 1) { altinnTransitService.startTransfer(any(), any(), any(), any()) }
        verify(exactly = 1) { broker.confirmDownload(any()) }
    }

    @Test
    fun `initializeFileTransfer - when event is already confirmed it returns early`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED)
        val fileOverview = createFileOverviewFromEvent(event = event, status = FileStatus.AllConfirmedDownloaded)
        every { broker.getFileOverview(any()) } returns fileOverview

        cloudEventHandler.handle(event)

        verify(exactly = 0) { altinnTransitService.startTransfer(any(), any(), any(), any()) }
    }
}
