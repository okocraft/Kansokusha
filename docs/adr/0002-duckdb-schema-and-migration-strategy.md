# ADR-0002: DuckDB schema と migration strategy

- 日付: 2026-09-20
- 関連 Issue: #6, #16
- 前提 ADR: ADR-0001

## コンテキスト

ADR-0001 は、公開 event type identity を Adventure `Key` の `namespace:value` とし、runtime registration と persistent identity を分離した。また、payload format generation を event type ごとの正整数とし、`EventSubmission` の common field と opaque payload、および受理後に解決された retention policy key を定義した。

Kansokusha v1 はこれらを各 Paper / Folia server または Velocity proxy のローカル DuckDB file に永続化する。provider が一時的に存在しない場合でも既存 event type identity と過去 payload generation を失ってはならず、schema を将来変更するときも有効な既存 data を破棄してはならない。

本 ADR は Issue #16 が指定する要件定義 §7、§8、§10、§13、§15 に対して、persistent identity、event record schema、transaction boundary、forward migration と failure behavior を決定する。retention policy の具体的な名前・duration・event type への割り当て・expiry deletion semantics は #27 で決定する。

## 要件への対応

| 要件 | 本 ADR の決定 |
| --- | --- |
| §7 | public event type key を immutable な persistent metadata として保存し、provider の active / inactive と独立した compact internal ID へ対応付ける。 |
| §8 | common field、opaque payload、payload generation、resolved retention policy key を lossless に event record へ保持する。payload generation は event type ごとに履歴を残す。 |
| §10 | 1 instance ごとの local DuckDB file に正規化した metadata と append-oriented event record を保存する。 |
| §13 | persistent registry update と event batch write の transaction boundary を明示し、失敗した batch を部分成功として扱わない。 |
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

v1 migration は次の sequence と table を作成する。

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

### 3. v1 event record は common model を直接復元できる形で保存する

v1 migration は次の event table を作成する。

