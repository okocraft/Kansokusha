# Kansokusha v1 built-in event catalog

- 日付: 2026-09-22
- 関連 Issue: #10, #37
- 前提: ADR-0001, ADR-0003, ADR-0004

## 目的

Kansokusha v1 の記録基盤を実イベントで検証するため、組み込み event の最小集合を固定する。

本 catalog は Minecraft の網羅的な監査ログを定義しない。選定対象は、共通 event fields、opaque payload、platform-specific capture point、retention mapping、非同期 submission を実装・検証するために必要な最小限に限定する。

## 選定方針

v1 built-in event は次の3種とする。

| Platform | Event type key | Capture point | Retention |
| --- | --- | --- | --- |
| Paper / Folia | `kansokusha:block_break` | non-cancelled `BlockBreakEvent` with earliest-available pre-state snapshot | `kansokusha:audit` |
| Paper / Folia | `kansokusha:block_place` | non-cancelled/buildable `BlockPlaceEvent` / `BlockMultiPlaceEvent` with LOWEST snapshots | `kansokusha:audit` |
| Velocity | `kansokusha:server_connected` | successful `ServerConnectedEvent` | `kansokusha:session` |

この3種により、次を最低限検証できる。

- Paper / Folia の world、block position、player subject、local server identity
- block state を含む event-specific payload
- Velocity の backend server identity と player subject
- platform event handler から storage I/O を待たずに submit する経路
- event type ごとの retention mapping と異なる retention duration

chat、command、sign、inventory、login/logout、自然 block update、entity、container などは v1 minimum built-in catalog には含めない。外部 plugin は public API から独自 event type として記録できる。

## 共通規約

### Event type registration

各 built-in event type は payload generation `1` を登録する。

listener は event type registration が成功して runtime が active になった後だけ有効化する。runtime registration conflict は administrator-visible failure とし、別 generation への暗黙置換は行わない。

### 発生時刻

`occurredAt` は対象 platform event を Kansokusha が最初に capture した時点の current instant とする。同一 platform event から複数の `EventSubmission` を生成する場合は、callback / capture の冒頭で1回だけ取得した同一 `occurredAt` を全 submission で共有する。

Minecraft/Paper/Velocity が event occurrence timestamp を提供しない場合に、別 thread で後から timestamp を生成してはならない。`kansokusha:block_break` のように二段階 capture を行う event では、最初の capture stage で取得した timestamp を finalization stage まで保持する。

### Subject

player-driven event の subject は `PlayerSubject(player UUID)` とする。

player name は identity として保存しない。必要になった場合は別 event または payload generation で扱う。

### Payload encoding

built-in payload generation 1 は次の binary convention を使用する。

- integer は network byte order (big-endian)
- string は UTF-8
- string field は signed 32-bit byte length の後に UTF-8 bytes を置く
- nullable string は length `-1`、non-null empty string は length `0`
- payload 内に event type key、generation、occurredAt、server、world、position、subject を重複保存しない

payload codec は built-in listener 実装の public API にはしない。generation 1 の decoder は tests から round-trip verification できる package-internal component として実装してよい。

## Retention catalog

v1 の catalog-defined retention configuration は次とする。

| Policy key | Initial duration | 用途 |
| --- | --- | --- |
| `kansokusha:audit` | `P180D` | player-driven world changes |
| `kansokusha:session` | `P30D` | proxy/backend transition events |
| `kansokusha:default` | `P30D` | event type mapping がない event の explicit fallback |

event type mapping は次とする。

| Event type | Policy |
| --- | --- |
| `kansokusha:block_break` | `kansokusha:audit` |
| `kansokusha:block_place` | `kansokusha:audit` |
| `kansokusha:server_connected` | `kansokusha:session` |

fallback policy は `kansokusha:default` とする。

duration の runtime source は ADR-0003 の validated retention configuration であり、Java listener に duration を hard-code しない。上記 policy definitions、mappings、fallback は operator が設定するための catalog-defined configuration example として提示し、初期 config skeleton へ自動投入しない。runtime activation には ADR-0003 どおり operator が明示した valid retention configuration を要求する。

