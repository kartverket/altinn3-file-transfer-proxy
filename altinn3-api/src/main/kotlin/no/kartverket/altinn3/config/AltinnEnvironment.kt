package no.kartverket.altinn3.config
enum class AltinnEnvironment(val id:String, val test:Boolean, val url:String) {
    TT02("TT02", true, "https://platform.tt02.altinn.no"),
    PRODUCTION("Production", false, "https://platform.altinn.no")
}
