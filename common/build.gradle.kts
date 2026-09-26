dependencies {
    api(projects.kansokushaApi)

    compileOnly(libs.duckdb.jdbc)

    testImplementation(libs.adventure.key)
    testImplementation(libs.duckdb.jdbc)
}

