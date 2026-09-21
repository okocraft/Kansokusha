# Kansokusha v1 built-in event catalog

- 日付: 2026-09-22
- 関連 Issue: #10, #37
- 前提: ADR-0001, ADR-0003, ADR-0004

## 目的

Kansokusha v1 の記録基盤を実イベントで検証するため、組み込み event の最小集合と、その意味・保証範囲を固定する。

本 catalog は Minecraft の網羅的な監査ログを定義しない。外部 plugin は public API から独自 event type を登録・記録できる。

## v1 catalog

| Platform | Event type key | Capture | Retention | Coalescing |
| --- | --- | --- | --- | --- |
| Paper / Folia | `kansokusha:block_break` | non-cancelled `BlockBreakEvent` with earliest-available pre-state | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:block_place` | non-cancelled/buildable `BlockPlaceEvent` / `BlockMultiPlaceEvent` with earliest-available snapshots | `kansokusha:audit` | none |
| Velocity | `kansokusha:server_connected` | successful `ServerConnectedEvent` | `kansokusha:session` | none |

chat、command、sign、inventory、login/logout、自然 block update、entity、container 等は v1 minimum built-in catalog に含めない。

## 共通 rules

### Registration / submission

各 built-in event type は payload generation `1` を使用する。

registration、submission outcome、asynchronous admission、acceptance / persistence の意味は ADR-0001 / ADR-0004 に従う。

### Timestamp / subject

`occurredAt` は Kansokusha が対象 platform event を最初に capture した時点の current instant とする。

同一 platform event から複数の `EventSubmission` を生成する場合、最初の capture 時に1回だけ取得した同一 `occurredAt` を共有する。

player-driven event の subject は `PlayerSubject(player UUID)` とする。

### Payload encoding

payload は ADR-0001 どおり provider-defined opaque bytes とし、platform 固有の built-in event を common codec へ抽象化しない。

common fields（event type、generation、occurredAt、server、world、position、subject）は payload に重複保存しない。

### Paper / Folia capture semantics

Paper / Folia の block event は二段階で扱う。

1. `EventPriority.LOWEST` で必要な common fields と block state を immutable value として snapshot する。
2. `EventPriority.MONITOR` で cancellation 等の event-specific condition を finalization し、条件を満たす場合だけ LOWEST snapshot から submission を作る。
3. MONITOR は cancelled event でも cleanup できるよう受信する。

LOWEST snapshot は「Kansokusha が取得できた earliest-available state」であり、同じ `LOWEST` priority で Kansokusha より先に実行された listener より前の state は保証しない。

また MONITOR 後の Paper / vanilla processing が実際に完了・成功したことまでは保証しない。

## Retention mapping

catalog が提示する retention configuration example は次のとおり。

| Policy key | Duration | 用途 |
| --- | --- | --- |
| `kansokusha:audit` | `P180D` | player-driven world changes |
| `kansokusha:session` | `P30D` | proxy/backend transition events |
| `kansokusha:default` | `P30D` | explicit fallback |

| Event type | Policy |
| --- | --- |
| `kansokusha:block_break` | `kansokusha:audit` |
| `kansokusha:block_place` | `kansokusha:audit` |
| `kansokusha:server_connected` | `kansokusha:session` |

fallback policy は `kansokusha:default` とする。

これらは operator-facing example であり、initial config skeleton へ自動投入しない。runtime retention semantics は ADR-0003 に従う。

## `kansokusha:block_break`

### Capture

LOWEST で次を snapshot する。

- target block の Paper/Minecraft block state payload
- server / world / position / subject
- `occurredAt`

MONITOR で `event.isCancelled() == false` の場合だけ1つの `EventSubmission` を生成・試行する。

この event は「LOWEST で pre-state を取得し、MONITOR で non-cancelled と確認した player break event」を表す。後続の vanilla block destruction 成功そのものは表さない。

### Fields / payload

common fields:

- server: Paper runtime の local server key
- world: Bukkit world key を Adventure `Key` へ lossless conversion
- position: broken block の integer block coordinates
- subject: breaking player UUID

payload generation 1 は Paper module で paperweight-userdev を利用して生成する binary NBT とする。

LOWEST で取得した Bukkit `BlockData` を `CraftBlockData#getState()` で Minecraft `BlockState` に変換し、`NbtUtils.writeBlockState` の `CompoundTag` を `NbtIo.write` で payload bytes にする。

