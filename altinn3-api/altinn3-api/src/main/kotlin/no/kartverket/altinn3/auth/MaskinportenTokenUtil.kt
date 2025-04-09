package no.kartverket.altinn3.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.util.Base64
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import no.kartverket.altinn3.config.MaskinportenConfig
import org.springframework.http.MediaType
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import java.time.Clock
import java.util.*

class MaskinportenTokenUtil(private val config: MaskinportenConfig) {

    private val restClient: RestClient =
        RestClient.builder()
            .baseUrl(config.tokenEndpoint!!)
            .defaultHeaders {
                it.contentType = MediaType.APPLICATION_FORM_URLENCODED
                it.accept = listOf(MediaType.APPLICATION_JSON)
            }
            .messageConverters {
                listOf(
                    MappingJackson2HttpMessageConverter(),
                    FormHttpMessageConverter())
            }
            .build()

    var accessToken: MaskinportenToken? = null
        @Synchronized
        get() {
            if(field?.isExpired != false){
                field = makeTokenRequest(makeJwt())
            }
            return field
        }
        private set

    private fun makeJwt(): String {

        val certChain = ArrayList<Base64>().apply {
            add(Base64.encode(config.signingCert!!.encoded))
        }
        val jwtHeader = JWSHeader.Builder(JWSAlgorithm.RS256)
                .x509CertChain(certChain)
                .build()

        val addScopesIfPresent: JWTClaimsSet.Builder.() -> JWTClaimsSet.Builder = {
            config.scopes
                .takeUnless { it.isEmpty() }
                ?.let { claim("scope", it) } ?: this
        }

        val claims: JWTClaimsSet = JWTClaimsSet.Builder()
            .audience(config.audience) //                .claim("resource", context.getResource())
            .claim("sub", config.issuer)
            .issuer(config.issuer)
            .run(addScopesIfPresent)
            .jwtID(UUID.randomUUID().toString()) // Must be unique for each grant
            .issueTime(Date(Clock.systemUTC().millis())) // Use UTC time!
            .expirationTime(Date(Clock.systemUTC().millis() + 120000)) // Expiration time is 120 sec.
            .build()

        val signer = RSASSASigner(config.signingKey)
        val signedJWT = SignedJWT(jwtHeader, claims)
        signedJWT.sign(signer)
        return signedJWT.serialize()
    }

    private fun makeTokenRequest(jwt: String): MaskinportenToken? {
        val body: MultiValueMap<String, String> = LinkedMultiValueMap(2)
        body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
        body.add("assertion", jwt)
        try {
            return restClient.post().body(body)
                .retrieve().body(MaskinportenToken::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

}