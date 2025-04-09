package no.kartverket.altinn3.events.server.config

import no.kartverket.altinn3.persistence.configuration.TransitRepositoryConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.PostgreSQLContainer

@Import(TransitRepositoryConfig::class)
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestContainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:latest")

    @Bean
    fun propRegistry(postgresContainer: PostgreSQLContainer<*>): DynamicPropertyRegistrar {
        return DynamicPropertyRegistrar { registry ->
            registry.add("spring.datasource.transit.url") { postgresContainer.jdbcUrl }
            registry.add("spring.datasource.transit.hikari.username") { postgresContainer.username }
            registry.add("spring.datasource.transit.hikari.password") { postgresContainer.password }
        }
    }
}
