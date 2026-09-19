# ADR-0001: v1 のイベント契約と公開 API 境界

- 日付: 2026-09-20
- 関連 Issue: #5, #12

## コンテキスト

Kansokusha v1 は、Paper / Folia / Velocity 上の組み込み処理と外部プラグインからイベントを受理し、共通モデルへ変換して永続化する必要がある。一方、公開 API を特定のプラットフォーム API や DuckDB の表現へ結合すると、共通実装の再利用、Velocity への公開、ストレージ方式の変更が難しくなる。

本 ADR は、Kansokusha v1 要件定義の次の要件を具体化する。

- §7「Event Type」
  - 外部プラグインが `namespace:key` 形式の安定した識別子を登録できること
  - 永続化では内部 ID を利用しても、公開識別子を復元できること
  - 提供元が存在しなくても識別情報を保持し、再登録時に同じ event type として扱えること
  - runtime registration と永続的な event type registry を分離できること
- §8「Event Payload」
  - Kansokusha が解釈しない event 固有 payload を保存できること
  - event type、発生時刻、サーバー、任意のワールド・位置・主体、retention に必要な情報を表現できること
  - 同じ event type の payload format を世代管理できること

本 ADR の目的は、後続の event model、runtime registry、外部向け記録 API を実装するために必要な契約を確定することである。ストレージ schema、非同期キュー、retention の期間、組み込みイベント、coalescing policy はここでは決定しない。

## 決定

### 1. 公開契約は common モジュールのプラットフォーム非依存 API とする

外部プラグインがイベント型を登録しイベントを送信するための契約は、common モジュールの公開パッケージに置く。公開契約には Paper、Folia、Velocity、DuckDB、JDBC および内部キューの型を含めない。

公開 API の中心は、概念上、次の二つの操作とする。

1. event type definition の登録
2. event submission の受理要求

実際の Java API は、登録結果と送信結果を戻り値で返す。外部プラグインへ runtime registry、永続 registry、キュー、writer、database connection を公開しない。

API の取得には、common モジュールの公開 entry point である `Kansokusha.api()` を使用する。Paper / Folia と Velocity の各 bootstrap は、起動時にそのプロセスの API 実装を `Kansokusha` へ設定し、shutdown 時に closed 実装へ置き換える。外部プラグインが platform ごとに異なる discovery mechanism を扱う必要はなく、plugin main class や内部 runtime も公開しない。

shutdown と同時に発生した呼び出しに対して、active 実装と closed 実装のどちらが取得されるかを厳密には保証しない。この lifecycle race を許容する代わりに取得方法を単純化する。取得した API が shutdown を認識した後の操作は、後述する closed outcome を返す。

Paper の `NamespacedKey` との変換など、プラットフォーム固有の利便機能は adapter 側へ置く。

### 2. event type の公開識別子には Adventure `Key` を使用する

event type の公開識別子には `net.kyori.adventure.key.Key` を使用する。永続化に用いる正規の文字列表現は `Key#asString()` が返す `namespace:value` とする。

構文と検証は Adventure `Key` の契約に従う。

- `namespace`: `[a-z0-9._-]+`
- `value`: `[a-z0-9/._-]+`
- 空文字、大文字、上記以外の文字は不正とする。
- Kansokusha は大文字小文字の変換などの追加の正規化を行わない。
- 同一性は `Key` の `namespace` と `value` で決める。

Paper の `NamespacedKey` を受け取る利便 API が必要な場合は、Paper adapter で同じ namespace と value を持つ `Key` へ変換する。common API は `NamespacedKey` に依存しない。

永続化層は保存効率のために整数 ID を割り当ててよいが、その ID は公開 API に出さず、event type の同一性にも使用しない。永続的な同一性は常に `Key` の namespace と value で決める。

### 3. payload format generation は event type ごとの正整数とする

payload format generation は、正の 32-bit 整数を保持する不変な値型で表す。

