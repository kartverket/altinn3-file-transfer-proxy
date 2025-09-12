package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.service.AltinnHealthCheckService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.support.beans
import org.springframework.stereotype.Component

val altinnHealthCheckConfig = beans {
    bean<AltinnHealthCheckService>() {
        val altinnServerConfig = ref<AltinnServerConfig>()
        AltinnHealthCheckService(
            ref<AltinnHealthCheckProperties>(),
            altinnServerConfig,
            ref(),
            ref(),
            ref<BrokerClient>()
        )
    }
}

@Component
@ConfigurationProperties(prefix = "altinn.config")
class AltinnHealthCheckProperties {
    var interval: Long = 10000  // Default value in milliseconds
}


