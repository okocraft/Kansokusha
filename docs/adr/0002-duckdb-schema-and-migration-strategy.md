# ADR-0002: DuckDB core storage schema と migration strategy

- 日付: 2026-09-20
- 更新: 2026-09-26 — ADR-0001 の optional server context を storage schema に反映
- 関連 Issue: #6, #16, #152, #153
- 前提 ADR: ADR-0001

## コンテキスト

ADR-0001 は event type、server、world、retention policy の公開 identity を `Key` とし、payload generation と common event fields を storage-independent contract として定義した。

Kansokusha はこれらを instance-local DuckDB に永続化する。本 ADR は core storage schema、compact persistent identity、event write の transaction boundary、schema migration compatibility を決定する。retention expiry の具体的な representation と bounded deletion semantics は #27 が決定し、その結果を #19 の initial migration に含める。

## 要件への対応

| 要件 | 本 ADR の担当範囲 |
| --- | --- |
| §7 | event type key と payload generation の persistent identity |
| §8 | common event data / opaque payload の core storage representation。retention expiry representation は #27 |
| §10 | local DuckDB の schema boundary。JDBC packaging / connection lifecycle は #17 |
| §13 | metadata resolution と event batch write の transaction boundary。ingestion / failure visibility / shutdown drain は #22 以降 |
| §15 | migration history validation と forward-only migration |

## 決定

### 1. migration history は immutable definition の exact prefix として検証する

```sql
CREATE TABLE schema_migrations (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    name VARCHAR NOT NULL,
    checksum VARCHAR NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
```

database の history は application が持つ migration definitions の連続した prefix であり、各 `(version, name, checksum)` が一致しなければならない。checksum は immutable migration artifact の改変検知に用いる。

unknown newer version、gap、name / checksum mismatch は storage initialization failure とする。downgrade は行わず、適用済み migration は変更しない。

migration は version 順に1件ずつ transaction 内で実行し、schema / data change と history row を atomic に commit する。失敗時は rollback し、valid data を destructive reset しない。transactional に安全に実行できない変更は generic runner で部分適用せず、個別の migration strategy を設計する。

### 2. 繰り返し現れる Key / generation は compact metadata ID に変換する

metadata surrogate ID は database-local な正の `INTEGER` とし、public identity にはしない。

```sql
CREATE TABLE event_types (
    id INTEGER PRIMARY KEY,
    event_type_key VARCHAR NOT NULL UNIQUE
);

CREATE TABLE payload_generations (
    id INTEGER PRIMARY KEY,
    event_type_id INTEGER NOT NULL REFERENCES event_types(id),
    generation INTEGER NOT NULL CHECK (generation > 0),
    UNIQUE (event_type_id, generation)
);

CREATE TABLE servers (
    id INTEGER PRIMARY KEY,
    server_key VARCHAR NOT NULL UNIQUE
);

CREATE TABLE worlds (
    id INTEGER PRIMARY KEY,
    server_id INTEGER NOT NULL REFERENCES servers(id),
    world_key VARCHAR NOT NULL,
    UNIQUE (server_id, world_key)
);

CREATE TABLE retention_policies (
    id INTEGER PRIMARY KEY,
    retention_policy_key VARCHAR NOT NULL UNIQUE
);
```

ID は database sequence から割り当て、gap に意味を持たせない。`generation` 自体は ADR-0001 の正の 32-bit integer contract を維持する。

`payload_generations` は event type ごとの payload format 世代を永続化し、`(event_type_id, generation)` を1つの compact ID に変換するための table である。これにより高頻度な event row は event type ID と generation の2値ではなく `payload_generation_id` 1値だけを保持できる。

world は server ごとの identity として `(server_id, world_key)` で解決する。retention policy table はこの ADR では key と compact ID の対応だけを固定し、duration / expiry facts は #27 に委ねる。

provider unregister や plugin removal は persistent metadata を削除しない。metadata table は event table より十分小さいため、identity integrity のための PK / UNIQUE / FK と、それに伴う ART maintenance を受け入れる。

persistent metadata の mutation と event batch write は storage-owned write path で直列化し、同じ Kansokusha instance 内で concurrent find-or-create transaction を実行しない。

### 3. high-volume `events` では文字列 identity と implicit / manual ART を避ける

次は本 ADR が固定する core columns であり、#27 が決める retention expiry fields は #19 が同じ initial migration に加える。

