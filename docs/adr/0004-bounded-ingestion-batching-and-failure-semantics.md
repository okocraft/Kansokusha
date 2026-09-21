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

runtime は正の有限 capacity を持つ thread-safe な intake queue を1つ持つ。

`submit` の caller thread では、runtime registration と payload generation の検証後、ADR-0003 の retention policy resolution と absolute expiry 計算を行い、storage-facing `AcceptedEvent` を作る。これらは immutable configuration と event value に対する in-memory 計算だけとし、DuckDB access を含めない。

resolved event は queue へ non-blocking に渡す。queue への追加は待機型 `put` ではなく `offer` 相当とし、storage transaction、persistent metadata lookup、batch flush 完了を待たない。

intake admission は「pipeline が `RUNNING` であることの判定」と「event ownership の queue への移転」を1つの論理操作として扱い、`RUNNING -> DRAINING` および `RUNNING -> FAILED` transition と線形化しなければならない。実装は lock、atomic state、queue close protocol 等の具体手段を自由に選べるが、次の保証を満たす。

- admission が lifecycle transition より先に linearize した場合、その event は accepted 済みとして transition 側が必ず考慮する。
- lifecycle transition が先に linearize した場合、その後の admission は ownership transfer に成功してはならない。
- state を `RUNNING` と読んだ後に transition が完了し、それでも単独の `offer` が成功して `ACCEPTED` を返す check-then-act race を許容しない。

したがって、`DRAINING` または `FAILED` transition の完了後に新しい event が queue ownership を取得することはない。

結果は次のように扱う。

- retention resolution が成功し queue が ownership を受け取った場合: `ACCEPTED`
- queue が満杯の場合: `INGESTION_UNAVAILABLE`
- writer failure により ingestion が利用不能の場合: `INGESTION_UNAVAILABLE`
- event-specific な retention resolution が完了できず ownership を取得しない場合: `INGESTION_UNAVAILABLE`
- shutdown により intake が閉じている場合: `CLOSED`

retention resolution failure はその submission の acceptance failure であり、pipeline 全体を terminal failure にしない。valid configuration 自体を構築できない場合は runtime startup / reload の configuration failure として別に扱う。

`ACCEPTED` は queue に追加された時点で返し、event が後で storage failure により永続化されない可能性を含む。

queue capacity は runtime startup 前に validation し、正の有限整数でなければ runtime を active にしない。

### 2. queue は resolved AcceptedEvent を保持し、persistent metadata resolution は writer 側に残す

intake queue は ADR-0003 に従って policy identity と absolute `expiresAt` が固定済みの `AcceptedEvent` を保持する。

これにより、configuration reload が queue 待機中に発生しても、既に accepted 済み event の retention decision は後から変化しない。

一方、persistent event type / payload generation / server / world / retention policy の compact ID 解決と DuckDB write は caller thread へ移さない。これらは dedicated writer context から storage operation を呼び出した transaction 内で行う。

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

writer は batch を `DuckDbEventWriter.append` 相当の storage operation に1回渡す。

storage operation は ADR-0002 のとおり、必要な persistent metadata ID の find-or-create と event inserts を同じ transaction に含め、commit 後にだけ success を返す。

batch flush 成功後、その batch の event は persisted とみなし、writer-owned collection から解放する。per-event persistence future や acknowledgment object は保持しない。

これにより retained memory は概ね次で bounded になる。

```text
intake queue <= queueCapacity
writer batch <= maxBatchSize
```

storage implementation が内部で保持する一時 collection も batch size に比例する範囲に限定する。

### 5. 最初の persistence failure で pipeline を terminal FAILED state にする

v1 は automatic writer recovery、unbounded retry queue、exponential backoff、dead-letter storage を実装しない。

batch persistence が失敗した場合、pipeline は最初の failure cause を保持して terminal `FAILED` state へ遷移する。

failure transition 後は次を行う。

- 新しい submission は `INGESTION_UNAVAILABLE` とする。
- 失敗した batch は成功扱いせず、再 enqueue しない。
- queue に残っている accepted event は failed-to-persist とし、再試行のため保持し続けない。
- writer は通常の batch persistence loop を終了する。
- 最初の failure cause を administrator-visible reporting port へ1回通知する。

同じ terminal failure に対して event ごと、poll ごと、shutdown ごとに同じ error を繰り返し report しない。

runtime state は少なくとも `RUNNING`, `DRAINING`, `FAILED`, `CLOSED` を区別し、`FAILED` では最初の failure cause を inspection 可能にする。これは event ごとの durability receipt ではなく、runtime が現在 ingestion 不能であることを表す health state である。

### 6. administrator-visible reporting は platform-neutral port とする

common runtime は storage / pipeline failure を受け取る platform-neutral reporting port を持つ。

port は exception と operation context を受け取り、Paper / Folia / Velocity adapter が server logger 等へ接続する。common code は platform logger type に依存しない。

reporting port 自体が exception を投げても original pipeline failure を置き換えない。reporting failure は可能な範囲で secondary failure として扱い、writer を再開しない。

### 7. lifecycle transition と submission outcome を明確に分ける

pipeline の主要 state transition は次とする。

```text
NEW -> RUNNING -> DRAINING -> CLOSED
          |
          +------> FAILED -> CLOSED
```

