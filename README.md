# MCIconGenerator

Minecraft Java Editionの元テクスチャと、インベントリ表示の透過PNGを取得する開発者向けCLI・Javaライブラリです。対象は1.13以降の正式版です。

開発中です。元PNG抽出、CLI・Java API、ローカルDockerでのバニラGUI描画を実装しています。固定集合の全51版に描画adapterを実装しています。全件生成とPNG監査の結果は版ごとに記録し、未完了の検査を公開しています。描画contextはfixed-v1ですが、画質と再現性の受入検証が残るため、すべてexperimentalです。設計・進捗は[Issue #1](https://github.com/rs0325/MCIconGenerator/issues/1)、実測結果は[検証記録](docs/validation.md)で管理しています。

インストールとJavaライブラリの利用は[配布ガイド](docs/distribution.md)、操作例は[利用ガイド](docs/usage.md)を参照してください。公開された版は[GitHub Releases](https://github.com/rs0325/MCIconGenerator/releases)で確認できます。

ソースからはJava 21以上でビルドします。描画にはDockerのLinux amd64コンテナを使い、版に応じてJava 8/17/21/25を別プロセスで起動します。GitHub Actionsは使用しません。

```sh
./gradlew test :cli:installDist
docker buildx build --platform linux/amd64 --load -t mcicon-render:dev -f docker/Dockerfile .
```

Apple SiliconのColima設定、実行例、offline手順は[利用ガイド](docs/usage.md)をご覧ください。CLIは `cli/build/install/mcicon/bin/mcicon` に生成されます。

```sh
mcicon versions --json
mcicon doctor --version 1.21.4
mcicon render --version 1.21.4 --id stone --id diamond --allow-experimental --out out
mcicon render --version 1.21.4 --all --allow-experimental --out out
mcicon extract --version 1.21.4 --texture minecraft:item/diamond --out out
mcicon extract --version 1.21.4 --all --out out
```

生成アイコンは `out/java/<version>/icons/assets/<namespace>/textures/{block,item}/<item-id>.png`、元テクスチャは `raw/` 以下に元のパス・bytesで保存します。描画画像の背景は透過です。rawでは元画像のalphaを保持し、不透明な元画像を改変しません。生成アイコンのディレクトリ構成はバニラを参考にした命名規約で、そのまま使うリソースパックではありません。

出力はmanifest・SHA-256で管理します。同じ入力・設定の成功ファイルを再利用し、別設定の混在は拒否します。`--overwrite` は今回選択した出力ファイルを置換できます。全件成功はexit 0、起動失敗は1、引数・ID・版の不備は2、部分失敗は3です。

描画passで追跡したfallbackモデルとmissing spriteは `MISSING_MODEL` / `MISSING_TEXTURE` として失敗を記録します。特殊rendererの全リソースの監査は継続中です。準備・I/Oの例外原因は任意の `--debug` でstderrへ表示できます。

公開API・worker・manifestの契約は[contracts.md](docs/contracts.md)、対応版の固定集合は[releases.json](adapters/releases.json)です。未実装adapterの版を指定すると `ADAPTER_UNAVAILABLE`、experimental描画へのopt-inがない場合は `UNVERIFIED_VERSION` を返します。

MinecraftのゲームJAR・アセット・生成画像はこのリポジトリに含めません。本プロジェクトは非公式で、Mojang/Microsoftとは関係ありません。プロジェクトコードはMIT License、Minecraft資産は各権利者のものです。利用時は[Minecraft EULA](https://www.minecraft.net/en-us/eula)をご確認ください。
