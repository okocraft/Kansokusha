# Kansokusha v1 built-in event catalog

- 日付: 2026-09-22
- 関連 Issue: #10, #37
- 前提: ADR-0001, ADR-0003

## 目的

Kansokusha v1 の記録基盤を実イベントで検証するため、組み込み event の最小集合を固定する。

本 catalog は Minecraft の網羅的な監査ログを定義しない。選定対象は、共通 event fields、opaque payload、platform-specific capture point、retention mapping、非同期 submission を実装・検証するために必要な最小限に限定する。

## 選定方針

v1 built-in event は次の3種とする。

| Platform | Event type key | Capture point | Retention |
| --- | --- | --- | --- |
| Paper / Folia | `kansokusha:block_break` | successful `BlockBreakEvent` | `kansokusha:audit` |
| Paper / Folia | `kansokusha:block_place` | successful `BlockPlaceEvent` / `BlockMultiPlaceEvent` | `kansokusha:audit` |
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

`occurredAt` は対象 platform event を listener が受信した時点の current instant とする。

Minecraft/Paper/Velocity が event occurrence timestamp を提供しない場合に、別 thread で後から timestamp を生成してはならない。

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

- Bukkit/Paper `BlockBreakEvent`
- `EventPriority.MONITOR`
- `ignoreCancelled = true`
- listener は world state を変更しない

event callback 時点の block state を break 前 state として取得する。

#### Common fields

- server: Paper runtime の configured local server key
- world: Bukkit world `NamespacedKey` を Adventure `Key` へ lossless conversion
- position: broken block の integer block coordinates
- subject: breaking player UUID

#### Payload generation 1

field order:

1. `blockData`: non-null string

`blockData` は break 前 block の complete block-data string とし、material identity と state properties を復元可能な表現を使う。

#### Granularity

successful `BlockBreakEvent` 1件につき1 row を submit する。

item drops、experience、tool durability などの副作用はこの event の payload に含めない。

#### Coalescing

行わない。player-driven audit event の中間状態を省略しない。

### `kansokusha:block_place`

#### Capture point

- Bukkit/Paper `BlockPlaceEvent`
- `EventPriority.MONITOR`
- `ignoreCancelled = true`
- `event.canBuild() == true` の場合だけ記録する
- `BlockMultiPlaceEvent` を含む
- listener は world state を変更しない

`BlockPlaceEvent` は cancellation と `canBuild` が独立しているため、cancelled でなくても `canBuild() == false` なら successful placement とみなさず記録しない。`BlockMultiPlaceEvent` も同じ条件を適用する。

#### Common fields

各 changed block について次を記録する。

- server: Paper runtime の configured local server key
- world: changed block の Bukkit world key を Adventure `Key` へ lossless conversion
- position: changed block の integer block coordinates
- subject: placing player UUID

#### Payload generation 1

field order:

1. `replacedBlockData`: non-null string
2. `placedBlockData`: non-null string

`replacedBlockData` は placement 前 state、`placedBlockData` は successful placement 後 state の complete block-data string とする。

#### Granularity

通常の `BlockPlaceEvent` は1 changed block = 1 row とする。

`BlockMultiPlaceEvent` は event 全体を1 row にまとめず、event が変更した block ごとに1 row を submit する。同一 player action で複数 row になることを許容する。

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

backend server key は Velocity server name の UTF-8 bytes を lowercase hexadecimal に変換し、

`kansokusha:velocity-server/<hex>`

とする。

この encoding は Velocity server name の文字種に依存せず deterministic / lossless とする。network-wide storage identity の統一は v1 scope 外であり、Paper local server key と一致させることは要求しない。

#### Payload generation 1

field order:

1. `previousServerName`: nullable string

target backend は common `server` field の lossless backend server key から復元できるため、`targetServerName` を payload に重複保存しない。

`previousServerName` は初回 backend connection では null とする。previous backend は current event の common `server` field では表せないため、transition-specific payload として保持する。

address、IP、player username は generation 1 payload に含めない。

#### Granularity

successful `ServerConnectedEvent` 1件につき1 row を submitする。初回 backend connection と backend 間 switch の両方を同じ event type で記録する。

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

Paper/Folia listener tests は cancelled event を記録しないこと、`BlockPlaceEvent` / `BlockMultiPlaceEvent` の `canBuild() == false` を記録しないこと、common fields と payload が catalog と一致することを確認する。

Velocity listener tests は target server の common `server` mapping、previous server payload、initial connection の nullable previous server、server key encoding、target server name が payload に重複しないこと、caller が storage completion を待たないことを確認する。

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
