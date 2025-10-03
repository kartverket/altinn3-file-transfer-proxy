package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.broker.apis.FileTransferApi
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.client.EventsClient
import no.kartverket.altinn3.config.AltinnConfig
import no.kartverket.altinn3.config.MaskinportenConfig
import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.service.*
import no.kartverket.altinn3.persistence.AltinnEventRepository
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.support.beans
import org.springframework.retry.support.RetryTemplate
import java.net.URI

val altinnConfig = beans {
    profile("altinn") {
        bean<AltinnTransitService>()
        bean<EventsClient>()
        bean<BrokerClient>()
        bean {
            createAltinnStartEventSupplier(
                ref<AltinnEventRepository>(),
                ref<AltinnServerConfig>()
            )
        }
        bean<CloudEventHandler>()
        bean<EventLoader>()
        bean<AltinnBrokerSynchronizer>()
        bean<AltinnWebhookInitializer>()
        bean<EventsApi> {
            AltinnAPIConfig.createEventApi(ref(), ref())
        }
        bean<SubscriptionApi> {
            AltinnAPIConfig.createSubscriptionApi(ref(), ref())
        }
        bean<FileTransferApi> {
            AltinnAPIConfig.createFileTransferApi(ref(), ref())
        }
        bean<RetryTemplate> {
            RetryConfig.createRetryTemplate(ref())
        }
    }
}

@Profile("altinn")
@EnableConfigurationProperties(value = [AltinnServerConfig::class])
@Configuration
class AltinnPropertiesConfiguration {

    @ConfigurationProperties("altinn.api")
    @Bean
    fun altinnConfig(): AltinnConfig {
        return AltinnConfig()
    }

    @ConfigurationProperties("altinn.api.maskinporten")
    @Bean
    fun maskinportenConfig(): MaskinportenConfig {
        return MaskinportenConfig()
    }

    @ConfigurationProperties("altinn.retry")
    @Bean
    fun altinnRetryConfig(): AltinnRetryConfig {
        return AltinnRetryConfig()
    }

    @Bean
    fun altinnWebhooks(altinnServerConfig: AltinnServerConfig): AltinnWebhooks {
        return AltinnWebhooks(altinnServerConfig)
    }
}

@ConfigurationProperties("altinn")
data class AltinnServerConfig(
    val webhookSubscriptionDelay: String = "20s",
    val pollTransitInterval: String = "15s",
    var pollTransitEnabled: Boolean = true,
    val recipientId: String = "",
    val resourceId: String = "",
    val persistCloudEvent: Boolean = true,
    val persistAltinnFile: Boolean = true,
    val sendResponse: Boolean = true,
    var pollAltinnInterval: String = "15s",
    var api: AltinnConfig? = null,
    var webhookExternalUrl: String? = null,
    var webhooks: List<AltinnWebhook> = emptyList(),
    var startEvent: String?,
    var serviceownerOrgnumber: String? = null,
    var skipPollAndWebhook: Boolean = false
)

data class AltinnRetryConfig(
    var initialInterval: Int = 500,
    var maxInterval: Int = 10,
    var multiplier: Double = 2.0,
    var maxAttempts: Int = 10
)

class AltinnWebhooks(altinnServerConfig: AltinnServerConfig) : List<AltinnWebhook> by altinnServerConfig.webhooks {

    private val webhookExternalUri: String? by altinnServerConfig::webhookExternalUrl

    fun webhookEndpoint(webhook: AltinnWebhook): URI {
        return URI.create(
            webhookExternalUri!!.trim().trimEnd('/') + webhook.path!!.trim().let {
                if (it.startsWith('/')) it else "/$it"
            }
        )
    }
}

data class AltinnWebhook(
    var path: String? = null,
    var resourceFilter: String? = null,
    var subjectFilter: String? = null,
    var typeFilter: String? = null,
    var handler: String = "webhookHandler",
)
