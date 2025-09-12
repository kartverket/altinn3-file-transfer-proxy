package no.kartverket.altinn3.events.server

import kotlinx.coroutines.runBlocking
import no.kartverket.altinn3.events.server.configuration.*
import no.kartverket.altinn3.events.server.service.transitConfig
import no.kartverket.altinn3.persistence.configuration.TransitRepositoryConfig
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.context.event.EventListenerMethodProcessor
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.support.beans
import org.springframework.context.support.registerBean
import org.springframework.scheduling.annotation.EnableScheduling

@ImportRuntimeHints(NativeHints::class)
@SpringBootApplication
@EnableScheduling
@Import(TransitRepositoryConfig::class)
class Application : SpringApplication()

fun main(args: Array<String>) = runBlocking<Unit> {
    @Suppress("SpreadOperator")
    runApplication<Application>(*args) {
        setAdditionalProfiles("altinn")
        addInitializers(ApplicationBeansInitializer())
    }
}

class ApplicationBeansInitializer : ApplicationContextInitializer<GenericApplicationContext> {
    override fun initialize(applicationContext: GenericApplicationContext) {
        applicationContext.registerBean<EventListenerMethodProcessor>()
        beans {
            bean<Scopes.Shutdown>()
        }.initialize(applicationContext)
        webConfig.initialize(applicationContext)
        altinnConfig.initialize(applicationContext)
        stateConfig.initialize(applicationContext)
        transitConfig.initialize(applicationContext)
        healthCheckConfig.initialize(applicationContext)
        altinnHealthCheckConfig.initialize(applicationContext)
    }
}
