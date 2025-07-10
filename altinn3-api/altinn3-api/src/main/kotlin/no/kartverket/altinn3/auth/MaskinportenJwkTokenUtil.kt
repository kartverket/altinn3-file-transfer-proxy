package no.kartverket.altinn3.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import no.kartverket.altinn3.config.MaskinportenConfig
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.nio.file.Files
import java.time.Clock
import java.util.*
import kotlin.io.path.Path
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val SYSTEM_USER_URN = "urn:altinn:systemuser"
private const val GRANT_TYPE_URN = "urn:ietf:params:oauth:grant-type:jwt-bearer"
private val TOKEN_EXPIRATION = 120.toDuration(DurationUnit.SECONDS)

class MaskinportenJwkTokenUtil(private val config: MaskinportenConfig) : MaskinportenTokenUtil {
    private val logger = LoggerFactory.getLogger(this.javaClass)
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
                    FormHttpMessageConverter()
                )
            }
            .build()

    override var accessToken: MaskinportenToken? = null
        @Synchronized
        get() {
            if (field?.isExpired != false) {
                field = makeTokenRequest(makeJwt())
            }
            return field
        }
        private set

    private fun makeJwt(): String {
        val path = requireNotNull(config.clientKeystoreFilePath) { "Keystore path not set" }
        val jwk = Files.readString(Path(path)).let { json -> JWK.parse(json) }

        val jwtHeader = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(jwk.keyID)
            .build()

        val addScopesIfPresent: JWTClaimsSet.Builder.() -> JWTClaimsSet.Builder = {
            config.scopes
                .takeUnless { it.isEmpty() }
                ?.let { claim("scope", it) } ?: this
        }

        val authorizationDetails = makeAuthDetails(
            requireNotNull(config.authority) { "Authority is required" },
            requireNotNull(config.systemUserOrgNumber) { "Org number is required" }
        )

        val tokenExpiresAt = Date(
            Clock.systemUTC()
                .millis() + TOKEN_EXPIRATION.inWholeMilliseconds
        )

        val claims: JWTClaimsSet = JWTClaimsSet.Builder()
            .claim("authorization_details", authorizationDetails)
            .audience(config.audience)
            .issuer(config.clientId)
            .run(addScopesIfPresent)
            .jwtID(UUID.randomUUID().toString()) // Must be unique for each grant
            .issueTime(Date(Clock.systemUTC().millis())) // Use UTC time!
            .expirationTime(tokenExpiresAt)
            .build()

        logger.debug("Using claims for auth: ${claims.toString(false)}")

        val signedJWT = SignedJWT(jwtHeader, claims)
        val signer = RSASSASigner(jwk.toRSAKey())
        signedJWT.sign(signer)
        return signedJWT.serialize()
    }

    /**
     * @param orgNumber Added to `systemuser_org` in auth details.
     * This is the organization that has created a connection between
     * the system user and the system in Altinn System Register
     * @param authority authority
     * @see <a href=https://docs.altinn.studio/nb/authentication/what-do-you-get/systemuser/">Systembruker - Altinn</a>
     * */
    private fun makeAuthDetails(authority: String, orgNumber: String): List<Map<String, Any>> = listOf(
        mapOf(
            "type" to SYSTEM_USER_URN,
            "systemuser_org" to mapOf(
                "authority" to authority,
                "ID" to orgNumber
            )
        )
    )

    private fun makeTokenRequest(jwt: String): MaskinportenToken {
        val body: MultiValueMap<String, String> = LinkedMultiValueMap(2)
        body.add("grant_type", GRANT_TYPE_URN)
        body.add("assertion", jwt)
        return try {
            restClient.post().body(body)
                .retrieve().body(MaskinportenToken::class.java)
        } catch (e: RestClientException) {
            throw IllegalStateException("Could not create Maskinporten token", e)
        }
    }
}