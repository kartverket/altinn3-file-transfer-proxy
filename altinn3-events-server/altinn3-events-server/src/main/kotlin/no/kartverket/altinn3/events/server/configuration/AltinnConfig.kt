package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.auth.AltinnAuthRequestInitializer
import no.kartverket.altinn3.broker.apis.FileTransferApi
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.client.EventsClient
import no.kartverket.altinn3.config.AltinnConfig
import no.kartverket.altinn3.config.MaskinportenConfig
import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.events.server.handler.CloudEventHandler
import no.kartverket.altinn3.events.server.service.*
import no.kartverket.altinn3.helpers.UUIDHttpMessageConverter
import no.kartverket.altinn3.helpers.UnitHttpMessageConverter
import no.kartverket.altinn3.persistence.AltinnEventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.support.beans
import org.springframework.core.env.Environment
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.client.RestClient
import java.net.URI
import java.util.function.Consumer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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

object RetryConfig {
    private val logger = LoggerFactory.getLogger(RetryConfig::class.java)

    fun createRetryTemplate(altinnRetryConfig: AltinnRetryConfig): RetryTemplate {
        val initialInterval = altinnRetryConfig.initialInterval.milliseconds
        val maxInterval = altinnRetryConfig.maxInterval.minutes
        val multiplier = altinnRetryConfig.multiplier
        val maxAttempts = altinnRetryConfig.maxAttempts


        return RetryTemplate
            .builder()
            .maxAttempts(maxAttempts)
            .withListener(
                object : RetryListener {
                    override fun <T : Any?, E : Throwable?> onError(
                        context: RetryContext?,
                        callback: RetryCallback<T?, E?>?,
                        throwable: Throwable?
                    ) {
                        logger.warn("Got error, retrying for the ${context?.retryCount ?: 0} time")
                        logger.warn(throwable?.message ?: "")
                        super.onError(context, callback, throwable)
                    }
                }
            )
            .exponentialBackoff(
                initialInterval.inWholeMilliseconds,
                multiplier,
                maxInterval.inWholeMilliseconds
            )
            .build()
    }
}

object AltinnAPIConfig {
    val logger = LoggerFactory.getLogger(javaClass)

    private fun setAuthIfApplicable(restBuilder: RestClient.Builder, env: Environment, altinnConfig: AltinnConfig) =
        if (!(env.activeProfiles.contains("test"))) restBuilder.requestInitializer(
            AltinnAuthRequestInitializer.instance(altinnConfig)
        ).defaultHeaders {
            it.set("Ocp-Apim-Subscription-Key", altinnConfig.apiKey)
        } else restBuilder.also {
            logger.info("Test profile is active. Skipping auth on requests to Altinn")
        }

    fun createEventApi(
        altinnConfig: AltinnConfig,
        environment: Environment,
        requestInterceptors: Consumer<MutableList<ClientHttpRequestInterceptor>>? = null
    ): EventsApi {
        val client = RestClient.builder()
            .requestInterceptors(requestInterceptors ?: Consumer {})
            .baseUrl(altinnConfig.baseUrl("events"))
            .messageConverters { it.add(MappingJackson2HttpMessageConverter()) }

        return setAuthIfApplicable(client, environment, altinnConfig)
            .build().let {
                EventsApi(it)
            }
    }

    fun createSubscriptionApi(
        altinnConfig: AltinnConfig,
        environment: Environment,
        requestInterceptors: Consumer<MutableList<ClientHttpRequestInterceptor>>? = null
    ): SubscriptionApi {
        val client = RestClient.builder()
            .requestInterceptors(requestInterceptors ?: Consumer { })
            .baseUrl(altinnConfig.baseUrl("events"))
            .messageConverters { it.add(MappingJackson2HttpMessageConverter()) }

        return setAuthIfApplicable(client, environment, altinnConfig)
            .build().let {
                SubscriptionApi(it)
            }
    }

    fun createFileTransferApi(
        altinnConfig: AltinnConfig,
        environment: Environment,
        requestInterceptors: Consumer<MutableList<ClientHttpRequestInterceptor>>? = null
    ): FileTransferApi {
        val client = RestClient.builder()
            .requestInterceptors(requestInterceptors ?: Consumer {})
            .baseUrl(altinnConfig.baseUrl())
            .messageConverters {
                it.apply {
                    add(MappingJackson2HttpMessageConverter())
                    add(FormHttpMessageConverter())
                    add(UUIDHttpMessageConverter())
                    add(UnitHttpMessageConverter())
                }
            }

        return setAuthIfApplicable(client, environment, altinnConfig)
            .build().let {
                FileTransferApi(it)
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
    var serviceownerOrgnumber: String? = null
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
