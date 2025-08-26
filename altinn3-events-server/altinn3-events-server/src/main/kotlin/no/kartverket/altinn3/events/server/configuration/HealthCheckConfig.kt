package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.events.server.routes.healthCheckRouter
import no.kartverket.altinn3.events.server.service.HealthCheckService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.support.beans
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

val healthCheckConfig = beans {
    bean<HealthCheckService>() {
        val altinnServerConfig = ref<AltinnServerConfig>()
        val restClient = RestClient.builder().baseUrl(altinnServerConfig.webhookExternalUrl).build()
        HealthCheckService(
            ref(),
            altinnServerConfig,
            ref(),
            ref(),
            restClient
        )
    }
    bean(::healthCheckRouter)
}

@Component
@ConfigurationProperties(prefix = "healthcheck")
class HealthCheckProperties {
    var interval: Long = 5000  // Default value in milliseconds
}


