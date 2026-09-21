# ADR-0004: bounded ingestion, batching, and failure semantics

- 日付: 2026-09-21
- 関連 Issue: #7, #22
- 前提 ADR: ADR-0001, ADR-0002, ADR-0003

## コンテキスト

ADR-0001 は `KansokushaApi.submit` の `ACCEPTED` が非同期 ingestion 境界への所有権移転だけを表し、永続化完了を意味しないことを決めた。また、queue saturation や writer failure 時に使用する `INGESTION_UNAVAILABLE` の具体的な条件を本 ADR に委ねている。

ADR-0002 は metadata resolution と event batch write を1 transaction にまとめ、storage-owned write path で直列化することを決めた。ADR-0003 は retention policy と absolute expiry を acceptance boundary で確定し、retention cleanup も recording caller thread では実行しないことを決めた。

本 ADR は v1 の asynchronous ingestion pipeline について、bounded intake、batching、failure state、administrator-visible reporting、normal shutdown drain の semantics を固定する。

## 要件への対応

| 要件 | 本 ADR の担当範囲 |
| --- | --- |
| §11 | recording caller を storage I/O で待たせないこと、batch write、後続計測が可能な明確な pipeline boundary |
| §12 | intake queue と writer batch の明示的な有限 bound |
| §13 | accepted / persisted / failed-to-persist の区別、storage failure の可視化、正常停止時の drain |

具体的な queue capacity、batch size、batch delay 等の運用値は configuration に委ね、この ADR では値を固定しない。v1 は shutdown timeout を設けない。

## 決定

### 1. recording path は in-memory resolution と bounded queue への non-blocking offer だけを行う

runtime は正の有限 capacity を持つ thread-safe な intake queue を1つ持つ。caller thread は registration / payload generation の検証後、ADR-0003 の retention policy と absolute expiry を in-memory で解決して `AcceptedEvent` を作り、`offer` 相当で non-blocking に queue へ渡す。DuckDB access、persistent metadata lookup、batch flush 完了は待たない。

intake admission は「pipeline が `RUNNING` であることの判定」と「event ownership の queue への移転」を1つの論理操作として扱い、`RUNNING -> DRAINING` および `RUNNING -> FAILED` transition と線形化しなければならない。実装は lock、atomic state、queue close protocol 等の具体手段を自由に選べるが、次の保証を満たす。

- admission が lifecycle transition より先に linearize した場合、その event は accepted 済みとして transition 側が必ず考慮する。
- lifecycle transition が先に linearize した場合、その後の admission は ownership transfer に成功してはならない。
- state を `RUNNING` と読んだ後に transition が完了し、それでも単独の `offer` が成功して `ACCEPTED` を返す check-then-act race を許容しない。

したがって、`DRAINING` または `FAILED` transition の完了後に新しい event が queue ownership を取得することはない。

submission outcome は、ownership transfer 成功を `ACCEPTED`、queue saturation・writer failure・event-specific retention resolution failure を `INGESTION_UNAVAILABLE`、shutdown 後を `CLOSED` とする。retention resolution failure はその submission だけを拒否し、pipeline 全体を terminal failure にしない。

`ACCEPTED` は永続化完了を意味しない。queue capacity は startup 前に正の有限整数として validation する。

### 2. queue は resolved AcceptedEvent を保持し、persistent metadata resolution は writer 側に残す

intake queue は ADR-0003 に従って policy identity と absolute `expiresAt` が固定済みの `AcceptedEvent` を保持し、queue 待機中の configuration reload で retention decision が変化しないようにする。persistent metadata ID の解決と DuckDB write は dedicated writer context の storage transaction に残す。

### 3. dedicated single consumer が batch を所有する

通常運用では dedicated writer worker を queue の唯一の consumer とし、event persistence を行う唯一の component とする。

writer の temporary batch collection は正の有限 `maxBatchSize` を超えない。batch size は queue capacity と独立に configuration 可能とする。

writer は次の trigger で batch を flush する。

1. `maxBatchSize` 件に達したとき
2. 最初の event を batch に入れてから configured `maxBatchDelay` が経過したとき
3. orderly shutdown drain で queue が空になり、partial batch が残っているとき

`maxBatchDelay` は正で whole-millisecond の有限 `Duration` とする。

idle 中は busy loop せず、queue wait / timed poll 等で worker を休止させる。flush timer は batch に最初の event が入った時点から測り、継続的な流入で partial batch の flush が無期限に延期されないようにする。

### 4. 1 batch は storage transaction 1回に対応する

writer は batch ごとに storage operation を1回実行する。ADR-0002 のとおり persistent metadata ID の find-or-create と event inserts を同じ transaction に含め、commit 後にだけ persisted とみなす。per-event persistence future / acknowledgment は保持しない。

retained memory は概ね次で bounded になる。

```text
intake queue <= queueCapacity
writer batch <= maxBatchSize
```

storage implementation が内部で保持する一時 collection も batch size に比例する範囲に限定する。

