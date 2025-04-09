package no.kartverket.altinn3.events.server.domain.state

enum class AltinnProxyState {
    INITIAL,
    STARTUP_RECOVERY,
    ERROR,
    SYNCHRONIZING,
    POLLING,
    SETUP_WEBHOOKS,
    WEBHOOKS_PENDING_VALIDATION,
    POLL_AND_WEBHOOKS,
    STOP_POLLING,
    WEBHOOKS,
    STOPPING
}
