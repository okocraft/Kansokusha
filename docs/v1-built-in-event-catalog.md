# Kansokusha v1 built-in event catalog

- 日付: 2026-09-26
- 関連 Issue: #10, #37, #101, #102, #103, #104, #105, #106
- 前提: `docs/design.md`

## 目的

Kansokusha v1 の組み込み event catalog について、#101〜#106 の最終選定を含む current runtime の event type、source API、retention、意味・保証範囲を固定する。

本 catalog は Minecraft の網羅的な監査ログを定義しない。外部 plugin は public API から独自 event type を登録・記録できる。

## Current catalog

以下は current runtime で register / wire される全56 event type と platform API source の対応である。source event は同一 Kansokusha event type へ canonicalize される場合がある。表にない close 済み対象外 event を built-in listener source として追加しない。

### Paper / Folia

| Kansokusha event type | Paper / Bukkit source event | Retention | Boundary |
| --- | --- | --- | --- |
| `kansokusha:block_break` | `BlockBreakEvent` | `audit` | non-cancelled break attempt; later vanilla destruction success is not asserted |
| `kansokusha:block_place` | `BlockPlaceEvent` / `BlockMultiPlaceEvent` | `audit` | non-cancelled + buildable placement attempt; final post-processing state is not asserted |
| `kansokusha:sign_change` | `SignChangeEvent` | `audit` | current sign lines + final event lines |
| `kansokusha:bucket_empty` | `PlayerBucketEmptyEvent` | `audit` | block-changing bucket operation |
| `kansokusha:bucket_fill` | `PlayerBucketFillEvent` | `audit` | block-changing fill only; non-block `BlockFace.SELF` paths are excluded |
| `kansokusha:block_harvest` | `PlayerHarvestBlockEvent` / `PlayerShearBlockEvent` | `audit` | #110 shear is canonicalized into #109 harvest |
| `kansokusha:flower_pot_change` | `PlayerFlowerPotManipulateEvent` | `audit` | insert/remove |
| `kansokusha:block_ignite` | `BlockIgniteEvent` | `audit` | ignition origin/cause; `SPREAD` is excluded and owned by `natural_block_change` |
| `kansokusha:block_burn` | `BlockBurnEvent` | `short` | fire-caused destruction; burning TNT is owned by `tnt_prime` while `tntExplodes` is true |
| `kansokusha:tnt_prime` | `TNTPrimeEvent` | `audit` | accepted TNT prime while `tntExplodes` is true |
| `kansokusha:explosion_block_change` | `BlockExplodeEvent` / `EntityExplodeEvent` | `audit` | one submission per affected block; exploded TNT is owned by `tnt_prime` while `tntExplodes` is true, except for the Ender Dragon |
| `kansokusha:piston_move` | `BlockPistonExtendEvent` / `BlockPistonRetractEvent` | `audit` | piston movement |
| `kansokusha:entity_block_change` | `EntityChangeBlockEvent` | `audit` | entity-caused block mutation |
| `kansokusha:natural_block_change` | `BlockFadeEvent`, `BlockFormEvent`, `BlockGrowEvent`, `BlockSpreadEvent`, `LeavesDecayEvent`, `MoistureChangeEvent`, non-bonemeal `StructureGrowEvent` | `short` | natural/environmental change; `EntityBlockFormEvent` is excluded |
| `kansokusha:fluid_change` | `BlockFromToEvent` | `short` | water/lava source → destination arrival only |
| `kansokusha:sponge_absorb` | `SpongeAbsorbEvent` | `audit` | one submission per absorbed block |
| `kansokusha:block_fertilize` | `BlockFertilizeEvent` | `audit` | bone meal changes; grow/spread events fired during bone meal are also recorded as `natural_block_change` |
| `kansokusha:cauldron_level_change` | `CauldronLevelChangeEvent` | `audit` | player/entity/natural changes; the payload records `reason`, while actor identity stays in the common actor columns |
| `kansokusha:container_transfer` | `InventoryMoveItemEvent` | `short` | non-cancelled transfer attempt, not a post-storage success signal |
| `kansokusha:container_pickup` | `InventoryPickupItemEvent` | `short` | world item → container pickup operation |
| `kansokusha:container_process` | `FurnaceSmeltEvent`, `BrewEvent`, `BlockCookEvent`, `CrafterCraftEvent` | `short` | furnace/brewing/campfire/crafter transformation boundary |
| `kansokusha:item_drop` | `PlayerDropItemEvent` | `audit` | player → world item ownership transfer |
| `kansokusha:item_pickup` | `EntityPickupItemEvent` when actor is `Player` | `audit` | world item → player ownership transfer |
| `kansokusha:book_edit` | `PlayerEditBookEvent` | `audit` | previous/final book state |
| `kansokusha:lectern_change` | `PlayerInsertLecternBookEvent` / `PlayerTakeLecternBookEvent` | `audit` | insert/take only; page navigation is excluded |
| `kansokusha:player_trade` | `PlayerPurchaseEvent` (including `PlayerTradeEvent` subclass) | `audit` | non-cancelled purchase attempt; trade success is not confirmed |
| `kansokusha:paper_join` | `PlayerJoinEvent` | `session` | successful backend join |
| `kansokusha:paper_quit` | `PlayerQuitEvent` | `session` | completed backend session end |
| `kansokusha:paper_kick` | `PlayerKickEvent` | `session` | accepted kick decision; a subsequent quit is intentionally separate |
| `kansokusha:player_world_change` | `PlayerChangedWorldEvent` | `session` | completed world state transition |
| `kansokusha:player_teleport` | `PlayerTeleportEvent` excluding `PlayerPortalEvent` preflight | `session` | accepted teleport operation; intentionally coexists with world change |
| `kansokusha:player_gamemode_change` | `PlayerGameModeChangeEvent` | `audit` | accepted old → final-new transition |
| `kansokusha:player_spawn_change` | `PlayerSetSpawnEvent` | `audit` | effective player respawn-point set/clear boundary |
| `kansokusha:player_death` | `PlayerDeathEvent` | `audit` | death context; full drops are not persisted |
| `kansokusha:paper_chat` | `AsyncChatEvent` | `default` | original raw message only |
| `kansokusha:paper_player_command` | `PlayerCommandPreprocessEvent` | `audit` | original raw command line only |
| `kansokusha:paper_server_command` | `ServerCommandEvent` / `RemoteServerCommandEvent` | `audit` | source descriptor + original raw command only |
| `kansokusha:entity_place` | `EntityPlaceEvent` / `HangingPlaceEvent` | `audit` | #156 hanging place is canonicalized into #155 |
| `kansokusha:entity_break` | `HangingBreakByEntityEvent` | `audit` | #157 hanging break is canonicalized into #164; non-hanging entities are handled once the Paper baseline provides `EntityBreakByEntityEvent` |
| `kansokusha:armor_stand_manipulate` | `PlayerArmorStandManipulateEvent` | `audit` | player armor-stand state change |
| `kansokusha:entity_leash_change` | `PlayerLeashEntityEvent` / `PlayerUnleashEntityEvent` | `audit` | player leash/unleash |
| `kansokusha:item_frame_change` | `PlayerItemFrameChangeEvent` | `audit` | existing frame content/rotation change; the payload records the fixed state |
| `kansokusha:entity_tame` | `EntityTameEvent` | `audit` | tame owner transition |
| `kansokusha:entity_name_change` | `PlayerNameEntityEvent` | `audit` | player-driven entity rename |
| `kansokusha:gamerule_change` | `WorldGameRuleChangeEvent` | `audit` | effective gamerule state change |
| `kansokusha:world_difficulty_change` | `WorldDifficultyChangeEvent` | `audit` | effective world difficulty change |
| `kansokusha:world_border_change` | `WorldBorderCenterChangeEvent` / `WorldBorderBoundsChangeEvent` | `audit` | transition start/state request; finish notification is excluded |
| `kansokusha:world_spawn_change` | `SpawnChangeEvent` | `audit` | world-global spawn change |
| `kansokusha:whitelist_change` | `WhitelistToggleEvent` / `WhitelistStateUpdateEvent` | `audit` | global toggle and profile add/remove |

