# ADR-0002: DuckDB core storage schema と migration strategy

- 日付: 2026-09-20
- 関連 Issue: #6, #16
- 前提 ADR: ADR-0001

## コンテキスト

ADR-0001 は event type の公開 identity を Adventure `Key` とし、runtime registration と persistent identity を分離した。また payload generation、common event fields、opaque payload、resolved retention policy key を storage-independent contract として定義した。

Kansokusha はこれらを instance-local DuckDB に永続化する。本 ADR は core storage schema boundary、persistent identity、event write の transaction boundary、schema migration compatibility を決定する。retention expiry の具体的な representation と bounded deletion semantics は #27 が決定し、その結果を #19 の initial migration に含める。

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
CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    name VARCHAR NOT NULL,
    checksum VARCHAR NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
```

database の history は application が持つ migration definitions の連続した prefix であり、各 `(version, name, checksum)` が一致しなければならない。checksum は immutable migration artifact の改変検知に用いる。

unknown newer version、gap、name / checksum mismatch は storage initialization failure とする。downgrade は行わず、適用済み migration は変更しない。

migration は version 順に1件ずつ transaction 内で実行し、schema / data change と history row を atomic に commit する。失敗時は rollback し、valid data を destructive reset しない。transactional に安全に実行できない変更は generic runner で部分適用せず、個別の migration strategy を設計する。

### 2. event type / payload generation は persistent metadata と compact ID を分離する

```sql
CREATE SEQUENCE event_type_id_seq START 1 MAXVALUE 2147483647;

CREATE TABLE event_types (
    id INTEGER PRIMARY KEY DEFAULT nextval('event_type_id_seq'),
    event_type_key VARCHAR NOT NULL UNIQUE
);

CREATE SEQUENCE payload_generation_id_seq START 1 MAXVALUE 2147483647;

CREATE TABLE payload_generations (
    id INTEGER PRIMARY KEY DEFAULT nextval('payload_generation_id_seq'),
    event_type_id INTEGER NOT NULL REFERENCES event_types(id),
    generation INTEGER NOT NULL CHECK (generation > 0),
    UNIQUE (event_type_id, generation)
);
```

`event_type_key` は ADR-0001 の canonical `namespace:value` を保存する。integer ID は database-local surrogate であり public identity ではない。

同じ key / generation は既存 row を再利用し、過去 generation を update / delete しない。provider unregister や plugin removal も persistent metadata を削除しない。

metadata table は event table より十分小さいため、identity integrity のための PK / UNIQUE / FK と、それに伴う ART maintenance を受け入れる。

persistent registry の mutation と event batch write は storage-owned write path で直列化し、同じ Kansokusha instance 内で concurrent find-or-create transaction を実行しない。

### 3. high-volume `events` では implicit / manual ART を避ける

次は本 ADR が固定する core columns であり、#27 が決める retention expiry fields は #19 が同じ initial migration に加える。

```sql
CREATE TABLE events (
    payload_generation_id INTEGER NOT NULL,
    occurred_at_epoch_second BIGINT NOT NULL,
    occurred_at_nano INTEGER NOT NULL
        CHECK (occurred_at_nano >= 0 AND occurred_at_nano < 1000000000),
    server_key VARCHAR NOT NULL,
    world_key VARCHAR,
    block_x INTEGER,
    block_y INTEGER,
    block_z INTEGER,
    subject_player_uuid UUID,
    retention_policy_key VARCHAR NOT NULL,
    payload BLOB NOT NULL,
    CHECK (
        (block_x IS NULL AND block_y IS NULL AND block_z IS NULL)
        OR (
            world_key IS NOT NULL
            AND block_x IS NOT NULL
            AND block_y IS NOT NULL
            AND block_z IS NOT NULL
        )
    )
);
```

DuckDB は PK / UNIQUE / FK に ART を暗黙作成する。append-heavy な `events` では write / memory cost を抑えるため、v1 core schema に PK / UNIQUE / FK と manual ART index を置かない。

`payload_generation_id` の referential integrity は、storage writer が同一 transaction 内で metadata を解決してから event を insert する invariant で保証する。

将来 FK が必要になっても in-place `ADD CONSTRAINT` を前提にしない。DuckDB の制約に応じ、validation と table replacement を含む migration を設計する。

server / world / retention policy identity は stable surrogate identity の要件がないため、v1 core schema では dictionary table に正規化しない。

### 4. `Instant` は epoch second + nano adjustment で保存する

Java `Instant` の public contract は DuckDB `TIMESTAMP_NS` の signed 64-bit epoch-nanoseconds range より広い。

そのため `occurredAt` は `Instant#getEpochSecond()` を `BIGINT`、`Instant#getNano()` を `INTEGER` として保存し、`Instant.ofEpochSecond(...)` で復元する。

これにより public API の valid value を lossless に保持する。SQL timestamp functions が必要な query では query boundary で変換する。

### 5. metadata resolution と event batch は1つの transaction boundary とする

batch persistence は必要な event type / payload generation の find-or-create と全 event insert を同一 transaction で行い、すべて成功した場合だけ commit する。

失敗時はその batch と、その transaction 内で新規作成した metadata を rollback する。既存の committed data は変更しない。success は commit 完了後にのみ返す。

### 6. retention の concrete schema は #27 に委ねる

本 ADR は resolved `retention_policy_key` を core event data として保持するところまで決める。

expiry representation と bounded deletion に durable row identity が必要かは #27 が決定し、その結果を #19 の initial migration に含める。`rowid` を使う場合も transaction-local な row targeting に限定し、persistent identity として扱わない。

## 検討した選択肢

### event row に public key と generation を直接保存する

同じ identity を event ごとに重複保存するため採用しない。metadata table で stable identity を保持し、event row は compact generation ID を参照する。

### `events.event_id UUID PRIMARY KEY` を設ける

v1 には durable event ID による point lookup / update / deduplication requirement がなく、PK は全 event row に ART maintenance を追加するため採用しない。retention deletion が durable identity を必要とする場合は #27 が initial schema 前に決定する。

### `events.payload_generation_id` に FOREIGN KEY を張る

referential integrity は強くなるが、全 event row に FK 用 ART を維持するため採用しない。storage-owned serialized write path と transaction invariant で valid generation ID を保証する。

### `occurred_at TIMESTAMP_NS` を使う

単純だが Java `Instant` の valid range を storage 固有の範囲へ狭めるため採用しない。

## 結果

- event write cost を抑える代わりに、`events.payload_generation_id` の referential integrity は database constraint では保証されない。
- Java `Instant` の全域を lossless に保持できる代わりに、SQL timestamp functions の直接利用には変換が必要になる。
- migration history を厳密に検証するため、改変済み migration や newer schema を自動修復せず initialization failure とする。

## 参照

- DuckDB Transaction Management: https://duckdb.org/docs/current/sql/statements/transactions
- DuckDB Concurrency: https://duckdb.org/docs/current/connect/concurrency
- DuckDB Indexing Performance Guide: https://duckdb.org/docs/lts/guides/performance/indexing
- DuckDB ALTER TABLE: https://duckdb.org/docs/current/sql/statements/alter_table
- DuckDB SELECT / rowid: https://duckdb.org/docs/current/sql/statements/select
- Java `Instant`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Instant.html
