package no.kartverket.altinn3.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

@Table("altinn_fil")
data class AltinnFil(
    @Id val id: UUID? = null,
    val payload: ByteArray,
    val created: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    val fileOverviewId: UUID,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AltinnFil

        return id == other.id
    }


    override fun toString(): String {
        return "AltinnFil(id=$id, created=$created, fileOverviewId=$fileOverviewId)"
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + created.hashCode()
        result = 31 * result + fileOverviewId.hashCode()
        return result
    }
}

@Table("altinn_fil_overview")
data class AltinnFilOverview(
    @Id
    val id: UUID? = null,
    val fileName: String? = null,
    val received: OffsetDateTime? = null,
    val sent: OffsetDateTime? = null,
    val created: OffsetDateTime? = null,
    val fileTransferId: UUID?,
    val direction: Direction,
    val transitStatus: TransitStatus,
    val sender: String? = null,
    val sendersReference: String? = null,
    val resourceId: String?,
    val jsonPropertyList: String?,
    val checksum: String? = null,
    val modified: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    val fagsystemreferanse: String? = null,
    @Version
    @get:JvmName("getVersion")
    @set:JvmName("setVersion")
    var version: Int = 0,
)
