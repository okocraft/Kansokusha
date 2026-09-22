import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.jcommon)
}

jcommon {
    javaVersion = JavaVersion.VERSION_25

    setupPaperRepository()
    setupJUnit(libs.junit.bom)
    setupMockito(libs.mockito)

    commonDependencies {
        compileOnlyApi(libs.annotations)
        compileOnlyApi(libs.configurate.yaml)

        testImplementation(libs.junit.jupiter)
        testImplementation(libs.configurate.yaml)
    }

    jarTask {
        manifest {
            attributes(
                "Implementation-Version" to project.version.toString()
            )
        }
    }
}

val paperArtifact = layout.buildDirectory.file(
    "libs/Kansokusha-Paper-${project.version}.jar"
)
val velocityArtifact = layout.buildDirectory.file(
    "libs/Kansokusha-Velocity-${project.version}.jar"
)

tasks.register("verifyV1") {
    group = "verification"
    description = "Run the v1 build and inspect final platform artifacts."

    dependsOn("build")
    inputs.files(paperArtifact, velocityArtifact)

    doLast {
        inspectArtifact(
            paperArtifact.get().asFile,
            "paper-plugin.yml",
            listOf(
                "name: Kansokusha",
                "version: '${project.version}'",
                "main: net.okocraft.kansokusha.paper.plugin.KansokushaPaperPlugin",
                "api-version: '1.21'",
                "folia-supported: true"
            )
        )
        inspectArtifact(
            velocityArtifact.get().asFile,
            "velocity-plugin.json",
            listOf(
                "\"id\": \"kansokusha\"",
                "\"name\": \"Kansokusha\"",
                "\"version\": \"${project.version}\"",
                "\"main\": \"net.okocraft.kansokusha.velocity.plugin.KansokushaVelocityPlugin\""
            )
        )
    }
}

fun inspectArtifact(
    artifact: File,
    descriptorPath: String,
    requiredDescriptorFragments: List<String>
) {
    check(artifact.isFile) {
        "Expected final artifact was not produced: ${artifact.absolutePath}"
    }

    ZipFile(artifact).use { jar ->
        check(jar.getEntry("org/duckdb/DuckDBDriver.class") != null) {
            "DuckDB JDBC driver is missing from ${artifact.name}."
        }

        val descriptorEntry = jar.getEntry(descriptorPath)
        check(descriptorEntry != null) {
            "$descriptorPath is missing from ${artifact.name}."
        }

        val descriptor = jar.getInputStream(descriptorEntry)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        for (fragment in requiredDescriptorFragments) {
            check(descriptor.contains(fragment)) {
                "$descriptorPath in ${artifact.name} is missing expected content: $fragment"
            }
        }
    }
}
