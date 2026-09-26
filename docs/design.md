# Kansokusha 設計

`docs/initial-requirements.md` の v1 要件に対する現在の設計をまとめる。
要件を満たす範囲で、実装と運用が最も単純になる方式を選ぶことを方針とする。

## モジュール構成

| モジュール | 役割 |
| --- | --- |
| `api` | 外部プラグイン向けの公開 API。platform / storage の型に依存しない |
| `common` | 設定読み込み、キュー、DuckDB への書き込み、保持期限切れイベントの削除 |
| `paper` | Paper / Folia プラグインと組み込みイベント |
| `velocity` | Velocity プラグインと組み込みイベント |
| `*-test-plugin` | 実サーバー上で公開 API と永続化を確認する結合テスト用プラグイン |

## 公開 API

外部プラグインは `Kansokusha.api()` から `KansokushaApi` を取得する。Kansokusha が起動していない場合は `IllegalStateException` となる。

- `registerEventType(EventTypeDefinition)`: event type (`Key`) と payload generation を登録する。同じ定義の再登録は何もしない。同じ key を別の generation で登録すると `IllegalArgumentException`。
- `submit(EventSubmission)`: イベントをキューへ入れ、ストレージ I/O を待たずに返る。
  - `true`: キューに入った。永続化の完了は意味しない。
  - `false`: キューが満杯、または Kansokusha が停止済みのため破棄した。
  - 未登録の event type、generation の不一致、保存できない発生時刻（保持期限の計算で範囲外になるものを含む）はプログラムの誤りなので `IllegalArgumentException`。この検証を submit 時に行うため、不正な 1 件が同じバッチの他のイベントの書き込みを失敗させることはない。
- `localServerKey()`: Paper ではローカルサーバーの key。Velocity では空。

`EventSubmission` は event type、payload generation、発生時刻、opaque な payload を必須とし、server / world / 座標 / player を任意で持つ。
world は server に、座標は world に属するため、それぞれ前者なしには指定できない。
payload の形式と解釈は提供側の責任で、形式を非互換に変えるときは payload generation を上げる。

## 記録パイプライン

```text
submit() ──offer──▶ ArrayBlockingQueue ──flush-interval ごと / batch-size 到達時──▶ DuckDB
                                   (kansokusha-storage スレッド 1 本)
```

- 呼び出し側はキューへの `offer` のみを行い、ブロックしない（要件 §11）。
- キューは `queue-capacity` で上限を持ち、満杯時の submit は破棄する（要件 §12）。
- `kansokusha-storage` スレッドが `flush-interval` ごとにキューのイベントを書き込む。キューが `batch-size` 件に達した場合は間隔を待たずに書き込む。1 トランザクションは最大 `batch-size` 件で、それを超える分は続けて別のトランザクションで書き込む。
- 保持期限（`expires_at = occurred_at + 保持期間`）は submit 時に計算してキューへ入れる。保持期限切れイベントの削除も同じスレッドで行うため、DuckDB 接続は並行に使われない。
- 書き込みに失敗したバッチはサーバーログへ出力して破棄し、次のバッチの書き込みは続ける（要件 §13）。
- 停止時は新しいイベントの受け付けを止め、キューに残ったイベントを書き込んでから接続を閉じる。受け付けの停止とキューの排出は lock で順序付けており、停止後にキューへ入るイベントはない。

## ストレージ

データディレクトリの `kansokusha.duckdb` に次の 1 テーブルだけを持つ。

```sql
CREATE TABLE events (
    event_type VARCHAR NOT NULL,
    payload_generation INTEGER NOT NULL,
    occurred_at TIMESTAMP_MS NOT NULL,
    server VARCHAR,
    world VARCHAR,
    x INTEGER,
    y INTEGER,
    z INTEGER,
    player UUID,
    expires_at TIMESTAMP_MS NOT NULL,
    payload BLOB NOT NULL
);
```

- key は `namespace:value` 文字列のまま保存する。DuckDB は列ごとに辞書圧縮を行うため、種類の少ない文字列を整数 ID の辞書テーブルへ正規化しなくても保存効率は十分であり、プラグインが削除されても識別子は失われない（要件 §7.2）。
- 起動時に `CREATE TABLE IF NOT EXISTS` でテーブルを作る。スキーマを変更する必要が生じた時点で、バージョン管理と migration を導入する（要件 §15）。現時点のスキーマにはバージョン表がないため、「バージョン表がない DB = 初版スキーマ」として扱える。

