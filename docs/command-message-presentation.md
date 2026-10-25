# コマンドメッセージの表示設計

## 情報の優先順位

一覧は相対時刻・主体・操作・対象・数量を優先する。ブロックの種類が同じ場合も、状態差分は一覧に並べずホバーで確認する。生の NBT、フィールド名、イベントの内部コード、UUID は一覧の本文へ流さない。

| 表示 | 常時表示 | ホバー／明示して開く情報 |
| --- | --- | --- |
| search | 時刻・主体・短い操作名・対象・数量、必要な座標 | 正確な日時・UUID・原因・状態差分・属性 |
| inspect | search と同じ行。対象座標はヘッダー | search と共通 |
| event | イベントごとの変更前後・数量・理由・経路などの主要項目 | 操作条件、記録情報、全文、アイテム一覧、全保存データ |

例（ゲーム内、複数地点の検索）:

```text
[2分前] Siroshun09 破壊操作 石 (12,64,-8)
[2分前] Siroshun09 コンテナ増減 土 +64 (-9,120,61)
[2分前] 自然変化 小さなアメジストの芽 (31,15,-5)
```

`container_transaction` の数量はコンテナ側の符号付き純増減であり、プレイヤーの所持品や移動経路を推測しない。アイテム同一性の記録は数量1に正規化されているため、その値を移動数として表示しない。`item_pickup` の保存時スタックと残数も詳細で分ける。

破壊・設置・自動転送・購入などは操作／試行として表現し、未確認の最終成功を断定しない。イベントの意味と保証範囲は `v1-built-in-event-catalog.md` を正とする。自然現象に「主体の記録なし」を繰り返さず、直接の主体が保存された場合にその情報を利用する。

## 座標と操作

検索条件が単一座標に限定される場合、座標をヘッダーへ集約する。範囲検索・座標未指定では各行に短い座標を表示する。ワールドが条件上単一ならワールドもヘッダーにまとめる。現在のページに偶然1地点しかない場合はレイアウトを変えない。

相対時刻には正確な日時・タイムゾーン、主体名にはUUID、対象には状態／アイテム属性、行の背景部分には場所と補足をホバー表示する。検索行のクリックは一律でイベント詳細を開く。権限がない場合はクリックを付けず、案内もホバーのみにする。

コンソール等ではホバーが使えないため、検索結果にイベントIDを明示する。次のコマンドで同じ情報へ到達できる。

```text
/kansokusha event <event-id>
/kansokusha event <event-id> record [page]
/kansokusha event <event-id> text_message [page]
/kansokusha event <event-id> text_command [page]
```

本文ビュー名は記録とイベントに応じて決まる。ゲーム内では生成された［全文］から開ける。`record` は共通の記録情報と全ペイロードを明示して展開する。1ページ8行で、前後ページ・概要への導線を維持する。未知のビュー・不正なページは明示的に拒否する。

## 詳細と長い情報

Paper のイベント群ごとの `PaperEventView` に、一覧の数量の取得元・概要で読むフィールド・ホバーの補足を宣言する。詳細で保存フィールドを機械的に全展開しない。例えば自然変化は変更前／変更後、移動は移動元／先と理由、コンテナ操作は数量と設備を優先する。

チャット・コマンド・看板は短いプレビューから［全文］を開く。短い設定値や技術的な列挙値には全文ボタンを増やさない。生の全保存データは記録情報を開いたときだけ生成する。文字列はUnicodeのコードポイント境界で分割し、改行・空行を維持する。

Paper のアイテムは既存 `PaperItemStackPayloadCodec.decode` の `ItemStack.deserializeBytes` を再利用する。標準の SHOW_ITEM ホバーで属性を表示する。本のページ本文をツールチップへ送らず、タイトル・著者・ページ数などのメタデータを保持した表示用コピーを使う。多数のアイテムは［アイテム］ビューで確認できる。

本の記録には［読む］を付ける。変更前／後の本はそれぞれの項目から開く。未署名本も署名済みの閲覧用コピーを作って標準の本の画面で読む。原本やライブのインベントリは変更しない。書式は保持し、記録されたクリック・ホバー等のアクションは閲覧用の本文から除去する。

## 実装上の境界

`DuckDbEventSearch` は opaque なバイト列だけを取得する。一覧は1件16 KiBまで、詳細は全量を取得する。ストレージは復号や翻訳を行わない。Paper / Velocity は自分の既知イベントと世代だけを復号する。未知世代・外部イベント・破損データは短い共通表示へフォールバックする。

Paper の現行 `id/properties` と従来の `Name/Properties` のブロック状態を扱い、どちらも人向けの名称へ変換する。Minecraft の名称は適切なAPIの翻訳キーからクライアントへ送る。独自名前空間や未知の種類は識別子へフォールバックする。

新しい表示は `kansokusha.display.compact.*` のカスタマイズ可能な日英メッセージを使う。既存言語ファイルに保存された以前の長い文章が新しい配置へ残らないよう、旧キーと分離する。旧キー自体は保持する。既存の保存形式・DBスキーマ・公開APIは変更しない。

