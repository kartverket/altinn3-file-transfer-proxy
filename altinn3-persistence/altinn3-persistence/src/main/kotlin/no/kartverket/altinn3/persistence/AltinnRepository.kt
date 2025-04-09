package no.kartverket.altinn3.persistence

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.*

interface AltinnFailedEventRepository : CrudRepository<AltinnFailedEvent, UUID> {
    override fun findAll(): List<AltinnFailedEvent>
    fun findDistinctByAltinnIdOrIdNull(altinnId: UUID): AltinnFailedEvent?
}

interface AltinnFilOverviewRepository : CrudRepository<AltinnFilOverview, UUID> {
    fun findByFileTransferId(fileReference: UUID): AltinnFilOverview?

    @Query(
        "select * from altinn_fil_overview " +
            "where transit_status = :transitStatus " +
            "and direction = :direction " +
            "order by created",
    )
    fun findAllByTransitStatus(
        transitStatus: TransitStatus = TransitStatus.NEW,
        direction: Direction = Direction.IN
    ): List<AltinnFilOverview>

    fun existsByFileTransferId(fileReference: UUID): Boolean
}

interface AltinnFilRepository : CrudRepository<AltinnFil, UUID> {
    fun findByFileOverviewId(id: UUID): AltinnFil?
}

interface AltinnEventRepository : CrudRepository<AltinnEvent, UUID> {
    fun findFirstByOrderByTimeDesc(): AltinnEvent?
    fun findByResourceinstance(resourceInstance: UUID): AltinnEvent?
    fun existsByAltinnId(altinnId: UUID): Boolean
}
