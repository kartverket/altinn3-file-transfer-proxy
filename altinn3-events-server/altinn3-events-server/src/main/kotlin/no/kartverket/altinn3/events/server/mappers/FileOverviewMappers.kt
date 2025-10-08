package no.kartverket.altinn3.events.server.mappers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.persistence.AltinnFilOverview
import no.kartverket.altinn3.persistence.Direction
import no.kartverket.altinn3.persistence.TransitStatus
import java.time.LocalDateTime

fun FileOverview.toAltinnFilOverview() = AltinnFilOverview(
    fileName = this.fileName,
    checksum = this.checksum,
    sendersReference = this.sendersFileTransferReference,
    sender = this.sender,
    created = this.created?.toLocalDateTime(),
    received = LocalDateTime.now(),
    fileTransferId = this.fileTransferId,
    resourceId = this.resourceId,
    transitStatus = TransitStatus.NEW,
    direction = Direction.IN,
    jsonPropertyList = jacksonObjectMapper().writeValueAsString(this.propertyList)
)