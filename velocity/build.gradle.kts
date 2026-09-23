import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Jar
import xyz.jpenilla.runvelocity.task.RunVelocity
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Properties

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
                      duration: P3650D
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

            val database = externalApiTestDirectory.get()
                .file("plugins/kansokusha/kansokusha.duckdb")
                .asFile
            check(database.isFile) {
                "Kansokusha did not create its instance-local DuckDB file."
            }

            val packagedJar = velocityShadowJar.get().archiveFile.get().asFile
            URLClassLoader(
                arrayOf(packagedJar.toURI().toURL()),
                ClassLoader.getPlatformClassLoader()
            ).use { loader ->
                val driver = loader
                    .loadClass("org.duckdb.DuckDBDriver")
                    .getDeclaredConstructor()
                    .newInstance() as java.sql.Driver
                driver.connect(
                    "jdbc:duckdb:" + database.absolutePath,
                    Properties()
                ).use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            count(*) AS event_count,
                            min(pg.generation) AS generation,
                            min(s.server_key) AS server_key,
                            min(hex(e.payload)) AS payload_hex
                        FROM events e
                        JOIN payload_generations pg ON pg.id = e.payload_generation_id
                        JOIN event_types et ON et.id = pg.event_type_id
                        JOIN servers s ON s.id = e.server_id
                        WHERE et.event_type_key = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, "fixture:custom_event")
                        statement.executeQuery().use { rows ->
                            check(rows.next()) {
                                "Packaged Velocity smoke query returned no aggregate row."
                            }
                            check(rows.getInt("event_count") == 1) {
                                "Expected one flushed fixture event, found " +
                                    rows.getInt("event_count") + "."
                            }
                            check(rows.getInt("generation") == 1) {
                                "Persisted fixture generation did not match."
                            }
                            check(rows.getString("server_key") == "fixture:backend") {
                                "Persisted fixture server key did not match."
                            }
                            check(rows.getString("payload_hex") == "010203") {
                                "Persisted fixture payload did not match."
                            }
                        }
                    }
                }
            }
        }
    }

    named("check") {
        dependsOn(velocityExternalApiIntegrationTest)
    }
}
