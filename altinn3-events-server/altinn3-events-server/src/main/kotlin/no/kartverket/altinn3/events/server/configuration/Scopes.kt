package no.kartverket.altinn3.events.server.configuration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean

class Scopes {
    companion object {
        val altinnProxyScope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Default)
    }

    class Shutdown : DisposableBean {
        val logger = LoggerFactory.getLogger(javaClass)

        override fun destroy() {
            logger.info("SHUTTING DOWN")
            logger.info("Stopping eventScope")
            altinnProxyScope.cancel("Shutdown")
        }
    }
}
