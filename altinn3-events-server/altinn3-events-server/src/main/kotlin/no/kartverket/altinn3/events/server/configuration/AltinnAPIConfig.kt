package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.auth.AltinnAuthRequestInitializer
import no.kartverket.altinn3.broker.apis.FileTransferApi
import no.kartverket.altinn3.config.AltinnConfig
import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.helpers.UUIDHttpMessageConverter
import no.kartverket.altinn3.helpers.UnitHttpMessageConverter
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.util.function.Consumer

object AltinnAPIConfig {
    private val logger = LoggerFactory.getLogger(javaClass)

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
