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
