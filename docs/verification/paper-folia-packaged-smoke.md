# Paper / Folia packaged-plugin smoke verification

## Automated Paper verification

Run:

```shell
./gradlew :kansokusha-paper:paperExternalApiIntegrationTest
```

The task builds the shaded Kansokusha Paper artifact and the external API fixture, then starts a real Paper server with only those plugin jars.

The verification requires all of the following:

- the packaged Kansokusha jar does not contain `org/duckdb/DuckDBDriver.class`;
- Kansokusha downloads the platform-specific DuckDB JDBC artifact into `plugins/Kansokusha/libs` on first start;
- Kansokusha starts without a separately installed DuckDB driver;
- the external fixture obtains `Kansokusha.api()`, registers an event type, and `submit` returns `true`;
- normal server shutdown makes `Kansokusha.api()` unavailable and the previously acquired API rejects further events;
- after the Paper process exits, the Gradle verification loads `org.duckdb.DuckDBDriver` from the downloaded platform-specific jar and reopens the instance-local `kansokusha.duckdb`;
- the accepted fixture event is present exactly once with its event key, generation, server key, and payload intact.

The task is also part of `check`.

## Folia compatibility smoke

The listener concurrency semantics are covered by unit tests, but the repository does not currently provision a Folia distribution in CI. For a packaged Folia smoke check:

1. Build `./gradlew :kansokusha-paper:shadowJar`.
2. Start a supported Folia 26.2+ server with Java 25 and only the produced Kansokusha jar.
3. Confirm Kansokusha downloads one platform-specific DuckDB JDBC jar into `plugins/Kansokusha/libs`, enables without scheduler/thread-affinity errors, and creates `plugins/Kansokusha/kansokusha.duckdb`.
4. Join with two players in different active regions when available, perform block break/place operations concurrently, and then stop the server normally.
5. Restart the same instance without network access and confirm Kansokusha reuses the cached DuckDB JDBC jar and opens the existing database without locking errors.

The Folia check is a compatibility smoke test, not a performance benchmark. Built-in listeners keep no state shared between events, so there is no cross-thread correlation to regression-test beyond the per-listener tests in the Paper module.
