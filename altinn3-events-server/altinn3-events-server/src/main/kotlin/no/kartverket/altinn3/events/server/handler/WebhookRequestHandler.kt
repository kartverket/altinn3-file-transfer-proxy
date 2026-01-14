package no.kartverket.altinn3.events.server.handler

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.interfaces.WebhookHandler
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileOverview
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.buildAndAwait
import java.time.OffsetDateTime
import java.util.*

class WebhookRequestHandler(
    private val webhookHandler: WebhookHandler,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val brokerClient: BrokerClient,
    private val retryTemplate: RetryTemplate,
    private val skipPollAndWebhook: Boolean,
) : suspend (ServerRequest) -> ServerResponse {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override suspend fun invoke(request: ServerRequest): ServerResponse {
        val cloudEvent = request.awaitBody<CloudEvent>()

        if (cloudEvent.type == AltinnEventType.PUBLISHED.type && isSender(cloudEvent.data)) {
            return ServerResponse.ok().buildAndAwait()
        }

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
                    if (skipPollAndWebhook) {
                        applicationEventPublisher.publishEvent(
                            AltinnProxyStateMachineEvent.WebhookReady(
                                cloudEvent.time ?: OffsetDateTime.now()
                            )
                        )
                    }
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

    private suspend fun fetchFileOverview(resourceInstance: String): FileOverview = withContext(Dispatchers.IO) {
        retryTemplate.execute<FileOverview, Exception> {
            brokerClient.getFileOverview(UUID.fromString(resourceInstance))
        }
    }

    private fun isSender(data: Any?): Boolean {
        val map = data as? Map<*, *> ?: return false
        return map["Role"] == "Sender"
    }

}