- 最初の generation は `1` とする。
- `0` と負数は不正とする。
- generation の意味域は event type の `Key` ごとに独立する。
- provider が payload の互換性を失う形式変更を行う場合は generation を増やす。
- 過去の generation は永続データ上で区別して保持し、新しい generation の登録によって上書きしない。

generation は schema の内容や serializer を表すものではない。payload の encoding、schema、serialization および generation 間の解釈は provider の責任とする。

### 4. event submission と受理後の envelope を分離する

外部 API が受け取る `EventSubmission` と、Kansokusha が受理後に扱う `EventEnvelope` を分ける。

`EventSubmission` は次の必須フィールドを持つ。

- event type key
- payload format generation
- 発生時刻 (`Instant` 相当)
- server identifier
- opaque payload

次のフィールドは任意とする。

- world identifier
- position
- subject reference

任意フィールドには次の不変条件を設ける。

- position が存在する場合は world identifier も必須とする。
- position の各座標は有限値とする。
- identifier と subject reference の構成文字列は空白のみであってはならない。

world identifier と server identifier は、プラットフォーム API のオブジェクトではなく、設定または adapter が与える安定した文字列として扱う。subject reference は主体の種類を表す namespace 付き識別子と、その種類の中での識別子から成る。これにより、プレイヤー UUID だけに契約を限定しない。

opaque payload は byte sequence とする。API 境界で防御的コピーを行い、呼び出し側の後続変更が受理済みデータへ影響しないようにする。common model、runtime registry、ingestion API は payload の内容を解釈しない。

`EventEnvelope` は、検証済みの submission に Kansokusha が解決した retention reference を付加した不変データとする。外部 provider はイベントごとの retention policy を直接指定しない。event type と retention policy の対応、および envelope や永続レコードへ保持する追加の expiry 情報は retention ADR で決定する。本 ADR では、少なくとも namespace 付きの retention policy reference を保持できる境界だけを定め、具体的な policy 名や期間は定めない。

### 5. runtime registration と永続 identity を分離する

runtime registry は、現在のプロセスで送信を許可する event type definition を保持する。definition は少なくとも event type key と active payload generation を含む。

登録は次のように扱う。

- 未登録の key に対する有効な definition は登録成功とする。
- 同じ key と同じ active generation の再登録は冪等な成功とし、既存 definition を変更しない。
- 同じ key に異なる active generation が既に登録されている場合は conflict とし、暗黙に置換しない。
- runtime から登録を除去しても、永続的な event type と payload generation の metadata は削除しない。
- process restart 後、または runtime registration の除去後に同じ key が登録された場合、永続 registry は既存の同一 key を解決する。
- 新しい generation は既存 generation の追加として永続化し、過去の generation を変更しない。

v1 の runtime registry では、一つの event type key に対して同時に active にできる generation は一つとする。稼働中に generation を切り替える hot replacement は v1 の公開 API に含めない。

runtime registry は内部の compact storage ID を保持または公開しない。永続 registry との同期に失敗した場合、登録を成功として扱ってはならない。

### 6. 登録結果と送信結果は構造化された outcome とする

競合、未登録、世代不一致、shutdown は運用上起こり得るため、通常の制御フローとして例外を使用しない。公開 API は構造化された outcome を即時に返す。

登録 outcome は少なくとも次を区別する。

- `REGISTERED`: 新しい runtime definition を登録した。
- `ALREADY_REGISTERED`: 同一 definition が既にあり、冪等に成功した。
- `CONFLICT`: 同じ key に異なる active generation が登録済みである。
- `CLOSED`: Kansokusha が registration を受理しない lifecycle state である。
- `UNAVAILABLE`: 永続 identity の解決を含む登録処理を完了できない。

送信 outcome は少なくとも次を区別する。

- `ACCEPTED`: event の所有権が Kansokusha に移り、非同期 ingestion 境界が受理した。
- `UNREGISTERED_EVENT_TYPE`: key が runtime registry にない。
- `PAYLOAD_GENERATION_MISMATCH`: submission の generation が active generation と異なる。
- `CLOSED`: shutdown 中または shutdown 後で、新しい event を受理しない。
- `INGESTION_UNAVAILABLE`: ingestion 境界が event を受理できない。

