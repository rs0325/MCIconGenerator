# 利用ガイド

## 準備

core/CLIにはJava 21以上が必要です。ゲーム資産は実行時に公式配信元から取得します。描画workerは専用作業ディレクトリを使い、利用者のワールドやLauncher設定を読みません。認証資格情報を要求・保存せず、バニラクライアントのdemo初期化経路を利用します。Dockerの描画プロセスはnetwork=noneです。

```sh
./gradlew test :cli:installDist
docker buildx build --platform linux/amd64 --load -t mcicon-render:dev -f docker/Dockerfile .
```

macOS Apple Siliconでは、今回の検証にColima・Virtualization.framework・Rosettaを使っています。Windows 11のDocker Desktop Linux backendも確認しています。ホスト別の実測範囲は[対応表](compatibility.md)を参照してください。

```sh
brew install colima docker docker-buildx
colima start --profile mcicon --vm-type vz --vz-rosetta --cpu 4 --memory 6 --disk 30
docker --context colima-mcicon buildx build --platform linux/amd64 --load \
  -t mcicon-render:dev -f docker/Dockerfile .
```

buildx pluginがCLIから見つからないHomebrew環境では `/opt/homebrew/lib/docker/cli-plugins/docker-buildx` を直接呼べます。その場合は `DOCKER_CONTEXT=colima-mcicon` を設定してください。imageはローカルに作成し、外部へpushしません。コンテナはXvfbとMesa llvmpipeを使います。仮想ディスプレイとOpenGLは必要です。

## CLI

配布ZIPのインストール・Maven形式ライブラリの使い方は[配布ガイド](distribution.md)を参照してください。以下では配布物の `bin/mcicon`（Windowsでは `bin/mcicon.bat`）、またはソースbuildの `cli/build/install/mcicon/bin/mcicon` を `mcicon` と表記します。全コマンドで `--help` を使えます。既定cacheはCWD相対の `.mcicon-cache` で、Windowsでは下記の環境変数で変更できます。

```sh
mcicon versions --json
mcicon doctor --version 1.13.2 --json
mcicon list --version 1.21.4 --kind block --json
mcicon render --version 1.21.4 --kind block --id minecraft:stone \
  --size 128 --out out-128
mcicon render --version 1.21.4 --id diamond --id chest \
  --out out
mcicon render --version 1.21.4 --all --out out
mcicon extract --version 1.21.4 --texture minecraft:item/diamond --out out
mcicon extract --version 1.21.4 --texture minecraft:entity/chest/normal --out out
mcicon extract --version 1.21.4 --all --out out
mcicon cache status --json
mcicon cache verify --json
```

`--id` と `--texture` は反復可能です。各々の `--all` と併用できません。namespace省略時はminecraft、`--kind` はall/item/block、`--size` は16/32/64/128/256/512です。`--json` の結果はstdout、進捗はstderrに出ます。

準備やI/Oの失敗を調べる場合は `mcicon --debug ...` または各サブコマンドの `--debug` を指定できます。通常のエラーコード・終了コードを保持し、原因例外とstack traceをstderrへ追加します。項目単位の描画失敗は結果のcode・messageを変えず、`--debug`時だけ元の例外（cause・suppressed含む）を項目ID付きでstderrへ出します。stdoutのJSONには診断を混ぜません。traceにはローカルのパスや例外メッセージが含まれます。

描画のオプションは `--worker-jar`、`--docker-context`（既定colima-mcicon）、`--docker-image`（既定mcicon-render:dev）、`--startup-timeout`（180秒）、`--item-timeout`（30秒）です。他のDocker contextでは明示してください。ゲームJVMはimage内の版別Javaを使います。`--java-home /opt/java/java21` のようにimage内のLinux絶対ディレクトリを指定すると、そのJavaを起動前に検査し、必要major未満なら失敗します。ホストのJavaはマウントしません。1.17系は既存runtime契約に合わせてJava17 LTSを下限にします。明示した新しいmajorでの描画互換性は保証せず、失敗時に別Javaへ切り替えません。

