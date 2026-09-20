# ADR-0003: retention resolution と expiry deletion semantics

- 日付: 2026-09-20
- 関連 Issue: #8, #27
- 前提 ADR: ADR-0001, ADR-0002

## コンテキスト

ADR-0001 は、外部 provider が event ごとの retention policy を直接指定せず、Kansokusha が acceptance boundary で retention policy を解決して `AcceptedEvent` に付与することを決めた。

ADR-0002 は retention policy key を compact `retention_policy_id` に変換して event row に保存すること、high-volume な `events` table に durable event ID や manual ART index を追加しないことを決めた。一方で、expiry の具体的な representation と bounded deletion の targeting は本 ADR に委ねている。

本 ADR は、event type ごとの retention policy resolution、duration と persisted expiry、bounded deletion、automatic cleanup の execution semantics を固定し、#19 の initial schema と #21 の writer が追加の schema decision なしで実装できる状態にする。

## 要件への対応

| 要件 | 本 ADR の担当範囲 |
| --- | --- |
| §8 | event row が retention policy identity と absolute expiry を保持し、保存後も expiry を判定できる representation |
| §9 | event type ごとの retention period resolution、automatic expiry、bounded cleanup、recording caller thread からの分離 |

具体的な policy 名、duration、event type mapping、cleanup interval、1 pass の削除上限値は本 ADR では決めず、operator configuration / built-in catalog に委ねる。

## 決定

### 1. retention policy identity は `Key`、duration は正の millisecond-aligned `Duration` とする

retention policy の identity は namespace-qualified な Adventure `Key` とする。persistent storage では ADR-0002 の `retention_policies` table により compact `INTEGER` ID に変換する。

policy duration の runtime representation は `java.time.Duration` とし、configuration では ISO-8601 duration text として表現する。読み込み時に次を検証する。

- duration は有限で正である。
- duration は whole milliseconds で表現できる。
- millisecond count は signed 64-bit integer に変換できる。
- months / years のような calendar-dependent unit は使用しない。

millisecond alignment は ADR-0002 の `occurred_at TIMESTAMP_MS` と persisted expiry の precision を一致させ、sub-millisecond rounding rule を retention calculation に持ち込まないためである。

built-in policy name、built-in duration、hard-coded fallback duration は定義しない。

### 2. event type mapping は exact mapping、次に明示設定された fallback policy の順で解決する

retention configuration は次の logical data を持つ。

- `policies: Key -> Duration`
- `event type mappings: event type Key -> policy Key`
- operator が明示する `fallback policy Key`

resolution は次の順序で行う。

1. event type に exact mapping があれば、その policy を使用する。
2. exact mapping がなければ、configuration に明示された fallback policy を使用する。

fallback は Kansokusha 内部の hard-coded default ではない。operator が configuration で policy identity を明示し、その policy definition も同じ validated configuration に存在しなければならない。

次は configuration validation failure とする。

- policy key / event type key が不正
- policy duration が上記 representation を満たさない
- duplicate policy definition / duplicate event type mapping
- mapping が存在しない policy key を参照する
- fallback policy reference が欠落する、または存在しない policy key を参照する

invalid configuration を部分的に適用しない。reload を実装する場合は、完全に validation 済みの immutable retention policy set を atomic に差し替え、失敗時は直前の valid snapshot を維持する。

これにより runtime に登録される未知の custom event type も、implicit default を使わず operator-selected fallback policy に解決できる。

### 3. expiry は occurrence time から一度だけ解決し、absolute `TIMESTAMP_MS` として event row に保存する

accepted event の persisted retention facts は次とする。

- resolved retention policy key
- `expiresAt`: absolute expiry instant at millisecond precision

`expiresAt` は次の意味を持つ。

```text
occurredAtMillis = truncate occurredAt to milliseconds
expiresAtMillis  = occurredAtMillis + policyDurationMillis
```

計算は ingestion/storage handoff より前の retention resolution で一度だけ行う。overflow や persistent timestamp として表現できない値は resolution failure とし、wraparound や saturation は行わない。

#19 は `events` に次の column を追加する。

```sql
expires_at TIMESTAMP_MS NOT NULL
```

ADR-0002 で決定済みの `retention_policy_id INTEGER NOT NULL` も保持する。v1 では duration 自体を event row や `retention_policies` dictionary に複製しない。

policy duration が configuration reload で変更されても、既に永続化された event の `expires_at` は再計算しない。同じ policy key であっても、過去の event は記録時に解決された expiry を維持し、新しい event だけが新しい duration を使用する。

storage writer は #29 から resolved policy key と `expiresAt` を受け取り、policy resolution や expiry calculation を再実行しない。

### 4. event は `expires_at <= cutoff` になった時点で expired とする

cleanup pass は開始時に current instant を一度だけ取得し、millisecond precision の `cutoff` として固定する。

その pass で削除対象になるのは次を満たす row だけである。

```text
expires_at <= cutoff
```

pass の途中で wall clock を再取得しない。同じ transaction 内の selection と deletion が同じ expiry boundary を使用する。

### 5. bounded deletion は transaction-local `rowid` を使い、durable event identity は追加しない

#19 は retention cleanup のための durable event ID、PK、UNIQUE constraint、manual ART index を追加しない。

