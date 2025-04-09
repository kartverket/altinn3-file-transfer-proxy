package no.kartverket.altinn3.client

import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.apis.SubscriptionApi

class EventsClient(
    val events: EventsApi,
    val subscription: SubscriptionApi
)
