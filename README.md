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

### Run a Paper server for debugging

```shell
./gradlew runServer
```

### Run a Velocity proxy for debugging

```shell
./gradlew runVelocity
```

## License

This project is under the GPL-3.0 license. Please see [LICENSE](LICENSE) for more info.

Copyright © 2026, OKOCRAFT