```sql
CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    payload_generation_id INTEGER NOT NULL REFERENCES payload_generations(id),
    occurred_at TIMESTAMP_NS NOT NULL,
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

column mapping は次のとおりとする。

- `event_id`: storage internal row identity。writer が UUID v7 を割り当てる。public API の event identity にはしない。
- `payload_generation_id`: `payload_generations` 経由で event type key と generation の両方を復元する。`event_type_id` を event row に重複保存しない。
- `occurred_at`: `EventSubmission.occurredAt`。UTC の instant として `TIMESTAMP_NS` に保存する。JDBC の microsecond timestamp binding に依存せず、writer は epoch nanoseconds と DuckDB の `make_timestamp_ns` / `epoch_ns` を使って nanosecond precision を維持する。
- `server_key`, `world_key`: Adventure `Key` の canonical string。server は必須、world は optional。
- `block_x`, `block_y`, `block_z`: `BlockPosition`。3 column は all-null または all-non-null とし、position がある場合は `world_key` を必須とする。
- `subject_player_uuid`: v1 で定義済みの `PlayerSubject`。subject がない場合は null。将来別 subject variant が必要になった場合は migration で schema を拡張する。
- `retention_policy_key`: `AcceptedEvent` で解決済みの policy key。canonical string として保存する。
- `payload`: opaque byte sequence を `BLOB` のまま保存し、storage layer は解釈しない。

server / world / retention policy は v1 では別 dictionary table に正規化しない。これらには event type と同じ persistent identity requirement がなく、retention policy の具体的な persistent facts は #27 の決定を待つためである。

### 4. retention は policy identity だけを v1 schema の必須 persistent fact とする

E2 の時点では `AcceptedEvent` が持つ resolved `retention_policy_key` を各 event row に保存する。duration、expiry timestamp、cleanup cursor などは本 ADR で先行決定しない。

#27 が event ごとに追加の retention fact を永続化すると決定した場合は、既存 `events` を破棄せず新しい migration で column / table / index を追加する。

これにより E2 は current common contract を lossless に永続化でき、E4 は policy resolution と deletion semantics を独立して決定できる。

### 5. persistent registry update は append-only な find-or-create とする

persistent registry は次の順で解決する。

1. canonical `event_type_key` で `event_types` を lookup し、なければ insert する。
2. `(event_type_id, generation)` で `payload_generations` を lookup し、なければ insert する。
3. 既存 row がある場合は immutable identity として再利用し、別の row へ置換しない。

`UNIQUE` constraint を database 側の最終的な整合性境界とする。identity conflict を `UPDATE` で上書きして解決してはならない。

persistent registry を単独で呼ぶ場合はその operation を transaction とする。event batch write から呼ぶ場合は caller の batch transaction に参加し、metadata resolution と event insert を同じ commit boundary に含める。

### 6. 1 batch は metadata resolution を含む 1 transaction とする

E2-T6 の batch append は次を 1 transaction で行う。

1. batch に必要な event type / payload generation を find-or-create する。
2. 全 event row を insert する。
3. すべて成功した場合だけ commit する。

constraint violation、binding failure、I/O failure などで batch 内のいずれかが失敗した場合は rollback し、その batch の event row と、その transaction で新規作成した metadata row を commit しない。既に commit 済みの過去 batch と metadata は変更しない。

成功 outcome は `COMMIT` 完了後にのみ返す。transaction は storage layer 内で完結し、game-processing caller thread や後続の asynchronous queue lifecycle を transaction に含めない。

### 7. migration は version ごとの transaction で forward にのみ進める

runner は未適用 migration を version 順に 1 件ずつ処理する。

各 migration について `BEGIN` し、schema / data transformation を実行し、最後に同じ transaction 内で `schema_migrations` row を insert して `COMMIT` する。migration が失敗した場合は `ROLLBACK` し、その version を適用済みとして記録しない。

既存 data の変換が必要な migration は、同一 transaction 内で add / copy / validate / replace する。失敗時の fallback として database file、table、または valid rows を無条件に削除して再作成してはならない。

将来、DuckDB 上で必要な変更が transaction 内では安全に実行できない場合は generic runner が部分適用を許可するのではなく、その migration の安全な手順を別途設計する。

migration failure、unknown newer version、invalid history は storage initialization error として caller に伝播し、writer を開始しない。自動 downgrade や silent reset は行わない。

### 8. v1 では secondary index を先行追加しない

`PRIMARY KEY` / `UNIQUE` constraint により metadata lookup と `events.event_id` 用の ART index は作成される。一方、v1 migration では追加の `CREATE INDEX` を行わない。

DuckDB は general-purpose column に zonemap を自動作成し、manual ART index は point lookup / 高選択性 lookup 向けである。event table は append-heavy であり、retention deletion predicate も #27 で未決定なので、`occurred_at` や `retention_policy_key` への secondary index を推測で追加して write cost を増やさない。

#27 で具体的な expiry predicate が決まり、measurement で有効性が確認できた場合は migration で retention-specific index を追加する。

## 検討した選択肢

### event row に public key と generation を直接保存する

event ごとに `namespace:value` と generation を重複保存する案は、write volume を増やし、同じ persistent identity を一箇所で保証できないため採用しない。metadata table で stable identity を保持し、event row は compact generation ID を参照する。

### event type row に active generation だけを保持する

current generation を mutable column で上書きすると過去 payload の解釈に必要な generation history を失うため採用しない。generation は append-only child row とする。

### provider unregister 時に persistent metadata を削除する

runtime availability と stored identity を結合し、re-registration や過去 event の復元を壊すため採用しない。

### migration failure 時に database を再作成する

valid existing data を失うため採用しない。failure は rollback して明示的に報告する。

### v1 で server / world / retention policy をすべて dictionary 化する

current requirement に stable surrogate identity がなく、schema と registry implementation を増やすため採用しない。必要性が生じた時点で migration する。

## 結果

- provider が消えても event type key と既存 payload generations は database に残り、同じ key は reopen / re-registration 後も同じ internal identity へ解決される。
- event record から ADR-0001 の common model と opaque payload を復元できる。
- batch failure は partial success として commit されない。
- schema evolution は既存 valid data を保持したまま forward migration でき、unknown / failed migration は visible initialization failure になる。
- retention の具体的な expiry facts と cleanup index は E4 の決定に残し、E2 で speculative schema を固定しない。

## 参照

- DuckDB Transaction Management: https://duckdb.org/docs/stable/sql/statements/transactions
- DuckDB CREATE SEQUENCE: https://duckdb.org/docs/stable/sql/statements/create_sequence
- DuckDB Timestamp Types / Functions: https://duckdb.org/docs/stable/sql/data_types/timestamp.html
- DuckDB Indexes: https://duckdb.org/docs/stable/sql/indexes
