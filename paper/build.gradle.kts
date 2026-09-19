plugins {
    alias(libs.plugins.bundler)
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.server)
}

val minecraftVersion = libs.versions.paper.get().replaceAfter(".build", "").removeSuffix(".build")

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
    runServer {
        minecraftVersion(minecraftVersion)
        systemProperty("com.mojang.eula.agree", "true")
        systemProperty("paper.disable-plugin-rewriting", "true")
    }
}
