package no.kartverket.altinn3.auth

import com.nimbusds.jwt.SignedJWT
import java.util.*

abstract class SignedJwtToken {

    abstract val token:String?

    val jwt = lazy { SignedJWT.parse(token) }
    val isExpired:Boolean
        get() =jwt.value.jwtClaimsSet.expirationTime.before(Date(System.currentTimeMillis() - 120000))
}