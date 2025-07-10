package no.kartverket.altinn3.events.server.domain

import no.kartverket.altinn3.persistence.AltinnFailedEvent
import java.time.OffsetDateTime

enum class AltinnEventType(val type: String) {

    INITIALIZED("no.altinn.broker.filetransferinitialized"),
    UPLOAD_PROCESSING("no.altinn.broker.uploadprocessing"),
    PUBLISHED("no.altinn.broker.published"),
    UPLOAD_FAILED("no.altinn.broker.uploadfailed"),
    DOWNLOAD_CONFIRMED("no.altinn.broker.downloadconfirmed"),
    ALL_CONFIRMED("no.altinn.broker.allconfirmeddownloaded"),
    NEVER_CONFIRMED("no.altinn.broker.fileneverconfirmeddownloaded"),
    FILE_DELETED("no.altinn.broker.filedeleted"),
    FILE_PURGED("no.altinn.broker.filepurged"),

    VALIDATE_SUBSCRIPTION("platform.events.validatesubscription");

    companion object {
        fun from(type: String): AltinnEventType? {
            return AltinnEventType.entries.find { it.type == type }
        }
    }
}

interface AltinnProxyApplicationEvent
class RecoveryFailedEvent : AltinnProxyApplicationEvent
class SyncFailedEvent : AltinnProxyApplicationEvent
class PollingFailedEvent(val altinnFailedEvent: AltinnFailedEvent) : AltinnProxyApplicationEvent
class FatalErrorEvent : AltinnProxyApplicationEvent
class SetupWebhooksFailedEvent : AltinnProxyApplicationEvent
class PollingStartedEvent : AltinnProxyApplicationEvent
class PollingReachedEndEvent : AltinnProxyApplicationEvent
class SubscriptionValidatedEvent : AltinnProxyApplicationEvent
class AltinnSyncFinishedEvent(val latestEventId: String) : AltinnProxyApplicationEvent
class WebhookHandlerReadyEvent(val cloudEventTime: OffsetDateTime) : AltinnProxyApplicationEvent
class SetupSubscriptionsDoneEvent : AltinnProxyApplicationEvent
class RecoveryDoneEvent : AltinnProxyApplicationEvent
