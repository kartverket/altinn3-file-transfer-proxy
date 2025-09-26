package no.kartverket.altinn3.events.server.models

import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileOverview

data class EventWithFileOverview(
    val cloudEvent: CloudEvent,
    val fileOverview: FileOverview
)
