package no.kartverket.altinn3.events.server.exceptions

class HandleSyncEventFailedException(val lastSuccessfulEventId: String) : RuntimeException()