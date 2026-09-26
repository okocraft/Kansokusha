import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Jar
import xyz.jpenilla.runvelocity.task.RunVelocity
import java.net.URLClassLoader
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
            project.delete(externalApiTestDirectory.get().asFile)
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
                            min(payload_generation) AS generation,
                            min(server) AS server_key,
                            min(hex(payload)) AS payload_hex
                        FROM events
                        WHERE event_type = ?
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