operator は duration を変更できる。既に accepted/persisted な event の `expires_at` は config 変更で再計算しない。

## Paper / Folia events

### `kansokusha:block_break`

#### Capture point

`BlockBreakEvent` は1本の MONITOR listener だけでは pre-state を保持できないため、同一 event instance に対して二段階で capture する。

1. `EventPriority.LOWEST` で、対象 block の complete block-data string、common fields、および `occurredAt` を ephemeral snapshot として取得する。この stage では cancellation の有無にかかわらず snapshot を取得し、world state を変更しない。
2. `EventPriority.MONITOR` で同じ event instance を finalization する。`event.isCancelled() == true` なら snapshot を破棄して記録しない。non-cancelled なら LOWEST で取得した snapshot から1つの `EventSubmission` を生成して `KansokushaApi.submit` を試行する。
3. MONITOR stage は cancelled event でも cleanup できるよう event を受信し、`ignoreCancelled = true` だけに cleanup を依存させない。

この event type が表すのは **Kansokusha が LOWEST で pre-state snapshot を取得し、MONITOR で non-cancelled と確認した player break event** である。後続の Paper/vanilla destroy 処理が実際に block を破壊したことまでを保証するものではない。

また、Bukkit/Paper の priority model では同じ `LOWEST` priority に登録された他 plugin との絶対的な先行順序は保証できない。そのため `blockData` は「event 発火直前の immutable state」ではなく、**Kansokusha が LOWEST stage で取得できた earliest-available state** と定義する。他 plugin が Kansokusha より先に同 priority で block を変更した場合、その変更前 state を復元できるとは保証しない。

#### Common fields

- server: Paper runtime の configured local server key
- world: Bukkit world `NamespacedKey` を Adventure `Key` へ lossless conversion
- position: broken block の integer block coordinates
- subject: breaking player UUID

#### Payload generation 1

field order:

1. `blockData`: non-null string

`blockData` は LOWEST capture stage で取得した block の complete block-data string とし、material identity と state properties を復元可能な表現を使う。これは上記の priority limitation を持つ earliest-available pre-state である。

#### Granularity

LOWEST snapshot が存在し、MONITOR で non-cancelled と確認できた `BlockBreakEvent` 1件につき1つの `EventSubmission` を生成して `KansokushaApi.submit` を試行する。`ACCEPTED` 以降の persistence semantics は ADR-0004 に従い、MONITOR 到達後の vanilla destroy 成功を event の意味には含めない。

item drops、experience、tool durability などの副作用はこの event の payload に含めない。

#### Coalescing

行わない。player-driven audit event の中間状態を省略しない。

### `kansokusha:block_place`

#### Capture point

`BlockPlaceEvent` / `BlockMultiPlaceEvent` も MONITOR だけでは before / tentative-after state を安定して保持できないため、同一 event instance に対して二段階で capture する。

1. `EventPriority.LOWEST` で、callback 冒頭に `occurredAt` を1回だけ取得する。
2. 同じ LOWEST stage で、各 changed block について common fields、`replacedBlockData`、およびその時点の tentative `placedBlockData` を immutable value として snapshot する。listener は world state や event state を変更しない。
3. `EventPriority.MONITOR` で同じ event instance を finalization する。`event.isCancelled() == true` または `event.canBuild() == false` なら snapshot を破棄して記録しない。それ以外の場合だけ LOWEST snapshot を submit する。
4. MONITOR stage は cancelled event でも cleanup できるよう event を受信し、`ignoreCancelled = true` だけに cleanup を依存させない。

single-place の `replacedBlockData` は LOWEST で `getBlockReplacedState()` から直ちに value snapshot へ変換する。`placedBlockData` は LOWEST で `getBlockPlaced()` の live block から直ちに value snapshot へ変換する。

multi-place では LOWEST で `getReplacedBlockStates()` の各 element から replaced state を value snapshot へ変換し、各 element が指す block location の live block から対応する tentative placed state を value snapshot へ変換する。mutable な `BlockState` や live `Block` reference 自体を MONITOR stage まで保持して payload source にしてはならない。

