package no.kartverket.altinn3.events.server.exceptions

class HandlePollEventFailedException(
    val pollFromEventId: String,
    val failedEventID: String
) : RuntimeException()