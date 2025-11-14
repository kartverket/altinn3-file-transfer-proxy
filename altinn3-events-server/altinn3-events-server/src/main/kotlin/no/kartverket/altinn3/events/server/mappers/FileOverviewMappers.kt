package no.kartverket.altinn3.events.server.mappers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.persistence.AltinnFilOverview
import no.kartverket.altinn3.persistence.Direction
import no.kartverket.altinn3.persistence.TransitStatus
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun FileOverview.toAltinnFilOverview() = AltinnFilOverview(
    fileName = this.fileName,
    checksum = this.checksum,
    sendersReference = this.sendersFileTransferReference,
    sender = this.sender,
    created = this.created,
    received = OffsetDateTime.now(ZoneOffset.UTC),
    fileTransferId = this.fileTransferId,
    resourceId = this.resourceId,
    transitStatus = TransitStatus.NEW,
    direction = Direction.IN,
    jsonPropertyList = jacksonObjectMapper().writeValueAsString(this.propertyList)
)