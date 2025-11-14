package no.kartverket.altinn3.events.server.mappers

import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.persistence.AltinnEvent
import java.util.*

fun CloudEvent.toAltinnEventEntity() = AltinnEvent(
    specVersion = specversion,
    altinnId = UUID.fromString(id),
    type = type,
    time = time,
    resource = resource,
    resourceinstance = UUID.fromString(resourceinstance),
    source = source.toString()
)