```sql
CREATE TABLE events (
    payload_generation_id INTEGER NOT NULL,
    occurred_at TIMESTAMP_MS NOT NULL,
    server_id INTEGER,
    world_id INTEGER,
    block_x INTEGER,
    block_y INTEGER,
    block_z INTEGER,
    subject_player_uuid UUID,
    retention_policy_id INTEGER NOT NULL,
    payload BLOB NOT NULL,
    CHECK (
        (block_x IS NULL AND block_y IS NULL AND block_z IS NULL)
        OR (
            world_id IS NOT NULL
            AND block_x IS NOT NULL
            AND block_y IS NOT NULL
            AND block_z IS NOT NULL
        )
    )
);
```

DuckDB は PK / UNIQUE / FK に ART を暗黙作成する。append-heavy な `events` では write / memory cost を抑えるため、v1 core schema に PK / UNIQUE / FK と manual ART index を置かない。

`server_id` は ADR-0001 の optional server context を表す。server context がない event は NULL を保存し、そのためだけに synthetic server metadata を作成しない。world は server-scoped なので、`world_id` がある event では `server_id` も必須という invariant を public API / storage writer が維持する。

`payload_generation_id`、存在する server / world、retention policy の整合性は、storage writer が同一 transaction 内で metadata を解決してから event を insert する invariant で保証する。world は event の `server_id` と同じ server に属する row だけを解決する。

initial migration の checksum compatibility を維持するため version 1 は変更せず、version 2 `optional_event_server` で `events.server_id` の NOT NULL constraint を drop する。

将来 FK が必要になっても in-place `ADD CONSTRAINT` を前提にしない。DuckDB の制約に応じ、validation と table replacement を含む migration を設計する。

### 4. occurrence time は millisecond precision で保存する

v1 の event time は millisecond precision で十分とし、`EventSubmission.occurredAt` は保存時に millisecond precision へ切り捨てて `TIMESTAMP_MS` に格納する。sub-millisecond precision は persistent contract に含めない。

writer は epoch milliseconds から `make_timestamp_ms` を用いて値を作成でき、query 側は DuckDB の timestamp operations を直接利用できる。

### 5. metadata resolution と event batch は1つの transaction boundary とする

batch persistence は必要な metadata ID の find-or-create と全 event insert を同一 transaction で行い、すべて成功した場合だけ commit する。

失敗時はその batch と、その transaction 内で新規作成した metadata を rollback する。既存の committed data は変更しない。success は commit 完了後にのみ返す。

### 6. retention の concrete schema は #27 に委ねる

本 ADR は resolved retention policy key を compact `retention_policy_id` に対応付けるところまで決める。

expiry representation と bounded deletion に durable row identity が必要かは #27 が決定し、その結果を #19 の initial migration に含める。`rowid` を使う場合も transaction-local な row targeting に限定し、persistent identity として扱わない。

## 検討した選択肢

### Key / generation を event row に直接保存する

文字列 key や `(event_type_id, generation)` を各 event に重複保存するより、metadata table で compact ID に解決した方が高頻度 row を小さくできるため採用しない。

### `events.event_id UUID PRIMARY KEY` を設ける

v1 には durable event ID による point lookup / update / deduplication requirement がなく、PK は全 event row に ART maintenance を追加するため採用しない。retention deletion が durable identity を必要とする場合は #27 が initial schema 前に決定する。

### `events` の metadata ID に FOREIGN KEY を張る

referential integrity は強くなるが、全 event row に FK 用 ART を維持するため採用しない。storage-owned serialized write path と transaction invariant で valid ID を保証する。

### nanosecond precision で occurrence time を保存する

監査用途として millisecond precision で十分であり、sub-millisecond data を保持する追加 complexity に明確な利用先がないため採用しない。

## 結果

- event row の文字列 identity と ART maintenance を避けて write / storage cost を抑える代わりに、metadata ID の referential integrity は database constraint では保証されない。
- occurrence time は SQL timestamp として扱いやすい一方、sub-millisecond precision は永続化時に失われる。
- migration history を厳密に検証するため、改変済み migration や newer schema を自動修復せず initialization failure とする。

## 参照

- DuckDB Transaction Management: https://duckdb.org/docs/current/sql/statements/transactions
- DuckDB Concurrency: https://duckdb.org/docs/current/connect/concurrency
- DuckDB Indexing Performance Guide: https://duckdb.org/docs/lts/guides/performance/indexing
- DuckDB Timestamp Functions: https://duckdb.org/docs/current/sql/functions/timestamp
- DuckDB ALTER TABLE: https://duckdb.org/docs/current/sql/statements/alter_table
- DuckDB SELECT / rowid: https://duckdb.org/docs/current/sql/statements/select
