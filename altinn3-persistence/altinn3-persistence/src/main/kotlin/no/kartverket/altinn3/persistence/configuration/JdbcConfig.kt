package no.kartverket.altinn3.persistence.configuration

import org.springframework.data.convert.CustomConversions
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.data.jdbc.core.dialect.DialectResolver
import org.springframework.data.jdbc.core.dialect.JdbcOracleDialect
import org.springframework.data.jdbc.core.mapping.JdbcSimpleTypes
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import org.springframework.data.mapping.model.SimpleTypeHolder
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations

abstract class JdbcConfig(private val operations: NamedParameterJdbcOperations) :
    AbstractJdbcConfiguration() {

    override fun jdbcDialect(operations: NamedParameterJdbcOperations) =
        DialectResolver.getDialect(operations.jdbcOperations)

    /**
     * Superklassen henter SQL-dialekten fra application context.
     * Siden vi ønsker å støtte mer enn én dialekt og flere datakilder,
     * får vi tvetydigheter med standardimpl.
     * @return "oversettere" mellom typer i koden og db-kolonner.
     * @see AbstractJdbcConfiguration
     */
    override fun jdbcCustomConversions(): JdbcCustomConversions {
        val dialect = jdbcDialect(operations)
        val gbokSimpleTypeHolder = if (dialect.simpleTypes().isEmpty()) JdbcSimpleTypes.HOLDER else SimpleTypeHolder(
            dialect.simpleTypes(),
            JdbcSimpleTypes.HOLDER
        )
        val storeConverters = buildList<Any> {
            addAll(JdbcOracleDialect.INSTANCE.converters)
            addAll(JdbcCustomConversions.storeConverters())
        }
        return JdbcCustomConversions(
            CustomConversions.StoreConversions.of(
                gbokSimpleTypeHolder,
                storeConverters
            ),
            userConverters(),
        )
    }
}
