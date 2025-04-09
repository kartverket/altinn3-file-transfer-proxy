package no.kartverket.altinn3.events.server.unit

import io.mockk.*
import kotlinx.coroutines.test.runTest
import no.kartverket.altinn3.client.EventsClient
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.events.server.configuration.AltinnWebhook
import no.kartverket.altinn3.events.server.configuration.AltinnWebhooks
import no.kartverket.altinn3.events.server.service.AltinnWebhookInitializer
import no.kartverket.altinn3.models.Subscription
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.net.URI

class AltinnWebhookInitializerTest {
    private val eventsClient = mockk<EventsClient>()
    private val applicationEventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val altinnWebhooks = mockk<AltinnWebhooks>(relaxed = true)
    private val subscriptionApi = mockk<SubscriptionApi>()

    private val initializer = spyk(
        AltinnWebhookInitializer(eventsClient, altinnWebhooks, applicationEventPublisher),
        recordPrivateCalls = true
    )

    val webhook1 = AltinnWebhook(
        path = "/somepath",
        handler = "handler1",
        resourceFilter = "resFilter1",
        typeFilter = null
    )
    val webhook2 = AltinnWebhook(
        path = "/anotherPath",
        handler = "handler2",
        resourceFilter = "resFilter2",
        typeFilter = "someType"
    )
    val resFilter1 = URI.create("resFilter1")
    val resFilter2 = URI.create("resFilter2")
    val sub1 = Subscription(1, URI.create("endpoint1"), resFilter1, "type1")
    val sub2 = Subscription(2, URI.create("endpoint2"), resFilter2, "type2")

    @Test
    fun `setupWebhooks() - calls subscriptionsPost for each webhook and logs completion`() = runTest {
        val allWebhooks = listOf(webhook1, webhook2)
        every { altinnWebhooks.iterator() } returns allWebhooks.iterator()
        every { eventsClient.subscription } returns subscriptionApi

        coEvery {
            subscriptionApi.subscriptionsPost(
                match { req ->
                    req.resourceFilter == resFilter1.toString()
                }
            )
        } returns sub1

        coEvery {
            subscriptionApi.subscriptionsPost(
                match { req ->
                    req.resourceFilter == resFilter2.toString()
                }
            )
        } returns sub2

        initializer.setupWebhooks()

        coVerify(exactly = 1) {
            subscriptionApi.subscriptionsPost(
                match {
                    it.resourceFilter == resFilter1.toString()
                }
            )
        }
        coVerify(exactly = 1) {
            subscriptionApi.subscriptionsPost(
                match {
                    it.resourceFilter == resFilter2.toString()
                }
            )
        }
    }

    @Test
    fun `destroy() - if subscriptions is set by setupWebhooks, calls deleteAll for each subscription`() = runTest {
        every { altinnWebhooks.iterator() } returns listOf(webhook1, webhook2).iterator()
        every { eventsClient.subscription } returns subscriptionApi

        coEvery {
            subscriptionApi.subscriptionsPost(
                match { req ->
                    req.resourceFilter == resFilter1.toString()
                }
            )
        } returns sub1

        coEvery {
            subscriptionApi.subscriptionsPost(
                match { req ->
                    req.resourceFilter == resFilter2.toString()
                }
            )
        } returns sub2

        coEvery { subscriptionApi.subscriptionsPost(any()) } returnsMany listOf(sub1, sub2)

        initializer.setupWebhooks()

        coEvery { subscriptionApi.subscriptionsIdDelete(sub1.id!!) } returns Unit
        coEvery { subscriptionApi.subscriptionsIdDelete(sub2.id!!) } returns Unit

        initializer.destroy()

        coVerify(exactly = 1) { subscriptionApi.subscriptionsIdDelete(sub1.id!!) }
        coVerify(exactly = 1) { subscriptionApi.subscriptionsIdDelete(sub2.id!!) }
    }

    @Test
    fun `destroy() - if subscriptions is NOT initialized, does nothing`() = runTest {
        every { eventsClient.subscription } returns subscriptionApi
        initializer.destroy()

        coVerify(exactly = 0) { subscriptionApi.subscriptionsIdDelete(any()) }
    }
}
