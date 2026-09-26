# Kansokusha v1 built-in event catalog

- 日付: 2026-09-26
- 関連 Issue: #10, #37, #101, #102, #103, #104, #105, #106
- 前提: ADR-0001, ADR-0003, ADR-0004

## 目的

Kansokusha v1 の記録基盤を実イベントで検証するため、組み込み event の最小集合と、その意味・保証範囲を固定する。

本 catalog は Minecraft の網羅的な監査ログを定義しない。外部 plugin は public API から独自 event type を登録・記録できる。

## v1 catalog

| Platform | Event type key | Capture | Retention | Coalescing |
| --- | --- | --- | --- | --- |
| Paper / Folia | `kansokusha:block_break` | non-cancelled `BlockBreakEvent` with earliest-available pre-state | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:block_place` | non-cancelled/buildable `BlockPlaceEvent` / `BlockMultiPlaceEvent` with earliest-available snapshots | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:sign_change` | non-cancelled `SignChangeEvent`, before-side snapshot + final event lines | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:bucket_empty` | non-cancelled block-changing `PlayerBucketEmptyEvent` | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:bucket_fill` | non-cancelled block-changing `PlayerBucketFillEvent`; non-block fills are excluded | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:block_harvest` | non-cancelled harvest/shear source event normalized to one canonical event | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:flower_pot_change` | non-cancelled flower-pot insert/remove with canonical before/after contents | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:item_drop` | non-cancelled player drop with item-entity UUID, detached stack, and drop position | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:item_pickup` | non-cancelled `EntityPickupItemEvent` when the actor is a Player, with item-entity UUID, detached stack, position, and remaining count | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:book_edit` | non-cancelled `PlayerEditBookEvent` with recoverable previous/final BookMeta and final signing flag | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:lectern_change` | non-cancelled lectern book insert/take only; page navigation is excluded | `kansokusha:audit` | none |
| Paper / Folia | `kansokusha:player_trade` | successful merchant transaction confirmed after `PlayerPurchaseEvent` by Paper's successful-trade statistic event | `kansokusha:audit` | none |
| Velocity | `kansokusha:server_connected` | successful `ServerConnectedEvent` | `kansokusha:session` | none |

上表は foundation 検証時の original minimum catalog である。#101〜#106 で採用された追加 built-in event は current catalog に含み、retention mapping は下記の current mapping を使用する。close 済みの duplicate / 対象外 event type は current mapping に含めない。item drop/pickup は inventory slot 差分ではなく、player と world item entity の ownership transfer を canonical operation として記録する。

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

また MONITOR 後の Paper / vanilla processing が実際に完了・成功したことまでは原則保証しない。例外として `kansokusha:player_trade` は、Paper 26.2 が `MerchantOffer#take` 成功後に発火する `PlayerStatisticIncrementEvent(TRADED_WITH_VILLAGER)` と pending purchase を相関し、実取引成立後にのみ submission する。

## Retention mapping

current built-in catalog の operator-facing retention は次の4 policy とする。

| Policy key | Duration | 用途 |
| --- | --- | --- |
| `kansokusha:audit` | `P180D` | command/admin/player-driven audit と長期調査価値の高い state change |
| `kansokusha:session` | `P30D` | proxy/backend/session transition |
| `kansokusha:default` | `P30D` | chat と explicit fallback |
| `kansokusha:short` | `P7D` | natural/fire/fluid/automated-container 等の高頻度・低長期価値 event |

exact event-type mapping は以下とする。

- `kansokusha:audit`: `block_break`, `block_place`, `sign_change`, `bucket_empty`, `bucket_fill`, `block_harvest`, `flower_pot_change`, `block_ignite`, `tnt_prime`, `explosion_block_change`, `piston_move`, `entity_block_change`, `sponge_absorb`, `block_fertilize`, `cauldron_level_change`, `item_drop`, `item_pickup`, `book_edit`, `lectern_change`, `player_trade`, `player_gamemode_change`, `player_spawn_change`, `player_death`, `paper_player_command`, `paper_server_command`, `velocity_command`, `entity_place`, `armor_stand_manipulate`, `entity_leash_change`, `item_frame_change`, `entity_tame`, `entity_name_change`, `entity_break`, `gamerule_change`, `world_difficulty_change`, `world_border_change`, `world_spawn_change`, `whitelist_change`, `backend_registry_change`.
- `kansokusha:short`: `block_burn`, `natural_block_change`, `fluid_change`, `container_transfer`, `container_pickup`, `container_process`.
- `kansokusha:session`: `server_connected`, `paper_join`, `paper_quit`, `paper_kick`, `player_world_change`, `player_teleport`, `velocity_post_login`, `velocity_disconnect`, `backend_kick`.
- `kansokusha:default`: `paper_chat`, `velocity_chat`.

