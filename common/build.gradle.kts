import org.gradle.api.tasks.JavaExec

val measurement = sourceSets.create("measurement") {
    compileClasspath += sourceSets.main.get().output
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}
val throughputMeasurementDirectory = layout.buildDirectory.dir("measurements/throughput")

dependencies {
    api(projects.kansokushaApi)
    implementation(libs.duckdb.jdbc)

    testImplementation(libs.adventure.key)
}

tasks.test {
    inputs.file(rootProject.file("docs/examples/v1-built-in-retention.yml"))
}

tasks.named("check") {
    dependsOn(measurement.classesTaskName)
}

tasks.register<JavaExec>("measureThroughput") {
    group = "measurement"
    description = "Measure common-runtime event throughput and process CPU time."

    dependsOn(measurement.classesTaskName)
    classpath = measurement.runtimeClasspath
    mainClass.set(
        "net.okocraft.kansokusha.common.measurement.ThroughputMeasurement"
    )

    val eventCount = providers.gradleProperty("kansokusha.measure.eventCount")
        .orElse("100000")
    val payloadSize = providers.gradleProperty("kansokusha.measure.payloadSize")
        .orElse("128")
    val queueCapacity = providers.gradleProperty("kansokusha.measure.queueCapacity")
        .orElse("8192")
    val batchSize = providers.gradleProperty("kansokusha.measure.batchSize")
        .orElse("512")
    val batchDelayMillis = providers.gradleProperty("kansokusha.measure.batchDelayMillis")
        .orElse("10")

    doFirst {
        project.delete(throughputMeasurementDirectory)
        args(
            "--event-count=${eventCount.get()}",
            "--payload-size=${payloadSize.get()}",
            "--queue-capacity=${queueCapacity.get()}",
            "--batch-size=${batchSize.get()}",
            "--batch-delay-ms=${batchDelayMillis.get()}",
            "--data-dir=${throughputMeasurementDirectory.get().asFile.absolutePath}"
        )
    }
}
