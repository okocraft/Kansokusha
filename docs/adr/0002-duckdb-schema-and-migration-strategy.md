# ADR-0002: DuckDB schema と migration strategy

- 日付: 2026-09-20
- 関連 Issue: #6, #16
- 前提 ADR: ADR-0001

## コンテキスト

ADR-0001 は、公開 event type identity を Adventure `Key` の `namespace:value` とし、runtime registration と persistent identity を分離した。また、payload format generation を event type ごとの正整数とし、`EventSubmission` の common field と opaque payload、および受理後に解決された retention policy key を定義した。

Kansokusha v1 はこれらを各 Paper / Folia server または Velocity proxy のローカル DuckDB file に永続化する。provider が一時的に存在しない場合でも既存 event type identity と過去 payload generation を失ってはならず、schema を将来変更するときも有効な既存 data を破棄してはならない。

本 ADR は Issue #16 が指定する要件定義 §7、§8、§10、§13、§15 に対して、persistent identity、event record schema、transaction boundary、forward migration と failure behavior を決定する。

retention policy の具体的な名前・duration・event type への割り当て・persisted expiry representation・expiry deletion semantics は #27 で決定する。ただし、その決定は initial v1 schema と batch writer に必要なので、#27 を #19 より前、#29 を #21 より前の prerequisite とする。#27 後に「いつか migration を追加する」のではなく、#19 が initial schema にその representation を含め、#21 が #29 で解決された metadata をそのまま保存する。

## 要件への対応

| 要件 | 本 ADR の決定 |
| --- | --- |
| §7 | public event type key を immutable な persistent metadata として保存し、provider の active / inactive と独立した compact internal ID へ対応付ける。 |
| §8 | common field と opaque payload を lossless に event record へ保持し、payload generation は event type ごとに履歴を残す。retention に必要な persisted expiry fact は #27 で決定し initial schema に含める。 |
| §10 | 1 instance ごとの local DuckDB file に正規化した metadata と append-oriented event record を保存する。 |
| §13 | persistent registry update と event batch write の transaction boundary を明示し、失敗した batch を部分成功として扱わない。high-volume event table の index maintenance cost も schema decision に含める。 |
| §15 | integer version の forward-only migration を順序通りに適用し、失敗時は rollback して既存の valid data を保持する。未知の新しい schema は拒否する。 |

## 決定

### 1. schema migration history を database 内に保持する

storage open 時に migration runner が `schema_migrations` を bootstrap する。

```sql
CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    name VARCHAR NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
```

migration は `1, 2, 3, ...` の連続した正整数で識別する。database に記録済みの version は、実行中の application が知る migration list の連続した prefix でなければならない。

- database の latest version が application の latest known version より新しい場合は storage open を失敗させる。
- version に gap、duplicate、または application が知らない適用済み version がある場合は schema state を不正として失敗させる。
- up-to-date database では migration を再実行しない。
- downgrade は実装しない。
- 適用済み migration の内容は immutable とし、schema 変更は必ず新しい version を追加する。

`schema_migrations` 自体の bootstrap は concrete v1 table migration から分離する。これにより migration runner は v1 schema の内容を知らずに実装できる。

### 2. v1 の persistent event type identity は compact ID と canonical key を分離する

v1 migration は次の sequence と metadata table を作成する。

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

`event_type_key` は ADR-0001 で定義した `Key#asString()` の canonical `namespace:value` を保存する。database から復元するときは公開 `Key` へ再構成する。

`event_types.id` と `payload_generations.id` は database file 内だけで使う compact surrogate ID であり、public identity ではない。sequence の値に gap が生じても意味を持たせない。

同じ key の再登録では既存 `event_types.id` を返す。新しい payload generation は同じ `event_type_id` の下に新しい row を追加し、過去 generation を update / delete しない。runtime provider の unregister や plugin removal はこれらの row を削除しない。

metadata table は event record より桁違いに小さいことを前提とし、ここでは PK / UNIQUE / FK による integrity と、それに伴う DuckDB ART index maintenance を受け入れる。

### 3. high-volume の `events` table には ART を暗黙作成する PK / FK / UNIQUE を置かない

DuckDB は `PRIMARY KEY`、`UNIQUE`、`FOREIGN KEY` の constraint に対して ART index を自動作成する。ART は constraint enforcement に有用だが、insert / update / delete ごとに maintenance cost があり、memory と disk にも追加 data を持つ。

Kansokusha の `events` は append-heavy で、v1 では public event ID による point lookup や row 単位 update を要求しない。そのため、event table 自体には UUID primary key を設けず、`payload_generation_id` にも database-level foreign key を張らない。

v1 の base event columns は次のとおりとする。#27 が選ぶ persisted expiry columns は #19 がこの table の initial migration に追加する。

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
    -- persisted expiry column(s): defined by #27 before #19 is implemented
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

