package no.kartverket.altinn3.client

import no.kartverket.altinn3.auth.AltinnAuthRequestInitializer
import no.kartverket.altinn3.config.AltinnConfig
import no.kartverket.altinn3.resource.apis.AccessListApi
import no.kartverket.altinn3.resource.apis.Altinn2ExportApi
import no.kartverket.altinn3.resource.apis.ResourceApi
import no.kartverket.altinn3.resource.apis.ResourceOwnerApi
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.util.function.Consumer

class ResourceRegistryClient(altinnConfig: AltinnConfig, requestInterceptors: Consumer<MutableList<ClientHttpRequestInterceptor>> = Consumer{}) {

    val accessList: AccessListApi
    val altinn2Export: Altinn2ExportApi
    val resource: ResourceApi
    val resourceOwner: ResourceOwnerApi

    init {
        val client = RestClient.builder()
            .requestInterceptors(requestInterceptors)
            .baseUrl(altinnConfig.baseUrl("resourceregistry"))
            .messageConverters { it.add(MappingJackson2HttpMessageConverter()) }
            .requestInitializer(AltinnAuthRequestInitializer.instance(altinnConfig))
            .build()
        accessList = AccessListApi(client)
        altinn2Export = Altinn2ExportApi(client)
        resource = ResourceApi(client)
        resourceOwner = ResourceOwnerApi(client)
    }

}

