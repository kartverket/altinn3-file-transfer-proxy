package no.kartverket.altinn3.events.server.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import no.kartverket.altinn3.events.server.ApplicationBeansInitializer
import no.kartverket.altinn3.events.server.Helpers.configuredObjectMapper
import no.kartverket.altinn3.events.server.Helpers.createCloudEvent
import no.kartverket.altinn3.events.server.Helpers.createEventWithFileDetails
import no.kartverket.altinn3.events.server.Helpers.webhooks
import no.kartverket.altinn3.events.server.config.PostgresTestContainersConfiguration
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.configuration.CLOUDEVENTS_JSON
import no.kartverket.altinn3.events.server.configuration.WebhookAvailabilityStatus
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.domain.state.State
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.events.server.service.AltinnBrokerSynchronizer
import no.kartverket.altinn3.events.server.service.EventLoader
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.persistence.AltinnFailedEvent
import no.kartverket.altinn3.persistence.AltinnFailedEventRepository
import no.kartverket.altinn3.persistence.AltinnFilOverviewRepository
import no.kartverket.altinn3.persistence.AltinnFilRepository
import no.kartverket.altinn3.persistence.configuration.TransitRepositoryConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.*
import kotlin.test.Test

const val WIREMOCK_PORT = 9292

// Workaround for å spinne opp wiremocks FØR applikasjonen begynner å sende requests,
// og fortsette å ta imot trafikk når SIGTERM får applikasjonen til å slette subscriptions.
// Mappings ligger i test/resources/mappings
class WireMockInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val wireMockServer = WireMockServer(
            WireMockConfiguration.options()
                .port(WIREMOCK_PORT)
                .templatingEnabled(true)
                .globalTemplating(true)
        )
        wireMockServer.start()
        configureFor(WIREMOCK_PORT)
        applicationContext.beanFactory.registerSingleton("wireMockServer", wireMockServer)
    }
}

@ActiveProfiles("local", "altinn", "test")
@ContextConfiguration(initializers = [WireMockInitializer::class, ApplicationBeansInitializer::class])
@EnableConfigurationProperties(value = [AltinnServerConfig::class])
@Testcontainers
@Import(
    TransitRepositoryConfig::class,
    PostgresTestContainersConfiguration::class
)
@AutoConfigureWebTestClient
@Suppress("LongParameterList")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = [
        "logging.level.com.github.tomakehurst.wiremock=DEBUG",
        "logging.level.no.kartverket=debug",
        "spring.flyway.enabled=true",
        "altinn.retry.max-attempts=1",
        "altinn.persist-cloud-event=true",
        "altinn.persist-altinn-file=true",
        "altinn.recipient-id=123456789",
        "altinn.send-response=true",
        "server.port=8181",
        "altinn.webhook-external-url=http://127.0.0.1:8181",
        "altinn.api.url=http://localhost:9292",
        "altinn.start-event=0",
        "altinn.webhooks[0].path=/test.event",
        "altinn.webhooks[0].handler=webhookHandler",
        "altinn.webhooks[0].resource-filter=urn:altinn:resource:kv_devtest",
        "altinn.webhooks[1].path=/the.event",
        "altinn.webhooks[1].handler=webhookHandler",
        "altinn.webhooks[1].resource-filter=urn:altinn:resource:kv_devtest",
        "altinn.webhooks[2].path=/osv.event",
        "altinn.webhooks[2].handler=webhookHandler",
        "altinn.webhooks[2].resource-filter=urn:altinn:resource:kv_devtest",
    ]
)
class CloudEventIntegrationTest {
    @Autowired
    private lateinit var failedEventRepository: AltinnFailedEventRepository

    @Autowired
    private lateinit var status: WebhookAvailabilityStatus

    @Autowired
    private lateinit var altinnFilOverviewRepository: AltinnFilOverviewRepository

    @Autowired
    private lateinit var altinnFilRepository: AltinnFilRepository

    @Autowired
    private lateinit var webTestClient: WebTestClient
    private val logger = LoggerFactory.getLogger(javaClass)
    private fun CloudEvent.toJson() =
        configuredObjectMapper.writeValueAsString(this)

    @Test
    fun `Should have initial files from syncing with broker`() = runTest {
        waitForAvailableWebhooks()

        val existingEventFetchedFromBroker = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val syncedFile = altinnFilOverviewRepository.findByFileTransferId(existingEventFetchedFromBroker)

        assertThat(syncedFile).isNotNull
        assertThat(syncedFile!!.fileTransferId).isEqualTo(existingEventFetchedFromBroker)
    }

