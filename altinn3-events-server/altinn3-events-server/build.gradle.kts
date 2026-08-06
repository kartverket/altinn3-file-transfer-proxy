import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.openapi.gen)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt.kotlin.analyzer)
    alias(libs.plugins.digibok.container.image)
    alias(libs.plugins.digibok.github.publish)
}

dependencies {
    implementation(project(":altinn3-persistence"))
    implementation(project(":altinn3-api"))
    implementation(libs.micrometer.prometheus)
    implementation(libs.spring.retry)
    implementation(libs.bundles.kotlin)
    implementation(libs.bundles.jackson)
    implementation(libs.bundles.coroutines)
    implementation(libs.spring.boot.webflux)
    implementation(libs.spring.boot.actuator)
    implementation(libs.bouncycastle)
    runtimeOnly(libs.logstash)
    implementation(libs.bundles.jdbc)
    runtimeOnly(libs.bundles.flyway)

    testImplementation(libs.bundles.kotlin.test)
    testImplementation(libs.spring.boot.test)
    {
        exclude(module = "mockito-core")
    }
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.bundles.mocking)
    testImplementation(libs.spring.test.client)
    testImplementation(libs.spring.boot.test.webtestclient)

    detektPlugins(libs.detekt.klint)
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperties.putAll(gradle.startParameter.systemPropertiesArgs)
}

tasks.register("openApiAltinnBrokerWebhooksGen", GenerateTask::class) {
    group = "openapi tools"
    outputDir.set(layout.buildDirectory.dir(name).get().asFile)
    generatorName.set("kotlin-spring")
    inputSpec.set("$rootDir/specs/altinn-broker-v1.json")

    packageName.set("no.kartverket.altinn3.webhooks")
    skipValidateSpec.set(true)
    removeOperationIdPrefix.set(true)
    configOptions.set(
        mapOf(
            "documentationProvider" to "none",
            "useSpringBoot4" to "true",
            "useSpringBoot3" to "false",
            "useSwaggerUI" to "false",
            "reactive" to "true",
            "serviceImplementation" to "true",
            "mapFileBinaryToByteArray" to "true",
            "moshiCodeGen" to "true",
            "library" to "spring-boot"
        )
    )
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

githubPublish {
    imageName.set(project.name)
}

containerImage {
    additionalTags.set(listOf("latest"))
}

detekt {
    config.setFrom("../detekt-config.yaml")
    buildUponDefaultConfig = true
}