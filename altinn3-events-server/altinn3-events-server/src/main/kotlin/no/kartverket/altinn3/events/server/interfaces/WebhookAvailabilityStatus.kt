package no.kartverket.altinn3.events.server.interfaces

interface WebhookAvailabilityStatus {
    fun isAvailable(): Boolean
}