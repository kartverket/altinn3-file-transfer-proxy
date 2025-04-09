package no.kartverket.altinn3.events.server.configuration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean

class Scopes {
    companion object {
        val altinnScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transitScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    class Shutdown : DisposableBean {
        val logger = LoggerFactory.getLogger(javaClass)
        override fun destroy() {
            logger.info("SHUTTING DOWN")
            logger.info("Stopping eventScope")
            altinnScope.cancel("Shutdown")
            logger.info("Stopping transitScope")
            transitScope.cancel("Shutdown")
        }
    }
}