fallback policy は `kansokusha:default` とする。chat は fallback に依存させず `default` へ明示 mapping する。communication 専用 policy は追加しない。

### Qualified retention mapping

通常の exact mapping は従来どおり event type だけで解決する。payload producer が transient な retention qualifier を付与した場合だけ、`(event type, qualifier)` mapping を exact mapping より先に評価する。qualifier は opaque payload bytes の一部ではなく永続化もしない。また qualifier 自体は policy 名ではなく、operator configuration が最終 policy を決定する。

`kansokusha:cauldron_level_change` は fail-safe として exact mapping を `kansokusha:audit` に置き、Paper payload codec が actor なしの `NATURAL_FILL` / `EVAPORATE` を `kansokusha:natural` qualifier として分類した場合だけ、次の qualified mapping で `kansokusha:short` を選択する。

```yaml
- event-type: kansokusha:cauldron_level_change
  policy: kansokusha:audit
- event-type: kansokusha:cauldron_level_change
  qualifier: kansokusha:natural
  policy: kansokusha:short
```

player/entity actor がある場合、および actor なしでも `UNKNOWN` 等の未知・分類不能 reason は qualifier を付けず `audit` に残す。これにより unknown cause を短期 expiry へ落とさない。

full configuration example は `docs/examples/v1-built-in-retention.yml` を authoritative fixture とし、initial config skeleton へ built-in policy を自動投入しない。reload / expiry semantics は ADR-0003 に従う。

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

## `kansokusha:sign_change`

LOWEST で edited side の既存 sign lines を Adventure Component JSON の immutable snapshot にする。MONITOR で cancelled でない場合のみ、event の final lines を Component JSON へ直ちに snapshot して submission を生成する。

generation 1 payload は次を持つ。

- `side`: edited sign side
- `before`: LOWEST で取得した line components
- `after`: MONITOR 時点の final event line components

## `kansokusha:bucket_empty` / `kansokusha:bucket_fill`

block-changing bucket operation のみを対象とする。牛・ヤギの搾乳等、Paper が `BlockFace.SELF` で発火する non-block `PlayerBucketFillEvent` は記録しない。

LOWEST で changed block の pre-state と operation metadata を immutable snapshot にする。

generation 1 payload は次を持つ。

- `operation`: `empty` / `fill`
- `bucket`, `hand`, `face`
- `clicked_x`, `clicked_y`, `clicked_z`
- `pre_state`: LOWEST の changed block state
- `initial_result_item`: LOWEST の event result item
- `final_result_item`: MONITOR の final event result item

bucket operation 後の block state は記録しない。vanilla の bucket 処理（waterlogged、cauldron、`WATER_EVAPORATES` 等）を Kansokusha 側で再現せず、`pre_state` と `bucket` から解釈する。

## `kansokusha:block_harvest`

`PlayerHarvestBlockEvent` と `PlayerShearBlockEvent` を canonical `kansokusha:block_harvest` に正規化する。通常の `BlockBreakEvent` はこの listener の source にしない。

LOWEST で pre-state と source-specific mutable values を detached snapshot にし、MONITOR では cancellation を確認して LOWEST payload を submission する。

generation 1 payload は次を持つ。

- `operation`: `harvest` / `shear`
- `source_event`: source platform event class
- `hand`
- `pre_state`
- `harvest_items`: harvest source の items。shear では empty
- `shear_tool`: shear source の tool。harvest では empty item
- `shear_drops`: shear source の drops。harvest では empty

real Paper integration verification で normal block break、sweet-berry harvest、pumpkin shear の platform event 発火を分離し、同一 vanilla action を `block_break` と `block_harvest` に二重保存しない semantics を固定する。

## `kansokusha:flower_pot_change`

`PlayerFlowerPotManipulateEvent` の insert/remove を記録する。contents は event に渡された inventory stack 自体ではなく、flower pot block state が表現できる canonical content とする。non-empty content は item type のみを保持した amount `1` の ItemStack として表現し、custom name、lore、custom data 等の ItemStack metadata/components は保持しない。

generation 1 payload は次を持つ。

- `action`: `insert` / `remove`
- `before`: 操作前の canonical pot content
- `after`: 操作後の canonical pot content

insert は `before = empty`、`after = placed item x1`、remove は `before = removed item x1`、`after = empty` とする。

## `kansokusha:item_drop` / `kansokusha:item_pickup`

player と world item entity の間の明示的な transfer を記録する。player inventory 内の generic slot mutation は source にしない。

`item_drop` は non-cancelled `PlayerDropItemEvent` を対象とし、LOWEST で item entity UUID、ItemStack、entity の world position、player subject を detached snapshot にする。

