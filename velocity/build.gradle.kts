import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Jar
import xyz.jpenilla.runvelocity.task.RunVelocity
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.bundler)
    alias(libs.plugins.run.velocity)
}

val externalApiTestDirectory = layout.buildDirectory.dir("velocity-external-api-integration")
val externalApiFixtureJar = project(":kansokusha-velocity-test-plugin")
    .tasks.named<Jar>("jar")
val velocityShadowJar = tasks.named<ShadowJar>("shadowJar")

jcommon {
    setupPaperRepository()
}

dependencies {
    implementation(projects.kansokushaCommon)
    compileOnly(libs.velocity)
    testImplementation(libs.velocity)
}

bundler {
    copyToRootBuildDirectory("Kansokusha-Velocity-${project.version}")
    replacePluginVersionForVelocity(project.version)
}

tasks {
    named<ShadowJar>("shadowJar") {
        doLast {
            ZipFile(archiveFile.get().asFile).use { jar ->
                check(jar.getEntry("org/duckdb/DuckDBDriver.class") != null) {
                    "DuckDB JDBC driver is missing from the Velocity artifact."
                }
            }
        }
    }

    runVelocity {
        velocityVersion(libs.versions.velocity.get())
    }

    val velocityExternalApiIntegrationTest = register<RunVelocity>(
        "velocityExternalApiIntegrationTest"
    ) {
        group = "verification"
        description = "Run the external Velocity API fixture against a real proxy."

        dependsOn(velocityShadowJar, externalApiFixtureJar)
        velocityVersion(libs.versions.velocity.get())
        runDirectory(externalApiTestDirectory.get().asFile)
        pluginJars(
            velocityShadowJar.flatMap { it.archiveFile },
            externalApiFixtureJar.flatMap { it.archiveFile }
        )
        systemProperty(
            "kansokusha.external-velocity-api-fixture.result",
            externalApiTestDirectory.get().file("fixture-result.txt").asFile.absolutePath
        )

        doFirst {
            val runDirectory = externalApiTestDirectory.get().asFile
            project.delete(runDirectory)

            val config = runDirectory.toPath()
                .resolve("plugins")
                .resolve("kansokusha")
                .resolve("config.yml")
            Files.createDirectories(config.parent)
            Files.writeString(
                config,
                """
                ingestion:
                  queue-capacity: 4
                  max-batch-size: 4
                  max-batch-delay: PT1H
                retention:
                  policies:
                    - key: example:default
                      duration: P1D
                  fallback-policy: example:default
                  cleanup-interval: PT1H
                  max-rows-per-pass: 100
                """.trimIndent()
            )
        }

        doLast {
            val result = externalApiTestDirectory.get().file("fixture-result.txt").asFile
            check(result.isFile) {
                "External Velocity API fixture did not produce a result."
            }
            check(result.readText() == "success") {
                "External Velocity API fixture failed:\n" + result.readText()
            }
        }
    }

    named("check") {
        dependsOn(velocityExternalApiIntegrationTest)
    }
}