### Velocity

| Kansokusha event type | Velocity source event | Retention | Boundary |
| --- | --- | --- | --- |
| `kansokusha:server_connected` | `ServerConnectedEvent` | `session` | successful backend connection |
| `kansokusha:velocity_post_login` | `PostLoginEvent` | `session` | successful proxy login |
| `kansokusha:velocity_disconnect` | `DisconnectEvent` | `session` | proxy session end |
| `kansokusha:backend_kick` | `KickedFromServerEvent` | `session` | backend kick plus Velocity final action |
| `kansokusha:velocity_chat` | `PlayerChatEvent` | `default` | original raw message only |
| `kansokusha:velocity_command` | `CommandExecuteEvent` | `audit` | source descriptor + original raw command only |
| `kansokusha:backend_registry_change` | `ServerRegisteredEvent` / `ServerUnregisteredEvent` | `audit` | runtime/reload-induced backend map changes after plugin initialization |

## 共通 rules

### Registration / submission

各 built-in event type は payload generation `1` を使用する。

registration、submission、acceptance / persistence の意味は `docs/design.md` に従う。

### Timestamp

`occurredAt` は Kansokusha が対象 platform event を処理した時点の current instant とする。

同一 platform event から複数の `EventSubmission` を生成する場合（`BlockMultiPlaceEvent`、爆発、sponge、fertilize、structure grow、piston 等）、処理時に1回だけ取得した同一 `occurredAt` を共有する。

