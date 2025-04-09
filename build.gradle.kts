import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.versions)
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "no.kartverket"
    version = "1.0-SNAPSHOT"
    repositories {
        mavenLocal()
        mavenCentral()
    }

    this.afterEvaluate {
        if (plugins.hasPlugin(rootProject.libs.plugins.kotlin.jvm.get().pluginId)) {
            apply {
                plugin("maven-publish")
            }

            val kotlin = this.extensions.getByType<KotlinJvmProjectExtension>()
            kotlin.apply {
                jvmToolchain(21)
            }

            val sourcesJar = tasks.register("sourcesJar", Jar::class) {
                archiveClassifier.set("sources")
                from(kotlin.sourceSets.getByName("main").kotlin)
            }

            extensions.configure<PublishingExtension> {
                publications {
                    println(components)
                    create<MavenPublication>("kotlin") {
                        from(components["java"])
                        artifact(sourcesJar)
                    }
                }
            }
        }
    }
}
