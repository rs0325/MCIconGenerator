# 利用ガイド

## 準備

core/CLIにはJava 21以上が必要です。ゲーム資産は実行時に公式配信元から取得します。描画workerは専用作業ディレクトリを使い、利用者のワールドやLauncher設定を読みません。認証資格情報を要求・保存せず、バニラクライアントのdemo初期化経路を利用します。Dockerの描画プロセスはnetwork=noneです。

```sh
./gradlew test :cli:installDist
docker buildx build --platform linux/amd64 --load -t mcicon-render:dev -f docker/Dockerfile .
```

macOS Apple Siliconでは、今回の検証にColima・Virtualization.framework・Rosettaを使っています。Docker Desktopが既にある場合はそのcontextも指定できますが、まだ検証していません。

```sh
brew install colima docker docker-buildx
colima start --profile mcicon --vm-type vz --vz-rosetta --cpu 4 --memory 6 --disk 30
docker --context colima-mcicon buildx build --platform linux/amd64 --load \
  -t mcicon-render:dev -f docker/Dockerfile .
```

buildx pluginがCLIから見つからないHomebrew環境では `/opt/homebrew/lib/docker/cli-plugins/docker-buildx` を直接呼べます。その場合は `DOCKER_CONTEXT=colima-mcicon` を設定してください。imageはローカルに作成し、外部へpushしません。コンテナはXvfbとMesa llvmpipeを使います。仮想ディスプレイとOpenGLは必要です。

## CLI

以下では `cli/build/install/mcicon/bin/mcicon` を `mcicon` と表記します。全コマンドで `--help` を使えます。既定cacheは `.mcicon-cache` です。

```sh
mcicon versions --json
mcicon doctor --version 1.13.2 --json
mcicon list --version 1.21.4 --kind block --json
mcicon render --version 1.21.4 --kind block --id minecraft:stone \
  --size 128 --allow-experimental --out out-128
mcicon render --version 1.21.4 --id diamond --id chest \
  --allow-experimental --out out
mcicon render --version 1.21.4 --all --allow-experimental --out out
mcicon extract --version 1.21.4 --texture minecraft:item/diamond --out out
mcicon extract --version 1.21.4 --texture minecraft:entity/chest/normal --out out
mcicon extract --version 1.21.4 --all --out out
mcicon cache status --json
mcicon cache verify --json
```

`--id` と `--texture` は反復可能です。各々の `--all` と併用できません。namespace省略時はminecraft、`--kind` はall/item/block、`--size` は16/32/64/128/256/512です。`--json` の結果はstdout、進捗はstderrに出ます。

描画のオプションは `--worker-jar`、`--docker-context`（既定colima-mcicon）、`--docker-image`（既定mcicon-render:dev）、`--startup-timeout`（180秒）、`--item-timeout`（30秒）です。他のDocker contextでは明示してください。ゲームJVMはimage内の版別Javaを使います。hostの `--java-home` / `--minecraft-dir` による準備経路は未実装です。

初回はオンラインで完了させ、同じcacheに `--offline` を付けて再実行できます。offline時も公式SHA-1・sizeまたは記録済みSHA-256を照合します。cache verifyは記録済みSHA-256のある入力を検証する機能で、cache内のすべてのファイルが検証対象という意味ではありません。破損した入力はオンライン準備時に修復します。

同じ出力先はサイズ、元JAR、mappings、worker agent、描画環境等が一致するジョブで再利用してください。条件が変わったら別の `--out` または `--overwrite` を使います。overwriteは今回選択したファイルに対して働き、以前の別IDのファイルを一括削除しません。PNG・manifestはそれぞれ原子的に保存します。複数ファイル全体が一つのトランザクションではないので、途中終了後はmanifestのhash照合から再開してください。

## 取得対象

iconsの全件はair以外の登録アイテムを既定ItemStackで各1枚です。count=1、未損傷、追加component/NBTなし、countや耐久バー等のoverlayなし。オペレーター用アイテムも含めます。同じアイテムの全ポーション効果や全ブロックステート等の直積は含めません。

block-itemの関連・aliasを実レジストリから取得します。水等の独立アイテムがないブロックは `no_item_form` です。`--kind block --id water` は `NO_ITEM_FORM` を返し、別の画像を代用しません。

rawの全件はtextures/item/**とtextures/block/**のPNG・存在する付随mcmetaです。entity等は明示texture IDで抽出します。アイテムIDとtexture IDは別概念です。animated sheet・解像度・alpha・bytesを保持します。

現行描画はpreview-v1です。clock/compass、glint、animated texture、季節チェスト等の時刻・乱数・暦日依存は未固定です。別ジョブ間のdecoded RGBA一致、GPU間一致、missing modelの完全検出、sRGBの厳密な検証をまだ保証しません。manifestのcontext.deterministic=falseを確認してください。

## Java APIと配布物

```java
var cancel = new CancellationToken();
var worker = new WorkerOptions(Path.of("worker/worker-agent.jar"),
    "colima-mcicon", "mcicon-render:dev", Duration.ofSeconds(180), Duration.ofSeconds(30));
try (var generator = new MCIconGenerator(Path.of("cache"), false, ProgressListener.NONE, cancel)) {
    var request = new RenderRequest("1.21.4", List.of(ResourceId.parse("stone")),
        false, "block", 64, Path.of("out"), false, false, true);
    ExportResult result = generator.render(request, worker).join();
}
```

`listVersions`, `listEntries`, `render`, `extract`, `doctor`, `cache` はCompletableFutureを返します。一つのgeneratorではジョブを順番に処理します。別generatorは別cache/outputの利用を推奨します。CancellationToken.cancel()でキャンセル、close()でexecutorとworkerの終了を要求します。キャンセルしたgeneratorは新ジョブに再利用せず、新しいinstance/tokenを作ってください。

```sh
./gradlew :cli:distZip :core:publishCorePublicationToLocalBuildRepository
```

CLI zipはcli/build/distributions、ライブラリのローカルMaven repositoryはcore/build/mavenに生成します。予定座標は `io.github.rs0325.mcicon:mcicon-core:0.1.0-SNAPSHOT`、公開Maven repository・正式releaseはまだありません。1.0以前のAPIは変更される可能性があります。Minecraftクラスは公開APIに露出しません。配布物のworker JARは本プロジェクトのagentとASM/Gsonだけを含みます。