`--memory-mib`（既定3072）と `--heap-mib`（既定2048）はゲームworkerのcontainerとJava heapの上限をMiB整数で指定します。両方を正の値にし、heapをcontainer未満にしてください。例えば `--memory-mib 4096 --heap-mib 2560` です。ホストJVM・Docker VMのRAMは変更しません。指定値を自動補正せず、低予算で起動に失敗しても予算を増やして再試行しません。描画設定のdigestには含めないため、予算だけを変更した出力は同じ条件で再開できます。

`--minecraft-dir` は既存インストールの公式入力をSHA-1・sizeで検証し、専用cacheへコピーします。`versions/<版>/<版>.jar`・同名JSON、libraries、assets/indexes・objectsの必要なファイルだけを読み、元のファイルは変更せず、Launcher設定・資格情報・ワールドは読み取りません。symlinkを含む入力パスは拒否します。元インストールを変更しないため、cacheとインストールが同一・入れ子になる配置も開始前に拒否します。破損・不足はオンラインでは公式配信から取得し、offlineでは `OFFLINE_CACHE_MISS` になります。公式全版manifestと旧版mappingsは既存インストールから取り込まないため、offlineで使うには先に専用cacheへ準備してください。

HTTPS取得は1試行あたりredirect・本文を含めて2分、最大3試行です。取消しは応答header・本文の待機中も接続を閉じます。asset並列取得の1件が失敗した場合は残りを取消します。native JARは1entry 64MiB、1JAR合計256MiB、4,096 entries以内で展開し、パス逸脱・symlinkを拒否します。

初回はオンラインで完了させ、同じcacheに `--offline` を付けて再実行できます。offline時も公式SHA-1・sizeまたは記録済みSHA-256を照合します。cache verifyは記録済みSHA-256のある入力を検証する機能で、cache内のすべてのファイルが検証対象という意味ではありません。破損した入力はオンライン準備時に修復します。

同じ出力先はサイズ、元JAR、mappings、worker agent、描画環境等が一致するジョブで再利用してください。条件が変わったら別の `--out` または `--overwrite` を使います。overwriteは今回選択したファイルに対して働き、以前の別IDのファイルを一括削除しません。PNG・manifestはそれぞれ原子的に保存します。複数ファイル全体が一つのトランザクションではないので、途中終了後はmanifestのhash照合から再開してください。icons/rawとも書き込み前に選択IDと必要な付随mcmetaを予約し、16件ごと・失敗時・終了時にmanifestを更新します。クラッシュ後の未確定ファイルは再生成します。OUTPUT_EXISTSとされた所有権不明のファイルは、再開しても上書きしません。明示overwriteの受理後は新たな予約として再開できます。ツール専用の `.mcicon-<数字>.part` が残った場合は、次のエクスポートがロック取得後に回収します。

## Windowsのデータ保存先

任意のプロセス環境変数`MCICON_DATA_HOME`で、Windowsの保存先をまとめて指定できます。通常のdrive付き絶対pathを指定してください。空白を含むpathも使えますが、値自体に引用符を含めません。

```powershell
$env:MCICON_DATA_HOME = 'H:\MCIconData'
mcicon cache status --offline --json
mcicon render --version 1.21.4 --id diamond --out 'H:\MCIconOutput'
```

| 対象 | 指定時の既定先 | 未指定時 |
| --- | --- | --- |
| bootstrap state・署名slot・更新記録 | `<MCICON_DATA_HOME>/bootstrap` | `%LOCALAPPDATA%/MCIconBootstrap`（未設定はhome配下AppData/Local） |
| 管理runtime | `<MCICON_DATA_HOME>/runtime` | `%LOCALAPPDATA%/MCIconGenerator`（未設定はhome配下AppData/Local） |
| CLIの入力cache | `<MCICON_DATA_HOME>/cache` | CWD相対の`.mcicon-cache` |
| bootstrapが起動するCLI/trial・更新準備子のtemp | `<MCICON_DATA_HOME>/bootstrap/tmp` | JVM/プロセスの既定 |
| export出力 | 必須の`--out` | 同左 |

