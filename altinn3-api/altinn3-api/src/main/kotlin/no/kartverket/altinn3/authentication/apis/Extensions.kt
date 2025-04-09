package no.kartverket.altinn3.authentication.apis

import org.springframework.web.client.RestClientResponseException

@Throws(RestClientResponseException::class)
fun AuthenticationApi.exchangeTokenProviderGetAsStringResponse(test: Boolean?): String {
    val request = this.exchangeTokenProviderGetRequestConfig("maskinporten", test)
    val response = this.request<Unit, String>(request)
    return response.body!!
}