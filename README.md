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

### Verify the packaged Paper plugin

```shell
./gradlew :kansokusha-paper:paperExternalApiIntegrationTest
```

This starts a real Paper server with the packaged Kansokusha jar and verifies plugin
startup, inspection listener wiring, Folia-support metadata, external API lifecycle,
DuckDB dependency loading, and persistence. Connected-player interaction checks are
documented in `docs/verification/paper-folia-packaged-smoke.md`.

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
`search-time-zone` (empty by default, using the server system time zone) for search expressions such as `today`,
`yesterday`, date-only bounds, and datetimes without an explicit offset. Restart the
server or proxy to apply changes. Existing configurations with `search-time-zone: UTC`
keep UTC; set `Asia/Tokyo` or another IANA zone explicitly to change them.
The resolved zone is logged at startup. See `docs/design.md` for the recording pipeline and
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

While inspection is enabled, left-clicking a block selects that block coordinate and
right-clicking an interactable block (such as a chest, door, or sign) selects that block.
Right-clicking other blocks selects the coordinate one block beyond the clicked face. Air
clicks are ignored. Block and held-item use are denied for both hands, while only the
main-hand interaction selects a target. Block damage and block break are also cancelled
as safety guards, so inspection clicks do not mutate the world or create normal
cancel-aware mutation logs.

The selected coordinate is searched through the existing asynchronous search backend
using an exact world/X/Y/Z condition, newest first, with the normal player default of
10 results. Event visibility is restricted by
`kansokusha.command.search.event.<event-type>`; free-form
`kansokusha.command.search` is not required for inspection itself. Result rows reuse
the normal search formatter and remain clickable to `/kansokusha event <event-id>`
when `kansokusha.command.event` is available.

If more than 10 results exist and the player has `kansokusha.command.search`, inspection
shows a clickable `[View full history]` link that opens the existing exact-position
search and its normal pagination. Lookups run in click order, so results are shown in the
same order as the clicks.

The packaged Paper verification and the manual Paper/Folia interaction checklist are in
`docs/verification/paper-folia-packaged-smoke.md`.

## Messages and command help

`/kansokusha` and `/kansokusha help` show the commands available to the sender.
Running `/kansokusha event` without an ID shows its usage.

English and Japanese messages are provided by default, with English as the fallback.
Administrators can customize `languages/*.properties` in the plugin data directory.
Startup adds missing keys while preserving existing translations. Updated default wording
therefore applies to new files; to adopt it in an existing file, remove the individual
keys you want to reset and restart the server or proxy. Back up customizations first.

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

Inspection results have their own previous/next links under `kansokusha.command.inspect`.
The hidden `inspect page` command accepts an exact world/X/Y/Z and a cursor only; it cannot
run arbitrary search conditions. Search permission is not needed for these links.

Right-clicking a container in inspection mode shows container transaction, pickup, and transfer
records; left-clicking still shows every visible event at the selected coordinate. Double
chests search both halves as an OR group, with the same filter retained during pagination
and in the full-search link. Automatic transfer/pickup records describe observed operations;
only container transaction deltas represent committed net inventory changes.

Event overview, full-text, item and record views provide a back-to-results link after a
search or inspection. The last displayed page is held per sender, including its cursor
and container mode, and discarded on logout or plugin shutdown. Opening an event with
no preceding results does not show a back link. Returning re-runs the query; results are
not a frozen snapshot of the database.
