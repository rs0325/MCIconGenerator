# CLI・Javaライブラリの配布

公開された版と添付ファイルは[GitHub Releases](https://github.com/rs0325/MCIconGenerator/releases)で確認してください。正式版`1.0.0`と旧`0.1.0-preview.1`を公開済みです。以下は、起動停止対策を含む修正版`1.0.1`の手順です。各版の配布物・tag・releaseの公開状況はGitHub Releasesを参照してください。1.0.0の配布物へ修正が遡って適用されるものではありません。固定51版のcanonical-v1/verifiedを対象とする受入条件は[検証記録](validation.md)を参照してください。

## 配布物と必要環境

| ファイル | 内容 |
| --- | --- |
| `mcicon-<version>.zip` | CLI、coreと依存JAR、worker agent、Dockerビルドファイル、利用文書、schema、検証記録 |
| `mcicon-library-<version>.zip` | Maven形式のcore binary・sources・javadoc・POM、利用文書、schema、Dockerビルドファイル、Java利用例 |
| `worker-<version>-agent.jar` | Java API利用時に明示指定する描画agent。CLI内のworkerと同じbytes |
| `SHA256SUMS` | 上記3ファイルのSHA-256 |

core/CLIのホストにはJava 21以上、描画にはLinux amd64のDocker環境とSSE4.1を扱えるCPUまたはエミュレーションが必要です。macOS Apple SiliconではColima/Rosetta、WindowsではDocker DesktopのLinux backendを使います。Linuxの確認範囲はVM内のamd64 containerです。任意のGPU/native/ARM64描画の保証はありません。[対応表](compatibility.md)に実測環境と版別結果を記載しています。

ゲーム版に応じたJava 8/17/21/25はローカルDocker image内に用意します。rawだけの取得にはDockerは不要です。公式入力を初回に取得するネットワーク接続とcache容量が必要です。ゲームJAR・元アセット・生成PNG・認証情報・QA用agentは配布物に含めません。Docker imageも外部へ配布せず、利用者の環境でビルドします。

## CLIを展開して使う

同じ版の4ファイルを取得し、展開前にchecksumを確認します。以下は修正版`1.0.1`の手順です。GitHub Release/tagの公開前は候補の検証用に使い、公開済みpreviewを使う場合はファイル名・versionを`0.1.0-preview.1`に読み替え、描画に`--allow-experimental`を付けてください。

macOSでは`shasum -a 256 -c SHA256SUMS`、Linuxでは`sha256sum -c SHA256SUMS`を実行します。Windows PowerShellでは次のように照合できます。

```powershell
Get-Content SHA256SUMS | ForEach-Object {
    $parts = $_ -split '  ', 2
    $actual = (Get-FileHash $parts[1] -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $parts[0]) { throw "SHA-256 mismatch: $($parts[1])" }
}
Expand-Archive mcicon-1.0.1.zip -DestinationPath .
```

macOS/Linuxでは次のように展開し、JavaがPATHから使えることを確認します。CLIは実行したディレクトリに既定cacheを作るため、管理する作業フォルダで実行してください。

```sh
unzip mcicon-1.0.1.zip
cd mcicon-1.0.1
java -version
bin/mcicon --version
bin/mcicon --help
```

Windowsでは同じ配布物の`bin\mcicon.bat`を使用します。ソースのGradle buildはインストールには不要です。

配布物のルートには`docker/Dockerfile`と`docker/entrypoint.sh`があります。利用するDocker contextを`docker context ls`で確認し、同じcontextでbuildとCLIを実行します。macOSのColima/Rosetta準備は[利用ガイド](usage.md)を参照してください。以下はColimaの例です。

```sh
docker --context colima-mcicon buildx build --platform linux/amd64 --load \
  -t mcicon-render:1.0.1 -f docker/Dockerfile .
bin/mcicon doctor --version 1.20.6 --docker-context colima-mcicon \
  --docker-image mcicon-render:1.0.1
bin/mcicon render --version 1.20.6 --id stone --id player_head \
  --docker-context colima-mcicon \
  --docker-image mcicon-render:1.0.1 --cache cache --out icons
bin/mcicon extract --version 1.20.6 --texture minecraft:item/diamond --cache cache --out raw
```

WindowsではCLIパスを`bin\mcicon.bat`へ変え、Docker contextを利用環境の名前へ明示します。PowerShellで改行する場合はバッククォートを使うか、一行にまとめてください。

初回成功後は同じcacheへ`--offline`を付けられます。全件はID指定の代わりに`--all`を使用します。[利用ガイド](usage.md)に単品・全件・raw・exit code・途中取消・再開、[出力契約](contracts.md)にmanifestを説明しています。Docker imageやworker hashが変わった場合は新規出力先で再生成してください。previewなど旧描画条件のicons manifestは、正式版候補の`canonical-v1`とsettingsDigestが一致しないため、同じ出力先への再開はできません。新規出力先、または明示した`--overwrite`で再生成してください。元テクスチャのbytesは変わりません。

## Javaライブラリを使う

Maven形式ZIPを展開すると`mcicon-library-<version>/maven`がローカルrepositoryになります。Maven Centralへの公開は行っていません。coreの依存JacksonはMaven Centralから通常どおり解決するため、利用者の最初のbuildではネットワーク接続が必要です。CLIを展開した`lib`には実行用依存JARも含まれます。

別のGradleプロジェクトに、次のrepositoryと依存を設定します。`url`は展開先の絶対パス、versionは取得した版へ合わせてください。ビルド用GradleとJava 21は利用者のプロジェクトで用意します。

```groovy
plugins { id 'application' }
repositories {
    maven { url = uri('/absolute/path/mcicon-library-1.0.1/maven') }
    mavenCentral()
}
dependencies { implementation 'io.github.rs0325.mcicon:mcicon-core:1.0.1' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = 'ExportExample' }
```

同梱の`examples/ExportExample.java`を`src/main/java/`へコピーしてください。公開APIのimportsを含む実行可能な例です。事前にCLIで1.20.6の描画とraw取得を完了してcacheを用意し、次の順に5引数を渡します。

1. 準備済みcacheの絶対パス
2. 新しい出力先の絶対パス
3. 単独worker JAR、またはCLI内の`worker/worker-agent.jar`の絶対パス
4. Docker context
5. Docker image tag

```sh
gradle run --args='/absolute/cache /absolute/api-output /absolute/worker-1.0.1-agent.jar colima-mcicon mcicon-render:1.0.1'
```

この例はofflineでdiamondの元PNGとstone/player_headの64pxアイコンを取得します。パスに空白がある場合は`--args`内でも各パスを二重引用符で囲んでください。worker JARはcore依存から自動取得されないため明示指定します。APIのジョブ・取消・closeの契約は[利用ガイド](usage.md)とcoreのjavadocを参照してください。

## ソースから配布候補を生成する

リポジトリのソースで次を実行します。Gradle wrapperは9.8.1、ビルドにはJava 21が必要です。`releaseVersion`は生成物に記録するバージョンの指定で、tag作成や外部公開は行いません。

```sh
./gradlew -PreleaseVersion=1.0.1 test prepareRelease
python3 scripts/audit-distribution.py build/release --version 1.0.1
python3 scripts/test-distribution-audit.py build/release --version 1.0.1
```

`build/release/`に4ファイルが生成されます。`releaseVersion`未指定の通常buildは`0.1.0-SNAPSHOT`です。CLI表示とicons/raw manifestのtoolVersionは同じbuild versionを使用します。未リリースの形式に移行処理は追加していません。配布候補の生成、PRのmerge、GitHub Release公開は別操作です。

コードはMIT License、依存は同梱のTHIRD_PARTY_NOTICESとlicensesを参照してください。Minecraft資産の権利は各権利者のもので、この非公式ツールの公開はその再配布権を与えません。[Minecraft EULA](https://www.minecraft.net/en-us/eula)を参照してください。

## 正式版候補の受入と既知の制約

`1.0.0`の描画受入は固定51版と検証済みLinux amd64・Mesa Docker imageを対象にします。Dockerfileの再buildは、固定済みimage IDと同じbytes・画素を保証しません。ホスト別の実測範囲と配布候補の受入状況は[検証記録](validation.md)を参照してください。

[Issue #78](https://github.com/rs0325/MCIconGenerator/issues/78)は、固定Mac/Rosetta環境の外部process生成とthread終了の競合を実証し、workerから不要な外部診断を除く対策を確認して完了しました。Rosetta自体の修正ではなく、過去すべての失敗の厳密な同一性は未証明として保持します。1.0.1はこの対策を含む構成ですが、公開済み1.0.0は含みません。worker hashが変わるため既存iconsの再開には新規出力先または明示`--overwrite`が必要です。rawの元bytes・保存形式は変更しません。失敗時はexit code・worker logと出力manifestを確認し、自動的な再試行は行いません。公開状況と添付ファイルはGitHub Releasesで確認してください。

1.1.0向けの同版worker推移依存とローカルCentral候補生成は[Centralガイド](maven-central.md)を参照してください。実公開前の候補とCentralで取得可能な版を区別します。
