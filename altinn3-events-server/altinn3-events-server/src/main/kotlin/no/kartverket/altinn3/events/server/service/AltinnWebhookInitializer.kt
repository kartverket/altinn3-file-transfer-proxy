package no.kartverket.altinn3.events.server.service

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.kartverket.altinn3.client.EventsClient
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.events.server.configuration.AltinnWebhooks
import no.kartverket.altinn3.models.Subscription
import no.kartverket.altinn3.models.SubscriptionRequestModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.retry.support.RetryTemplate
import kotlin.time.Duration

class AltinnWebhookInitializer(
    private val eventsClient: EventsClient,
    private val altinnWebhooks: AltinnWebhooks,
    private val retryTemplate: RetryTemplate,
) : DisposableBean {
    private lateinit var subscriptions: Set<Subscription>
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private fun SubscriptionApi.setUpSubscriptions(): Collection<Subscription> {
        return altinnWebhooks.map { webhook ->
            retryTemplate.execute<Subscription, IllegalStateException> {
                subscriptionsPost(
                    SubscriptionRequestModel(
                        endPoint = altinnWebhooks.webhookEndpoint(webhook),
                        resourceFilter = webhook.resourceFilter,
                        typeFilter = webhook.typeFilter,
                        subjectFilter = webhook.subjectFilter,
                    )
                )
            }
        }.onEach {
            logger.info("new Altinn subscription: $it")
        }
    }

    private fun SubscriptionApi.deleteAll(): Collection<Int> {
        return subscriptions.map { subscription ->
            retryTemplate.execute<Unit, IllegalStateException> { this.subscriptionsIdDelete(subscription.id!!) }
            subscription.id!!
        }.onEach { logger.info("Altinn subscription id($it) deleted") }
    }

    override fun destroy() = runBlocking {
        deleteSubscriptions()
    }

    fun deleteSubscriptions() {
        if (::subscriptions.isInitialized) runCatching {
            eventsClient.subscription.deleteAll()
        }.onFailure {
            logger.error("delete subscriptions error", it)
        }
    }

    suspend fun setupWebhooks(delay: Duration) = coroutineScope {
        delay(delay)
        subscriptions = eventsClient.subscription.setUpSubscriptions().toCollection(hashSetOf())
        logger.info("setup subscriptions: completed")
    }
}
