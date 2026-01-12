package no.kartverket.altinn3.persistence

import org.springframework.data.jdbc.repository.query.Modifying
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
                "and fagsystemreferanse = :fagsystemreferanse " +
                "order by published " +
                "limit 1",
    )
    fun findCompletedTransitsByFagsystemreferanse(
        transitStatus: TransitStatus = TransitStatus.COMPLETED,
        direction: Direction = Direction.OUT,
        fagsystemreferanse: String? = null,
    ): AltinnFilOverview?

    @Query(
        "select * from altinn_fil_overview " +
                "where transit_status = :transitStatus " +
                "and direction = :direction " +
                "order by published",
    )
    fun findAllByTransitStatus(
        transitStatus: TransitStatus = TransitStatus.NEW,
        direction: Direction = Direction.IN
    ): List<AltinnFilOverview>

    fun existsByFileTransferId(fileReference: UUID): Boolean

    fun existsByFagsystemreferanse(fagsystemReferanse: String?): Boolean
}

interface AltinnFilRepository : CrudRepository<AltinnFil, UUID> {
    fun findByFileOverviewId(id: UUID): AltinnFil?

    @Modifying
    @Query(
        """
        UPDATE altinn_fil
        SET PAYLOAD = null
        WHERE id IN (
           SELECT af.id FROM altinn_fil af
           JOIN altinn_fil_overview afo ON af.file_overview_id = afo.id
           WHERE af.PAYLOAD IS NOT NULL
           AND afo.transit_status = 'COMPLETED'
           AND af.CREATED < now() - interval '30 days'
        )
        """
    )
    fun deletePayloadOlderThan30Days() : Int
}

interface AltinnEventRepository : CrudRepository<AltinnEvent, UUID> {
    fun findFirstByOrderByTimeDesc(): AltinnEvent?
    fun findByResourceinstance(resourceInstance: UUID): AltinnEvent?
    fun existsByAltinnId(altinnId: UUID): Boolean
}
