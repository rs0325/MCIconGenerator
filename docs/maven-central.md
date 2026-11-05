# Maven Centralと同版workerの公開準備

Issue #92の先行実装は、core/workerのローカルMaven配布、薄いworker JAR、内部resolverを提供する。Centralへのupload・検証・公開は未実施。`1.1.0-probe` はローカル検証用の版であり、公開済みstable版ではない。root buildの既定版は変更しない。

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

`--managed-runtime <準備済みruntime root>` を指定すると、consumerがその正式runtime入力で `doctorManaged` の正常診断とfixture一件の `renderManaged` 成功を要求する。省略時は従来どおり未公開runtimeを `MANAGED_RUNTIME_INVALID` として確認し、reportの管理API欄を「未実施」とする。管理runtimeの実artifact・署名・固定信頼設定が未発行のため、この入力は別途用意されたruntime rootに限る。実描画の成否はホストOSのhelper、仮想化/コンテナ機能、対応条件にも依存する。描画入力は合成したMinecraft fixtureであり、公式ゲーム資産や別ホスト/OSの受入を検証しない。

`--candidate-bundle` を指定すると、Gradleが実際に解決した同版core/workerのbinary JARを候補ZIP内の同座標とSHA-256で照合する。module/POMの両経路で取得元と候補のhash・一致結果をreportへ残し、片方でも異なれば検証を失敗にする。未指定時はbytesの照合を行わない。

`--repository` を省略すると `mavenCentral()` だけを使う。実公開後は保全した公開前候補を指定し、次の形で再取得bytesを照合する。POM・sources・javadoc・署名は別途bundle監査と公開時検証を行う。ローカル候補の成功をCentral実取得成功として扱わない。runtime取得、51版・ホスト別受入は後続作業に残る。

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

## 最終coreからの署名前再生成（C1、2026-11-05）

旧1.1.0 bundleは公開対象外。origin/main a4756f38e0db0b9c31a984dbbcf90a37b8d16332 から、既存prepareCentralCandidateへ -PreleaseVersion=1.1.0 と -PmanagedRuntimeInputDir=H:/MCIconGenerator/worktrees/milestone-release-final-r2/MCIconGenerator/build/r2/final-input を渡し、JDK 21.0.12 / Gradle 9.8.1で再生成した。追加依存・公開API・データ形式の変更はない。

coreは427817 bytes、SHA-256 b3ea6fccb3a0571ee88cda3d94a2faac71dbe9dfd64e990eb7b0967838e81bca。R3確定JAR・両host payload内coreと全bytes一致し、450-byte pinsと公開trust-setも一致。workerは同版1.1.0で、内包agentもR3と一致した。

候補 build/c1/mcicon-central-1.1.0-candidate.zip は1457960 bytes、SHA-256 aab7a6a3dc60a52b425b50d8942e4237371dc7ab7c8c6f098f2a189ea230ff3e。core/workerのbinary・sources・javadoc・POM・module各5件と4種checksum、計50fileを含み、署名なし監査はfailures 0で成功。PGP署名対象は10件。

Mac転送用単一zipは build/c1/mcicon-central-1.1.0-signing-kit.zip（1470775 bytes、SHA-256 652dc82606c018c8417fc99bb95b35c448adb018f0c340c1838d69714b47f7bf）。ユーザー本人の署名手順と全file一覧は build/c1/SIGN-ON-MAC.md、測定記録は [QA JSON](../qa/central-bundle-candidate-2026-11-05.json) と [LF blob基準checksum](../qa/central-bundle-candidate-2026-11-05.json.sha256)。登録済みSSHへの転送は行っていない。

C2（2026-11-05）で、ユーザーがMacで署名・gpgv検証済みの返却ZIP（build/c1/returned/mcicon-central-1.1.0-signatures.zip、5737 bytes、SHA-256 2d48e13213c2b07167204832063e389d30de3a78f470824b50a186108b7d1e12）を照合した。entryは.asc 10件と公開鍵1件のみで、絶対path・..・重複はない。元50fileを置換せず配置し、WindowsのGnuPG 2.5.24 gpgvと返却公開鍵だけで署名10/10を再検証し、fingerprint B766BA473AF3178D42371A0F50562040DFAE0F27を確認した。Mac側の実行成功はユーザー報告、Windows側は本タスクの実測である。

署名済み最終ZIPは build/c1/mcicon-central-1.1.0.zip、1472738 bytes、SHA-256 dfa73ac3b26d12bd2432d7de919cfc0093aaace1e67b6f91b94a95aacfecaedc。元50fileに.asc 10件と署名checksum 40件を加えた100fileで、元50fileの全bytesおよび上記core SHA-256は不変。prepareCentralBundleの再publishは行っていない。scripts/audit-central-bundle.pyを --require-signatures 付きで実行し、failures 0・passed true（build/c1/audit-signed-final.json）。この監査の署名file構成検査と、別途成功したgpgvの暗号学的署名検証を区別して記録した。

PGP署名・gpgv検証・署名済み再結合・署名必須監査は成功。Central upload・Central検証・公開・公開後consumerは未実施であり、ローカル監査成功をCentral受理・公開成功とは扱わない。commit・push・PR作成・tag・releaseも未実施。
