# テストガイドライン

このドキュメントは、okocraft の Java / Paper / Velocity プロジェクトにおけるテスト基盤とテスト方針を定めるものです。

現時点の基準は、以下のリポジトリに存在するテストです。

- [okocraft/Yaminabe](https://github.com/okocraft/Yaminabe) — `d04ab36141322cd4f1ab2c3d8ef8673b94977da3` 時点
- [okocraft/ArmorStandEditor](https://github.com/okocraft/ArmorStandEditor) — `c6224e8988cb9c94cec31c43e258926c6b63792e` 時点

本文では、要件の強さを次の語で表します。

- **MUST**: 原則として必須です。
- **SHOULD**: 特別な理由がなければ従います。
- **MAY**: 必要に応じて採用します。

## 1. 目的

テストの目的は、実装詳細を固定することではなく、変更によって壊れ得る振る舞いを、再現性のある方法で検証することです。

テストは次を満たすことを目標とします。

1. ローカルと CI で同じ結果になること。
2. Minecraft サーバー全体を起動せず、必要な範囲だけを実行すること。
3. 外部 API との境界と、プロジェクト自身のロジックを区別して検証すること。
4. 正常系だけでなく、権限不足・不正入力・境界値・キャンセル・失敗時の副作用を検証すること。
5. テストを読むことで、その機能が保証している振る舞いを把握できること。

カバレッジ率そのものは目的にしません。行カバレッジを増やすためだけのテストは追加しません。

## 2. 基本方針

### 2.1 最も軽い実行環境を選ぶ

テスト対象を検証できる範囲で、最も軽い環境を使います。

| 種別 | 主な対象 | 使用するもの |
| --- | --- | --- |
| 純粋な単体テスト | 計算、パース、値変換、状態遷移 | 実オブジェクト + JUnit |
| 境界の単体テスト | Bukkit / Paper / Velocity API、scheduler、event、sender | Mockito |
| 軽量 Paper テスト | `ItemStack`、data component、registry、Paper の argument type など | `TestServer` + 実オブジェクト |
| 複数コンポーネントの協調テスト | command tree、menu transaction、editor の一連の処理 | 実装クラス + 必要最小限の mock |
| フルサーバーテスト | world/tick/network/plugin lifecycle そのものが必要な機能 | 標準 `test` とは分離して導入する |

**MUST:** フルサーバーを起動しなくても検証できる処理を、フルサーバーテストにしません。

**MUST:** Paper API の型だからという理由だけで mock にしません。実 `ItemStack` のように軽量環境で安全に使える値オブジェクトは、実物を優先します。

**MUST:** 実サーバーの tick、world、network、plugin lifecycle が存在する前提の振る舞いを、軽量 `TestServer` で保証したことにはしません。

### 2.2 観測可能な結果を検証する

テストは、利用側から意味のある結果を優先して検証します。

例:

- 戻り値
- 状態の変更
- player / sender に送られるメッセージ
- event の cancel 状態
- inventory / equipment の変更
- scheduler に登録された処理と cancel の伝播
- config ファイルの生成・保持・読み戻し
- command の可用性、実行結果、suggestion

private method の呼び出し回数など、外部から意味を持たない実装詳細は原則として検証しません。

## 3. 標準テスト基盤

### 3.1 JUnit と Mockito

テストランナーは JUnit Jupiter、mock ライブラリは Mockito を標準とします。

Gradle では、各プロジェクトが直接バージョンを散在させず、version catalog と共通 Gradle plugin 経由で設定します。

現在の 2 リポジトリはいずれも次の形を採用しています。

```kotlin
jcommon {
    setupJUnit(libs.junit.bom)
    setupMockito(libs.mockito)
}
```

依存関係は概ね次の責務で分けます。

```kotlin
testImplementation(libs.junit.jupiter)
testImplementation(libs.platform.paper) // Paper のテストで必要な場合
testImplementation(libs.slf4j.api)      // TestServer の bootstrap で必要な場合
testRuntimeOnly(libs.slf4j.simple)
```

**MUST:** JUnit / Mockito のバージョンは version catalog で管理します。

**SHOULD:** 新しいテストライブラリは、JUnit / Mockito / 標準ライブラリでは表現しにくい問題が継続的に存在する場合だけ追加します。

### 3.2 軽量 `TestServer`

Paper の一部 API は、Minecraft registry が初期化されていない JVM 上では正しく利用できません。`ItemStack`、data component、persistent data、command argument type などを実物で検証するため、テスト専用の `TestServer` を使用します。

`TestServer` の責務は次に限定します。

- Minecraft / Paper registry の bootstrap
- data-driven registry と tag の読み込み
- item data component の初期化
- `CraftRegistry` への registry 設定
- Paper command argument type が必要とする dispatcher/context の初期化

`TestServer` は次を起動しません。

- Bukkit `Server`
- world
- network
- tick loop
- plugin lifecycle

したがって、これはフルサーバーテストではありません。

#### 自動初期化

Paper 系のテストでは、各テストが個別に `TestServer.setUp()` を呼ばない構成を標準とします。

JUnit extension を `META-INF/services/org.junit.jupiter.api.extension.Extension` に登録し、test task で auto-detection を有効にします。

```kotlin
tasks.test {
    systemProperty("org.slf4j.simpleLogger.cacheOutputStream", "true")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
```

**MUST:** bootstrap は JVM 内で一度だけ実行できる実装にします。

**MUST:** bootstrap に必要な Paper / Minecraft 内部 API は `testsupport` 内に閉じ込め、通常のテストから直接参照しません。

**SHOULD:** Paper 更新時に基盤の破損箇所を特定しやすくするため、`TestServer` 自体の smoke test を置きます。最低限、実 `ItemStack` と registry 依存機能の一つが動作することを確認します。

#### リポジトリ間の共有

Yaminabe と ArmorStandEditor の `TestServer` は同じ設計ですが、Paper / CraftBukkit / NMS のバージョンに強く結合します。

**SHOULD:** Paper のバージョン更新をリポジトリごとに独立して行う間は、`TestServer` のバイナリ共通ライブラリ化を避けます。共通化する場合は、対象リポジトリの Paper バージョンを同時に揃えられることを前提とします。

**MAY:** 3 つ以上のリポジトリで同じ bootstrap を維持する状況になった場合、source template、Gradle convention、または version-aligned な test fixture module への抽出を検討します。

### 3.3 `testsupport` の責務

複数テストから繰り返し使われ、かつ意味のあるテスト概念だけを `testsupport` に置きます。

現在の良い例は次です。

- `CommandTester`: Brigadier dispatcher を通して command tree 自体を実行する
- `TestSources`: sender / executor / permission 状態を一貫して作る
- `TestIds`: 固定 UUID を共有する
- `TestServer`: registry bootstrap を隠蔽する

**MUST:** `testsupport` はテストを読みやすくするために使い、production API の設計不足を隠すために巨大化させません。

**SHOULD:** 同じ mock setup が 3 箇所程度以上で現れ、かつ同じ意味を持つ場合に helper 化を検討します。

**SHOULD:** helper の名前は、Mockito の操作ではなくドメイン上の意味を表します。例: `grant(...)`, `deny(...)`, `authorizedViewer(...)`。

## 4. テストの配置と命名

テストは production code と同じ package 構造に置きます。

基本の class 名は `<対象クラス名>Test` とします。関心を分ける必要がある場合は、既存テストと同様に責務を suffix で明示します。

例:

- `PTimeCommandTest`
- `PTimeCommandPermissionTest`
- `EquipmentMenuAccessTest`
- `EquipmentMenuTransactionTest`
- `ArmorStandMenuLifecycleTest`
- `EditModeIntegrationTest`

**SHOULD:** 1 class が大きくなった場合、単純に行数で分割するのではなく、permission、transaction、lifecycle など振る舞いの境界で分けます。

テストメソッド名は、既存コードとの一貫性を優先し、`test` + 期待する振る舞いを基本とします。

```java
@Test
void testCommandIsHiddenWithDeniedPermission() {
}

@Test
void testLoadKeepsExistingFileUntouched() {
}
```

**MUST:** `test1`, `works`, `success` のように、失敗時に何が壊れたか分からない名前を使いません。

**SHOULD:** 条件が重要な場合は名前に含めます。`When...`, `With...`, `Without...`, `On...` などは使用して構いません。

## 5. テストケースの作り方

### 5.1 1 テスト 1 振る舞い

1 テストでは、一つのシナリオとその結果を検証します。一つのシナリオに複数の関連 assertion が必要なことは問題ありません。

例として、command 実行で次を同時に確認するのは同じ振る舞いです。

- command result が `1`
- editor の状態が変わる
- player に成功メッセージが送られる

一方、正常系と権限不足を同じテストにまとめません。

### 5.2 Arrange / Act / Assert を見える形にする

コメントで区切る必要はありませんが、setup、実行、検証がコード上で判別できるようにします。

```java
Player player = playerWithPermission(Permissions.COMMAND_AXIS);
CommandSourceStack source = source(player);

execute(ArmorStandEditorCommand.axis(), source, "axis y");

Assertions.assertEquals(Axis.Y, PlayerEditorProvider.getEditor(player).getAxis());
Mockito.verify(player).sendMessage(Messages.COMMAND_AXIS_CHANGE.apply(Axis.Y));
```

**SHOULD:** テスト対象の実行前に assertion を挟みすぎないようにします。ただし fixture 自体の前提を確認する必要がある場合は例外です。

### 5.3 実値を優先する

pure Java の値や、軽量 `TestServer` で扱える Paper の値は実物を優先します。

良い対象:

- `Instant`, `LocalTime`, `ZoneId`, `Duration`
- `EulerAngle`, `Location` のような単純値
- `ItemStack` と item data
- config の実ファイル
- Brigadier `CommandDispatcher`

mock が有効なのは、主に次の境界です。

- `Player`, `CommandSender`, `Server`
- Bukkit/Paper/Velocity scheduler
- event
- plugin
- world/entity lifecycle に結び付いた API

**MUST:** 「依存型だから」という理由だけで mock にしません。値そのものの意味を検証する場合は実値を使います。

### 5.4 Mockito は境界の観測に使う

Mockito の `verify` は、外部 API への副作用が仕様である場合に使います。

例:

- message が送られた
- event が cancel された
- equipment が変更された
- scheduled task の `cancel()` が呼ばれた

**SHOULD:** production class 内部の呼び出し順を細かく固定しません。

**MAY:** 通知順のように順序自体が仕様の場合、`InOrder` を使います。

**MAY:** 渡された値が重要で、直接取り出せない場合は `ArgumentCaptor` または `argThat` を使います。

### 5.5 static mock は限定する

ArmorStandEditor では `Bukkit` や static provider との境界を `Mockito.mockStatic` で隔離しています。これは既存設計をテストする手段として許容しますが、新規コードの第一選択にはしません。

**SHOULD:** 新規コードでは、差し替え可能な小さい collaborator や platform abstraction を注入できるなら、static mock よりそちらを優先します。

**MUST:** static mock は `try-with-resources` でスコープを閉じます。

```java
try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
    // stub and execute
}
```

### 5.6 時刻・非同期処理は決定的にする

**MUST:** テストで `Thread.sleep` を使いません。

**MUST:** 現在時刻に依存するロジックは、`Clock`、`Instant`、`Duration` などを注入・指定して検証します。

Yaminabe の restart 系テストのように `Clock.fixed(...)` や固定 `Instant` を使い、DST gap / overlap のような境界条件も実データで検証します。

scheduler abstraction のテストでは、実時間を待つのではなく次のいずれかを使います。

- 即時実行する test scheduler
- scheduler API の mock
- task object への cancel 伝播の verify

### 5.7 ファイル I/O は `@TempDir` を使う

config や生成ファイルを扱うテストは JUnit の `@TempDir` を使います。

**MUST:** repository 内の実ファイルやユーザー環境の一時ディレクトリに依存しません。

**SHOULD:** 読み込みだけでなく、次のようなファイルのライフサイクルを必要に応じて検証します。

- 存在するファイルを保持する
- 未存在なら初期値を書き出す
- parent directory を作る
- 空ファイル・空 map の扱い
- 不正形式で例外になる
- 書き出した内容を再度読み込める

### 5.8 共有状態は必ず戻す

static cache、provider、registry 以外の共有状態をテスト間に持ち越しません。

**MUST:** テストが変更した production の static state は `@AfterEach` 等で戻します。

例:

```java
@AfterEach
void tearDown() {
    PlayerEditorProvider.unloadAll();
}
```

registry bootstrap のように JVM 単位で一度だけ初期化するものは例外ですが、個々のテストがその状態を書き換えない設計にします。

## 6. 対象別のテスト方針

### 6.1 純粋ロジック

計算、変換、parser、schedule、state machine は mock なしを第一選択とします。

**MUST:** 代表的な正常値だけでなく、境界値を含めます。

例:

- 正方向 / 逆方向
- 先頭 / 末尾 / wrap-around
- empty
- invalid input
- DST gap / overlap
- 元オブジェクトを変更しないこと

同じ入力集合に対して同じ性質を繰り返す場合は `@ParameterizedTest` を使用して構いません。Yaminabe の text 系テストのように、データ違いだけのケースをコピーし続けるより parameterized test を優先します。

### 6.2 command

command のテストは handler method を直接呼ぶのではなく、可能な限り実際の Brigadier command tree を通します。

これにより次も同時に検証できます。

- node の permission
- sender / executor 条件
- argument parsing
- command result
- suggestion

Paper / Velocity ごとに `CommandTester` の薄い adapter を持つ方式を推奨します。

command については、該当するものを次の観点で検証します。

1. 正常実行
2. 権限が未設定
3. 権限が明示的に deny
4. 類似した別 permission のみ保持
5. player 限定 command を console が実行
6. 自分自身への操作と他 player への操作
7. 不正 argument
8. suggestion の公開範囲
9. 成功・失敗メッセージ
10. 状態変更または外部 API への副作用

**SHOULD:** permission に階層や `others` の概念がある場合、permission ごとの独立性を専用テストで確認します。

### 6.3 event listener

listener は「何もしない条件」が重要です。

正常系に加えて、早期 return の条件を明示的に検証します。

例:

- off hand なので無視する
- 対象 action ではないので無視する
- edit tool ではないので無視する
- permission がないので無視する
- 対象 entity ではないので無視する

**SHOULD:** 無視するケースでは、状態が変わらないことと、重要な副作用が起きていないことを確認します。

例:

```java
Mockito.verify(event, Mockito.never()).setCancelled(true);
Assertions.assertEquals(Axis.X, editor.getAxis());
```

### 6.4 menu / inventory transaction

inventory 操作は条件分岐が多いため、「入力操作 → 最終的な inventory/equipment 状態」を中心に検証します。

必要に応じて次を分離します。

- access: 誰が操作できるか
- transaction: アイテムがどう移動するか
- lifecycle: open / close / invalidation
- provider: 同一対象に対する menu の共有や再利用

**MUST:** survival / creative / spectator のように仕様が変わる条件を混同しません。

**SHOULD:** cursor、hotbar、offhand、clone など Minecraft 固有の transaction は、それぞれ別シナリオとして検証します。

### 6.5 platform adapter / scheduler

Paper / Velocity / Folia の違いを吸収する adapter は、adapter が正しい API を選択し、戻された task の lifecycle を伝播することを検証します。

例:

- delay が 0 の場合に `runNow` を使う
- delay がある場合に `runDelayed` を使う
- repeating task に正しい interval を渡す
- wrapper の `cancel()` が platform task の `cancel()` に伝わる
- Folia と非 Folia で teleport 経路を切り替える

**MUST:** scheduler テストで実時間を待ちません。

### 6.6 config / resource

config は、Java object の field 値だけでなく、ファイルとして維持すべき契約も検証します。

language resource は、少なくとも次を検証します。

- bundled locale 間で key 集合が一致する
- production code が生成する translation key が全 locale に存在する

resource の内容全文を snapshot として固定するのは、フォーマット自体が仕様である場合だけにします。

### 6.7 state / copy / data

editor state や armor stand data のような状態オブジェクトでは、次を重視します。

- 初期状態
- 変更後の状態
- copy が必要な値を全て引き継ぐこと
- clone が必要な mutable value を共有しないこと
- reset / unload 後に状態が残らないこと

## 7. 失敗系と境界条件

新しい振る舞いを追加した場合、正常系だけで完了とはしません。

変更内容に該当するものから、少なくとも一つ以上の失敗・境界条件を追加します。

- null / empty / missing
- invalid format
- permission denied / unset
- 対象 entity が存在しない
- inventory に空きがない
- operation が拒否される game mode
- scheduler が処理を受理しない
- reload / file read が失敗する
- 既存状態を上書きしてはいけないケース
- 値の wrap-around
- 時刻の timezone / DST 境界

例外を期待する場合は、例外 class だけでなく、例外発生前に不正な副作用が起きていないことも必要に応じて確認します。

## 8. mock の粒度

mock は必要な interaction だけを stub します。

**MUST:** テスト対象が読まない値まで「本物らしくする」ための大量 stub を作りません。

**SHOULD:** 一つの mock が多くの unrelated method を stub し始めた場合、helper 化より先に、production code の責務分割が必要か検討します。

`verifyNoMoreInteractions()` は、余分な interaction が仕様違反になる場合だけ使います。通知順や「この通知以外を送ってはいけない」場合には有効ですが、一般的な unit test に一律適用するとリファクタリング耐性を下げます。

## 9. テストデータ

テストデータは再現可能で、意味が読み取れるものにします。

**MUST:** ランダム UUID、現在時刻、環境依存 locale などを無条件に使いません。

**SHOULD:** UUID の identity 自体が重要でない場合、`TestIds` のような固定値を使います。

**SHOULD:** assertion の理解に必要な値はテスト内に直接見えるようにします。共通化しすぎて、テストを読むために多数の helper を辿る状態は避けます。

## 10. カバレッジ方針

数値の coverage target は設定しません。

現在の両リポジトリには JaCoCo による gate はなく、既存テストも主に振る舞い単位で構成されています。この方針を維持します。

PR では、変更された振る舞いについて次を基準にします。

- 新しい分岐には、その分岐を意味のある形で通るテストがあるか
- bug fix には、修正前に失敗する regression test があるか
- permission / failure path が追加された場合、その path のテストがあるか
- platform-specific 分岐が追加された場合、各経路が検証されているか
- public behavior が変わる場合、既存テストの期待値変更だけでなく、その変更理由がテスト名から分かるか

coverage tool を導入する場合も、最初は未検証領域を探す補助指標として使い、プロジェクト横断の一律 percentage gate にはしません。

## 11. CI とローカル実行

標準の検証コマンドは Gradle wrapper を使います。

```bash
./gradlew test
```

PR の CI では、test 以外の検証も含めるため、可能であれば次を authoritative な command とします。

```bash
./gradlew check
```

**MUST:** system Gradle ではなく repository の Gradle wrapper を使います。

**MUST:** CI とローカルで同じ test task を実行できる状態を維持します。

**MUST:** CI でのみ必要な sleep、retry、順序依存によって flaky test を隠しません。

同じ JVM 内でしか成立しない registry bootstrap があるため、test parallelization を有効化する場合は `TestServer` と Paper internals の thread safety を確認してから行います。

## 12. フルサーバーテストを追加する基準

現状の標準 suite は、unit / lightweight integration を中心とします。

次のような機能を自動検証する必要が出た場合のみ、別 task のフルサーバーテストを導入します。

- plugin enable / disable lifecycle
- 実 world 上の entity lifecycle
- server tick を跨ぐ動作
- network / connected player を必要とする動作
- Paper/Folia の実 scheduler thread ownership
- 他 plugin との実統合

導入する場合は通常の `test` と分離し、例として `integrationTest` のような task にします。通常の unit suite を遅くしたり、不安定にしたりしないことを優先します。

## 13. 新規テスト追加時のチェックリスト

PR を出す前に、追加・変更したテストについて次を確認します。

- [ ] テスト名から、壊れた振る舞いを判断できますか。
- [ ] 検証に必要な最も軽い実行環境を使っていますか。
- [ ] 実値で表現できるものまで mock にしていませんか。
- [ ] mock は platform / I/O / lifecycle などの境界に寄せられていますか。
- [ ] 正常系に加えて、変更に対応する失敗系または境界条件がありますか。
- [ ] permission が関係する場合、unset / denied / allowed の違いを考慮しましたか。
- [ ] 時刻や非同期処理で `Thread.sleep` や wall clock に依存していませんか。
- [ ] static state を変更した場合、テスト後に戻していますか。
- [ ] static mock は `try-with-resources` で閉じていますか。
- [ ] Paper registry が必要なだけなのに、フルサーバーを要求していませんか。
- [ ] bug fix の場合、修正前に失敗する regression test になっていますか。

## 14. 現在のテスト群から採用する設計判断

Yaminabe と ArmorStandEditor の既存テストから、今後も維持する判断をまとめます。

1. **JUnit Jupiter + Mockito を標準にする。** 両リポジトリですでに同一構成です。
2. **Paper の registry だけを起動する軽量 `TestServer` を標準化する。** 実 `ItemStack` などを使いつつ、world/server/network は起動しません。
3. **command は Brigadier tree を通してテストする。** handler の直接呼び出しだけで済ませません。
4. **permission は独立した仕様としてテストする。** 特に unset と explicit deny、self と others を区別します。
5. **platform API は mock、プロジェクト自身の計算・状態は実物を優先する。**
6. **時間は固定する。sleep しない。** scheduler は abstraction または mock で検証します。
7. **config は `@TempDir` 上で実 I/O を行う。** parse だけでなく生成・保持・再読込も検証します。
8. **listener / menu は「何もしない条件」を正常系と同じ重さで扱う。**
9. **shared static state は cleanup する。**
10. **カバレッジ率ではなく、変更された振る舞いと regression の有無でテスト不足を判断する。**

この 10 点を、okocraft プロジェクトのテスト設計のデフォルトとします。
