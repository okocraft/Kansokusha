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
    runVelocity {
        velocityVersion(libs.versions.velocity.get())
    }
}
