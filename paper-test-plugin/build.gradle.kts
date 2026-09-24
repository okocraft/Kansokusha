import org.gradle.api.file.DuplicatesStrategy

repositories {
    maven("https://repo.opencollab.dev/maven-snapshots/") {
        name = "opencollab-snapshots"
    }
}

dependencies {
    compileOnly(projects.kansokushaApi)
    compileOnly(projects.kansokushaPaper)
    compileOnly(libs.paper)

    implementation("org.geysermc.mcprotocollib:protocol:26.2-SNAPSHOT")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }
    )
    exclude(
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.SF"
    )
}
