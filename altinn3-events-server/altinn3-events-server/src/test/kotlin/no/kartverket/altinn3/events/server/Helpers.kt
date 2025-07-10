package no.kartverket.altinn3.events.server

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.kartverket.altinn3.events.server.domain.AltinnEventType
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileOverview
import no.kartverket.altinn3.models.FileStatus
import org.junit.jupiter.params.provider.Arguments
import java.net.URI
import java.time.OffsetDateTime
import java.util.*
import java.util.stream.Stream

// De genererte klassene arver av HashMap, så jackson sliter med å finne ut av serialiseringen ut av boksen.
private class CloudEventSerializer : JsonSerializer<CloudEvent>() {
    override fun serialize(cloudEvent: CloudEvent, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeStartObject()
        cloudEvent.forEach { (key, value) ->
            gen.writeObjectField(key, value)
        }
        gen.writeStringField("specversion", cloudEvent.specversion)
        gen.writeStringField("id", cloudEvent.id)
        gen.writeStringField("type", cloudEvent.type)
        gen.writeObjectField("time", cloudEvent.time)
        gen.writeStringField("resource", cloudEvent.resource)
        gen.writeStringField("resourceinstance", cloudEvent.resourceinstance)
        gen.writeStringField("source", cloudEvent.source?.toString())
        gen.writeEndObject()
    }
}

object Helpers {
    val webhooks = arrayOf("test.event", "the.event", "osv.event")

    @JvmStatic
    fun brokerWebhookArguments(): Stream<Arguments> {
        return Stream.of(*webhooks).map(Arguments::of)
    }

    const val resource = "urn:altinn:resource:kv_devtest"

    fun createCloudEvent(eventType: AltinnEventType) = CloudEvent(
        "1",
        id = UUID.randomUUID().toString(),
        type = eventType.type,
        time = OffsetDateTime.now(),
        resource = resource,
        resourceinstance = UUID.randomUUID().toString(),
        source = URI.create("test"),
    )

    fun createFileOverviewFromEvent(event: CloudEvent, status: FileStatus = FileStatus.Published) =
        FileOverview(
            fileTransferId = UUID.fromString(event.resourceinstance),
            resourceId = "kv_devtest",
            fileTransferStatus = status,
            created = event.time,
            fileName = "file.xml",
            sender = "innsendtFraTest",
            propertyList = mapOf(),
            sendersFileTransferReference = "sendersFileTransferReference-${event.resourceinstance}",
        )

    val configuredObjectMapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .registerModules(SimpleModule().addSerializer(CloudEvent::class.java, CloudEventSerializer()))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(Include.NON_ABSENT)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
}
