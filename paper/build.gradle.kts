import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Jar
import xyz.jpenilla.runpaper.task.RunServer
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.bundler)
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.server)
}

val minecraftVersion = libs.versions.paper.get().replaceAfter(".build", "").removeSuffix(".build")
val externalApiTestDirectory = layout.buildDirectory.dir("paper-external-api-integration")
val externalApiFixtureJar = project(":kansokusha-paper-test-plugin")
    .tasks.named<Jar>("jar")
val paperShadowJar = tasks.named<ShadowJar>("shadowJar")

dependencies {
    implementation(projects.kansokushaCommon)

    compileOnlyApi(libs.paper)
    testImplementation(libs.paper)

    paperweight.paperDevBundle(libs.versions.paper.get())
}

bundler {
    copyToRootBuildDirectory("Kansokusha-Paper-${project.version}")
    replacePluginVersionForPaper(project.version)
}

tasks {
    named<ShadowJar>("shadowJar") {
        doLast {
            ZipFile(archiveFile.get().asFile).use { jar ->
                check(jar.getEntry("org/duckdb/DuckDBDriver.class") != null) {
                    "DuckDB JDBC driver is missing from the Paper artifact."
                }
            }
        }
    }

    runServer {
        minecraftVersion(minecraftVersion)
        systemProperty("com.mojang.eula.agree", "true")
        systemProperty("paper.disable-plugin-rewriting", "true")
    }

    val paperExternalApiIntegrationTest = register<RunServer>("paperExternalApiIntegrationTest") {
        group = "verification"
        description = "Run the external Paper API fixture against a real Paper server."

        dependsOn(paperShadowJar, externalApiFixtureJar)
        minecraftVersion(minecraftVersion)
        runDirectory(externalApiTestDirectory.get().asFile)
        pluginJars(
            paperShadowJar.flatMap { it.archiveFile },
            externalApiFixtureJar.flatMap { it.archiveFile }
        )

        systemProperty("com.mojang.eula.agree", "true")
        systemProperty("paper.disable-plugin-rewriting", "true")
        systemProperty(
            "kansokusha.external-api-fixture.result",
            externalApiTestDirectory.get().file("fixture-result.txt").asFile.absolutePath
        )

        doFirst {
            val runDirectory = externalApiTestDirectory.get().asFile
            project.delete(runDirectory)

            val config = runDirectory.toPath()
                .resolve("plugins")
                .resolve("Kansokusha")
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
                "External Paper API fixture did not produce a result."
            }
            check(result.readText() == "success") {
                "External Paper API fixture failed:\n" + result.readText()
            }
        }
    }

    named("check") {
        dependsOn(paperExternalApiIntegrationTest)
    }
}