### Actor / target

actor（誰が）はイベントを直接起こした主体、target type（何を）はイベントが作用した対象の種類 key とする。間接的な主体（projectile を撃った entity、primed TNT を着火した entity、攻撃した tameable の owner 等）は actor にせず、既存の payload field に残す。

- `PlayerActor(player UUID)`: player
- `EntityActor(entity UUID, entity type key)`: player 以外の entity
- `BlockActor(block type key)`: block。block の位置が必要な場合は payload の既存 field を参照する

block の変化で変化前と変化後の両方がある event の target は、変化前の block type とし、変化前が空気の場合は変化後の block type とする（以下「変化した block」）。

| Event type | Actor | Target type |
| --- | --- | --- |
| `block_break` | player | 破壊された block |
| `block_place` | player | 設置された block |
| `sign_change` | player | sign の block |
| `bucket_empty` | player | 使用した bucket item（例: `minecraft:lava_bucket`） |
| `bucket_fill` | player | 結果の bucket item（例: `minecraft:water_bucket`）。結果 item がなければなし |
| `block_harvest` | player | 収穫・剪定された block |
| `flower_pot_change` | player | 出し入れされた item。なければなし |
| `block_ignite` | 着火した entity、なければ着火元の block（溶岩・火等）、どちらもなければなし | 着火された block。空気への着火は `minecraft:fire` |
| `block_burn` | 着火元の block。なければなし | 焼失した block |
| `tnt_prime` | 着火した entity、なければ着火元の block、どちらもなければなし | TNT の block |
| `explosion_block_change` | 爆発した entity（`BlockExplodeEvent` では爆発した block） | 破壊された block |
| `piston_move` | piston の block | 移動した block |
| `entity_block_change` | block を変化させた entity | 変化した block |
| `natural_block_change` | `BlockSpreadEvent` では広がり元の block。それ以外はなし | 変化した block（`LeavesDecayEvent` では葉の block） |
| `fluid_change` | 流れた液体の block（`minecraft:water` / `minecraft:lava`） | 流入先の block。流入先が空気なら液体の block |
| `sponge_absorb` | sponge の block | 吸収された block |
| `block_fertilize` | player。なければなし | 変化した block |
| `cauldron_level_change` | 変化させた entity。なければなし | 変化前の cauldron の block |
| `container_transfer` | 移動を起こした inventory の holder（hopper の block、hopper minecart の entity）。holder が block / entity でなければなし | 移動した item |
| `container_pickup` | 拾った inventory の holder。holder が block / entity でなければなし | 拾われた item |
| `container_process` | 処理した block | furnace / campfire / crafter は結果の item、brewing は ingredient の item |
| `item_drop` / `item_pickup` | player | item |
| `book_edit` | player | 署名時 `minecraft:written_book`、それ以外 `minecraft:writable_book` |
| `lectern_change` | player | 出し入れされた book の item |
| `player_trade` | player | 取引の結果 item |
| `entity_place` | player。なければなし | 設置された entity |
| `entity_break` | 取り除いた entity | 壊された entity |
| `armor_stand_manipulate` / `entity_leash_change` / `item_frame_change` / `entity_name_change` | player | 対象の entity |
| `entity_tame` | 新しい owner（entity の場合）。それ以外はなし | 手懐けられた entity |
| `gamerule_change` | 変更した command sender（player / entity / command block）。それ以外はなし | gamerule の key |
| `world_difficulty_change` | 変更した command sender（player / entity / command block）。それ以外はなし | なし |
| `paper_server_command` | command block または command minecart。console / rcon はなし | なし |
| `world_border_change` / `world_spawn_change` / `whitelist_change` | なし | なし |
| 上記以外の Paper event（session、chat、player command、player state） | player | なし |
| Velocity event | player（`velocity_command` は player が送った場合のみ）。`backend_registry_change` はなし | なし |

