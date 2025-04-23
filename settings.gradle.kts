pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "altinn3-proxy"

include("altinn3-api")
include("altinn3-persistence")
include("altinn3-events-server")