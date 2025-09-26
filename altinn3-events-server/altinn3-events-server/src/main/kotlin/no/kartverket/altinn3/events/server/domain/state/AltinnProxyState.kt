package no.kartverket.altinn3.events.server.domain.state

sealed class State {
    object Initial : State()
    object StartupRecovery : State()
    object Error : State()
    object Synchronize : State()
    object Poll : State()
    object SetupWebhook : State()
    object PollAndWebhook : State()
    object Webhook : State()
    object Stop : State()

    override fun toString() = this::class.java.simpleName
}
