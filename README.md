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

`build` also runs the unit tests and the packaged Paper and Velocity integration checks.

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

## Configuration

`config.yml` is created in the plugin data directory on first start. It contains the
retention periods recommended by `docs/v1-built-in-event-catalog.md` and
`search-time-zone` (default `UTC`) for search expressions such as `today`,
`yesterday`, date-only bounds, and datetimes without an explicit offset. Restart the
server or proxy to apply changes. See `docs/design.md` for the recording pipeline and
storage layout.

## Inspection mode

Paper/Folia provides session-scoped inspection mode commands:

- `/kansokusha inspect` toggles inspection mode.
- `/kansokusha inspect on` enables it idempotently.
- `/kansokusha inspect off` disables it idempotently.
- `/kansokusha i` is an alias for `inspect`.
- `kansokusha.command.inspect` controls access independently of
  `kansokusha.command.search`.

Inspection state is held only for the current player session. It is cleared on logout,
plugin disable, and server restart. Permission is rechecked when the inspection state is
consumed, so losing `kansokusha.command.inspect` invalidates an enabled session.

This stage provides the inspection session and command surface only; block-click lookup is
wired separately.

## Search

- `/kansokusha search ...` searches persisted events in the current instance's DuckDB database.
- `/kansokusha event <event-id>` displays one persisted event by its UUIDv7 event ID.
- `kansokusha.command.search`, `kansokusha.command.event`, and
  `kansokusha.command.search.event.<event-type>` control search, event detail, and per-event
  visibility. Kansokusha delegates exact permission nodes to the platform permission API rather
  than implementing wildcard expansion itself.

See `docs/search.md` for query syntax, time-zone behavior, pagination, permissions, output, and
the intentionally unsupported initial search scope.

## License

This project is under the GPL-3.0 license. Please see [LICENSE](LICENSE) for more info.

Copyright © 2026, OKOCRAFT
