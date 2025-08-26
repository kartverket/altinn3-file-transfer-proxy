package no.kartverket.altinn3.events.server.routes

import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter

private suspend fun checkAvailability(req: ServerRequest): ServerResponse {
    return ServerResponse.ok().buildAndAwait()
}

const val HEALTH_CHECK_URL = "/availability"

internal fun healthCheckRouter() = coRouter {
    GET(HEALTH_CHECK_URL, ::checkAvailability)
}
