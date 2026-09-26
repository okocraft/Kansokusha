dependencies {
    api(projects.kansokushaApi)

    compileOnly(libs.duckdb.jdbc)

    testImplementation(libs.adventure.key)
    testImplementation(libs.duckdb.jdbc)
}

tasks.processResources {
    val duckdbVersion = libs.versions.duckdb.get()
    inputs.property("duckdbVersion", duckdbVersion)

    filesMatching("META-INF/kansokusha/dependencies.properties") {
        expand("duckdbVersion" to duckdbVersion)
    }
}
