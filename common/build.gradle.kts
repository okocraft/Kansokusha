dependencies {
    api(projects.kansokushaApi)
    implementation(libs.duckdb.jdbc)

    testImplementation(libs.adventure.key)
}

tasks.test {
    inputs.file(rootProject.file("docs/examples/v1-built-in-retention.yml"))
}
