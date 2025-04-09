package no.kartverket.altinn3.events.server

import kotlinx.coroutines.runBlocking
import no.kartverket.altinn3.events.server.config.PostgresTestContainersConfiguration
import org.springframework.boot.fromApplication

fun main(args: Array<String>) = runBlocking<Unit> {
    fromApplication<Application>()
        .withAdditionalProfiles("altinn")
        .with(PostgresTestContainersConfiguration::class.java)
        .run(*args)
}
