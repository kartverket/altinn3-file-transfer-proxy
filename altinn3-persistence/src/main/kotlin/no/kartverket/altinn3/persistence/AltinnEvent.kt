package no.kartverket.altinn3.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime
import java.util.*

@Table("altinn_failed_event")
data class AltinnFailedEvent(
    val altinnId: UUID,
    val previousEventId: UUID,
    val altinnProxyState: String,
    val created: LocalDateTime = LocalDateTime.now(),
    @Id
    val id: UUID? = null,
)

@Table(name = "altinn_event")
data class AltinnEvent(
    @Id
    val id: UUID? = null,
    val altinnId: UUID,
    val resourceinstance: UUID? = null,
    val specVersion: String? = null,
    val type: String? = null,
    val time: LocalDateTime? = null,
    val resource: String? = null,
    val source: String? = null,
    val received: LocalDateTime = LocalDateTime.now(),
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AltinnEvent

        return (altinnId == other.altinnId)
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString() =
        """AltinnEventEntity(
             id=$altinnId, 
             resourceinstance=$resourceinstance, 
             specVersion=$specVersion, 
             type=$type, 
             time=$time, 
             resource=$resource, 
             source=$source, 
             recieved=$received
            )"""
}
