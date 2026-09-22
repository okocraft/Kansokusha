# Throughput and resource measurement

The common module provides explicit measurement tasks that are not part of the
functional test suite.

## Sustained persistence measurement

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

The run reports:

- `process_cpu_seconds`: JVM process CPU time across the persistence interval,
  including the submitting thread and asynchronous writer.
- `heap_used_before_bytes`, `peak_heap_used_bytes`, and
  `heap_used_after_bytes`: JVM heap usage around the persistence interval. Heap pool
  peak counters are reset immediately before submission begins.
- `logical_payload_bytes`: the persisted event count multiplied by configured payload
  bytes. This is the documented write-volume proxy.
- `duckdb_disk_bytes`: the final byte length of the DuckDB database and any remaining
  sidecar files sharing the database filename prefix after normal shutdown.
- the configured queue capacity, maximum batch size, batch delay, Java/VM version,
  OS/architecture, available processor count, and database directory.

`logical_payload_bytes` is not operating-system bytes written. It excludes table and
index metadata, WAL traffic, compression, filesystem behavior, and storage-engine
write amplification. `duckdb_disk_bytes` uses logical file lengths rather than
allocated filesystem blocks. Heap measurements cover the measurement JVM and can
include JVM/runtime allocations not attributable solely to Kansokusha.

## Bounded-buffer scenario

```shell
./gradlew :kansokusha-common:measureBoundedBuffer
```

This scenario starts the asynchronous writer with a blocking batch writer. Once the
first batch reaches the held writer, it fills the intake queue to its configured
capacity and performs additional submissions while the writer remains blocked. The
run reports the held batch size, maximum observed queued event count, accepted events,
and temporarily unavailable attempts.

Defaults are a 64-event queue, 16-event maximum batch, 128-byte payload, and 64 extra
submissions. The shared queue, batch, and payload properties can be overridden, and
the extra attempt count uses a dedicated property:

```shell
./gradlew :kansokusha-common:measureBoundedBuffer \
  -Pkansokusha.measure.queueCapacity=128 \
  -Pkansokusha.measure.batchSize=32 \
  -Pkansokusha.measure.payloadSize=256 \
  -Pkansokusha.measure.boundedExtraAttempts=128
```

The scenario fails if the observed queued event count exceeds the configured queue
capacity, if the held batch exceeds the configured maximum batch size, or if an
additional submission is accepted after the held queue reaches capacity. It verifies
the existing finite buffering behavior; it does not introduce or select a saturation
policy.

All reported values are measurements for comparison between controlled runs. Neither
task defines a numeric performance or resource-usage acceptance threshold.