1回の cleanup pass は storage-owned transaction 内で次を行う。

1. `expires_at <= cutoff` を満たす event から最大 `maxRowsPerPass` 件の `rowid` を選択する。
2. 選択した `rowid` の row だけを同じ transaction 内で削除する。
3. deleted row count を結果として返して commit する。

概念上の targeting は次の形である。

```sql
SELECT rowid
FROM events
WHERE expires_at <= ?
LIMIT ?;
```

選択した `rowid` はその transaction 内だけで deletion targeting に使用し、commit 後に保存・公開・再利用しない。DuckDB の `rowid` を persistent event identifier として扱わない。

selection に `ORDER BY` は要求しない。earliest-expiry ordering のために全 expired set を sort するより、任意の expired row を bounded count で処理する。各 successful pass が最大 N 件を削除し、repeated pass により現在 expired な row を最終的に除去する。

ADR-0002 の方針どおり、v1 では `expires_at` に manual ART index を追加しない。cleanup scan の cost が実運用上問題になる場合は、write/memory cost と合わせて別の ADR / migration で再評価する。

### 6. cleanup cadence と work bound は configuration で与え、1回の scheduled invocation は1 passだけ実行する

automatic cleanup は次の2値を operator configuration から受け取る。

- positive / whole-millisecond ISO-8601 `Duration` の cleanup interval
- positive integer の `maxRowsPerPass`

本 ADR は具体値を決めない。

scheduler は fixed-delay semantics とし、1 pass の完了後から configured interval を置いて次の pass を実行する。1回の invocation で「expired row がなくなるまで」loop しないため、database size に応じて1 invocation の retained row-id set が増え続けることはない。

expired row が `maxRowsPerPass` を超える場合は後続 pass に残す。cleanup の追いつき方は configured interval / bound の operational tuning で調整する。

### 7. cleanup は recording caller thread で実行せず、failure 後も次回 schedule を維持する

automatic cleanup は runtime-owned background execution context から storage-owned deletion operation を呼び出す。Paper/Folia / Velocity の event-recording caller thread では DuckDB I/O を実行しない。

storage access は event writer と同じ storage ownership rule に従って serialize し、cleanup と event batch write が同じ connection を同時使用しない。

cleanup failure 時は transaction を rollback し、failure を administrator-visible reporting path に渡す。failure を success や deleted-row-count 0 として扱わない。

1回の cleanup failure は scheduler を恒久停止させない。runtime が稼働中である限り次回 cadence で再試行する。shutdown 開始後は新しい cleanup pass を schedule せず、storage lifecycle の close と競合させない。

## 検討した選択肢

### event row に policy duration だけを保存し、query 時に expiry を計算する

policy duration の変更と historical event の expiry semantics が結びつきやすく、cleanup ごとに計算も必要になるため採用しない。absolute `expires_at` を記録時に固定する。

### policy key の duration 変更時に既存 event の expiry を更新する

過去に accepted された retention decision を retroactive に変更し、大量 update も必要になるため採用しない。

### expiry がない「永久保存」policy を nullable `expires_at` で表す

v1 requirement に infinite retention policy はなく、nullable expiry は cleanup semantics を増やすため採用しない。必要になった場合は policy representation と schema migration を別途設計する。

### cleanup 用の durable event UUID / sequence ID を追加する

v1 では event row の durable point identity requirement がなく、ADR-0002 が避けた PK / ART maintenance を追加することになるため採用しない。transaction 内で安定する DuckDB `rowid` だけを bounded targeting に使用する。

### `expires_at` に index を追加する

cleanup scan は速くなり得るが、append-heavy event write に index maintenance を追加する。v1 は write path を優先し、必要性を実測してから migration で追加する。

### cleanup invocation 内で expired row がなくなるまで繰り返す

backlog size によって1 invocation の duration と I/O が無制限に伸び、bounded cleanup requirement と合わないため採用しない。

## 結果

- #19 は `events.expires_at TIMESTAMP_MS NOT NULL` を initial schema に含め、cleanup 専用 durable event ID / index は追加しない。
- #28 は policy definitions、event mappings、明示 fallback policy、policy duration の validation を実装する。
- #29 は occurrence time と resolved policy duration から millisecond-precision `expiresAt` を一度だけ計算し、storage-facing event data に渡す。
- #21 は `retention_policy_id` と `expires_at` をそのまま transactionally persist し、retention decision を再計算しない。
- #30 は same-transaction `rowid` targeting と configured `maxRowsPerPass` により bounded deletion を実装する。
- #31 は cleanup interval / `maxRowsPerPass` の configuration、fixed-delay lifecycle、caller-thread separation、failure reporting / retry semantics を実装する。

## 参照

- ADR-0001: `docs/adr/0001-v1-event-contract-and-api-boundaries.md`
- ADR-0002: `docs/adr/0002-duckdb-schema-and-migration-strategy.md`
- DuckDB SELECT / rowid: https://duckdb.org/docs/current/sql/statements/select
- DuckDB DELETE: https://duckdb.org/docs/current/sql/statements/delete
- DuckDB LIMIT: https://duckdb.org/docs/current/sql/query_syntax/limit
- Java Duration: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Duration.html
