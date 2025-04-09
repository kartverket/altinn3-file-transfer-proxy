import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.openapi.gen)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt.kotlin.analyzer)
}

dependencies {
    implementation(project(":altinn3-persistence"))
    implementation(project(":altinn3-api"))
    implementation(libs.spring.boot.statemachine)
    implementation(libs.bundles.kotlin)
    implementation(libs.bundles.jackson)
    implementation(libs.bundles.coroutines)
    implementation(libs.spring.boot.webflux)
    implementation(libs.spring.boot.actuator)
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
    outputDir.set(layout.buildDirectory.dir(name).map { it.toString() })
    generatorName.set("kotlin-spring")
    inputSpec.set("$rootDir/specs/altinn-broker-v1.json")
//    apiFilesConstrainedTo.addAll("Default")
//    modelFilesConstrainedTo.addAll("")
//    supportingFilesConstrainedTo.addAll("")

    packageName.set("no.kartverket.altinn3.webhooks")
    skipValidateSpec.set(true)
    removeOperationIdPrefix.set(true)
    configOptions.set(
        mapOf(
            //        "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "useSpringBoot3" to "true",
            "useSwaggerUI" to "false",
            "reactive" to "true",
            "serviceImplementation" to "true",
            "mapFileBinaryToByteArray" to "true",
            "moshiCodeGen" to "true",
            "library" to "spring-boot"
            //"serializationLibrary" to "jackson"
        )
    )
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

tasks.named<BootBuildImage>("bootBuildImage") {
    imageName = "kartverket.no/${project.name}:${project.version}"
    createdDate = "now"
    environment.putAll(
        mapOf(
            "BP_NATIVE_IMAGE" to "false",
            "BP_JVM_VERSION" to "21",
            "BPE_DELIM_JAVA_TOOL_OPTIONS" to " ",
        )
    )
    if (!System.getenv("IMAGE_URL").isNullOrEmpty()) {
        imageName = System.getenv("IMAGE_URL")
        tags = setOf(imageName.get().replace(Regex(":.*"), ":latest"))
        publish = true
        docker {
            publishRegistry {
                url = System.getenv("REGISTRY_URL")
                username = System.getenv("DOCKER_USERNAME")
                password = System.getenv("DOCKER_PASSWORD")
            }
        }
    }
}

detekt {
    config.setFrom("../detekt-config.yaml")
    buildUponDefaultConfig = true
}