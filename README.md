# Kansokusha

Kansokusha (観測者) is a plugin for Minecraft that logs player actions.

## Requirements

- Java 25
- One of the following platforms:
  - Paper or Folia 26.2+
  - Velocity 4.1+

## Build

```shell
./gradlew build
```

Platform-specific jars such as `Kansokusha-Paper-x.x.x.jar` and
`Kansokusha-Velocity-x.x.x.jar` are written to the `build/libs` directory.

For the complete v1 verification sequence, including unit tests, packaged integration
checks, and final artifact inspection, run:

```shell
./gradlew clean verifyV1
```

See `docs/verification/v1.md` for the verification scope and CI behavior.

### Run a Paper server for debugging

```shell
./gradlew runServer
```

### Run a Velocity proxy for debugging

```shell
./gradlew runVelocity
```

### Verify the packaged Velocity plugin

```shell
./gradlew :kansokusha-velocity:velocityExternalApiIntegrationTest
```

This verification starts a real Velocity proxy with the shaded Kansokusha artifact and
the external API fixture, submits a fixture event through the public API, performs a
normal proxy shutdown, then reopens the instance-local DuckDB file using the driver
contained in the packaged artifact and verifies the flushed event.

### Measure ingestion and resource usage

```shell
./gradlew :kansokusha-common:measureThroughput
./gradlew :kansokusha-common:measureBoundedBuffer
```

See `docs/throughput-measurement.md` for parameters, measurement boundaries,
resource metrics, and the bounded-buffer scenario.

## License

This project is under the GPL-3.0 license. Please see [LICENSE](LICENSE) for more info.

Copyright © 2026, OKOCRAFT
