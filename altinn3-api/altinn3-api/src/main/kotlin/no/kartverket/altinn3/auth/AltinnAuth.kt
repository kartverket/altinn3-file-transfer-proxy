package no.kartverket.altinn3.auth

import no.kartverket.altinn3.authentication.apis.AuthenticationApi
import no.kartverket.altinn3.authentication.apis.exchangeTokenProviderGetAsStringResponse
import no.kartverket.altinn3.config.AltinnConfig
import org.springframework.web.client.RestClient

class AltinnAuth(
    val altinnConfig: AltinnConfig,
    private val maskinporten: MaskinportenTokenUtil
) {

    private val api = AuthenticationApi(
        RestClient.builder()
            .baseUrl(altinnConfig.baseUrl("authentication"))
            .requestInitializer {
                it.headers.setBearerAuth(maskinporten.accessToken!!.token!!)
            }
            .build()
    )

    var altinnToken: AltinnToken? = null
        @Synchronized
        get() {
            if (field?.isExpired != false) {
                field =
                    AltinnToken(api.exchangeTokenProviderGetAsStringResponse(altinnConfig.environment!!.test).trim('"'))
            }
            return field
        }
        private set

}

