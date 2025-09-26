package no.kartverket.altinn3.events.server.domain

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
