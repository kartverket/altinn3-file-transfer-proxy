package no.kartverket.altinn3.events.server.service

import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.client.EventsClient
import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.server.configuration.AltinnWebhooks
import no.kartverket.altinn3.events.server.models.EventWithFileOverview
import no.kartverket.altinn3.models.CloudEvent
import no.kartverket.altinn3.models.FileOverview
import org.slf4j.LoggerFactory
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.client.RestClientResponseException
import java.util.*

class EventLoader(
    private val eventsClient: EventsClient,
    private val brokerClient: BrokerClient,
    private val webhooks: AltinnWebhooks,
    private val retryTemplate: RetryTemplate,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    fun fetchAndMapEventsByResource(
        recipientOrgNr: String,
        afterSupplier: (resource: String) -> String = { "0" }
    ): List<EventWithFileOverview> =
        webhooks
            .groupBy({ it.resourceFilter!! }) { it.typeFilter }
            .flatMap { (resource, typeFilters) ->
                fetchEventsForResource(
                    resource = resource,
                    recipientOrgNr = recipientOrgNr,
                    typeFilters = typeFilters,
                    afterSupplier = afterSupplier
                )
            }

    private fun fetchEventsForResource(
        resource: String,
        recipientOrgNr: String,
        typeFilters: List<String?>,
        afterSupplier: (resource: String) -> String
    ): List<EventWithFileOverview> {
        val filteredTypes = typeFilters
            .takeUnless { it.any { type -> type.isNullOrBlank() } }
            ?.filterNotNull()
            ?.filter { it.isNotBlank() }

        return eventsClient.events.loadResourceEventTypeWithFileOverview(
            resource = resource,
            type = filteredTypes,
            subject = "urn:altinn:organization:identifier-no:$recipientOrgNr",
            afterSupplier = afterSupplier
        )
    }

    fun EventsApi.loadResourceEventTypeWithFileOverview(
        resource: String,
        type: List<String>? = null,
        pageSize: Int = 50,
        subject: String,
        afterSupplier: (resource: String) -> String = { "0" }
    ): List<EventWithFileOverview> {
        var after: String = afterSupplier(resource)
        val collectedEvents = mutableListOf<EventWithFileOverview>()

        do {
            val page = retryTemplate.execute<List<CloudEvent>, IllegalStateException> {
                eventsGet(
                    resource,
                    after,
                    size = pageSize,
                    type = type,
                    subject = subject
                )
            }

            // Fetch FileOverview for each event
            val pageWithFileOverviews = page.mapNotNull { event ->
                event.resourceinstance?.let { resourceInstance ->
                    try {
                        val fileOverview = fetchFileOverview(resourceInstance)
                        EventWithFileOverview(event, fileOverview)
                    } catch (e: RestClientResponseException) {
                        logger.warn("Could not fetch file overview for event ${event.id}")
                        null
                    }
                }
            }

            collectedEvents.addAll(pageWithFileOverviews)

            if (page.isNotEmpty()) {
                after = page.last().id!!
            }
        } while (page.size == pageSize)

        return collectedEvents
    }

    private fun fetchFileOverview(resourceInstance: String): FileOverview {
        return retryTemplate.execute<FileOverview, RestClientResponseException> {
            brokerClient.getFileOverview(UUID.fromString(resourceInstance))
        }
    }
}