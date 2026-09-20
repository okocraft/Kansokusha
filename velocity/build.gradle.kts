plugins {
    alias(libs.plugins.bundler)
    alias(libs.plugins.run.velocity)
}

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
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        doLast {
            java.util.zip.ZipFile(archiveFile.get().asFile).use { jar ->
                check(jar.getEntry("org/duckdb/DuckDBDriver.class") != null) {
                    "DuckDB JDBC driver is missing from the Velocity artifact."
                }
            }
        }
    }

    runVelocity {
        velocityVersion(libs.versions.velocity.get())
    }
}
