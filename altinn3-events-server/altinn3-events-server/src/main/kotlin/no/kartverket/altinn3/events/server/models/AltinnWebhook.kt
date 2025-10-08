package no.kartverket.altinn3.events.server.models

data class AltinnWebhook(
    var path: String? = null,
    var resourceFilter: String? = null,
    var subjectFilter: String? = null,
    var typeFilter: String? = null,
    var handler: String = "webhookHandler",
)