package no.kartverket.altinn3.events.server.configuration

import org.slf4j.LoggerFactory
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.support.RetryTemplate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

object RetryConfig {
    private val logger = LoggerFactory.getLogger(RetryConfig::class.java)

    fun createRetryTemplate(
        altinnRetryConfig: AltinnRetryConfig,
    ): RetryTemplate {
        val initialInterval = altinnRetryConfig.initialInterval.milliseconds
        val maxInterval = altinnRetryConfig.maxInterval.minutes
        val multiplier = altinnRetryConfig.multiplier
        val maxAttempts = altinnRetryConfig.maxAttempts

        return RetryTemplate
            .builder()
            .maxAttempts(maxAttempts)
            .withListener(
                object : RetryListener {
                    override fun <T : Any?, E : Throwable?> onError(
                        context: RetryContext?,
                        callback: RetryCallback<T?, E?>?,
                        throwable: Throwable?
                    ) {
                        logger.warn("Got error, retrying for the ${context?.retryCount ?: 0} time")
                        logger.warn(throwable?.message ?: "")
                        super.onError(context, callback, throwable)
                    }
                }
            )
            .exponentialBackoff(
                initialInterval.inWholeMilliseconds,
                multiplier,
                maxInterval.inWholeMilliseconds
            )
            .build()
    }
}
