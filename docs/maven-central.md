# Maven Centralと同版workerの公開準備

Issue #92の先行実装は、core/workerのローカルMaven配布、薄いworker JAR、内部resolverを提供する。Centralへのupload・検証・公開と、#93以降の管理API接続・実描画は未実施。`1.1.0-probe` はローカル検証用の版であり、公開済みstable版ではない。root buildの既定版は変更しない。

## 利用側の固定依存

Centralで1.1.0が公開された後は、Java 21以上の利用側で次を使う。現時点で取得できることを示す例ではない。

```groovy
repositories { mavenCentral() }
dependencies { implementation 'io.github.rs0325.mcicon:mcicon-core:1.1.0' }
```

coreは同じ版の `io.github.rs0325.mcicon:mcicon-worker` をruntime推移依存にする。Gradle module metadataとMaven POMのどちらでも版を固定し、利用側のGradle指定版を実行時に更新しない。rawの `extract` はworkerを展開せず、VM/containerを起動しない。

workerのbinaryは薄いwrapper JARで、`META-INF/mcicon/worker/agent.jar` と `version.properties` を持つ。外側へagent classやASM/Gsonを重ねず、ASM/Gsonは内側agentに含める。coreにはagent bytesを複製せず、同じbuildで生成した期待SHA-256だけを固定する。

内部の `WorkerArtifact.resolve(cacheRoot, token)` は、定義元ClassLoaderで各resourceが一意であること、metadataがUTF-8/LFの厳密な2行であること、tool版・agent SHA-256・Premain-Classを検証する。`cacheRoot/workers/<agent-sha256>/agent.jar` へlock下で原子的に保存し、既存cacheもhash検証する。戻したpathをジョブごとのsnapshotへ渡す接続は#93の範囲。既存 `WorkerOptions` の明示agentパス経路は継続する。

元worker JARのfile pathやCodeSourceには依存しない。resourceを保持した依存統合JARも検証できる。resourceの欠落は `WORKER_NOT_FOUND`、重複・版/hash不一致・cache破損は `WORKER_INVALID`。統合ツールがresourceを捨てる形式は利用できない。既存cache破損時は、稼働jobがないことを確認して該当hashの専用directoryを削除し、同版resourceから再生成する。

## ローカル候補

指定版に対応するcoreとworkerを1つのrepositoryへ生成する。以下は秘密鍵・tokenを使わず、network上へ公開しない。

```sh
./gradlew test prepareRelease prepareCentralCandidate -PreleaseVersion=1.1.0-probe
python3 scripts/audit-distribution.py build/release --version 1.1.0-probe
python3 scripts/audit-central-bundle.py \
  build/central/mcicon-central-1.1.0-probe-candidate.zip --version 1.1.0-probe
python3 scripts/verify-maven-consumer.py --version 1.1.0-probe \
  --repository build/maven/1.1.0-probe \
  --candidate-bundle build/central/mcicon-central-1.1.0-probe-candidate.zip \
  --output build/consumer-validation
```

ローカルMaven repositoryは `build/maven/<版>`、Central候補は `build/central/`、従来の配布候補は `build/release/` に生成する。core/workerのbinary・sources・javadoc・POM・Gradle metadataとchecksumを含める。POMにはMIT license、SCM、developer情報を持つ。publish系taskは同じ版のローカル出力を消してから生成するため、同じworktreeで別のpublish実行を並行しない。

consumer検証は独立projectと空のGradle cacheを各metadata経路ごとに作り、coreの固定依存だけを宣言する。ローカル候補を指定した場合は `exclusiveContent` で本プロジェクトのgroupをそのrepositoryに限定し、候補のcore/workerが不足していてもCentralの同版へ切り替えない。ローカル候補のcore/workerとCentralの外部依存を解決し、合成したoffline入力によるrawのbytes保持、workerの推移依存とhash、旧5/6/8引数Optionsと既存メソッド署名を確認する。合成入力はMinecraft資産の再配布ではない。`--gradle <インストール済みGradleの絶対path>` を指定すれば、空cacheへのGradle本体の再downloadを避けられる。

`--candidate-bundle` を指定すると、Gradleが実際に解決した同版core/workerのbinary JARを候補ZIP内の同座標とSHA-256で照合する。module/POMの両経路で取得元と候補のhash・一致結果をreportへ残し、片方でも異なれば検証を失敗にする。未指定時はbytesの照合を行わない。

`--repository` を省略すると `mavenCentral()` だけを使う。実公開後は保全した公開前候補を指定し、次の形で再取得bytesを照合する。POM・sources・javadoc・署名は別途bundle監査と公開時検証を行う。ローカル候補の成功をCentral実取得成功として扱わない。管理APIの実描画、runtime取得、51版・ホスト別受入は後続作業に残る。

```sh
python3 scripts/verify-maven-consumer.py --version 1.1.0 \
  --candidate-bundle build/central/mcicon-central-1.1.0-candidate.zip \
  --output build/central-consumer-validation
```

## 署名と実公開の境界

[Centralの要件](https://central.sonatype.org/publish/requirements/)に従い、全公開artifactをPGP署名し、checksumと共に[Maven layoutのbundle](https://central.sonatype.org/publish/publish-portal-upload/)へまとめる。`prepareCentralCandidate` は未署名を許す検査用候補で、`prepareCentralBundle` はSNAPSHOTや署名file不足を拒否する。どちらもuploadを実行しない。ローカル監査はPOM・checksum・署名file構成を検査するが、署名の暗号学的検証とCentral側の検証を代替しない。

Central Portalのnamespace `io.github.rs0325` はVerified、本人が作成した公開PGP fingerprintは `B766BA473AF3178D42371A0F50562040DFAE0F27`。この確認から秘密鍵使用・token読取・公開の許可を推定しない。更新descriptorのEd25519信頼鍵とは別の鍵である。

秘密鍵使用と対象版の署名が許可された後に限り、本人のGnuPG agentと公開fingerprintを使う。passphraseをコマンド引数、repository、ログへ置かない。実行時は署名対象版を再確認する。

```sh
./gradlew prepareCentralBundle -PreleaseVersion=1.1.0 \
  -PsignPublications=true \
  -Psigning.gnupg.keyName=B766BA473AF3178D42371A0F50562040DFAE0F27
python3 scripts/audit-central-bundle.py \
  build/central/mcicon-central-1.1.0.zip --version 1.1.0 --require-signatures
```

本人が管理する公開鍵だけを指定した `gpgv` で全署名を検証し、fingerprintを確認する。秘密鍵やtokenはbuild出力・consumer fixtureにも入れない。実際の署名、Central Portalへのupload・検証・公開はそれぞれ許可範囲を確認する。

Portalでは[USER_MANAGEDの検証](https://central.sonatype.org/publish/publish-portal-api/)と公開を分ける。`VALIDATED` は未公開であり、公開操作を実行したとは報告しない。公開後はCentralのみの空cache consumer、再取得bytes照合、管理API結合受入まで確認して#92の完成を判定する。GitHub Actions、release/tag作成、OS署名はこの準備に含めない。