この event type が表すのは **Kansokusha が LOWEST で before / tentative-after snapshot を取得し、MONITOR で non-cancelled かつ `canBuild() == true` と確認した player placement event** である。Paper が event handler 完了後に行う block entity installation、`onPlace`、physics update 等を反映した final world state までを保証するものではない。

また、`block_break` と同様に、同じ `LOWEST` priority に登録された他 plugin との絶対的な先行順序は保証できない。他 plugin が Kansokusha より先に同 priority で replaced state、event state、または live block を変更した場合、その変更前 value を復元できるとは保証しない。

`BlockPlaceEvent` は cancellation と `canBuild` が独立しているため、MONITOR で cancelled でなくても `canBuild() == false` なら記録しない。`BlockMultiPlaceEvent` も同じ条件を適用する。

#### Common fields

各 changed block について次を記録する。

- server: Paper runtime の configured local server key
- world: LOWEST で snapshot した changed block の Bukkit world key を Adventure `Key` へ lossless conversion
- position: LOWEST で snapshot した changed block の integer block coordinates
- subject: placing player UUID

#### Payload generation 1

field order:

1. `replacedBlockData`: non-null string
2. `placedBlockData`: non-null string

`replacedBlockData` は LOWEST で取得できた earliest-available replaced state、`placedBlockData` は LOWEST で取得できた earliest-available tentative placed state の complete block-data string とする。いずれも event handler 後の final world state を表さない。

#### Granularity

通常の `BlockPlaceEvent` は1 changed block につき1つの `EventSubmission` を生成して `KansokushaApi.submit` を試行する。

`BlockMultiPlaceEvent` は event 全体を1 submission にまとめず、LOWEST で snapshot した replaced-state list の各 changed block について厳密に1つの `EventSubmission` を生成し、それぞれ `KansokushaApi.submit` を試行する。replaced-state list が N blocks なら厳密に N submissions を生成・試行し、base `BlockPlaceEvent` 分の追加 submission を作らない。

ADR-0004 の admission は submission ごとに独立しているため、N submissions の all-or-none acceptance / persistence は保証しない。queue saturation、shutdown、writer failure 等により一部だけ `ACCEPTED` となる partial acceptance を許容し、各 submission の結果は通常の `SubmissionOutcome` に従う。v1 built-in catalog は batch reservation / atomic multi-event admission を要求しない。

同一 `BlockMultiPlaceEvent` から生成する全 submission は LOWEST callback 冒頭で1回だけ取得した同一 `occurredAt` を共有する。subclass と base class の listener を別々に登録して二重 submission を作ってはならない。

#### Coalescing

行わない。multi-place の複数 block も省略しない。

## Velocity event

### `kansokusha:server_connected`

#### Capture point

- Velocity `ServerConnectedEvent`
- player が target backend へ正常に接続し、previous server connection が既に解除された後
- listener は connection result を変更しない

この Velocity event は handler completion を待つ historical behavior があるため、listener 内では database I/O や flush completion を待たず、通常の bounded submission のみを行う。

#### Common fields

- server: target backend server を表す Adventure `Key`
- world: なし
- position: なし
- subject: connected player UUID

backend server key は Velocity server name の Java UTF-16 code units を順序どおり、それぞれ4桁の lowercase hexadecimal (`0000`–`ffff`) に変換して連結し、

`kansokusha:velocity-server/<utf16-hex>`

とする。

この encoding は Unicode normalization や UTF-8 replacement semantics を介さず、Java `String` の全 code unit sequence（unpaired surrogate を含む）に対して deterministic / lossless とする。decode 時は suffix を4桁ごとに UTF-16 code unit へ戻し、元の Java `String` を復元する。network-wide storage identity の統一は v1 scope 外であり、Paper local server key と一致させることは要求しない。

#### Payload generation 1

field order:

1. `previousServerKey`: nullable string

target backend は common `server` field の lossless backend server key から元の Velocity server name を復元できるため、`targetServerName` を payload に重複保存しない。

