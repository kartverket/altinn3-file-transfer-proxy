pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/kartverket/digibok-tools")
            credentials {
                username = "altinn3-proxy"
                password = providers.gradleProperty("TOKEN").orElse(providers.environmentVariable("TOKEN")).get()
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "altinn3-proxy"

include("altinn3-api")
include("altinn3-persistence")
include("altinn3-events-server")