package no.kartverket.altinn3.auth

import no.kartverket.altinn3.config.AltinnConfig
import no.kartverket.altinn3.config.AltinnEnvironment
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestInitializer

class AltinnAuthRequestInitializer private constructor(private val altinnAuth: AltinnAuth) :
    ClientHttpRequestInitializer {

    override fun initialize(request: ClientHttpRequest) {
        request.uri.path
        request.headers.setBearerAuth(altinnAuth.altinnToken!!.token)

    }

    companion object {
        @Synchronized
        fun instance(altinnConfig: AltinnConfig): AltinnAuthRequestInitializer {
            return instances.getOrPut(altinnConfig.environment!!) {
                val tokenUtil = when (altinnConfig.maskinporten?.cryptoKeyType?.lowercase()) {
                    "jwk" -> MaskinportenJwkTokenUtil(altinnConfig.maskinporten!!)
                    "x509" -> MaskinportenKeystoreTokenUtil(altinnConfig.maskinporten!!)
                    else -> error("Cryptographic key type not supported or specified")
                }
                AltinnAuthRequestInitializer(AltinnAuth(altinnConfig, tokenUtil))
            }
        }

        private val instances = mutableMapOf<AltinnEnvironment, AltinnAuthRequestInitializer>()
    }

}