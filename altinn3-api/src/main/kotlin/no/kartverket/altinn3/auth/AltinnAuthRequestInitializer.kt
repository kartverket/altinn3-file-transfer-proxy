package no.kartverket.altinn3.auth

import no.kartverket.altinn3.config.AltinnConfig
import no.kartverket.altinn3.config.AltinnEnvironment
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestInitializer

class AltinnAuthRequestInitializer private constructor(private val altinnAuth: AltinnAuth)  : ClientHttpRequestInitializer {

    override fun initialize(request: ClientHttpRequest) {
        request.uri.path
        request.headers.setBearerAuth(altinnAuth.altinnToken!!.token)

    }

    companion object {
        @Synchronized
        fun instance(altinnConfig: AltinnConfig): AltinnAuthRequestInitializer {
            return instances.getOrPut(altinnConfig.environment!!) {
                AltinnAuthRequestInitializer(AltinnAuth(altinnConfig))
            }
        }
        private val instances = mutableMapOf<AltinnEnvironment, AltinnAuthRequestInitializer>()
    }

}