- `NEW`: queue / writer はまだ submission を受けない。
- `RUNNING`: non-blocking intake と writer persistence が有効。
- `DRAINING`: new intake は閉じ、既に accepted 済みの event だけを処理する。
- `FAILED`: persistence failure 後の terminal unavailable state。
- `CLOSED`: worker と runtime resource の lifecycle が終了した状態。

public API 自体が閉じている場合は ADR-0001 の `CLOSED` を優先する。API が open でも pipeline が `FAILED` または saturation 中なら `INGESTION_UNAVAILABLE` を返す。

queue saturation は terminal state transition を起こさない。capacity が空けば後続 submission は再び `ACCEPTED` になり得る。

`RUNNING -> DRAINING` / `FAILED` transition は section 1 の intake admission と同じ linearization order に参加する。transition が完了した時点で、それより後に新たな ownership transfer が成立しないことを lifecycle invariant とする。

### 8. normal shutdown は intake を先に閉じ、その後 accepted event を drain する

正常停止開始時は次の順序とする。

1. public intake を閉じ、新しい event の ownership を取得しない。
2. writer に drain 開始を通知する。
3. queue 内の accepted event と partial batch を可能な限り flush する。
4. writer が終了した後に storage resource を閉じる。
5. runtime を `CLOSED` にする。

shutdown drain 中の storage failure は通常運用中と同じ `FAILED` transition と reporting semantics を使用する。失敗を success として扱わない。

v1 は shutdown timeout を設けない。正常停止では writer が queue と partial batch の drain を完了して終了するまで lifecycle / shutdown context が待機し、その後にだけ storage resource を閉じる。storage failure が発生した場合は通常どおり `FAILED` へ遷移して writer を終了させ、その終了後に storage resource を閉じる。

この待機は event-recording caller thread の submission path では行わない。JVM 強制終了や process termination により正常停止 sequence 自体が完了しない場合は、この drain guarantee の対象外とする。

JVM crash、OS crash、強制終了では queue または writer batch の未永続化 event が失われ得る。これは §13 の許容範囲であり、`ACCEPTED` は crash durability を意味しない。

### 9. storage access は runtime 内で serialized ownership を維持する

event writer は dedicated worker から storage operation を実行する。

retention cleanup 等の別 background storage operation は ADR-0003 のとおり recording caller thread では動かさず、database の serialized storage ownership mechanism を通す。cleanup と event batch write が同時に同一 connection を操作しないようにする。

caller thread が queue offer 後に database lock の取得を待つ設計は採用しない。

## 状態の意味

| 状態 | event の意味 |
| --- | --- |
| rejected | queue ownership を取得しておらず、`ACCEPTED` を返していない |
| accepted | bounded asynchronous pipeline が resolved event の ownership を取得したが、未永続化でもよい |
| persisted | batch transaction が commit 済み |
| failed-to-persist | accepted 後、storage failure または terminal failure transition により永続化されなかった |

v1 public API は persisted / failed-to-persist を event ごとに問い合わせる receipt API を提供しない。管理者には runtime failure state と reporting port で記録不能を知らせる。

## 検討した選択肢

### unbounded queue

一時的な writer 遅延を吸収しやすいが、§12 の有限メモリ要件を満たさないため採用しない。

### caller を queue capacity が空くまで block する

event loss は減るが、game-processing thread が writer / storage の進行を待つ可能性があるため採用しない。v1 は saturation を `INGESTION_UNAVAILABLE` として明示する。

### retention resolution を writer dequeue 後まで遅延する

caller-side work は減るが、accepted event が queue 待機中の configuration reload によって別 policy / expiry へ変化し得て ADR-0003 の acceptance-boundary semantics に反するため採用しない。

### event ごとの Future を返す

永続化結果を精密に伝えられるが、caller の待機を誘発し、未完了 future の lifecycle と memory ownership を増やすため v1 では採用しない。

### storage failure 後に同じ batch を無期限 retry する

一時障害から自動回復できる場合があるが、retry buffer、duplicate semantics、backoff、shutdown interaction が必要になるため v1 の範囲を超える。最初の failure を terminal state とし、管理者へ明示する。

### failure 後も新しい event を queue に受け続ける

memory は queue capacity で有限でも、永続化見込みのない event に `ACCEPTED` を返し続けることになるため採用しない。

## 結果

- recording caller は in-memory validation / retention resolution と bounded queue への non-blocking offer だけを行い、DuckDB I/O を待たない。
- accepted event の policy / expiry は queue 待機中の reload で変化しない。
- queue と batch の両方に finite bound があり、writer 遅延時も retained event 数は無制限に増えない。
- batch flush は size と delay の両方で trigger され、low-volume 時にも partial batch が残り続けない。
- storage failure は terminal runtime state と administrator-visible report になり、silently ignored されない。
- normal shutdown は new intake を止めた後に accepted event の drain を試みるが、crash durability は保証しない。

## 参照

- ADR-0001: `docs/adr/0001-v1-event-contract-and-api-boundaries.md`
- ADR-0002: `docs/adr/0002-duckdb-schema-and-migration-strategy.md`
- ADR-0003: `docs/adr/0003-retention-resolution-and-expiry-deletion-semantics.md`
- Java BlockingQueue: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingQueue.html
- Java ArrayBlockingQueue: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html
