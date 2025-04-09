package no.kartverket.altinn3.config

data class AltinnConfig(
    var environment: AltinnEnvironment? = null,
    var apiKey:String? = null,
    var url:String? = null, //to override environment url

    var maskinporten: MaskinportenConfig? = null
) {
    fun baseUrl(api:String) = "${url ?: environment?.url ?: "null"}/$api/api/v1"
    fun baseUrl() = url ?: environment?.url ?: "null"
}