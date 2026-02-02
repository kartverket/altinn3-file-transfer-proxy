package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import kotlinx.coroutines.test.runTest
import no.kartverket.altinn3.broker.infrastructure.Serializer
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.service.AltinnService
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.models.*
import no.kartverket.altinn3.persistence.AltinnEvent
import no.kartverket.altinn3.persistence.AltinnFil
import no.kartverket.altinn3.persistence.AltinnFilOverview
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.springframework.retry.support.RetryTemplate
import java.util.*
import kotlin.test.Test

class AltinnServiceTest {

    private val brokerClient = mockk<BrokerClient>(relaxed = true)
    private val config = mockk<AltinnServerConfig>(relaxed = true)
    private val altinnTransitService = mockk<AltinnTransitService>(relaxed = true)
    private val retryTemplate = RetryTemplate.builder().maxAttempts(1).build()
    private val altinnService = AltinnService(
        brokerClient,
        config,
        altinnTransitService,
        retryTemplate,
    )

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        every { config.resourceId } returns "resource"
    }

    @AfterEach
    fun tearDown() {
        altinnService.shutdown()
        unmockkAll()
    }

    @Test
    fun `sendResponseTilInnsender initializes transfer and uploads file`() {
        every { config.recipientId } returns "recipientId"
        val fileOverview = mockk<AltinnFilOverview>(relaxed = true) {
            every { sender } returns "1234:5678"
            every { fileName } returns "test"
            every { fileTransferId } returns UUID.randomUUID()
            every { jsonPropertyList } returns null
        }
        val altinnFil = mockk<AltinnFil>(relaxed = true) {
            every { payload } returns "dummy content".toByteArray()
        }
        val fileTransferId = UUID.randomUUID()

        every { brokerClient.initializeFileTransfer(any()) } returns FileTransferInitializeResponseExt(fileTransferId = fileTransferId)
        every {
            brokerClient.uploadFileToAltinn(
                fileTransferId,
                altinnFil.payload
            )
        } returns FileTransferUploadResponseExt(fileTransferId)

        val result = altinnService.sendResponseTilInnsender(fileOverview, altinnFil)

        Assertions.assertEquals(fileTransferId, result)
        verify { brokerClient.initializeFileTransfer(any()) }
        verify { brokerClient.uploadFileToAltinn(fileTransferId, any()) }
    }

    @Test
    fun `tryPoll does nothing when there are no file transfers`() = runTest {
        val altinnEvent = AltinnEvent(altinnId = UUID.randomUUID())

        every {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        } returns emptyList()

        altinnService.tryPoll(altinnEvent)

        verify(exactly = 1) {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        }
        verify(exactly = 0) { brokerClient.getFileOverview(any()) }
        verify(exactly = 0) { altinnTransitService.prepareForFileTransfer(any(), altinnEvent) }
        verify(exactly = 0) { altinnTransitService.startTransfer(any(), any(), any()) }
    }

    @Test
    fun `tryPoll processes a single file transfer and confirms download`() = runTest {
        val transferId = UUID.randomUUID()
        val altinnEvent = AltinnEvent(altinnId = UUID.randomUUID())
        val fileOverview = mockk<FileOverview>(relaxed = true) {
            every { fileTransferId } returns transferId
            every { created } returns java.time.OffsetDateTime.now()
        }

        every {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        } returns listOf(transferId)

        every { brokerClient.getFileOverview(transferId) } returns fileOverview
        every { altinnTransitService.prepareForFileTransfer(fileOverview, any()) } returns transferId

        val payload = "payload".toByteArray()
        every { brokerClient.downloadFileBytes(transferId) } returns payload

        every { altinnTransitService.startTransfer(fileOverview, payload, any()) } answers {
            thirdArg<() -> Unit>().invoke()
        }

        every { brokerClient.confirmDownload(transferId) } returns Unit

        altinnService.tryPoll(altinnEvent)

        verify(exactly = 1) {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        }
        verify(exactly = 1) { brokerClient.getFileOverview(transferId) }
        verify(exactly = 1) { altinnTransitService.prepareForFileTransfer(fileOverview, altinnEvent) }
        verify(exactly = 1) { brokerClient.downloadFileBytes(transferId) }
        verify(exactly = 1) { altinnTransitService.startTransfer(fileOverview, payload, any()) }
        verify(exactly = 1) { brokerClient.confirmDownload(transferId) }
    }

    @Test
    fun `scheduledTryPoll triggers polling`() {
        every {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        } returns emptyList()

        altinnService.scheduledTryPoll()

        verify(timeout = 1_000, exactly = 1) {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        }

        verify(exactly = 0) { brokerClient.getFileOverview(any()) }
        verify(exactly = 0) { brokerClient.downloadFileBytes(any()) }
        verify(exactly = 0) { brokerClient.confirmDownload(any()) }
    }



    @Test
    fun `scheduledTryPoll processes a single file transfer and confirms download`() {
        val transferId = UUID.randomUUID()
        val fileOverview = mockk<FileOverview>(relaxed = true) {
            every { fileTransferId } returns transferId
            every { created } returns java.time.OffsetDateTime.now()
        }

        every {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        } returns listOf(transferId)

        every { brokerClient.getFileOverview(transferId) } returns fileOverview
        every { altinnTransitService.prepareForFileTransfer(fileOverview, any()) } returns transferId

        val payload = "payload".toByteArray()
        every { brokerClient.downloadFileBytes(transferId) } returns payload

        every { altinnTransitService.startTransfer(fileOverview, payload, any()) } answers {
            thirdArg<() -> Unit>().invoke()
        }

        every { brokerClient.confirmDownload(transferId) } returns Unit

        altinnService.scheduledTryPoll()

        verify(timeout = 1_000, exactly = 1) {
            brokerClient.getFileTransfers(
                resourceId = "resource",
                status = FileTransferStatusNullable.Published,
                role = Role.Recipient,
                orderAscending = true,
                from = any()
            )
        }
        verify(timeout = 1_000, exactly = 1) { brokerClient.getFileOverview(transferId) }
        verify(timeout = 1_000, exactly = 1) { altinnTransitService.prepareForFileTransfer(fileOverview, null) }
        verify(timeout = 1_000, exactly = 1) { brokerClient.downloadFileBytes(transferId) }
        verify(timeout = 1_000, exactly = 1) { altinnTransitService.startTransfer(fileOverview, payload, any()) }
        verify(timeout = 1_000, exactly = 1) { brokerClient.confirmDownload(transferId) }
    }

    @Test
    fun `getFromTime should format the date correctly`() {
        val _config = AltinnServerConfig(
            webhookSubscriptionDelay = "20s",
            pollTransitInterval = "15s",
            pollTransitEnabled = true,
            recipientId = "",
            resourceId = "",
            pollAltinnInterval = "15s",
            api = null,
            webhookExternalUrl = null,
            webhooks = emptyList(),
            startEvent = null,
            serviceownerOrgnumber = null,
            skipPollAndWebhook = false,
            pollLookback = 14
        )

        val fromTime = AltinnService.FromUtil.getFromTime(_config.pollLookbackDays)
        val expectedTime = java.time.OffsetDateTime.now().minusDays(14L)

        // Allow a small delta for execution time
        val delta = java.time.Duration.ofSeconds(1)

        val fromTimeString: String = Serializer.jacksonObjectMapper.writeValueAsString(fromTime).replace("\"", "")
        // assert format of fromTimeString to be given as yyyy-MM-ddTHH:mm:ss
        Assertions.assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d*)?Z").matches(fromTimeString),
            "fromTimeString should match the expected format"
        )

        Assertions.assertTrue(
            fromTime.isAfter(expectedTime.minus(delta)) && fromTime.isBefore(expectedTime.plus(delta)),
            "fromTime should be within the expected range"
        )
    }

    @Test
    fun `pollLookbackDays should never return under 5`() {
        val _config = AltinnServerConfig(
            webhookSubscriptionDelay = "20s",
            pollTransitInterval = "15s",
            pollTransitEnabled = true,
            recipientId = "",
            resourceId = "",
            pollAltinnInterval = "15s",
            api = null,
            webhookExternalUrl = null,
            webhooks = emptyList(),
            startEvent = null,
            serviceownerOrgnumber = null,
            skipPollAndWebhook = false,
            pollLookback = 0
        )

        Assertions.assertEquals(5, _config.pollLookbackDays)
    }
}