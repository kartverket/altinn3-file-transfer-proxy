package no.kartverket.altinn3.events.server.utils

import no.kartverket.altinn3.auth.AltinnAuthRequestInitializer
import no.kartverket.altinn3.config.AltinnConfig
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient

object AuthConfigurationUtils {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun configureAuthAndHeaders(
        restBuilder: RestClient.Builder,
        env: Environment,
        altinnConfig: AltinnConfig
    ): RestClient.Builder =
        when {
            isTestProfile(env) -> configureTestClient(restBuilder)
            else -> configureProductionClient(restBuilder, altinnConfig)
        }

    private fun isTestProfile(env: Environment): Boolean =
        env.activeProfiles.contains("test")

    private fun configureTestClient(restBuilder: RestClient.Builder): RestClient.Builder =
        restBuilder.also {
            logger.info("Test profile is active. Skipping auth on requests to Altinn")
        }

    private fun configureProductionClient(
        restBuilder: RestClient.Builder,
        altinnConfig: AltinnConfig
    ): RestClient.Builder =
        restBuilder.requestInitializer(
            AltinnAuthRequestInitializer.instance(altinnConfig)
        ).defaultHeaders {
            it.set(
                "Ocp-Apim-Subscription-Key",
                altinnConfig.apiKey
            )
        }
}