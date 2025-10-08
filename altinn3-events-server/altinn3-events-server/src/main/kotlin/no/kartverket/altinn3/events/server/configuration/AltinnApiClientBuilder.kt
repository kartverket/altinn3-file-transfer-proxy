package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.broker.apis.FileTransferApi
import no.kartverket.altinn3.config.AltinnConfig
import no.kartverket.altinn3.events.apis.EventsApi
import no.kartverket.altinn3.events.apis.SubscriptionApi
import no.kartverket.altinn3.events.server.utils.AuthConfigurationUtils.configureAuthAndHeaders
import no.kartverket.altinn3.helpers.UUIDHttpMessageConverter
import no.kartverket.altinn3.helpers.UnitHttpMessageConverter
import org.springframework.core.env.Environment
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.util.function.Consumer

object AltinnApiClientBuilder {

    fun createEventApi(
        altinnConfig: AltinnConfig,
        environment: Environment,
        requestInterceptors: Consumer<MutableList<ClientHttpRequestInterceptor>>? = null
    ): EventsApi {
        val client = RestClient.builder()
            .requestInterceptors(requestInterceptors ?: Consumer {})
            .baseUrl(altinnConfig.baseUrl("events"))
            .messageConverters { it.add(MappingJackson2HttpMessageConverter()) }
            .let { configureAuthAndHeaders(it, environment, altinnConfig) }

        return client
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
            .let { configureAuthAndHeaders(it, environment, altinnConfig) }

        return client
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
            .let { configureAuthAndHeaders(it, environment, altinnConfig) }

        return client
            .build().let {
                FileTransferApi(it)
            }
    }
}
