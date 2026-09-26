import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Jar
import xyz.jpenilla.runpaper.task.RunServer
import java.net.URLClassLoader
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.bundler)
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.server)
}

val paperApiVersion = libs.versions.paper.get()
val minecraftVersion = paperApiVersion.substringBefore(".build.")
val paperBuild = paperApiVersion.substringAfter(".build.").substringBefore('-').toInt()
val externalApiTestDirectory = layout.buildDirectory.dir("paper-external-api-integration")
val externalApiFixtureJar = project(":kansokusha-paper-test-plugin")
    .tasks.named<Jar>("jar")
val paperShadowJar = tasks.named<ShadowJar>("shadowJar")

dependencies {
    implementation(projects.kansokushaCommon)

    compileOnlyApi(libs.paper)
    testImplementation(libs.paper)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)

    paperweight.paperDevBundle(libs.versions.paper.get())
}

bundler {
    copyToRootBuildDirectory("Kansokusha-Paper-${project.version}")
    replacePluginVersionForPaper(project.version)
}

tasks {
    test {
        systemProperty("org.slf4j.simpleLogger.cacheOutputStream", "true")
        systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    }

    runServer {
        minecraftVersion(minecraftVersion)
        build(paperBuild)
        systemProperty("com.mojang.eula.agree", "true")
        systemProperty("paper.disable-plugin-rewriting", "true")
    }

    val paperExternalApiIntegrationTest = register<RunServer>("paperExternalApiIntegrationTest") {
        group = "verification"
        description = "Run the external Paper API fixture against a real Paper server."

        dependsOn(paperShadowJar, externalApiFixtureJar)
        minecraftVersion(minecraftVersion)
        build(paperBuild)
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
            project.delete(externalApiTestDirectory.get().asFile)
        }

        doLast {
            val result = externalApiTestDirectory.get().file("fixture-result.txt").asFile
            check(result.isFile) {
                "External Paper API fixture did not produce a result."
            }
            check(result.readText() == "success") {
                "External Paper API fixture failed:\n" + result.readText()
            }

            val database = externalApiTestDirectory.get()
                .file("plugins/Kansokusha/kansokusha.duckdb")
                .asFile
            check(database.isFile) {
                "Kansokusha did not create its instance-local DuckDB file."
            }

            val packagedJar = paperShadowJar.get().archiveFile.get().asFile
            ZipFile(packagedJar).use { jar ->
                check(jar.getEntry("org/duckdb/DuckDBDriver.class") == null) {
                    "Packaged Kansokusha jar must not contain the DuckDB JDBC driver."
                }
            }

            val libraryDirectory = externalApiTestDirectory.get()
                .dir("plugins/Kansokusha/libs")
                .asFile
            val duckDbJars = libraryDirectory.listFiles { file ->
                file.isFile &&
                    file.name.startsWith("duckdb_jdbc-") &&
                    file.name.endsWith(".jar")
            }?.toList().orEmpty()
            check(duckDbJars.size == 1) {
                "Expected one downloaded DuckDB JDBC artifact, found: " +
                    duckDbJars.joinToString { it.name }
            }

            URLClassLoader(
                arrayOf(duckDbJars.single().toURI().toURL()),
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
                                "Packaged Paper smoke query returned no aggregate row."
                            }
                            check(rows.getInt("event_count") == 1) {
                                "Expected one flushed fixture event, found " +
                                    rows.getInt("event_count") + "."
                            }
                            check(rows.getInt("generation") == 1) {
                                "Persisted fixture generation did not match."
                            }
                            check(!rows.getString("server_key").isNullOrBlank()) {
                                "Persisted fixture server key was missing."
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
        dependsOn(paperExternalApiIntegrationTest)
    }
}
