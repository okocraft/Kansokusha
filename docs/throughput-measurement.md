# Throughput and CPU-time measurement

The common module provides an explicit measurement task that is not part of the
functional test suite:

```shell
./gradlew :kansokusha-common:measureThroughput
```

The default run uses 100000 accepted events, 128-byte payloads, an 8192-event queue,
a 512-event maximum batch, and a 10 ms maximum batch delay. Override them with Gradle
properties:

```shell
./gradlew :kansokusha-common:measureThroughput \
  -Pkansokusha.measure.eventCount=250000 \
  -Pkansokusha.measure.payloadSize=256 \
  -Pkansokusha.measure.queueCapacity=16384 \
  -Pkansokusha.measure.batchSize=1024 \
  -Pkansokusha.measure.batchDelayMillis=5
```

Each run starts from a clean database under
`common/build/measurements/throughput`, registers one event type through the public
API, and submits until the requested number of events has been accepted. A full queue
is treated as temporary backpressure and is reported as
`temporarily_unavailable_attempts`.

The submission interval starts immediately before the first submit call and ends when
the requested number of events has been accepted. The persistence interval uses the
same start point and ends only after normal runtime close has drained the queue,
flushed the writer, and closed storage. The harness then reopens DuckDB and verifies
that the persisted row count equals the accepted event count before reporting
`persisted_events_per_second`.

`process_cpu_seconds` is the JVM process CPU time consumed across the persistence
interval, so it includes the submitting thread and asynchronous writer activity.
The output also records the Java version, VM, OS/architecture, available processor
count, all measurement parameters, and the database directory.

The reported values are measurements for comparison between controlled runs. The
harness does not define an acceptance threshold.
