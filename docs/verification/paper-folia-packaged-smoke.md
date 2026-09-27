# Paper / Folia packaged-plugin smoke verification

## Automated Paper verification

Run:

```shell
./gradlew :kansokusha-paper:paperExternalApiIntegrationTest
```

The task builds the shaded Kansokusha Paper artifact and the external API fixture, then
starts a real Paper server with only those plugin jars.

The verification requires all of the following:

- the packaged Kansokusha jar does not contain `org/duckdb/DuckDBDriver.class`;
- the packaged `paper-plugin.yml` declares `folia-supported: true`;
- Kansokusha downloads the platform-specific DuckDB JDBC artifact into
  `plugins/Kansokusha/libs` on first start;
- Kansokusha starts without a separately installed DuckDB driver;
- the external fixture obtains `Kansokusha.api()`, registers an event type, and
  `submit` returns `true`;
- every expected built-in audit listener is registered by the packaged plugin;
- `InspectionSessionListener` and `InspectionInteractionListener` are both registered
  by the packaged plugin;
- normal server shutdown makes `Kansokusha.api()` unavailable and the previously
  acquired API rejects further events;
- after the Paper process exits, the Gradle verification loads
  `org.duckdb.DuckDBDriver` from the downloaded platform-specific jar and reopens the
  instance-local `kansokusha.duckdb`;
- the accepted fixture event is present exactly once with its event key, generation,
  server key, and payload intact.

The task is also part of `check`.

This automated task verifies packaged startup, listener wiring, lifecycle, dependency
loading, and persistence. It does not create a connected player or exercise real world
interaction semantics.

## Manual inspection smoke on Paper / Folia

Use a fresh test server with the packaged Kansokusha jar. Give the test player
`kansokusha.command`, `kansokusha.command.inspect`, and selected
`kansokusha.command.search.event.<event-type>` permissions. Add
`kansokusha.command.event` and `kansokusha.command.search` only for the steps that
explicitly test those capabilities.

Before enabling inspection, create known history at two nearby coordinates, including a
coordinate that is currently air. Then verify all of the following:

1. `/kansokusha inspect` toggles ON and OFF, while `inspect on` and `inspect off`
   are idempotent. `/kansokusha i` shares the same state.
2. In survival mode, left-clicking while inspection is ON does not damage or break the
   clicked block.
3. In creative mode, left-clicking while inspection is ON does not instantly break the
   clicked block.
4. Right-clicking a chest does not open it, and right-clicking a door, button, or lever
   does not change its state.
5. Right-clicking while holding a placeable block does not place it. Right-clicking with
   a bucket does not fill or empty it.
6. A left click reports history for the clicked block coordinate.
7. A right click reports history for the coordinate one block beyond the clicked face.
   Use this to inspect the prepared air coordinate from an adjacent solid block.
8. Only event types permitted by
   `kansokusha.command.search.event.<event-type>` appear. Removing one such permission
   hides that event type from subsequent inspection results.
9. With `kansokusha.command.event`, a result row opens
   `/kansokusha event <event-id>`. Without that permission, the row is not executable.
10. Prepare more than 10 events at one coordinate. With
    `kansokusha.command.search`, `[View full history]` opens
    `/kansokusha search position <world> <x> <y> <z>` and the existing search
    pagination. Without the permission, only the newest 10 inspection results are shown.
11. Click two coordinates rapidly several times. A slower result for an older click must
    never appear after the result for the latest click.
12. Inspection clicks must not create new cancel-aware mutation records such as
    `block_break`, `block_place`, or `bucket_*`. The
    `/kansokusha inspect ...` command itself may still be present in command history.
13. Remove `kansokusha.command.inspect` while inspection is ON. The next block
    interaction must disable inspection instead of performing a lookup.
14. Logout while inspection is ON, reconnect, and confirm it is OFF. Also restart the
    server and confirm inspection state is not persisted.

Repeat the interaction checks with an empty off hand and with an item in the off hand.
Both hands must remain suppressed, while one physical click produces only one lookup.

## Folia compatibility smoke

The repository does not currently provision a Folia distribution in CI, so Folia
thread ownership is verified manually rather than represented as an automated guarantee.

1. Build `./gradlew :kansokusha-paper:shadowJar`.
2. Start a supported Folia 26.2+ server with Java 25 and only the produced Kansokusha
   jar.
3. Confirm Kansokusha downloads one platform-specific DuckDB JDBC jar into
   `plugins/Kansokusha/libs`, enables without scheduler/thread-affinity errors, and
   creates `plugins/Kansokusha/kansokusha.duckdb`.
4. Complete the manual inspection smoke above.
5. When possible, join with two players in different active regions. Enable inspection
   for both and click different coordinates concurrently while normal block audit events
   also occur in each region. Confirm lookups complete without thread-affinity errors,
   cross-player result mixing, or stale-result reordering.
6. Stop the server normally. Restart the same instance without network access and
   confirm Kansokusha reuses the cached DuckDB JDBC jar and opens the existing database
   without locking errors.

The Folia check is a compatibility smoke test, not a performance benchmark. Inspection
session and request-order state are keyed by player UUID in concurrent collections;
interaction callbacks snapshot only immutable UUID/world/coordinate values before the
asynchronous search path.
