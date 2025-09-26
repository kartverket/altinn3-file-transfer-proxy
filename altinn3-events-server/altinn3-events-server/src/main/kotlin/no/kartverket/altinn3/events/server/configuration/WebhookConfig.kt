package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileOverview
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.support.beans
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.reactive.function.server.*
import java.util.*

val webhookConfig = beans {
//    bean {
//        object : WebFluxConfigurer {
//            override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
//            }
//        }
//    }
    profile("!poll") {
        bean<DefaultWebhookHandler>("webhookHandler")
        bean<WebhooksRouterProvider>()
        bean<StateMachineWebhookAvailabilityStatus>()
        bean(::router)
    }
}

interface WebhookAvailabilityStatus {
    fun isAvailable(): Boolean
}

interface WebhookHandler {
    suspend fun handle(event: EventWithFileOverview): ServerResponse
}

class DefaultWebhookHandler(
    private val cloudEventHandler: CloudEventHandler,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val webhookAvailabilityStatus: WebhookAvailabilityStatus,
) : WebhookHandler {
    private var firstWebhookEvent = true

    override suspend fun handle(event: EventWithFileOverview): ServerResponse {
        if (!webhookAvailabilityStatus.isAvailable())
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).buildAndAwait()

        val onSuccess = suspend {
            if (firstWebhookEvent) {
                applicationEventPublisher.publishEvent(
                    AltinnProxyStateMachineEvent.WebhookReady(requireNotNull(event.fileOverview.created))
                )
                firstWebhookEvent = false
            }
            ServerResponse.ok().buildAndAwait()
        }

        val onError: suspend (Throwable) -> ServerResponse = { e ->
            when (e) {
                is IllegalArgumentException -> e.localizedMessage?.let {
                    ServerResponse.badRequest().bodyValueAndAwait(it)
                } ?: ServerResponse.badRequest().buildAndAwait()
                // TODO: Ved enkelte feil kan vi vurdere å gå tilbake til starttilstand med restore -> synk osv.
                else -> ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
            }
        }

        return cloudEventHandler.tryHandle(event, onSuccess, onError)
    }
}

val CLOUDEVENTS_JSON: MediaType = MediaType.parseMediaType("application/cloudevents+json")
fun router(webhookRoutes: WebhooksRouterProvider): RouterFunction<ServerResponse> = webhookRoutes()

class WebhookRequestHandler(
    private val webhookHandler: WebhookHandler,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val brokerClient: BrokerClient,
    private val retryTemplate: RetryTemplate,
) : suspend (ServerRequest) -> ServerResponse {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override suspend fun invoke(request: ServerRequest): ServerResponse {
        val cloudEvent = request.awaitBody<CloudEvent>()
        logger.info(
            "Got cloud event with ID: ${cloudEvent.id}, type ${cloudEvent.type} and time: ${
                cloudEvent.time?.toLocalTime()
            }"
        )
        return when (cloudEvent.type) {
            AltinnEventType.VALIDATE_SUBSCRIPTION.type -> {
                logger.info("Validation event received: $cloudEvent")
                ServerResponse.ok().buildAndAwait().also {
                    applicationEventPublisher.publishEvent(AltinnProxyStateMachineEvent.WebhookValidated())
                }
            }

            else -> {
                val fileOverview = fetchFileOverview(requireNotNull(cloudEvent.resourceinstance))
                webhookHandler.handle(EventWithFileOverview(cloudEvent, fileOverview)).also {
                    logger.debug("Responding http status: ${it.statusCode()} on event with id: ${cloudEvent.id}")
                }
            }
        }
    }

    private fun fetchFileOverview(resourceInstance: String): FileOverview {
        return retryTemplate.execute<FileOverview, Exception> {
            brokerClient.getFileOverview(UUID.fromString(resourceInstance))
        }
    }
}

class WebhooksRouterProvider(
    private val altinn: AltinnServerConfig,
    private val context: ApplicationContext,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val brokerClient: BrokerClient,
    private val retryTemplate: RetryTemplate,
) : () -> RouterFunction<ServerResponse> {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun invoke(): RouterFunction<ServerResponse> = coRouter {
        altinn.webhooks.forEach {
            logger.debug("Setting up webhook: {}", it)
            val handler: WebhookHandler = context.getBean(it.handler, WebhookHandler::class.java)
            POST(
                "${it.path}",
                accept(CLOUDEVENTS_JSON),
                WebhookRequestHandler(handler, applicationEventPublisher, brokerClient, retryTemplate),
            )
        }
    }
}
