package no.kartverket.altinn3.auth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MaskinportenToken(
    @JsonProperty("access_token") override val token:String? = null,
) : SignedJwtToken()