plugins {
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.get())

    compileOnly(projects.kansokushaApi)
    compileOnly(projects.kansokushaPaper)
    compileOnly(libs.paper)
}
