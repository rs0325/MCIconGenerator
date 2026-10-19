# 管理runtimeのresource生成とpackage結合

`managedRuntimeInputDir` は、発行入力からcoreの固定runtime resourcesを生成するbuild専用の明示指定である。生成器はネットワークへ接続せず、入力内のhelper・Java・daemonを実行しない。ローカルの合成入力による検証と、正式runtimeの発行・実OS受入は分ける。

## 入力と生成順序

入力directoryに `input.json` を置く。fieldは `schemaVersion: 1`、boolean `finalBytesConfirmed`、`runtimes` の3つだけ。`runtimes` の各entryは次の既pins形式を使う。

```json
{
  "host": {"os": "macos", "arch": "aarch64"},
  "descriptorPath": "descriptors/macos-aarch64.json",
  "descriptorSha256": "検証対象descriptor元bytesのSHA-256",
  "trustKeyPath": "trust.der"
}
```

SHA欄の説明文字列は入力値ではない。descriptorと64byteの隣接 `.sig`、X509 Ed25519公開鍵、descriptorが参照する全inventoryを同directory内の相対pathへ置く。各artifactの実archiveは `artifacts/<id>.<format>` に置く。同じ入力集合で異なるbytesのartifactには異なるIDを使う。link、配置衝突、host重複を認めない。

`finalBytesConfirmed` がfalse、署名・tool版・agent・adapter集合・host・Java major集合が不一致、inventory hashが不正、artifactの実size/SHAが不一致の場合は生成を拒否する。host helperは既 `RuntimeArchive` で展開を検証し、entryの存在・実行属性を確かめる。guest/renderer archiveは元bytesの検証だけを行い、hostへ展開しない。未発行URLや未採用candidateを通すためにflagを書き換えない。URLは既descriptorのHTTPS規則に従い、URLが到達可能であることまではローカル生成で証明しない。

Gradleは `compileJava → generateManagedRuntimeResources → processResources → JAR` の順に実行する。生成JVMのclasspathはcompiled classesとcompile依存だけで、core JARや未生成resourcesへ依存しない。tool版、実worker agent JAR、repositoryのadapter元directoryを明示引数で渡す。既adapter digestは元の2resourceだけを読むparent無しの標準URLClassLoaderで計算する。

artifact元bytes → inventory → descriptor/署名 → pins/resources → core JAR → tool payload ZIP → 更新manifestの順で固定する。agent SHAとadapter集合digestはcore JAR hashではない。runtime descriptorへpayload自身のhashを入れないため、循環依存はない。

## 通常buildと正式package入口

通常buildでpropertyを省略した場合、runtime resourceを同梱せずbuildは成功可能である。managed起動は現在どおり明確な不足診断になり、Dockerへ暗黙fallbackしない。propertyを明示した場合、不完全・不正な入力はbuild失敗となる。

```sh
./gradlew --offline -PreleaseVersion=1.1.0 \
  -PmanagedRuntimeInputDir=/absolute/path/to/verified-inputs :core:jar
```

生成専用directoryと `processResources` のruntime prefixは毎回の入力状態へ合わせて置換する。A→B→無指定へ切り替えても、旧QA resourceを生成先・processed resources・JARへ残さない。手書きの `core/src/main/resources/META-INF/mcicon/runtime` を併用しない。

正式Mac app-image用の検査付き入口は次のtaskとする。

```sh
./gradlew --offline -PreleaseVersion=1.1.0 \
  -PmanagedRuntimeInputDir=/absolute/path/to/verified-inputs \
  -PbootstrapSeedDir=/absolute/path/to/seed \
  -PbootstrapTrustKey=/absolute/path/to/public-trust.der \
  :bootstrap:managedAppImage
```

3propertyのいずれかが未指定でも失敗する。`managedRuntimeInputDir` の相対指定はrepository起点、既 `bootstrapSeedDir` / `bootstrapTrustKey` の相対指定はbootstrap project起点を維持する。混同を避けるには絶対pathを使う。`verifyManagedRuntimePackage` は現 `:core:jar` 完成後に動き、署名seedを既 `Seed` で検証・有限展開し、同hostの公開trust DER、descriptorと署名の元bytes、payload内coreの全runtime resource、実agent hashを比較する。さらにpayload内core JARの全size/SHAを当該buildのcore JARへ一致させ、旧coreへ新resourceだけを載せた組合せも拒否する。他のpayload JARがruntime resourceを重複提供する場合も拒否する。検査後のapp生成は既packagerを再利用する。

同一descriptor/.sigをseedにも同梱するため、runtime公開trustとpackage/seed公開trustのDERは一致を必須とする。別署名・dual-signature・新しい鍵回転の仕組みは追加しない。公開鍵だけを入力し、生成器は秘密鍵を受け取らない。PGPのMaven署名やAppleのコード署名とは責務・鍵が異なる。

既 `appImage` は合成QAにも使う低位入口として残す。その成功を正式runtimeの受入とは表示しない。`managedAppImage` の検査成功も、公開URLの可用性、本番trust採用、installer、Developer ID/notarization、実managed描画を保証しない。

## 検証範囲と未発行入力

直接fixtureはJVM内で一時Ed25519 KeyPairを生成し、秘密のencoded取得・保存・表示・送信を行わない。公開鍵と署名済合成入力だけをQAに使う。これを本番trustへ採用しない。

この成果はRefs #93、関連 #94/#97の生成接続部分である。Mac一式のQAだけで3OSのMaven core正式対応とは呼ばない。発行対象host全体の入力は、それぞれの確定artifact・署名・source/noticeから集約する。未発行hostは既契約どおりsetup-requiredとし、偽のentryを作らない。

Mac helper196候補（196はfile数）はfinal採用と3asset URLが未確定で、renderer source URL、本番Ed25519 trust、Apple配布方式、最終candidateのVM受入も残る。正式inputの完成前にproduction resourcesを仮値で発行しない。resource追加はcore JARとpayload/seedのhashを変えるため、採用後のpackage・managed受入はfresh世代で実施する。未release 1.1開発rootの黙った移行は追加しない。worker/helper protocol1、bootstrap protocol2、公開APIとruntime保存schemaは変更しない。