### Payload encoding

payload は provider-defined opaque bytes とし、platform 固有の built-in event を common codec へ抽象化しない。

Paper / Folia の generation 1 payload は、各 event codec が `CompoundTag` を logical structure として構築した後、`PaperPayloadNbtCodec` の compact binary format へ encode する。persisted bytes は binary NBT ではない。built-in field name は append-only の small integer ID、integer は ZigZag + varint、boolean は専用 tag、UUID string は 16 bytes、`minecraft:` key string は namespace を省略して保存する。block-state property 等の open-ended field name には UTF-8 literal fallback を使う。

payload ごとの Deflate / Zstd 等の圧縮は行わない。短い payload に圧縮 header を追加せず、chat / command の自由長 text は compact envelope 内の UTF-8 string として保存する。

common fields（event type、generation、occurredAt、server、world、position、actor、target type）から一意に復元できる情報は payload に重複保存しない。primary actor の UUID/type は actor columns を正とし、payload には shooter / owner 等の indirect attribution のみを残す。block state や ItemStack のように event 固有の復元に必要な構造は payload に保持する。

### Paper / Folia capture semantics

Paper / Folia の組み込み listener は `@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)` の1 handler で記録する。cancel された event は Bukkit が handler を呼ばないため記録しない。

payload の pre-state（変更前の block state、item frame の中身、player の game mode 等）は MONITOR 時点で読む。Bukkit / Paper の多くの event は変更の適用前に発火するため、MONITOR 時点でも変更前の state を読める。LOWEST〜MONITOR の間に他 plugin が world state を変更した場合への対応は行わない。

listener は event 間で共有する状態を持たない。1つの platform event は1つの handler 呼び出しの中で完結するため、Folia の region thread から並行に呼ばれても同期は不要である。

MONITOR 後の Paper / vanilla processing が実際に完了・成功したことは保証しない。

例外として、chat と command（`paper_chat`、`paper_player_command`、`paper_server_command`）は plugin による書き換えや cancel の前の original raw input を記録するため、`EventPriority.LOWEST` で cancel 状態に関係なく記録する。

## Canonical integration / duplicate semantics

Canonicalization and intentional coexistence are part of the event contract, not generic repeated-log suppression.