### 5. 最初の persistence failure で pipeline を terminal FAILED state にする

v1 は automatic writer recovery、unbounded retry queue、exponential backoff、dead-letter storage を実装しない。

batch persistence が失敗した場合、pipeline は最初の failure cause を保持して terminal `FAILED` state へ遷移する。

failure transition 後は新しい submission を `INGESTION_UNAVAILABLE` とし、失敗した batch と queue 内の accepted event を再試行せず failed-to-persist とする。writer は persistence loop を終了し、最初の failure cause を保持して administrator-visible reporting port へ1回だけ通知する。

runtime state は `RUNNING`, `DRAINING`, `FAILED`, `CLOSED` を区別し、`FAILED` では最初の failure cause を inspection 可能にする。

### 6. administrator-visible reporting は platform-neutral port とする

common runtime は storage / pipeline failure を受け取る platform-neutral reporting port を持ち、Paper / Folia / Velocity adapter が logger 等へ接続する。reporting failure は original pipeline failure を置き換えず、writer の再開理由にもならない。

### 7. lifecycle transition と submission outcome を明確に分ける

pipeline の主要 state transition は次とする。

```text
NEW -> RUNNING -> DRAINING -> CLOSED
          |           |
          v           v
        FAILED <------+
          |
          v
        CLOSED
```

- `NEW`: submission を受けない。
- `RUNNING`: intake と writer persistence が有効。
- `DRAINING`: new intake を閉じ、accepted event だけを処理する。
- `FAILED`: `RUNNING` または `DRAINING` 中の persistence failure による terminal unavailable state。
- `CLOSED`: lifecycle 終了。

public API が閉じていれば ADR-0001 の `CLOSED` を優先する。queue saturation は terminal transition ではなく、capacity が空けば後続 submission を再び受理できる。


### 8. normal shutdown は intake を先に閉じ、その後 accepted event を drain する

正常停止開始時は次の順序とする。

1. public intake を閉じ、新しい event の ownership を取得しない。
2. writer に drain 開始を通知する。
3. queue 内の accepted event と partial batch を可能な限り flush する。
4. writer が終了した後に storage resource を閉じる。
5. runtime を `CLOSED` にする。

v1 は shutdown timeout を設けず、正常停止では drain 完了まで lifecycle / shutdown context が待機する。drain 中の storage failure は `FAILED` とする。この待機は submission path では行わない。

JVM crash、OS crash、強制終了では未永続化 event が失われ得るため、`ACCEPTED` は crash durability を意味しない。

### 9. storage access は runtime 内で serialized ownership を維持する

writer と cleanup を含む storage operation は同一の serialized ownership mechanism を通し、submission path はその ownership 待ちを行わない。

## 状態の意味

| 状態 | event の意味 |
| --- | --- |
| rejected | queue ownership を取得しておらず、`ACCEPTED` を返していない |
| accepted | bounded asynchronous pipeline が resolved event の ownership を取得したが、未永続化でもよい |
| persisted | batch transaction が commit 済み |
| failed-to-persist | accepted 後、storage failure または terminal failure transition により永続化されなかった |

v1 public API は event ごとの persistence receipt を提供せず、記録不能は runtime failure state と reporting port で管理者へ知らせる。

## 検討した選択肢

### unbounded queue

§12 の有限メモリ要件を満たさないため採用しない。

### caller を queue capacity が空くまで block する

game-processing thread が writer / storage の進行を待ち得るため採用せず、saturation は `INGESTION_UNAVAILABLE` とする。

### retention resolution を writer dequeue 後まで遅延する

caller-side work は減るが、accepted event が queue 待機中の configuration reload によって別 policy / expiry へ変化し得て ADR-0003 の acceptance-boundary semantics に反するため採用しない。

### event ごとの Future を返す

caller の待機と未完了 future の lifecycle / memory ownership を増やすため v1 では採用しない。

### storage failure 後に同じ batch を無期限 retry する

一時障害から自動回復できる場合があるが、retry buffer、duplicate semantics、backoff、shutdown interaction が必要になるため v1 の範囲を超える。最初の failure を terminal state とし、管理者へ明示する。

### failure 後も新しい event を queue に受け続ける

永続化見込みのない event に `ACCEPTED` を返すため採用しない。

## 結果

- recording caller は DuckDB I/O を待たず、accepted event の retention decision は acceptance 時点で固定される。
- queue と batch は有限で、flush は size / delay / shutdown drain により発生する。
- storage failure は terminal `FAILED` state と administrator-visible report になり、正常停止は accepted event の drain を試みる。
- `ACCEPTED` は crash durability を保証しない。

## 参照

- ADR-0001: `docs/adr/0001-v1-event-contract-and-api-boundaries.md`
- ADR-0002: `docs/adr/0002-duckdb-schema-and-migration-strategy.md`
- ADR-0003: `docs/adr/0003-retention-resolution-and-expiry-deletion-semantics.md`
- Java BlockingQueue: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingQueue.html
- Java ArrayBlockingQueue: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html