`item_pickup` は `EntityPickupItemEvent` の actor が Player の場合だけ対象とし、LOWEST で item entity UUID、ItemStack、pickup position、remaining count、player subject を detached snapshot にする。non-player pickup は保存しない。

generation 1 payload は共通して `item_entity_uuid`、`stack`、exact entity `position` を持ち、pickup は追加で `remaining` を持つ。ItemStack は `PaperItemStackPayloadCodec` の byte serialization を再利用し、live reference を保持しない。

## `kansokusha:book_edit`

non-cancelled `PlayerEditBookEvent` を記録する。

LOWEST で previous BookMeta と slot を snapshot し、MONITOR で final new BookMeta と signing flag を snapshot する。BookMeta は writable/written book ItemStack に適用して既存 `PaperItemStackPayloadCodec` で serialization するため、本文、title、author、generation、components 等を復元でき、live BookMeta reference を保持しない。本文への独自 redaction は行わない。

generation 1 payload:

- `slot`
- `signing`
- `previous_book_meta`
- `new_book_meta`

## `kansokusha:lectern_change`

`PlayerInsertLecternBookEvent` と `PlayerTakeLecternBookEvent` を action `insert` / `take` に正規化する。`PlayerLecternPageChangeEvent` は閲覧状態であり persistent ownership change ではないため保存しない。

common position は lectern block position とし、generation 1 payload は `action`、`before`、`after` の detached ItemStack snapshot を持つ。insert は empty → inserted book、take は taken book → empty とする。

## `kansokusha:player_trade`

`PlayerPurchaseEvent` を canonical handler とし、その subclass である `PlayerTradeEvent` 用の別 handler は登録しない。このため同一 transaction を二重保存しない。payload の `source_event` で villager/trader の `PlayerTradeEvent` と standalone merchant の `PlayerPurchaseEvent` を区別する。

MONITOR で non-cancelled purchase の final merchant、recipe、reward/increase-use flags を detached payload にして pending に保持するが、この時点では submit しない。Paper 26.2 の `MerchantResultSlot#onTake` は event dispatch 後に final recipe で `MerchantOffer#take` を実行し、成功した branch だけで `Stats.TRADED_WITH_VILLAGER` を award する。この award に伴って同期発火する `PlayerStatisticIncrementEvent(TRADED_WITH_VILLAGER)` を同一 player の pending purchase と相関できた場合だけ `kansokusha:player_trade` を submit する。

これにより、plugin が `PlayerPurchaseEvent#setTrade(...)` で現在の input が満たせない recipe に変更した場合、event が non-cancelled でも `MerchantOffer#take` が失敗するため保存しない。statistic increment event 自体が cancel されても、event の発火位置は既に `MerchantOffer#take` 成功後なので transaction 成立確認として扱う。success signal が発生しない pending は次 tick に破棄する。

merchant が Entity の場合は UUID/type を保存し、standalone merchant は `kind = standalone` とする。trade payload は result、ingredients、adjusted first ingredient、uses/max uses、experience、price/demand/special-price 等の event API が提供する確定値を detached ItemStack/value として保存する。

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

Velocity server name を `Locale.ROOT` で小文字化し、

`kansokusha:velocity-server/<lower-cased name>`

とする。

小文字化した name に Adventure key value で使えない文字（`[a-z0-9_.-/]` 以外）が含まれる場合、その server への接続・その server からの移動は記録せず、server name ごとに1回だけ warning を出力する。大文字小文字だけが異なる server name は同じ server key になる。Paper local server key との network-wide identity 統一は v1 scope 外とする。

### Payload generation 1

payload は nullable `previousServerKey` 1 field を持つ Velocity-specific binary encoding とする。

- null: signed 32-bit big-endian length `-1`
- non-null: signed 32-bit big-endian UTF-8 byte length + canonical Adventure key string の UTF-8 bytes

`previousServerKey` は previous backend server name に同じ server-key encoding を適用した canonical Adventure key string とする。初回 backend connection では null とする。

target backend name は common `server` field から復元可能なため payload に重複保存しない。

## 要件への対応

| 要件 | Catalog decision |
| --- | --- |
| §5 | v1 minimum built-in event を13 event type に固定 |
| §6 | 選定 built-in event では coalescing しない |
| §8 | common fields と generation 1 payload fields を event ごとに固定 |
| §9 | retention policy example、event mapping、fallback を固定 |
| §11 | platform callback は storage I/O / flush completion を待たず bounded submission を使用 |
| §14 | chat / command は v1 minimum built-in 対象外 |

## 参照

- `docs/initial-requirements.md`
- `docs/adr/0001-v1-event-contract-and-api-boundaries.md`
- `docs/adr/0003-retention-resolution-and-expiry-deletion-semantics.md`
- `docs/adr/0004-bounded-ingestion-batching-and-failure-semantics.md`
