# ADR-0001: v1 のイベント契約と公開 API 境界

- 日付: 2026-09-20
- 関連 Issue: #5, #12

## コンテキスト

Kansokusha v1 は、Paper / Folia / Velocity 上の組み込み処理と外部プラグインからイベントを受理し、共通モデルへ変換して永続化する。公開 API をプラットフォーム API や DuckDB の表現へ結合すると、共通実装の再利用と将来の変更が難しくなる。

本 ADR は、Kansokusha v1 要件定義 §7 の event type identity、runtime registration と永続 identity の分離、および §8 の共通フィールド、opaque payload、payload format generation を具体化する。

## 決定

### 1. 公開 API は platform / storage 非依存とする

event type の登録と event の送信に用いる契約は common モジュールの公開パッケージに置き、Paper、Folia、Velocity、DuckDB、JDBC および内部キューの型を含めない。

公開 API は概念上、次の操作を提供する。

1. event type definition の登録
2. event submission の受理要求

外部プラグインには runtime registry、persistent registry、キュー、writer、database connection を公開しない。

API は common entry point の `Kansokusha.api()` から取得する。shutdown 後の操作は closed outcome を返す。shutdown と同時に行われる API 取得には、それ以上の強い順序保証を設けない。

### 2. event type identity には Adventure `Key` を使用する

公開識別子には `net.kyori.adventure.key.Key` を使用し、構文と検証は `Key` の契約に従う。永続化に用いる正規の文字列表現は `Key#asString()` の `namespace:value` とする。

event type の同一性は `Key` の namespace と value で決める。永続化層は compact な整数 ID を割り当ててよいが、内部 ID は公開せず、event type の identity として扱わない。

Paper の `NamespacedKey` との変換は Paper adapter の責務とし、common API は `NamespacedKey` に依存しない。

### 3. payload format generation は event type ごとの正整数とする

payload format generation は、event type の `Key` ごとに独立した正の 32-bit 整数とし、最初の generation を `1` とする。

provider が互換性のない payload format へ変更するときは generation を増やす。過去の generation は永続データ上で区別し、新しい generation で上書きしない。

generation は schema や serializer 自体を表さない。payload の encoding、schema、serialization、および各 generation の解釈は provider の責任とする。

### 4. submission と受理後の event を分離する

外部 API が受け取る `EventSubmission` と、Kansokusha が受理後に扱う `AcceptedEvent` を分ける。

`EventSubmission` の必須フィールドは次のとおりとする。

- event type key
- payload format generation
- 発生時刻
- server identifier
- opaque payload

任意フィールドは次のとおりとする。

- world identifier
- position
- subject reference

server、world、subject の識別子には Adventure `Key` を使用する。position はブロック座標を表す3つの整数とする。subject はプラットフォーム API のオブジェクトではなく namespace 付きの参照として表現し、プレイヤーだけに限定しない。

Paper integration はローカル server key を API から取得可能にする。複数の backend server を扱う Velocity integration では、送信側が対象 server key を指定する。

payload は byte sequence とし、Kansokusha は内容を解釈しない。

common 内部の `AcceptedEvent` は、検証済み submission に Kansokusha が解決した retention policy key を加えたものとする。外部 provider は event ごとの retention policy を直接指定しない。retention policy の identity、対応付け、expiry 情報は retention ADR で決定する。

### 5. runtime registration と persistent identity を分離する

runtime registry は、現在の process で送信可能な event type definition のみを in-memory で管理する。definition は event type key と active payload generation を含む。

登録 semantics は次のとおりとする。

- 未登録の key は登録に成功する。
- 同じ key と同じ active generation の再登録は冪等に成功する。
- 同じ key に異なる active generation が登録済みの場合は conflict とし、暗黙に置換しない。
- 一つの key で同時に active にできる generation は一つとする。
- runtime registration の除去は persistent metadata の削除を意味しない。

runtime registry は persistent registry と同期せず、compact storage ID を保持しない。persistent event type と generation の解決・作成は、受理済み event を扱う writer / storage boundary で行う。同じ `Key` の再登録を同じ persistent identity へ対応させる責務も persistent registry が持つ。

### 6. 登録結果と送信結果は構造化された outcome とする

競合、未登録、世代不一致、shutdown は通常の制御フローとして構造化された outcome で返す。

登録 outcome は次を区別する。

- `REGISTERED`
- `ALREADY_REGISTERED`
- `CONFLICT`
- `CLOSED`

送信 outcome は次を区別する。

- `ACCEPTED`
- `UNREGISTERED_EVENT_TYPE`
- `PAYLOAD_GENERATION_MISMATCH`
- `CLOSED`
- `INGESTION_UNAVAILABLE`

`ACCEPTED` は非同期 ingestion 境界が event の所有権を受け取ったことだけを表し、永続化完了や crash durability を意味しない。submit 操作は storage I/O 完了を待たない。

`INGESTION_UNAVAILABLE` を返す具体的な条件、writer failure の公開方法、shutdown drain は非同期 pipeline ADR で決定する。

## 検討した選択肢

### event type identifier

- 生の `String` は構文検証が分散するため採用しない。
- 独自の `EventTypeKey` は Adventure `Key` と同じ契約を重複実装するため採用しない。
- Paper `NamespacedKey` は common API を Paper に依存させるため採用しない。
- Adventure `Key` は検証済みの namespace 付き identity を Paper / Folia と Velocity で共有できるため採用する。

### payload generation

- semantic version 文字列は順序と互換性の意味が曖昧になるため採用しない。
- 正整数は payload format の非互換な世代を最小限の契約で区別できるため採用する。

### API outcome

予測可能な拒否を例外だけで通知する案は、通常の制御フローを例外にするため採用しない。event ごとの永続化完了を `Future` で返す案も、呼び出し側の待機と未完了 state の保持を誘発するため採用しない。

## 結果

- 外部 provider は platform と storage の型に依存せず、同じ event contract を利用できる。
- opaque payload の意味と互換性管理は provider が担い、Kansokusha は共通 metadata と byte sequence を扱う。
- `ACCEPTED` と永続化完了を分離することで、後続の非同期 pipeline は caller を storage I/O で待たせずに実装できる。

組み込み event、retention の具体値、queue / batching / failure semantics、DuckDB schema、migration、coalescing は本 ADR の対象外とする。