`payload_generation_id` の referential integrity は storage implementation が保証する。writer は同じ transaction 内で persistent registry から generation ID を解決した後にだけ event row を insert する。Kansokusha 自身が不正な ID を書かないことを integration test で検証する。

この判断は、database を手作業で編集した場合まで孤立 row を防ぐ強い constraint より、通常運用で継続的に増加する event rows の write / memory cost を優先する trade-off である。将来、measurement により FK enforcement の利益がコストを上回ると分かった場合は migration で追加できる。

### 4. `Instant` は epoch second と nano adjustment に分けて lossless に保存する

`EventSubmission.occurredAt` は Java `Instant` であり、現在の public contract は DuckDB `TIMESTAMP_NS` / signed 64-bit epoch nanoseconds の範囲に制限していない。

そのため `occurred_at` を単一の `TIMESTAMP_NS` とせず、次の2値で保存する。

- `occurred_at_epoch_second BIGINT`: `Instant#getEpochSecond()`
- `occurred_at_nano INTEGER`: `Instant#getNano()`。範囲は 0 以上 1,000,000,000 未満

復元時は `Instant.ofEpochSecond(epochSecond, nano)` を用いる。この表現は current Java `Instant` contract の全域を表現でき、storage 固有の約 ±292 年の epoch-nanoseconds 制約を public API に逆流させない。

将来 SQL 側で timestamp function を必要とする query を追加する場合は、query boundary で変換するか、要件に応じて derived column / schema migration を検討する。v1 の storage correctness のために public `Instant` の受理範囲を暗黙に狭めない。

### 5. v1 event record は common model を直接復元できる形で保存する

column mapping は次のとおりとする。

- `payload_generation_id`: `payload_generations` を lookup して event type key と generation の両方を復元する。event row には `event_type_id` を重複保存しない。
- `occurred_at_epoch_second`, `occurred_at_nano`: `EventSubmission.occurredAt` を lossless に表現する。
- `server_key`, `world_key`: Adventure `Key` の canonical string。server は必須、world は optional。
- `block_x`, `block_y`, `block_z`: `BlockPosition`。3 column は all-null または all-non-null とし、position がある場合は `world_key` を必須とする。
- `subject_player_uuid`: v1 で定義済みの `PlayerSubject`。subject がない場合は null。将来別 subject variant が必要になった場合は migration で schema を拡張する。
- `retention_policy_key`: resolved policy identity の canonical string。
- persisted expiry column(s): #27 が定義し、#19 が initial schema に含め、#29 が domain 側で計算し、#21 がその値を保存する。
- `payload`: opaque byte sequence を `BLOB` のまま保存し、storage layer は解釈しない。

server / world / retention policy identity は v1 では別 dictionary table に正規化しない。これらには event type と同じ persistent surrogate identity requirement がないためである。

### 6. retention schema は #27 を initial migration の prerequisite とする

E2-T1 は retention policy identity を event row に保持する boundary を決めるが、expiry の exact representation を先決めしない。一方で expiry representation を後続の担当未定 migration へ先送りもしない。

実装順序は次の dependency を持つ。

1. #16 で storage / migration boundary を確定する。
2. #27 で persisted expiry representation を確定する。
3. #19 が #16 と #27 の両方に従って initial v1 schema を作成する。
4. #28 / #29 が policy configuration と resolved expiry metadata を domain model に実装する。
5. #21 が #29 の resolved metadata を recompute せず batch transaction 内で保存する。
6. #30 が同じ persisted representation を用いて bounded deletion を実装する。

したがって、v1 の fresh database が #19 を適用した時点で retention deletion に必要な storage shape は存在する。expiry metadata を追加するためだけの follow-up migration は不要とする。

### 7. persistent registry update は append-only な find-or-create とする

persistent registry は次の順で解決する。

1. canonical `event_type_key` で `event_types` を lookup し、なければ insert する。
2. `(event_type_id, generation)` で `payload_generations` を lookup し、なければ insert する。
3. 既存 row がある場合は immutable identity として再利用し、別の row へ置換しない。

`UNIQUE` constraint を metadata table 側の最終的な整合性境界とする。identity conflict を `UPDATE` で上書きして解決してはならない。

persistent registry を単独で呼ぶ場合はその operation を transaction とする。event batch write から呼ぶ場合は caller の batch transaction に参加し、metadata resolution と event insert を同じ commit boundary に含める。

### 8. 1 batch は metadata resolution を含む 1 transaction とする

E2-T6 の batch append は次を 1 transaction で行う。

1. batch に必要な event type / payload generation を find-or-create する。
2. #29 が解決した retention / expiry metadata を含め、全 event row を insert する。
3. すべて成功した場合だけ commit する。

constraint violation、binding failure、I/O failure などで batch 内のいずれかが失敗した場合は rollback し、その batch の event row と、その transaction で新規作成した metadata row を commit しない。既に commit 済みの過去 batch と metadata は変更しない。