- fire: `block_ignite` does not accept `BlockIgniteEvent.IgniteCause.SPREAD`; propagation is `natural_block_change` via `BlockSpreadEvent`; destruction is `block_burn`.
- grow/fertilize: bone meal `StructureGrowEvent` is left to `block_fertilize`. Other `BlockGrowEvent` / `BlockSpreadEvent` fired inside bone meal processing (e.g. cocoa, crops, rooted dirt) are recorded as `natural_block_change` in addition to `block_fertilize`; no cross-event correlation is performed.
- TNT: while the `tntExplodes` game rule is true, TNT lit by fire or hit by a non-dragon explosion is recorded only as `tnt_prime`; `block_burn` and `explosion_block_change` skip TNT blocks. When the rule is false, TNT is destroyed instead of primed, so `block_burn` / `explosion_block_change` record it and `tnt_prime` does not. The Ender Dragon does not fire `TNTPrimeEvent`, so TNT it destroys is recorded as `explosion_block_change`.
- scaffolding: `BlockFadeEvent` for scaffolding at the maximum distance is not recorded because the block falls and `entity_block_change` records the falling block.
- harvest/shear/break: `PlayerHarvestBlockEvent` and `PlayerShearBlockEvent` both map to `block_harvest`; normal `BlockBreakEvent` remains `block_break`, and the real Paper fixture verifies representative vanilla actions are not double-owned.
- entity placement: generic `EntityPlaceEvent` skips `Hanging`; `HangingPlaceEvent` supplies the hanging path into the same `entity_place` type.
- entity break: the Paper 26.2 baseline only exposes `HangingBreakByEntityEvent`, which is the sole source.
- trade: only the `PlayerPurchaseEvent` handler is registered for purchase/trade dispatch; `PlayerTradeEvent` is identified as its subclass in payload metadata.
- kick/quit: an accepted `paper_kick` and the subsequent `paper_quit` with kicked quit reason are intentionally both recorded because they represent the kick decision and completed session end.
- world change/teleport: `player_world_change` and `player_teleport` intentionally coexist because they represent state transition and operation history respectively.

Generic coalescing, rate-based repeated-log suppression, or automatic-machine aggregation is not implemented by this catalog expansion and remains separate scope. Event-specific ownership/canonicalization above does not imply a general coalescing facility.

## Raw activity and success boundaries

Chat and command event types persist only the original raw activity observed at their platform pre-execution/pre-routing boundary. They do not persist rewritten/final content, cancellation/allow/deny decisions, command result objects, or execution success/failure.

Cancellable Paper events are submitted only when they are not cancelled at MONITOR, but that does not prove later vanilla processing completed successfully. In particular `block_break`, `block_place`, `container_transfer`, and `player_trade` are event/attempt observations within the documented boundary. Completed session/state events such as join, quit, post-login, server-connected, and player-world-change represent transitions that have already occurred.

## Explicit exclusions

The final catalog intentionally has no built-in listener for the following reviewed API events/semantics:

- generic player inventory slot mutation (#126)
- Paper/Velocity login decision or deny events (#134 / #143)
- Velocity server pre-connect/routing decision (#147)
- cross-host transfer (#148)
- post-command execution/result (#154)
- lectern page navigation (`PlayerLecternPageChangeEvent`)
- world-border finish notification (`WorldBorderBoundsChangeFinishEvent`)
- fluid level-only change (`FluidLevelChangeEvent`); #119 is water/lava `BlockFromToEvent` arrival only
- proxy reload event itself (#171 / `ProxyReloadEvent`); actual backend registry deltas remain observable through register/unregister events

## Retention mapping

current built-in catalog の推奨保持期間は bundled `config.yml`（`common/src/main/resources/config.yml`）に定義し、初回起動時にそのままデータディレクトリへコピーする。

| Policy | Duration | 用途 |
| --- | --- | --- |
| `audit` | `P180D` | command/admin/player-driven audit と長期調査価値の高い state change |
| `short` | `P7D` | natural/fire/fluid/automated-container 等の高頻度・低長期価値 event |
| default | `P30D` | 上記以外（session transition、chat） |

- `audit`: `block_break`, `block_place`, `sign_change`, `bucket_empty`, `bucket_fill`, `block_harvest`, `flower_pot_change`, `block_ignite`, `tnt_prime`, `explosion_block_change`, `piston_move`, `entity_block_change`, `sponge_absorb`, `block_fertilize`, `cauldron_level_change`, `item_drop`, `item_pickup`, `book_edit`, `lectern_change`, `player_trade`, `player_gamemode_change`, `player_spawn_change`, `player_death`, `paper_player_command`, `paper_server_command`, `velocity_command`, `entity_place`, `armor_stand_manipulate`, `entity_leash_change`, `item_frame_change`, `entity_tame`, `entity_name_change`, `entity_break`, `gamerule_change`, `world_difficulty_change`, `world_border_change`, `world_spawn_change`, `whitelist_change`, `backend_registry_change`.
- `short`: `block_burn`, `natural_block_change`, `fluid_change`, `container_transfer`, `container_pickup`, `container_process`.
- default: `server_connected`, `paper_join`, `paper_quit`, `paper_kick`, `player_world_change`, `player_teleport`, `velocity_post_login`, `velocity_disconnect`, `backend_kick`, `paper_chat`, `velocity_chat`.

## `kansokusha:block_break`

### Capture

cancel されていない `BlockBreakEvent` ごとに1つの `EventSubmission` を生成・試行する。target block の state は MONITOR 時点（破壊の適用前）に読む。

この event は non-cancelled の player break event を表す。後続の vanilla block destruction 成功そのものは表さない。

### Fields / payload

common fields:

- server: Paper runtime の local server key
- world: Bukkit world key を Adventure `Key` へ lossless conversion
- position: broken block の integer block coordinates
- actor: breaking player
- target type: broken block type

payload generation 1 は Paper module の compact binary codec で保存する。

MONITOR 時点で取得した Bukkit `BlockData` を `CraftBlockData#getState()` で Minecraft `BlockState` に変換し、`NbtUtils.writeBlockState` の `CompoundTag` を logical structure として codec に渡す。

これにより block identity と全 block-state properties を Minecraft の block-state serialization の意味を保ったまま保持する。block entity NBT、item drops、experience、tool durability 等は generation 1 payload に含めない。

## `kansokusha:block_place`

### Capture

`event.isCancelled() == false && event.canBuild() == true` の場合だけ、各 changed block について submission を生成・試行する。

Bukkit は `BlockPlaceEvent` の発火前に block を仮設置し、cancel 時に戻す。このため MONITOR 時点の world の block を placed state、event の replaced state（`getBlockReplacedState()` / `getReplacedBlockStates()`）を replaced state とする。

この event は MONITOR 時点の replaced state と仮設置された placed state を表す。event handler 後の block entity installation、`onPlace`、physics 等を反映した final world state は表さない。

### Fields / payload

common fields:

- server: Paper runtime の local server key
- world: changed block の Bukkit world key を Adventure `Key` へ lossless conversion
- position: changed block の integer block coordinates
- actor: placing player
- target type: placed block type

payload generation 1 の logical payload は次の2 child compounds を持ち、Paper compact binary codec で保存する。

1. `replaced`: event の replaced Minecraft `BlockState` を `NbtUtils.writeBlockState` した value
2. `placed`: MONITOR 時点で world に仮設置されている Minecraft `BlockState` を `NbtUtils.writeBlockState` した value

outer compound の field name / primitive value は compact binary codec で保存する。block entity NBT は generation 1 payload に含めない。

### Multi-place granularity

通常の `BlockPlaceEvent` は1 changed blockにつき1 submission を生成・試行する。

`BlockMultiPlaceEvent` は N changed blocks について厳密に N submissions を生成・試行し、base `BlockPlaceEvent` 分の追加 submission は作らない。全 submission は同一 `occurredAt` を共有する。

各 submission は独立してキューへ入るため、partial acceptance は許容する。v1 は atomic multi-event admission を要求しない。

## `kansokusha:sign_change`

cancel されていない `SignChangeEvent` について、edited side の現在の sign lines と event の final lines を Adventure Component JSON にして submission を生成する。sign の block entity は event の後に更新されるため、MONITOR 時点の sign lines は変更前の値である。

generation 1 payload は次を持つ。

- `side`: edited sign side
- `before`: MONITOR 時点の sign の line components（変更前）
- `after`: MONITOR 時点の event の line components

## `kansokusha:bucket_empty` / `kansokusha:bucket_fill`

block-changing bucket operation のみを対象とする。牛・ヤギの搾乳等、Paper が `BlockFace.SELF` で発火する non-block `PlayerBucketFillEvent` は記録しない。

cancel されていない event について、changed block の pre-state（MONITOR 時点、bucket 処理の適用前）と operation metadata を記録する。

generation 1 payload は次を持つ。

- `operation`: `empty` / `fill`
- `bucket`, `hand`, `face`
- `clicked_x`, `clicked_y`, `clicked_z`
- `pre_state`: changed block state
- `result_item`: MONITOR 時点の event result item

bucket operation 後の block state は記録しない。vanilla の bucket 処理（waterlogged、cauldron、`WATER_EVAPORATES` 等）を Kansokusha 側で再現せず、`pre_state` と `bucket` から解釈する。

## `kansokusha:block_harvest`

`PlayerHarvestBlockEvent` と `PlayerShearBlockEvent` を canonical `kansokusha:block_harvest` に正規化する。通常の `BlockBreakEvent` はこの listener の source にしない。

cancel されていない event について、MONITOR 時点の pre-state と source-specific values を記録する。

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

`item_drop` は non-cancelled `PlayerDropItemEvent` を対象とし、item entity UUID、ItemStack、entity の world position、player actor を記録する。

`item_pickup` は `EntityPickupItemEvent` の actor が Player の場合だけ対象とし、item entity UUID、ItemStack、pickup position、remaining count、player actor を記録する。non-player pickup は保存しない。

generation 1 payload は共通して `item_entity_uuid`、`stack`、exact entity `position` を持ち、pickup は追加で `remaining` を持つ。ItemStack は `PaperItemStackPayloadCodec` の byte serialization を再利用し、live reference を保持しない。

## `kansokusha:book_edit`

non-cancelled `PlayerEditBookEvent` を記録する。

event の previous BookMeta、slot、final new BookMeta、signing flag を記録する。previous BookMeta は plugin が変更できない値のため、MONITOR 時点で読んでも LOWEST 時点と同じである。BookMeta は writable/written book ItemStack に適用して既存 `PaperItemStackPayloadCodec` で serialization するため、本文、title、author、generation、components 等を復元でき、live BookMeta reference を保持しない。本文への独自 redaction は行わない。

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

cancel されていない purchase の final merchant、recipe、reward/increase-use flags を、MONITOR の時点で submit する。

これは取引の試行であり、成立は確認しない。Paper 26.2 の `MerchantResultSlot#onTake` は event dispatch 後に final recipe で `MerchantOffer#take` を実行するため、plugin が `PlayerPurchaseEvent#setTrade(...)` で現在の input が満たせない recipe に変更した場合などは、event が non-cancelled でも取引は成立しない。この場合も `player_trade` は記録される。

merchant が Entity の場合は UUID/type を保存し、standalone merchant は `kind = standalone` とする。trade payload は result、ingredients、adjusted first ingredient、uses/max uses、experience、price/demand/special-price 等の event API が提供する確定値を detached ItemStack/value として保存する。

## `kansokusha:server_connected`

### Capture / fields

Velocity `ServerConnectedEvent` が示す successful backend connection を記録する。

common fields:

- server: target backend server key
- world: なし
- position: なし
- actor: connected player

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

## 最終 integration invariants

| Area | Current contract |
| --- | --- |
| runtime wiring | Paper 49 event type / Velocity 7 event type = 56 adopted built-ins are registered when the platform plugin starts |
| closed/out-of-scope | reviewed exclusions above are not registered as built-in listeners |
| canonical merges | #110 → #109 `block_harvest`; #156 → #155 `entity_place`; #157 → #164 `entity_break` |
| retention | the bundled `config.yml` maps event types to `audit` / `short`; the others use the default period |
| lifecycle | the platform unregisters listeners when the plugin stops; a startup failure disables the plugin |
| ingestion | platform callbacks call the bounded `KansokushaApi.submit` boundary and do not wait for storage completion |
| Folia | listeners keep no state shared between events; each platform event is recorded within one MONITOR handler call |
| coalescing | no generic coalescing/repeated-log suppression mechanism is part of this expansion |

## 参照

- `docs/initial-requirements.md`
- `docs/design.md`