明示`--cache`、Java APIの明示`ManagedOptions.runtimeRoot`を優先し、`--out`は変更しません。相対`--cache`と`--out`はCWD基準です。Hへ揃える受入では明示cache/outもHの絶対pathを指定します。全利用者へのH強制や同volume強制は行いません。直接Java APIへ渡す入力cacheも従来どおり明示引数です。

相対path、drive相対（`H:relative`）、root相対、UNC、device/拡張prefix、空・不正な値は入力エラーです。値のtrim・環境変数展開や旧既定先へのfallbackは行いません。bootstrapは`BOOTSTRAP_INVALID`/終了2、CLIは`INVALID_REQUEST`/終了2、`ManagedOptions.defaults()`は`IllegalArgumentException`になります。不正値はCLIの明示`--cache`でも救済しません。明示`ManagedOptions` constructorは環境変数を参照しません。macOS/Linuxは変数を参照せず、既定先を維持します。

指定先は独立した保存領域となり、旧C等のstate/cache/slotを探索・採用・コピー・削除・移行しません。新規準備・再取得が必要です。bootstrapのroot・親・既tmpのowner/ACL/reparse/未知状態に対する既存の拒否条件を維持し、不適格なHrootを自動修復しません。program prefixとdata rootは別で、通常uninstallはdataを保持する責務境界です。uninstallerの実装はこの指定機能に含みません。

H配置の保証は本機能対応済みと確認した固定bootstrap・seed・core/CLI・選択slot・trial/更新候補の組合せが対象です。署名とbootstrap protocol 2だけでは未対応の旧版も選択できるため、旧版へ変数を渡すだけではHを保証しません。受入では各配布物の版・識別子・ハッシュと通常/pin/更新の選択経路を固定して利用先を確認します。旧Cのtree/hash不変は無変更の証拠であり、未読取・未採用は解決した各rootとconsumerの実利用先/入出力先で別途確認します。OS全体のread監査を保証する機能ではありません。

bootstrapは上記2種類の子だけに正規化した変数、`-Djava.io.tmpdir`、`TMP`/`TEMP`を渡します。最初のbootstrap JVM自身、bootstrapを経由しない直接CLI/core API、build時ManagedPackageVerifier、OS/JVM・外部runtimeが別途選ぶtempのH配置は保証対象外です。正式H専用entry/installerの値供給、winget受入、Hrootの資格修復は別作業です。Windows実機でのH配置受入は未実施です。

## 取得対象

iconsの全件はair以外の登録アイテムを既定ItemStackで各1枚です。count=1、未損傷、追加component/NBTなし、countや耐久バー等のoverlayなし。オペレーター用アイテムも含めます。同じアイテムの全ポーション効果や全ブロックステート等の直積は含めません。

block-itemの関連・aliasを実レジストリから取得します。水等の独立アイテムがないブロックは `no_item_form` です。`--kind block --id water` は `NO_ITEM_FORM` を返し、別の画像を代用しません。

描画passで追跡したモデル・quadにバニラのfallbackが含まれた場合は `MISSING_MODEL` / `MISSING_TEXTURE` として失敗をmanifestへ記録します。特殊rendererの直接テクスチャ取得等も含む、欠損リソースの完全検出はまだ保証しません。

