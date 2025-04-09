plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt.kotlin.analyzer)
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}

tasks.bootJar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    systemProperties.putAll(gradle.startParameter.systemPropertiesArgs)
}

dependencies {
    implementation(libs.bundles.kotlin)
    runtimeOnly(libs.logstash)
    implementation(libs.bundles.jdbc)
    runtimeOnly(libs.bundles.flyway)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlintest.runner.junit)
    testImplementation(libs.spring.boot.test)
    {
        exclude(module = "mockito-core")
    }

    detektPlugins(libs.detekt.klint)
}

detekt {
    config.setFrom("../detekt-config.yaml")
    buildUponDefaultConfig = true
}