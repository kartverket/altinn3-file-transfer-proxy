package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import kotlinx.coroutines.test.runTest
import no.kartverket.altinn3.broker.apis.downloadFileBytes
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.Helpers.createCloudEvent
import no.kartverket.altinn3.events.server.Helpers.createFileOverviewFromEvent
import no.kartverket.altinn3.events.server.Helpers.createFileStatusDetailsFromEvent
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.models.FileStatus
import no.kartverket.altinn3.models.FileStatusEvent
import no.kartverket.altinn3.persistence.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatusCode
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.*

class CloudEventHandlerTest {
    private val broker = mockk<BrokerClient>(relaxed = true)
    private val altinnEventRepo = mockk<AltinnEventRepository>(relaxed = true)
    private val altinnFilRepo = mockk<AltinnFilRepository>(relaxed = true)
    private val altinnFilOverViewRepo = mockk<AltinnFilOverviewRepository>(relaxed = true)
    private val altinnServerConfig = mockk<AltinnServerConfig>(relaxed = true)
    private val transactionTemplate = mockk<TransactionTemplate>(relaxed = true)
    private val altinnTransitService =
        AltinnTransitService(
            altinnFilOverViewRepo,
            altinnEventRepo,
            altinnFilRepo,
            altinnServerConfig,
            transactionTemplate
        )
    private val cloudEventHandler = CloudEventHandler(broker, altinnTransitService)

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
        every {
            altinnServerConfig.saveToDb
        } returns true
        every {
            altinnEventRepo.save(any())
        } answers {
            firstArg<AltinnEvent>().copy(id = UUID.randomUUID())
        }
        every { altinnEventRepo.existsByAltinnId(any()) } returns false
        every {
            altinnFilOverViewRepo.save(any(AltinnFilOverview::class))
        } answers {
            firstArg<AltinnFilOverview>().copy(
                id = UUID.randomUUID(),
            )
        }

        every {
            altinnFilRepo.save(any(AltinnFil::class))
        } answers {
            firstArg<AltinnFil>().copy(
                id = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `tryHandle - success path calls onSuccess`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED)

        coEvery {
            broker.file.brokerApiV1FiletransferFileTransferIdGet(UUID.fromString(event.resourceinstance))
        } returns createFileOverviewFromEvent(
            event,
            FileStatus.Published
        )
        coEvery { broker.file.downloadFileBytes(any()) } returns "<OK />".encodeToByteArray()
        coEvery {
            broker.file.brokerApiV1FiletransferFileTransferIdConfirmdownloadPostWithHttpInfo(any())
        } returns mockk(relaxed = true) {
            every { statusCode } returns HttpStatusCode.valueOf(200)
        }
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

        coEvery { broker.file.brokerApiV1FiletransferFileTransferIdGet(any()) } throws ex

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
    fun `handle - unknown event type logs and throws`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED).copy(
            type = "unknown",
        )

        assertThrows<IllegalArgumentException> {
            cloudEventHandler.handle(event)
        }.also {
            val message = requireNotNull(it.message)
            assertTrue(message.contains("Unknown event"))
        }
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
    fun `handle - INITIALIZED event calls handleInitialized`() = runTest {
        val event = createCloudEvent(AltinnEventType.INITIALIZED)
        val spyHandler = spyk(cloudEventHandler, recordPrivateCalls = true)
        val fileDetails = createFileStatusDetailsFromEvent(event).copy(
            fileTransferStatusHistory = listOf(FileStatusEvent(FileStatus.Initialized, "SomeText"))
        )
        coEvery { broker.file.brokerApiV1FiletransferFileTransferIdDetailsGet(any()) } returns fileDetails
        spyHandler.handle(event)

        coVerify(exactly = 1) {
            spyHandler["handleInitialized"](eq(event))
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

        coEvery { broker.file.brokerApiV1FiletransferFileTransferIdGet(any()) } returns createFileOverviewFromEvent(
            event,
            FileStatus.AllConfirmedDownloaded
        )

        cloudEventHandler.handle(event)

        verify(exactly = 0) { transactionTemplate.execute(any()) }
    }

    @Test
    fun `handlePublished - when fileTransferStatus is Published it sends the file to tinglysing`() = runTest {
        val event = createCloudEvent(AltinnEventType.PUBLISHED)

        every { broker.file.brokerApiV1FiletransferFileTransferIdGet(any()) } answers {
            createFileOverviewFromEvent(
                event,
                FileStatus.Published
            )
        }
        every { broker.file.downloadFileBytes(any()) } returns "<OK />".encodeToByteArray()

        coEvery {
            broker.file.brokerApiV1FiletransferFileTransferIdConfirmdownloadPostWithHttpInfo(any())
        } returns mockk(relaxed = true) {
            every { statusCode } returns HttpStatusCode.valueOf(200)
        }
        cloudEventHandler.handle(event)

        verify(exactly = 1) { altinnFilRepo.save(any()) }
        verify(exactly = 1) { altinnFilRepo.save(any()) }
    }

    @Test
    fun `handleInitialized - when event is already confirmed it returns early`() = runTest {
        val event = createCloudEvent(AltinnEventType.INITIALIZED)

        val fileDetails = createFileStatusDetailsFromEvent(event).copy(
            fileTransferStatusHistory = listOf(FileStatusEvent(FileStatus.AllConfirmedDownloaded, "SomeText"))
        )
        coEvery { broker.file.brokerApiV1FiletransferFileTransferIdDetailsGet(any()) } returns fileDetails

        cloudEventHandler.handle(event)

        verify(exactly = 0) { altinnEventRepo.save(any()) }
    }

    @Test
    fun `handleInitialized - no fileTransferStatusHistory starts preparations for file transit`() = runTest {
        val event = createCloudEvent(AltinnEventType.INITIALIZED)
        val fileOverview = createFileStatusDetailsFromEvent(event = event, status = FileStatus.Initialized)
        coEvery { broker.file.brokerApiV1FiletransferFileTransferIdDetailsGet(any()) } returns fileOverview

        cloudEventHandler.handle(event)
        verify(exactly = 1) { altinnFilOverViewRepo.save(any()) }
        verify(exactly = 1) { altinnEventRepo.save(any()) }
    }
}
