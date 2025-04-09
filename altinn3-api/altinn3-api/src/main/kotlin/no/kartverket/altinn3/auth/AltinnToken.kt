package no.kartverket.altinn3.auth

data class AltinnToken(override val token: String) : SignedJwtToken()