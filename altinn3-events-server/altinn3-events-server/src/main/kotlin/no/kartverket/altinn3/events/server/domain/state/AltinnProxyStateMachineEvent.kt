package no.kartverket.altinn3.events.server.domain.state

import no.kartverket.altinn3.persistence.AltinnFailedEvent
import java.time.OffsetDateTime

sealed class AltinnProxyStateMachineEvent {
    class StartRecovery : AltinnProxyStateMachineEvent()
    class RecoveryFailed : AltinnProxyStateMachineEvent()
    class RecoverySucceeded : AltinnProxyStateMachineEvent()
    class SyncFailed : AltinnProxyStateMachineEvent()
    class SyncSucceeded(val lastSyncedEvent: String) : AltinnProxyStateMachineEvent()
    class PollingFailed(val failedEvent: AltinnFailedEvent) : AltinnProxyStateMachineEvent()
    class PollingSucceeded : AltinnProxyStateMachineEvent()
    class ServiceUnavailable(val lastSyncedEvent: String) : AltinnProxyStateMachineEvent()
    class WaitForConnection(val lastSyncedEvent: String) : AltinnProxyStateMachineEvent()
    class ServiceAvailable(val lastSyncedEvent: String) : AltinnProxyStateMachineEvent()
    class WebhookValidated : AltinnProxyStateMachineEvent()
    class WebhookFailed : AltinnProxyStateMachineEvent()
    class WebhookReady(val cloudEventTime: OffsetDateTime) : AltinnProxyStateMachineEvent()
    class CriticalError(val throwable: Throwable? = null) : AltinnProxyStateMachineEvent()
    class Stop : AltinnProxyStateMachineEvent()

    override fun toString(): String = javaClass.simpleName
}