## 保持期間

- 保持期間は `config.yml` の `retention` で event type ごとに設定する。一覧にない event type は `retention.default` を使う。
- submit 時に `expires_at = occurred_at + 保持期間` を計算して保存する。保持期間を変更しても既存イベントの `expires_at` は変わらない。
- `cleanup-interval` ごと（および起動直後）に `expires_at <= 現在時刻` の行を削除する。
- 設定の変更は再起動で反映する。

## 設定

初回起動時に `common/src/main/resources/config.yml` をデータディレクトリへコピーする。このファイルは組み込みイベントカタログの推奨保持期間を含む。

| キー | 意味 |
| --- | --- |
| `server-key` | Paper / Folia のサーバー key。空なら `kansokusha:<サーバーディレクトリ名>` |
| `queue-capacity` | 書き込み待ちイベント数の上限 |
| `batch-size` | 1 トランザクションで書き込む最大件数。この件数がキューにたまると間隔を待たずに書き込む |
| `flush-interval` | キューを DuckDB へ書き込む間隔 |
| `cleanup-interval` | 保持期限切れイベントを削除する間隔 |
| `retention.default` | 一覧にない event type の保持期間 |
| `retention.policies.<name>` | `duration` と、それを適用する `event-types` の一覧 |

## Paper 組み込みイベント

- 組み込みリスナーは `@EventHandler(priority = MONITOR, ignoreCancelled = true)` の 1 ハンドラで記録する。cancel されたイベントは記録しない。
- 変更前の状態は MONITOR 時点で読む。多くの Bukkit / Paper イベントは変更の適用前に発火するため、MONITOR でも変更前の状態を読める。
- chat とコマンドだけは、書き換えや cancel の前の入力を残すため LOWEST で記録する。
- リスナーはイベント間で共有する状態を持たず、各 platform event を独立に記録する。Folia の region thread から並行に呼ばれても同期は不要である。
- 1 つの platform event から複数件を記録する場合（複数ブロックの設置、爆発など）は、発生時刻を 1 回だけ取得して共有する。
- イベントの詳細は `docs/v1-built-in-event-catalog.md` に記載する。

## 意図的に持たない仕組み

要件上必須ではなく、実装・運用の複雑さに見合わないため、次の仕組みは持たない。必要になった時点で追加する。

| 仕組み | 理由 |
| --- | --- |
| 設定の実行時リロード | 呼び出す経路（コマンド等）が存在しない。再起動で反映する |
| 1 つの event type 内で保持期間を分ける qualifier | 用途がコールドロンの自然変化 1 件のみだった。必要なら event type を分ける |
| 書き込み失敗後の恒久停止 | 一時的な失敗でも記録が永久に止まる。失敗したバッチだけを破棄してログに出す |
| 1 回の削除件数の上限 | DuckDB の一括削除で十分に速い |
| 辞書テーブルと整数 ID | DuckDB の列圧縮で代替できる |
| migration 履歴とチェックサム検証 | スキーマ変更がまだ存在しない |
| 登録・送信結果の詳細な enum | 組み込みリスナーは結果を使っておらず、未登録・世代不一致はプログラムの誤りである |
| 無効化時のリスナー登録解除 | Bukkit / Velocity がプラグイン停止時に行う |
| 未リリースの Paper API へのリフレクションによる対応 | 対象バージョンを上げた時点で直接実装する |
| LOWEST でスナップショットを取り MONITOR で確定する 2 段階キャプチャ | 多くのイベントは変更の適用前に発火するため、MONITOR でも変更前の状態を読める。LOWEST〜MONITOR の間に他プラグインが状態を変えた場合への対応は要件外である。イベント単位の共有状態と同期も不要になる |
| イベント間の因果関係の追跡（TNT の着火と爆発・延焼、施肥と成長、取引成立の statistic による確認、火打石の着火と設置の突き合わせ） | 要件 §4 で非対象としている。各 platform event を独立に記録し、重複はイベントの種類による単純な除外で扱う（例: `tntExplodes` が有効な間の TNT は `tnt_prime` のみで記録する） |
| 次 tick まで記録を遅らせる仕組み | 上記の因果関係の追跡のためだけに存在した |
| 非推奨の `com.destroystokyo.paper.event.block.TNTPrimeEvent` の購読 | 削除予定の API であり、現行の `org.bukkit.event.block.TNTPrimeEvent` で着火を記録できる |