`previousServerKey` は previous backend server name に同じ UTF-16 code-unit hex encoding を適用した canonical Adventure `Key#asString()` とする。初回 backend connection では null とする。raw `previousServerName` は payload に保存せず、必要な場合は `previousServerKey` から復元する。これにより ill-formed UTF-16 を含む任意の Java `String` でも UTF-8 payload string の replacement semantics による identity loss を避ける。

address、IP、player username は generation 1 payload に含めない。

#### Granularity

successful `ServerConnectedEvent` 1件につき1つの `EventSubmission` を生成して `KansokushaApi.submit` を試行する。acceptance / persistence semantics は ADR-0004 に従う。初回 backend connection と backend 間 switch の両方を同じ event type で記録する。

#### Coalescing

行わない。短時間の server hopping も個別 event として保持する。

## v1 で採用しない coalescing

要件 §6 が例示する fluid / natural block state progression は、本 catalog の3 event には含まれない。

したがって E6 では general coalescing engine を実装しない。将来 natural state event を追加する場合、その event-specific catalog decision で time window と省略可能な intermediate state を定義してから実装する。

## 実装 task への分割基準

E6-T2 では少なくとも次の独立 task に分ける。

1. built-in payload generation 1 codec と catalog retention configuration example
2. Paper/Folia block break listener
3. Paper/Folia block place / multi-place listener
4. Velocity server connected listener

各 task は event registration、listener capture、payload codec verification、submission outcome handling を testable boundary とする。

Paper/Folia listener tests は、`BlockBreakEvent` の LOWEST snapshot が後続 listener の block mutation より前に取得できた場合にその snapshot を MONITOR submit へ引き継ぐこと、cancelled break では snapshot を破棄して記録しないこと、MONITOR 時点の live block state を payload source にしないことを確認する。

`BlockPlaceEvent` / `BlockMultiPlaceEvent` については、LOWEST と MONITOR の間で別 listener が mutable replaced `BlockState` または live placed block を変更しても LOWEST で value snapshot した before / tentative-after state を submit すること、cancelled または `canBuild() == false` なら snapshot を破棄して記録しないことを確認する。multi-place の replaced-state list が N blocks なら厳密に N `EventSubmission` を生成・試行すること、その全 submission が LOWEST 冒頭で取得した同一 `occurredAt` を共有すること、base/subclass の二重 submission がないことを確認する。queue capacity や lifecycle transition を制御した test では partial acceptance が起こり得ることも ADR-0004 に沿って固定し、all-or-none を期待しない。common fields と payload が catalog と一致することも確認する。

Paper/Folia の二段階 snapshot storage は in-flight event instance を複数 region thread から同時に扱える必要がある。Folia 上で共有され得る storage に unsynchronized な `HashMap` 等を使用してはならず、thread-safe な ownership / synchronization を持たせ、MONITOR finalization 時に対応 snapshot を必ず remove する。

Velocity listener tests は target server の common `server` mapping、nullable `previousServerKey` payload、initial connection の null previous backend、UTF-16 code-unit hex server-key encoding、target server name が payload に重複しないこと、caller が storage completion を待たないことを確認する。server-key codec は ASCII、BMP、surrogate pair、unpaired high surrogate、unpaired low surrogate、empty string を round-trip し、異なる Java `String` が同じ key に衝突しないことを確認する。

## 要件への対応

| 要件 | Catalog decision |
| --- | --- |
| §5 | v1 minimum built-in event を3種に限定 |
| §6 | 選定3種では coalescing しない。general policy は導入しない |
| §8 | common fields と generation 1 payload fields を event ごとに固定 |
| §9 | policy key、initial duration、event mapping、fallback を固定 |
| §11 | platform callback は storage I/O / flush completion を待たず bounded submission のみ行う |
| §14 | chat / command は v1 minimum built-in 対象外 |

## 参照

- `docs/initial-requirements.md`
- `docs/adr/0001-v1-event-contract-and-api-boundaries.md`
- `docs/adr/0003-retention-resolution-and-expiry-deletion-semantics.md`
- `docs/adr/0004-bounded-ingestion-batching-and-failure-semantics.md`
