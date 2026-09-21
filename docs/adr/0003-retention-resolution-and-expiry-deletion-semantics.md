# ADR-0003: retention resolution と expiry deletion semantics

- 日付: 2026-09-20
- 関連 Issue: #8, #27
- 前提 ADR: ADR-0001, ADR-0002

## コンテキスト

ADR-0001 は、外部 provider が event ごとの retention policy を直接指定せず、Kansokusha が acceptance boundary で policy を解決することを決めた。

ADR-0002 は resolved policy key を compact `retention_policy_id` として event row に保存し、high-volume な `events` table に durable event ID や manual ART index を置かないことを決めた。一方、expiry representation と bounded deletion の targeting は本 ADR に委ねている。

本 ADR は、retention resolution、persisted expiry、bounded deletion、automatic cleanup の semantics を固定する。

## 要件への対応

| 要件 | 本 ADR の担当範囲 |
| --- | --- |
| §8 | event row に retention identity と expiry を保持する representation |
| §9 | event type ごとの retention、automatic expiry、bounded cleanup、caller thread からの分離 |

具体的な policy 名、duration、event type mapping、cleanup interval、1 pass の削除上限値は operator configuration / built-in catalog に委ねる。

## 決定

### 1. policy identity は `Key`、duration は millisecond-aligned `Duration` とする

retention policy の identity は namespace-qualified な Adventure `Key` とする。storage では ADR-0002 の dictionary により compact `INTEGER` ID に変換する。

policy duration の runtime representation は `java.time.Duration`、configuration representation は ISO-8601 duration text とする。validation では次を要求する。

- duration は正である。
- whole milliseconds で表現できる。
- millisecond count への変換が overflow しない。

millisecond alignment は `occurred_at TIMESTAMP_MS` と persisted expiry の precision を一致させるためである。

built-in policy name、built-in duration、hard-coded fallback duration は定義しない。

### 2. exact mapping、次に explicit fallback policy の順で解決する

retention configuration は logical に次を持つ。

- `policies: Key -> Duration`
- `event type mappings: event type Key -> policy Key`
- operator が明示する `fallback policy Key`

event type に exact mapping があればそれを使用し、なければ fallback policy を使用する。mapping と fallback が参照する policy は、同じ validated configuration に存在しなければならない。

duplicate definition、不正な key / duration、unknown policy reference、fallback の欠落は configuration validation failure とする。

retention configuration が valid でなければ runtime を active にしない。初回起動用の config skeleton を生成することは許可するが、implicit policy / duration は生成しない。

reload が失敗した場合は invalid replacement を適用せず、既に active な valid policy set を維持する。

### 3. expiry は occurrence time から一度だけ決め、absolute `TIMESTAMP_MS` として保存する

persisted retention facts は resolved policy identity と absolute `expiresAt` とする。

```text
occurredAtMillis = truncate occurredAt to milliseconds
expiresAtMillis  = occurredAtMillis + policyDurationMillis
```

overflow や persistent timestamp として表現できない値は resolution failure とし、wraparound / saturation は行わない。

`events` は次の column を持つ。

```sql
expires_at TIMESTAMP_MS NOT NULL
```

policy duration 自体は event row に複製しない。configuration reload で同じ policy key の duration が変わっても、既存 event の `expires_at` は再計算しない。

storage writer は resolved policy identity と `expiresAt` を受け取り、retention decision を再計算しない。

### 4. `expires_at <= cutoff` を expired とする

cleanup pass は開始時に current instant を一度取得し、millisecond precision の `cutoff` として固定する。

その pass の削除対象は次を満たす row とする。

```text
expires_at <= cutoff
```

selection と deletion は同じ cutoff を使用する。

### 5. bounded deletion は transaction-local `rowid` を使う

retention cleanup のための durable event ID、PK、UNIQUE constraint、manual ART index は追加しない。

1回の cleanup pass は1 transaction 内で次を行う。

1. expired row から最大 `maxRowsPerPass` 件の `rowid` を選択する。
2. 選択した row だけを削除する。
3. deleted row count を返して commit する。

```sql
SELECT rowid
FROM events
WHERE expires_at <= ?
LIMIT ?;
```

選択した `rowid` は同じ transaction 内だけで targeting に使用し、commit 後に保存・公開・再利用しない。

selection に `ORDER BY` は要求しない。したがって eventual deletion の保証は、新たな expired row が追加され続けない有限集合に対するものとする。継続的な expiry 流入下では per-row fairness や finite-time deletion latency を保証しない。

v1 では `expires_at` に manual ART index を追加しない。必要性が確認された場合は write/memory cost と合わせて再評価する。

### 6. startup immediate pass の後、fixed-delay で1 passずつ実行する

automatic cleanup は configuration から次を受け取る。

- positive / whole-millisecond ISO-8601 `Duration` の cleanup interval
- positive integer の `maxRowsPerPass`

runtime startup 後、storage が cleanup を実行できる状態になり次第、background execution context で initial pass を delay なしに1回実行する。

以後は各 pass の完了後から configured interval を置く fixed-delay とする。1 invocation は1 passだけ実行し、expired row がなくなるまで loop しない。

### 7. cleanup failure は報告し、次回 schedule を維持する

cleanup は recording caller thread では実行せず、event writer と同じ storage ownership rule に従って storage access を serialize する。

failure 時は cleanup transaction を rollback し、administrator-visible reporting path に渡す。failure を success や deleted-row-count 0 として扱わない。

1回の recoverable failure で scheduler を恒久停止せず、runtime が稼働中なら configured interval 後に再試行する。shutdown 開始後は新しい cleanup pass を開始しない。

ただし JVM/runtime 自体の継続を信頼できない `VirtualMachineError`、`LinkageError`、`ThreadDeath` は terminal failure とする。これらも可能な範囲で administrator-visible reporting path に渡した後、cleanup lifecycle を failed として停止し、元の fatal error を再送出する。

## 検討した選択肢

### duration だけを保存し、query 時に expiry を計算する

policy duration の変更と historical event の expiry semantics が結びつくため採用しない。absolute `expires_at` を記録時に固定する。

### cleanup 用の durable event ID を追加する

v1 には durable point identity requirement がなく、PK / ART maintenance を追加するため採用しない。同一 transaction 内だけで `rowid` を使用する。

### `expires_at` に index を追加する

cleanup scan は速くなり得るが、append-heavy write に index maintenance を追加するため v1 では採用しない。

### built-in fallback policy / duration を生成する

初回起動は簡単になるが、operator が選択していない retention decision を暗黙に作るため採用しない。valid retention configuration がない場合は runtime activation を失敗させる。

## 結果

- event ごとの expiry は記録時に absolute timestamp として確定し、後から policy 変更で書き換えない。
- retention cleanup のための durable event identity / index は導入せず、bounded pass 内だけ `rowid` を利用する。
- cleanup work は1 pass単位で bounded とし、startup immediate pass と fixed-delay retry で自動実行する。
- concrete policy values と operational bounds は configuration / catalog の責務として残る。

## 参照

- ADR-0001: `docs/adr/0001-v1-event-contract-and-api-boundaries.md`
- ADR-0002: `docs/adr/0002-duckdb-schema-and-migration-strategy.md`
- DuckDB SELECT / rowid: https://duckdb.org/docs/current/sql/statements/select
- DuckDB DELETE: https://duckdb.org/docs/current/sql/statements/delete
- Java Duration: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Duration.html
