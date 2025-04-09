package no.kartverket.altinn3.config

import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

data class MaskinportenConfig(
    var issuer: String? = null,
    var audience: String? = null,
    var tokenEndpoint: String? = null,
    var scopeList: List<String>? = null,
    var clientKeystoreType: String? = null,
    var clientKeystoreAlias: String? = null,
    var clientKeystorePassword: String? = null,
    var clientKeystoreFilePath: String? = null
) {
    val scopes: String
        get() = scopeList?.joinToString(" ") ?: ""

    val signingCert: X509Certificate?
        get() = keyStore.value.getCertificate(clientKeystoreAlias) as X509Certificate?

    val signingKey:PrivateKey?
        get() = keyStore.value.getKey(clientKeystoreAlias, clientKeystorePassword!!.toCharArray()) as PrivateKey?

    private val keyStore: Lazy<KeyStore> = lazy {
        check(clientKeystoreFilePath != null) { "clientKeystoreFilePath kan ikke være null" }
        KeyStore.getInstance(clientKeystoreType).apply {
            load(
                FileInputStream(clientKeystoreFilePath!!),
                clientKeystorePassword!!.toCharArray()
            )
        }
    }
}