成功 outcome は `COMMIT` 完了後にのみ返す。transaction は storage layer 内で完結し、game-processing caller thread や後続の asynchronous queue lifecycle を transaction に含めない。

### 9. migration は version ごとの transaction で forward にのみ進める

runner は未適用 migration を version 順に 1 件ずつ処理する。

各 migration について `BEGIN` し、schema / data transformation を実行し、最後に同じ transaction 内で `schema_migrations` row を insert して `COMMIT` する。migration が失敗した場合は `ROLLBACK` し、その version を適用済みとして記録しない。

既存 data の変換が必要な migration は、同一 transaction 内で add / copy / validate / replace する。失敗時の fallback として database file、table、または valid rows を無条件に削除して再作成してはならない。

将来、DuckDB 上で必要な変更が transaction 内では安全に実行できない場合は generic runner が部分適用を許可するのではなく、その migration の安全な手順を別途設計する。

migration failure、unknown newer version、invalid history は storage initialization error として caller に伝播し、writer を開始しない。自動 downgrade や silent reset は行わない。

### 10. manual secondary index は query requirement と measurement が出るまで追加しない

DuckDB は general-purpose column に zonemap を自動作成する。v1 では `events` に manual `CREATE INDEX` を追加しない。また、前述のとおり high-volume event table では PK / FK / UNIQUE 由来の implicit ART も避ける。

metadata table の PK / UNIQUE / FK が作る ART は、stable identity と uniqueness enforcement のため受け入れる。

retention deletion predicate は #27 で決める。#27 が exact expiry representation を決めた後も、range-oriented deletion に ART が有効だと仮定せず、zonemap と bounded scan を基本とする。後続 measurement で明確な利益が確認された場合にのみ migration で secondary index を追加する。

## 検討した選択肢

### event row に public key と generation を直接保存する

event ごとに `namespace:value` と generation を重複保存する案は、write volume を増やし、同じ persistent identity を一箇所で保証できないため採用しない。metadata table で stable identity を保持し、event row は compact generation ID を参照する。

### event type row に active generation だけを保持する

current generation を mutable column で上書きすると過去 payload の解釈に必要な generation history を失うため採用しない。generation は append-only child row とする。

### provider unregister 時に persistent metadata を削除する

runtime availability と stored identity を結合し、re-registration や過去 event の復元を壊すため採用しない。

### `events.event_id UUID PRIMARY KEY` を設ける

v1 requirement に public / storage event ID を使う point lookup、update、deduplication はない。UUID PK は高頻度 insert のたびに ART maintenance を追加する一方、現在の機能では利用先がないため採用しない。row identity が必要な具体的要件が生じた場合は、その用途に適した identity と index を migration で追加する。

### `events.payload_generation_id` に FOREIGN KEY を張る

DuckDB は FK constraint にも ART を自動作成する。event table の全 row で ART を維持するコストを避けるため採用しない。metadata resolution と event insert を同じ storage transaction が所有し、application-level invariant と integration test で valid generation ID を保証する。

### `occurred_at TIMESTAMP_NS` を使う

単純だが、epoch nanoseconds が signed 64-bit に収まる範囲へ current public `Instant` contract を暗黙に狭める。storage layer が public contract の valid value を lossless に保存できなくなるため採用しない。

### migration failure 時に database を再作成する

valid existing data を失うため採用しない。failure は rollback して明示的に報告する。

### v1 で server / world / retention policy をすべて dictionary 化する

current requirement に stable surrogate identity がなく、schema と registry implementation を増やすため採用しない。必要性が生じた時点で migration する。

## 結果

- provider が消えても event type key と既存 payload generations は database に残り、同じ key は reopen / re-registration 後も同じ internal identity へ解決される。
- current common model の `Instant` を含む event data と opaque payload を storage 固有の timestamp range で欠損させず復元できる。
- high-volume `events` table は v1 では implicit / manual ART index を持たず、metadata table に必要な integrity index を限定する。
- #27 の retention decision は initial schema より前に行われ、#29 の resolved expiry metadata は #21 が保存するため、担当不在の follow-up schema migration を生まない。
- batch failure は partial success として commit されない。
- schema evolution は既存 valid data を保持したまま forward migration でき、unknown / failed migration は visible initialization failure になる。

## 参照

- DuckDB Transaction Management: https://duckdb.org/docs/stable/sql/statements/transactions
- DuckDB Constraints: https://duckdb.org/docs/stable/sql/constraints
- DuckDB Indexing Performance Guide: https://duckdb.org/docs/current/guides/performance/indexing
- DuckDB Indexes: https://duckdb.org/docs/stable/sql/indexes
- DuckDB Timestamp Functions: https://duckdb.org/docs/stable/sql/functions/timestamp
- Java `Instant`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Instant.html
