package no.kartverket.altinn3.events.server.service

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.events.server.configuration.Scopes
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.support.beans
import kotlin.time.Duration

val transitConfig = beans {
    bean<TransitPoller>()
    bean<AltinnService>()
}

@EnableConfigurationProperties(AltinnServerConfig::class)
class TransitPoller(
    private val config: AltinnServerConfig,
    private val altinnTransitService: AltinnTransitService,
    private val handler: AltinnService
) : ApplicationListener<ApplicationReadyEvent> {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun poll() = coroutineScope {
        val pollingInterval = Duration.parse(config.pollTransitInterval)
        while (config.pollTransitEnabled) {
            val utgaendeFiler = altinnTransitService.findNewUtgaendeFiler()

            if (utgaendeFiler.isNotEmpty()) {
                logger.info("Found ${utgaendeFiler.size} outgoing transfers...")

                utgaendeFiler.forEach {
                    val (overview, file) = it
                    handler.sendResponseTilInnsender(overview, file).let { fileTransferId ->
                        val overviewWithIdFromAltinn = overview.copy(
                            fileTransferId = fileTransferId,
                        )
                        altinnTransitService.completeFileTransfer(overviewWithIdFromAltinn)
                    }
                }
            } else {
                logger.debug("No new utgaender found.. waiting")
            }
            delay(pollingInterval)
        }
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (config.pollTransitEnabled) {
            Scopes.transitScope.launch {
                poll()
            }
        } else {
            logger.info("Started without polling for outing files")
        }
    }
}
