package no.kartverket.altinn3.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime
import java.util.*

@Table("altinn_fil")
data class AltinnFil(
    @Id val id: UUID? = null,
    val payload: ByteArray,
    val created: LocalDateTime = LocalDateTime.now(),
    val fileOverviewId: UUID,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AltinnFil

        return id == other.id
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }
}

@Table("altinn_fil_overview")
data class AltinnFilOverview(
    @Id
    val id: UUID? = null,
    val fileName: String? = null,
    val received: LocalDateTime? = null,
    val sent: LocalDateTime? = null,
    val created: LocalDateTime?,
    val fileTransferId: UUID?,
    val direction: Direction,
    val transitStatus: TransitStatus,
    val sender: String? = null,
    val sendersReference: String? = null,
    val resourceId: String?,
    val jsonPropertyList: String?,
    val checksum: String? = null,
    val modified: LocalDateTime = LocalDateTime.now(),
    @Version
    @get:JvmName("getVersion")
    @set:JvmName("setVersion")
    var version: Int = 0,
)
