package no.kartverket.altinn3.events.server.models

sealed class SideEffect {
    object RecoveryRequested : SideEffect()
    object RecoveryFailed : SideEffect()
    object SyncRequested : SideEffect()
    object SyncFailed : SideEffect()
    object PollRequested : SideEffect()
    object PollFailed : SideEffect()
    object WebhookRequested : SideEffect()
    object WebbhookFailed : SideEffect()
    object StopPollRequested : SideEffect()
    object CriticalError : SideEffect()
    object ServiceAvailableAgainAfterUnavailability : SideEffect()

    /**
     * If Health Check fails, put state to polling, while waiting for connection
     */
    object WaitForConnection : SideEffect()
}