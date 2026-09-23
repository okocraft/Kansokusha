plugins {
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    compileOnly(projects.kansokushaApi)
    compileOnly(projects.kansokushaPaper)
    compileOnly(libs.paper)

    paperweight.paperDevBundle(libs.versions.paper.get())
}