    @Test
    fun `Should save files when recieving cloud events to webhook`() = runTest {
        val webhook = webhooks[0]
        val publishedEvent = createCloudEvent(AltinnEventType.PUBLISHED)

        waitForAvailableWebhooks()

        webTestClient.post()
            .uri("/$webhook")
            .contentType(CLOUDEVENTS_JSON)
            .bodyValue(publishedEvent.toJson())
            .exchange()
            .expectStatus().isOk

        val persistedFile =
            altinnFilOverviewRepository.findByFileTransferId(UUID.fromString(publishedEvent.resourceinstance))
        val allPersistedFiles = altinnFilOverviewRepository.findAll()

        assertThat(persistedFile?.fileTransferId.toString()).isEqualTo(publishedEvent.resourceinstance)
        assertThat(allPersistedFiles).hasSize(2)
    }

    @ParameterizedTest
    @MethodSource("no.kartverket.altinn3.events.server.Helpers#brokerWebhookArguments")
    fun `Should set up webhooks from settings in properties file`(webhook: String) = runTest {
        logger.info("Starting test on webhook: $webhook")
        val publishedEvent = createCloudEvent(AltinnEventType.PUBLISHED)

        waitForAvailableWebhooks()

        webTestClient.post()
            .uri("/$webhook")
            .contentType(CLOUDEVENTS_JSON)
            .bodyValue(publishedEvent.toJson())
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `Should return status code 500 and rollback changes if confirm download fails`() = runTest {
        val webhook = webhooks[0]
        val publishedEvent = createCloudEvent(AltinnEventType.PUBLISHED)
        val resourceId = UUID.fromString(publishedEvent.resourceinstance)

        waitForAvailableWebhooks()

        configureFor(WIREMOCK_PORT)
        stubFor(
            post(urlPathMatching(".*/confirmdownload$"))
                .willReturn(serverError())
        )

        webTestClient.post()
            .uri("/$webhook")
            .contentType(CLOUDEVENTS_JSON)
            .bodyValue(publishedEvent.toJson())
            .exchange()
            .expectStatus().is5xxServerError

        val persistedFile = altinnFilRepository.findByFileOverviewId(resourceId)

        assertThat(persistedFile).isNull()
    }

    suspend fun waitForAvailableWebhooks() = coroutineScope {
        withContext(Dispatchers.Default) {
            logger.info("Waiting for webhooks...")
            while (!status.isAvailable()) {
                logger.info("Delaying 1000ms")
                delay(1000)
            }
        }
    }

    @Test
    fun `Should recover previously failed events`() = runTest {
        val altinnConfig: AltinnServerConfig = mockk(relaxed = true)
        val eventLoader: EventLoader = mockk(relaxed = true)
        val applicationEventPublisher: ApplicationEventPublisher = mockk(relaxed = true)
        val cloudEventHandler: CloudEventHandler = mockk(relaxed = true)
        val brokerSynchronizer by lazy {
            AltinnBrokerSynchronizer(
                eventLoader,
                cloudEventHandler,
                applicationEventPublisher,
                altinnConfig,
                failedEventRepository
            )
        }

        val event1 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileDetails()
        val event2 = createCloudEvent(AltinnEventType.PUBLISHED).createEventWithFileDetails()
        val failedEvents = listOf(
            AltinnFailedEvent(
                altinnId = UUID.fromString(event1.cloudEvent.id),
                altinnProxyState = State.PollAndWebhook::class.simpleName,
                previousEventId = UUID.randomUUID(),
            ),
            AltinnFailedEvent(
                altinnId = UUID.fromString(event2.cloudEvent.id),
                altinnProxyState = State.PollAndWebhook::class.simpleName,
                previousEventId = UUID.fromString(event1.cloudEvent.id),
            )
        )
        failedEventRepository.deleteAll()
        failedEventRepository.saveAll(failedEvents)

        assertThat(failedEventRepository.findAll()).hasSize(2)

        every { eventLoader.fetchAndMapEventsByResource(any(), any()) } returns listOf(event1, event2)
        coEvery { cloudEventHandler.handle(any()) } just runs

        brokerSynchronizer.recoverFailedEvents()
        assertThat(failedEventRepository.findAll()).hasSize(0)

        val handledEvents = mutableListOf<EventWithFileOverview>()
        coVerify { cloudEventHandler.handle(capture(handledEvents)) }

        assertThat(handledEvents).anyMatch { it.cloudEvent.id == event1.cloudEvent.id }
        assertThat(handledEvents).anyMatch { it.cloudEvent.id == event2.cloudEvent.id }
    }
}
