package no.kartverket.altinn3.events.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.configuration.AltinnServerConfig
import no.kartverket.altinn3.models.FileTransferInitialize
import no.kartverket.altinn3.persistence.AltinnFil
import no.kartverket.altinn3.persistence.AltinnFilOverview
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.util.*

private const val ALTINN_ORG_NUMBER_PREFIX = "0192:"

@EnableConfigurationProperties(AltinnServerConfig::class)
class AltinnService(
    private val altinnBrokerClient: BrokerClient,
    private val config: AltinnServerConfig,
) {
    val logger = LoggerFactory.getLogger(javaClass)
    fun sendResponseTilInnsender(fileOverview: AltinnFilOverview, altinnFil: AltinnFil): UUID? {
        if (!config.sendResponse)
            return null.also { logger.debug("Send response to innsender disabled. Skipping...") }

        val innsender = requireNotNull(fileOverview.sender) { "Could not find innsender" }
        val propertyList = fileOverview.jsonPropertyList
            ?.let { jacksonObjectMapper().readValue<Map<Any, Any>>(it) }
            ?: mapOf()

        val fileInfo = FileTransferInitialize(
            resourceId = config.resourceId,
            sender = "$ALTINN_ORG_NUMBER_PREFIX${config.recipientId}",
            recipients = listOf(innsender),
            fileName = requireNotNull(fileOverview.fileName) { "File name must be specified" },
            sendersFileTransferReference = fileOverview.sendersReference.toString(),
            propertyList = propertyList
        )

        val initializedFile = altinnBrokerClient
            .initializeFileTransfer(
                fileInfo
            ).fileTransferId ?: error("Could not initialize file transfer ID")

        altinnBrokerClient.uploadFileToAltinn(
            fileTransferId = initializedFile,
            payload = altinnFil.payload
        )
        logger.info("Successfully uploaded file with transfer ID: {}", initializedFile)
        logger.debug("Initialized file: {}", initializedFile)
        return initializedFile
    }
}
