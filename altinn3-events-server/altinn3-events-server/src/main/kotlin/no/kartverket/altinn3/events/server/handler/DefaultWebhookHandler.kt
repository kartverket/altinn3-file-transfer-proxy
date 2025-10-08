package no.kartverket.altinn3.events.server.handler

import no.kartverket.altinn3.events.server.domain.state.AltinnProxyStateMachineEvent
import no.kartverket.altinn3.events.server.interfaces.WebhookAvailabilityStatus
import no.kartverket.altinn3.events.server.interfaces.WebhookHandler
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait

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

