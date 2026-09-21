pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "kansokusha"

addProject("api")
addProject("common")
addProject("paper")
addProject("paper-test-plugin")
addProject("velocity")
addProject("velocity-test-plugin")

fun addProject(name: String) {
    include("${rootProject.name}-$name")
    project(":${rootProject.name}-$name").projectDir = file(name)
}