権限確認は概要・全文・記録情報・本の閲覧すべてで行う。本のUI操作はプレイヤーのregion schedulerに移し、実行直前にも権限を再確認する。復号前に権限を確認し、保存されていない情報は補わない。

## 確認項目

- Paper登録58種と表示定義の集合一致、Velocity全7種のCodec経由表示。
- 実際のMinecraftブロック状態で、一覧に `properties` や内部イベントコードが出ないこと。
- 座標の出し分け、時刻ホバーと行クリック、概要・全文・記録情報の分離とページ境界。
- 日英メッセージのキー／引数一致、既存カスタマイズの保持、原文中のタグを解釈しないこと。
- 標準アイテムホバー、アイテム属性・本の全ページ・書式・原本の保持。
- 本の閲覧経路、regionでのUI実行、権限失効、インベントリに追加しないこと。
- CIの `build` に含まれる既存のコマンド・ストレージ・リスナー・Paper / Velocity 外部API統合テスト。

## Paper イベントごとの概要フィールド

全58種の一覧には短い操作・対象を表示し、下表の状態差分は詳細／ホバーに置く。詳細フィールドが空のイベントは、要約・場所と必要時の記録情報を表示する。数量列は保存済み数量を一覧に使える場合の取得元である。

| イベント | 一覧の数量 | 概要の主要フィールド |
| --- | --- | --- |
| `anvil_use` | `result_item` | `input_items result_item rename_text` |
| `armor_stand_manipulate` | `—` | `equipment_slot player_item_before armor_stand_item_before` |
| `block_break` | `—` | `—` |
| `block_burn` | `—` | `—` |
| `block_fertilize` | `—` | `pre_state post_state` |
| `block_harvest` | `—` | `harvest_items shear_drops` |
| `block_ignite` | `—` | `cause` |
| `block_interaction` | `—` | `—` |
| `block_place` | `—` | `replaced` |
| `book_edit` | `—` | `previous_book_meta new_book_meta` |
| `bucket_empty` | `—` | `pre_state result_item` |
| `bucket_fill` | `—` | `pre_state result_item` |
| `cauldron_level_change` | `—` | `old_state new_state reason` |
| `container_pickup` | `source_item` | `source_item inventory` |
| `container_process` | `—` | `input_items event_result_items` |
| `container_transaction` | `amount_delta` | `transfer_direction amount_delta container` |
| `container_transfer` | `item` | `source_inventory destination_inventory item` |
| `craft_item` | `result_item` | `input_items result_item` |
| `dispenser_dispense` | `item` | `item` |
| `enchant_item` | `—` | `item consumed_levels` |
| `entity_block_change` | `—` | `before to` |
| `entity_break` | `—` | `cause` |
| `entity_death` | `—` | `damage_type` |
| `entity_leash_change` | `—` | `action reason` |
| `entity_name_change` | `—` | `previous_custom_name new_custom_name` |
| `entity_place` | `—` | `used_item` |
| `entity_spawn` | `—` | `spawn_reason` |
| `entity_tame` | `—` | `new_owner` |
| `explosion_block_change` | `—` | `—` |
| `flower_pot_change` | `—` | `before after` |
| `fluid_change` | `—` | `source destination` |
| `gamerule_change` | `—` | `before after` |
| `item_drop` | `stack` | `stack` |
| `item_frame_change` | `—` | `item_before item_after rotation_before rotation_after` |
| `item_pickup` | `—` | `stack remaining` |
| `lectern_change` | `—` | `before after` |
| `natural_block_change` | `—` | `pre_state post_state` |
| `paper_chat` | `—` | `message` |
| `paper_join` | `—` | `$server` |
| `paper_kick` | `—` | `$server ~reason cause` |
| `paper_player_command` | `—` | `command` |
| `paper_quit` | `—` | `$server reason` |
| `paper_server_command` | `—` | `source_kind source_name command` |
| `piston_move` | `—` | `from to` |
| `player_death` | `—` | `death_message last_damage_cause` |
| `player_gamemode_change` | `—` | `old_gamemode new_gamemode cause` |
| `player_spawn_change` | `—` | `before after cause` |
| `player_teleport` | `—` | `from $world to cause` |
| `player_trade` | `trade.result` | `trade` |
| `player_world_change` | `—` | `from_world $world` |
| `sign_change` | `—` | `before after` |
| `smith_item` | `result_item` | `input_items result_item` |
| `sponge_absorb` | `—` | `—` |
| `tnt_prime` | `—` | `cause` |
| `whitelist_change` | `—` | `action profile_name before_enabled after_enabled before_whitelisted after_whitelisted` |
| `world_border_change` | `—` | `before after before_size after_size transition_duration_ticks` |
| `world_difficulty_change` | `—` | `before after` |
| `world_spawn_change` | `—` | `before after` |