`ACCEPTED` は永続化完了や crash durability を意味しない。submit 操作は storage I/O 完了を待たず、future や callback で個々の永続化完了を返さない。キュー容量、saturation 時に `INGESTION_UNAVAILABLE` を返す条件、writer failure の公開方法、正常停止時の drain は非同期 pipeline ADR で決定する。

値型の constructor または factory は、構文違反、null、field invariant 違反を即時に拒否する。このようなプログラミングエラーと、上記の runtime outcome は区別する。

## 検討した選択肢

### event type identifier

#### 生の `String`

依存が少なく単純だが、API の各所で構文検証が必要になり、不正な値が runtime registry や storage 境界まで到達しやすい。検証済みの Adventure `Key` を採用する。

#### 独自の `EventTypeKey`

event type 専用の型を作れるが、Adventure `Key` と同じ構文、検証、等価性を重複実装することになる。Paper / Folia と Velocity の双方が利用する Adventure の既存契約を再利用するため採用しない。

#### Paper `NamespacedKey`

Paper との接続は自然だが、common API と Velocity 実装を Paper に依存させる。adapter で変換し、common API では採用しない。

#### UUID または整数 ID

固定長で保存しやすいが、外部 provider が安定して再登録するための識別子として扱いにくく、永続化方式が公開契約へ漏れる。内部 storage ID に限定する。

### payload generation

#### semantic version 文字列

柔軟だが、順序、互換性、正規化の意味が曖昧になる。generation は互換性のない format を区別するためだけの単調な正整数とする。

#### event type key へ generation を埋め込む

storage 上の区別は可能だが、同じ event type の identity が generation ごとに分断され、要件 §7 の再登録 semantics と要件 §8 の世代管理を別々に表せないため採用しない。

### payload の型

#### JSON など一つの共通形式を強制する

調査時の可読性は上がるが、外部 provider の payload を Kansokusha が理解しないという要件を狭め、サイズと serialization cost を増やす可能性がある。v1 では opaque byte sequence とする。

#### 任意の Java object を受け取る

使いやすいが、class loader、serialization、provider 不在時の復元に依存するため採用しない。

### API の結果通知

#### 例外のみで通知する

未登録や shutdown など予測可能な状態まで例外制御になるため採用しない。入力値自体の不正と予測不能な programming error に限って例外を使用する。

#### 個々の event の永続化完了を `Future` で返す

呼び出し側が完了待ちを行う誘因となり、未完了 future の保持も増える。v1 の durability 要件は直近の未永続化ログの消失を許容するため、即時の受理 outcome のみを返す。

#### platform ごとの discovery mechanism

Bukkit `ServicesManager` と Velocity `PluginContainer` を個別に利用すると、外部プラグイン側の取得処理と lifecycle 処理が platform ごとに分かれる。common の `Kansokusha.api()` へ統一し、shutdown との race は許容する。

## 結果

- 外部 provider は Paper、Velocity、DuckDB の型に依存せず、同じ event contract を利用できる。
- Adventure `Key` と payload generation の組により、provider が不在でも過去データの形式を識別できる。
- opaque payload の意味と互換性管理は provider の責任になる。Kansokusha は共通 metadata の検証と byte sequence の保持だけを担う。
- runtime registration の削除や provider の不在は、永続 identity の削除を意味しない。
- event submission の `ACCEPTED` と永続化完了は明確に異なる。後続の非同期 pipeline はこの契約を破らずに batching、saturation、failure reporting を決定できる。
- Adventure `Key` への依存は増えるが、独自 key 型を実装せず、platform と storage の依存が公開 API へ漏れることを防げる。

## 本 ADR で決定しない事項

- 組み込み event type の種類、key、payload
- payload の具体的な encoding または schema
- retention policy の具体的な名称、期間、event type との対応、expiry の永続形式
- queue 容量、batch 条件、saturation・writer failure・drain の詳細
- DuckDB schema、内部 ID の型、migration 方法
- coalescing の対象、時間幅、中間状態
- event ごとの payload size 上限
