package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.events.server.service.HealthCheckService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.support.beans
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

val healthCheckConfig = beans {
    bean<HealthCheckService> {
        val properties = ref<HealthCheckProperties>()
        val restClient = RestClient.builder().baseUrl(properties.url).build()
        HealthCheckService(
            ref(),
            ref(),
            restClient,
        )
    }
}


@Component
@ConfigurationProperties(prefix = "healthcheck")
class HealthCheckProperties {
    companion object {
        const val THIRTY_SECONDS: Long = 30000
    }

    var interval: Long = THIRTY_SECONDS
    var url: String = ""
}