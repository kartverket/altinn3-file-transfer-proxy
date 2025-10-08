package no.kartverket.altinn3.events.server.interfaces

import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import org.springframework.web.reactive.function.server.ServerResponse

interface WebhookHandler {
    suspend fun handle(event: EventWithFileOverview): ServerResponse
}