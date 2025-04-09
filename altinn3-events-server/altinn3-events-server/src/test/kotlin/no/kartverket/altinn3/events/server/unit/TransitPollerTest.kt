package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.service.AltinnService
import no.kartverket.altinn3.events.server.service.AltinnTransitService
import no.kartverket.altinn3.events.server.service.TransitPoller
import no.kartverket.altinn3.persistence.AltinnFil
import no.kartverket.altinn3.persistence.AltinnFilOverview
import org.junit.jupiter.api.AfterEach
import org.springframework.boot.context.event.ApplicationReadyEvent
import java.util.*
import kotlin.test.Test
import kotlin.time.Duration

class TransitPollerTest {
    val config = AltinnServerConfig(
        pollTransitEnabled = true,
        pollTransitInterval = "PT0.5S",
        startEvent = null,
        senderId = "test",
        resourceId = "test",
    )

    @AfterEach
    fun tearDown() {
        unmockkAll()
        config.pollTransitEnabled = true
    }

    @Test
    fun `poll processes files when files exist`() = runTest(timeout = Duration.parse("5s")) {
        val altinnTransitService = mockk<AltinnTransitService>()
        val handler = mockk<AltinnService>(relaxed = true)

        val fileOverview = mockk<AltinnFilOverview>(relaxed = true) {
            every { sender } returns "1234:5678"
            every { fileName } returns "testFile.txt"
            every { fileTransferId } returns UUID.randomUUID()
        }
        val file = mockk<AltinnFil>(relaxed = true) {
            every { payload } returns "dummy content".toByteArray()
        }
        val filePair = mapOf(fileOverview to file)

        coEvery { altinnTransitService.findNewUtgaendeFiler() } coAnswers {
            config.pollTransitEnabled = false
            filePair
        }
        val randomUuid = UUID.randomUUID()
        coEvery { handler.sendResponseTilInnsender(any(), any()) } returns randomUuid
        coEvery { altinnTransitService.completeFileTransfer(any()) } returns Unit

        TransitPoller(config, altinnTransitService, handler).poll()

        coVerify(exactly = 1) { handler.sendResponseTilInnsender(fileOverview, file) }
        coVerify(exactly = 1) { altinnTransitService.completeFileTransfer(any()) }
    }

    @Test
    fun `poll does nothing when no files found`() = runTest(timeout = Duration.parse("5s")) {
        val altinnTransitService = mockk<AltinnTransitService>(relaxed = true)
        val handler = mockk<AltinnService>(relaxed = true)

        coEvery { altinnTransitService.findNewUtgaendeFiler() } coAnswers {
            config.pollTransitEnabled = false
            emptyMap()
        }

        TransitPoller(config, altinnTransitService, handler).poll()

        coVerify(exactly = 0) { handler.sendResponseTilInnsender(any(), any()) }
        coVerify(exactly = 0) { altinnTransitService.completeFileTransfer(any()) }
    }

    @Test
    fun `onApplicationEvent starts polling when enabled`() = runTest(timeout = Duration.parse("5s")) {
        val altinnTransitService = mockk<AltinnTransitService>()
        val handler = mockk<AltinnService>(relaxed = true)

        // Prepare a dummy file so that poll() does something.
        val fileOverview = mockk<AltinnFilOverview>(relaxed = true) {
            every { sender } returns "1234:532432"
            every { fileTransferId } returns UUID.randomUUID()
            every { fileName } returns "testFile.txt"
        }
        val file = mockk<AltinnFil>(relaxed = true) {
            every { payload } returns "dummy content".toByteArray()
        }

        coEvery { altinnTransitService.findNewUtgaendeFiler() } coAnswers {
            config.pollTransitEnabled = false
            mapOf(fileOverview to file)
        }
        coEvery { altinnTransitService.completeFileTransfer(any()) } returns Unit

        val poller = spyk(TransitPoller(config, altinnTransitService, handler))
        val event = mockk<ApplicationReadyEvent>(relaxed = true)
        poller.onApplicationEvent(event)
        delay(200)
        coVerify(exactly = 1) { poller.poll() }
    }
}