rawの全件はtextures/item/**とtextures/block/**のPNG・存在する付随mcmetaです。entity等は明示texture IDで抽出します。アイテムIDとtexture IDは別概念です。animated sheet・解像度・alpha・bytesを保持します。

公開済み1.0.0と修正版1.0.1の描画はcanonical-v1です。固定51版のverified版はopt-in不要です。各版の配布状況は配布ガイドとGitHub Releasesを参照してください。暦日を2000-01-01 UTC、描画中の経過時刻を1000ms、partialTickを0、atlas animationを初期フレームに固定します。時計・コンパス等の動的propertyの乱数はseed=0に戻します。モデルのquad選択など、バニラ自身がseedを指定する乱数は保持します。黒・白背景の2回のネイティブ描画からstraight alphaを復元します。manifestのcontext.deterministic=trueは、同じ入力・adapter・settings・記録環境で固定状態を再現する契約です。受入対象は[契約](contracts.md#canonical-v1の受入)に記録したimage・Java・rendererと確認済みホスト条件に限ります。任意のGPU・独自image・別JDKへのRGBA一致や、missing modelの完全検出へ保証を広げません。同じDockerfileから再buildした別imageも同一受入とは扱いません。旧fixed-v1や別agentのiconsはsettingsDigestが変わるため再開を拒否し、新しい出力先または明示的な--overwriteを使います。rawの元bytesは維持します。

## Java APIと配布物

```java
var cancel = new CancellationToken();
var worker = new WorkerOptions(Path.of("worker/worker-agent.jar"),
    "colima-mcicon", "mcicon-render:dev", Duration.ofSeconds(180), Duration.ofSeconds(30));
try (var generator = new MCIconGenerator(Path.of("cache"), false, ProgressListener.NONE, cancel)) {
    var request = new RenderRequest("1.21.4", List.of(ResourceId.parse("stone")),
        false, "block", 64, Path.of("out"), false, false, false);
    ExportResult result = generator.render(request, worker).join();
}
```

既存インストールを利用するAPIは `new MCIconGenerator(cache, offline, progress, cancel, minecraftDirectory)`、image内Javaを選ぶAPIは `new WorkerOptions(agent, context, image, startupTimeout, itemTimeout, "/opt/java/java21")` です。従来の5/6引数constructorは3072/2048 MiBで引き続き利用できます。明示予算は `new WorkerOptions(agent, context, image, startupTimeout, itemTimeout, javaHome, 4096, 2560)` で指定します。`javaHome=null` は従来どおり版に適した同梱Javaを選びます。

`listVersions`, `listEntries`, `render`, `extract`, `doctor`, `cache` はCompletableFutureを返します。一つのgeneratorではジョブを順番に処理します。進捗callbackはジョブのスレッドで実行するため、同じgeneratorの別ジョブの完了をcallback内で待たないでください。別generatorは別cache/outputの利用を推奨します。CancellationToken.cancel()は途中のエクスポートを中断し、未完了のExportResult/manifestを返します。close()は実行中・待機中のFutureをCancellationExceptionで終了させ、executorとworkerの終了を要求します。完了済みの結果は保持します。close後の新規呼び出しはGENERATOR_CLOSEDのfailed Futureを返します。キャンセルしたgeneratorは新ジョブに再利用せず、新しいinstance/tokenを作ってください。

```sh
./gradlew :cli:distZip :core:publishCorePublicationToLocalBuildRepository :worker:publishWorkerPublicationToLocalBuildRepository
```

CLI zipはcli/build/distributions、ライブラリのローカルMaven repositoryはbuild/maven/<version>に生成します。開発時の座標は `io.github.rs0325.mcicon:mcicon-core:0.1.0-SNAPSHOT` です。配布候補の形式と公開状況は[配布ガイド](distribution.md)で説明します。1.0以前のAPIは変更される可能性があります。Minecraftクラスは公開APIに露出しません。配布物のworker JARは本プロジェクトのagentとASM/Gsonだけを含みます。

1.1.0向けのworker推移依存・Central公開準備・独立consumer検証は[Centralガイド](maven-central.md)を参照してください。管理APIの接続と実描画は後続の結合受入対象です。