これにより block identity と全 block-state properties を Minecraft の block-state serialization で保持する。block entity NBT、item drops、experience、tool durability 等は generation 1 payload に含めない。

## `kansokusha:block_place`

### Capture

LOWEST で各 changed block の次を immutable value として snapshot する。

- replaced state
- tentative placed state
- server / world / position / subject
- event 全体で共通の `occurredAt`

mutable な `BlockState` / live `Block` reference を MONITOR 時の payload source として使用しない。

MONITOR で `event.isCancelled() == false && event.canBuild() == true` の場合だけ submission を生成・試行する。

この event は LOWEST 時点の earliest-available replaced state と tentative placed state を表す。event handler 後の block entity installation、`onPlace`、physics 等を反映した final world state は表さない。

### Fields / payload

common fields:

- server: Paper runtime の local server key
- world: changed block の Bukkit world key を Adventure `Key` へ lossless conversion
- position: changed block の integer block coordinates
- subject: placing player UUID

payload generation 1 は Paper module で生成する binary NBT compound とし、次の2 child compounds を持つ。

1. `replaced`: LOWEST で取得した earliest-available replaced Minecraft `BlockState` を `NbtUtils.writeBlockState` した value
2. `placed`: LOWEST で取得した earliest-available tentative placed Minecraft `BlockState` を `NbtUtils.writeBlockState` した value

outer compound は `NbtIo.write` で payload bytes にする。block entity NBT は generation 1 payload に含めない。

### Multi-place granularity

通常の `BlockPlaceEvent` は1 changed blockにつき1 submission を生成・試行する。

`BlockMultiPlaceEvent` は LOWEST で snapshot した N changed blocks について厳密に N submissions を生成・試行し、base `BlockPlaceEvent` 分の追加 submission は作らない。全 submission は同一 `occurredAt` を共有する。

各 submission の admission は ADR-0004 に従って独立するため、partial acceptance は許容する。v1 は atomic multi-event admission を要求しない。

## `kansokusha:server_connected`

### Capture / fields

Velocity `ServerConnectedEvent` が示す successful backend connection を記録する。

common fields:

- server: target backend server key
- world: なし
- position: なし
- subject: connected player UUID

listener は storage completion を待たず、通常の bounded submission のみを行う。

### Server key encoding

Velocity server name の Java UTF-16 code units を各4桁の lowercase hex に変換し、

`kansokusha:velocity-server/<hex>`

とする。

この encoding は全 Java `String` に対して deterministic / lossless とする。Paper local server key との network-wide identity 統一は v1 scope 外とする。

### Payload generation 1

payload は nullable `previousServerKey` 1 field を持つ Velocity-specific binary encoding とする。

- null: signed 32-bit big-endian length `-1`
- non-null: signed 32-bit big-endian UTF-8 byte length + canonical Adventure key string の UTF-8 bytes

`previousServerKey` は previous backend server name に同じ server-key encoding を適用した canonical Adventure key string とする。初回 backend connection では null とする。

target backend name は common `server` field から復元可能なため payload に重複保存しない。

## 要件への対応

| 要件 | Catalog decision |
| --- | --- |
| §5 | v1 minimum built-in event を3種に限定 |
| §6 | 選定3種では coalescing しない |
| §8 | common fields と generation 1 payload fields を event ごとに固定 |
| §9 | retention policy example、event mapping、fallback を固定 |
| §11 | platform callback は storage I/O / flush completion を待たず bounded submission を使用 |
| §14 | chat / command は v1 minimum built-in 対象外 |

## 参照

- `docs/initial-requirements.md`
- `docs/adr/0001-v1-event-contract-and-api-boundaries.md`
- `docs/adr/0003-retention-resolution-and-expiry-deletion-semantics.md`
- `docs/adr/0004-bounded-ingestion-batching-and-failure-semantics.md`
