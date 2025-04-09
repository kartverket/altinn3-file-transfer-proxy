import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.openapi.gen)
}

dependencies {
    implementation(libs.bundles.jackson)
    implementation(libs.spring.security.oauth2.jose)
    implementation(libs.slf4j)

    // TODO: knyttet til BrokerExtension:customizedUploadSingleFileRequestConfig, fjern dersom nevnt fun fjernes
    // Avhengighet knyttet til MultipartBodyBuilder
//    implementation("org.reactivestreams:reactive-streams:1.0.4")
    // /

    testImplementation(libs.kotlintest.runner.junit)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.logback)
}

val sourcesJar = tasks.creating(Jar::class) {
//    val sourceSets = this.extensions.getByType<KotlinJvmProjectExtension>().sourceSets
    archiveClassifier.set("sources")
    sourceSets.getByName("main").allSource
    from(sourceSets.getByName("main").kotlin)
}


enum class AltinnApi(val spec: String, val config: Action<GenerateTask> = Action {}) {
    authentication("altinn-platform-authentication-v1.json"),
    events("altinn-platform-events-v1.json"),
    broker("altinn-broker-v1.json"),
    resource("altinn-resource-registry-v1.json"),
    ;

    fun genClientTaskName(): String {
        return "${this}AltinnClient"
    }

}

val genAltinnApiTaskProviders = AltinnApi.values().map {
    tasks.register(it.genClientTaskName(), GenerateTask::class, defaultAltinnApiConfig(it)).apply {
        configure(it.config)
    }
}

fun defaultAltinnApiConfig(api: AltinnApi): Action<GenerateTask> = Action {
    group = "openapi tools"

    generatorName.set("kotlin")
    inputSpec.set("$rootDir/specs/${api.spec}")
    outputDir.set(layout.buildDirectory.dir(api.genClientTaskName()).map { it.toString() })

    packageName.set("no.kartverket.altinn3.$api")
    modelPackage.set("no.kartverket.altinn3.models")
    skipValidateSpec.set(true)
    removeOperationIdPrefix.set(true)


    //Modeller kotlin generatoren ikke takler så godt, så vi lager dem manuelt(se fil):
    ignoreFileOverride.set("${rootProject.projectDir.path}/specs/openapi-generator-ignore")
    configOptions.set(
        mapOf(
            "library" to "jvm-spring-restclient",
            "serializationLibrary" to "jackson",
            "useSpringBoot3" to "true",
            "omitGradleWrapper" to "true",
        )
    )

    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

val codeGen = tasks.register("openApiAltinnGen", Sync::class) {
    group = "openapi tools"

    with(copySpec {
        from(genAltinnApiTaskProviders) {
            include("src/main/**")
        }
    })

    // Egen håndtering av ApiClient:request i den genererte klienten slik at denne bli tilgjengelig i BrokerExtension.kt
    with(copySpec {
        from(genAltinnApiTaskProviders) {
            include("src/main/**/ApiClient.kt")
        }
        filter { line ->
            line.replace(Regex("^(\\s*)(protected)(.*request\\(.*)"), "$1internal$3")
        }
    })
    into(layout.buildDirectory.dir(this.name))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDirs(codeGen.map { "${it.destinationDir}/src/main/kotlin" })
        }
    }
}

tasks.withType(JavaCompile::class) {
    dependsOn(codeGen)
}
