package no.kartverket.altinn3.events.server.configuration

import no.kartverket.altinn3.client.BrokerClient
import no.kartverket.altinn3.events.server.constants.CLOUDEVENTS_JSON
import no.kartverket.altinn3.events.server.handler.DefaultWebhookHandler
import no.kartverket.altinn3.events.server.handler.WebhookRequestHandler
import no.kartverket.altinn3.events.server.interfaces.WebhookHandler
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.support.beans
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

val webhookConfig = beans {
//    bean {
//        object : WebFluxConfigurer {
//            override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
//            }
//        }
//    }
    profile("!poll") {
        bean<DefaultWebhookHandler>("webhookHandler")
        bean<WebhooksRouterProvider>()
        bean<StateMachineWebhookAvailabilityStatus>()
        bean(::router)
    }
}

fun router(webhookRoutes: WebhooksRouterProvider): RouterFunction<ServerResponse> = webhookRoutes()

class WebhooksRouterProvider(
    private val altinn: AltinnServerConfig,
    private val context: ApplicationContext,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val brokerClient: BrokerClient,
    private val retryTemplate: RetryTemplate,
) : () -> RouterFunction<ServerResponse> {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun invoke(): RouterFunction<ServerResponse> = coRouter {
        altinn.webhooks.forEach {
            logger.debug("Setting up webhook: {}", it)
            val handler: WebhookHandler = context.getBean(it.handler, WebhookHandler::class.java)
            POST(
                "${it.path}",
                accept(CLOUDEVENTS_JSON),
                WebhookRequestHandler(
                    handler,
                    applicationEventPublisher,
                    brokerClient,
                    retryTemplate,
                    altinn.skipPollAndWebhook
                ),
            )
        }
    }
}
