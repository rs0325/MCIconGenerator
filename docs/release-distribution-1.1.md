# 1.1.0 配布方式と署名手順

## 配布経路

| 内容 | 配布先 | 利用方法 |
| --- | --- | --- |
| `io.github.rs0325.mcicon:mcicon-core` と同版 `mcicon-worker` | Maven Central | Gradleで `mavenCentral()` とcore依存だけを指定する。workerは推移依存で解決する。 |
| managed runtime artifact、host descriptorと署名、inventory、stable manifestと署名、tool payload | GitHub Release assets | descriptor内artifact URLはHTTPSの `https://github.com/rs0325/MCIconGenerator/releases/download/<tag>/<asset>` 形式とする。 |
| CLI/toolのstable自動更新 | GitHub Release上の `stable.json` と `stable.json.sig` | packageに明示設定されたHTTPS manifest URLをbootstrapが参照する。未設定時の既定製品URLはない。 |

Central公開はcore JARへpins・descriptor・trust-setが正しい最終bytesで収録されてから行う。現行1.1.0 bundleは公開しない。Centralのartifact署名はMaven公開用PGPであり、managed資産のEd25519署名とは別である。OS署名・notarizationは行わない。installer、Homebrew、wingetは後続Issue #97。

## Release assetの名前と構成

次は既存契約で名前が確認できるもの。Release内の配置先はasset直下を想定する。

- host別descriptor: `descriptors/<os>-<arch>.json`。署名は隣接する同名`.sig`（例: `descriptors/macos-aarch64.json.sig`）。
- stable更新manifest: `stable.json` と `stable.json.sig`。
- tool payload: `tool.zip`。stable manifestから参照する。
- artifact bytes: descriptorが参照するURLとinventoryのpathに従う。artifactの共通命名規則は未確定。
- inventory: descriptorが参照するファイル名・配置は入力ごとに既存参照を維持する。Release assetとしての共通名は未確定。
- descriptorが参照する各署名、trust-set配布位置、stable manifest以外の更新index名・形式・署名関係は未確定。

host別descriptorはhostごとに公開するが、全hostで同一の96-byte `trust-set.bin` を使う。署名は64-byte raw Ed25519。確定済みの`.sig`等の名前を変えず、未確定部分の命名をこの文書で新設しない。

## Ed25519署名

release作業時にユーザーがMacの運用鍵で署名する。運用鍵が使えない場合はWindowsの回復鍵を使用する。CIへ秘密鍵を置かない。署名対象は整形・改行変換を追加せず、確定したUTF-8 JSON全体のbytes。署名出力は64-byte rawである。例では秘密鍵のパスを環境変数で渡すが、鍵内容・パスフレーズを引数、ログ、repositoryへ置かない。パスフレーズ付き鍵はOpenSSLの対話promptで入力する。

```sh
# descriptorを確定した元bytesのまま署名
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" \
  -in descriptors/macos-aarch64.json \
  -out descriptors/macos-aarch64.json.sig

# stable manifestも同じ方法
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" \
  -in stable.json -out stable.json.sig

# 公開鍵DERで検証（公開鍵はSubjectPublicKeyInfo DER）
openssl pkeyutl -verify -rawin -pubin -keyform DER \
  -inkey trust-public.der -in descriptors/macos-aarch64.json \
  -sigfile descriptors/macos-aarch64.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER \
  -inkey trust-public.der -in stable.json -sigfile stable.json.sig
wc -c descriptors/macos-aarch64.json.sig stable.json.sig

# 更新indexのfilename/schema確定後に同様に署名・検証する
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" \
  -in "$UPDATE_INDEX_FILE" -out "$UPDATE_INDEX_SIG_FILE"
openssl pkeyutl -verify -rawin -pubin -keyform DER \
  -inkey trust-public.der -in "$UPDATE_INDEX_FILE" \
  -sigfile "$UPDATE_INDEX_SIG_FILE"
wc -c "$UPDATE_INDEX_SIG_FILE"
```

検証は公開trust-set内の各公開鍵で行い、受理鍵のいずれか一つで成功すること、署名が各64 bytesであることを確認する。秘密鍵ファイルのパスをコマンドで指定する必要はあるが、鍵の内容やパスフレーズを引数に書かない。OpenSSLの対話promptで入力し、詳細ログを有効にせず、コマンド記録にも秘密を出さない。上記は鍵選択・trust-set生成・JSON生成の手順を定めない。stable manifest以外に「更新index」がある場合、そのファイル名・schema・署名対象の契約は未確定であり、placeholderを実名へ置換してから署名する。

## 公開順序と確認点

1. 公開対象runtime artifactの最終bytesを確定する。
2. artifactのsize/SHAを含むinventoryを生成・確定する。
3. inventoryを参照するhost descriptorを確定する。
4. descriptorとstable manifestをユーザーが署名し、公開DERで検証する。更新indexを別途設ける場合は、その契約確定後に署名する。
5. 全host共通のtrust-setを確定し、runtime入力とbootstrap seed/packageの信頼集合が一致することを確認する。
6. pins/resourcesを組み立て、core JARを生成する。descriptor元bytes、署名、trust、pinsがJAR内で一致することを確認する。
7. Central bundleを作成し、PGP署名・checksum・bundleを監査する。**Centralへの公開はユーザー確認後**に行う。現1.1.0 bundleは公開対象にしない。
8. 対応するmanaged artifact、descriptor、signature、inventory、stable manifest/signature、tool payloadをGitHub Release用にまとめる。**tag作成とGitHub Release公開はそれぞれユーザー確認後**に行う。
9. 公開後、空のGradle cacheによるCentral consumer検証と、空のruntime/bootstrap cacheによるGitHub Release取得・署名検証・managed consumer検証を行う。再取得bytesを公開前候補と照合する。

順序の根拠となる既存生成契約は[managed runtime resources](managed-runtime-resources-1.1.md)、[trust-set](../bootstrap/docs/trust-set.md)、[Maven Central](maven-central.md)、[自動更新](../bootstrap/docs/auto-update.md)。ここに記したユーザー確認は公開操作の実行承認であり、事前準備・候補生成・ローカル監査を止めるものではない。

## 未確定事項

- 最終runtime artifact bytes、各hostの公開可否と受入、descriptorの本番値。
- artifactとinventoryの共通asset名・配置、trust-setをReleaseから配布する方法。
- stable manifestの本番URL、sequenceの初期値・更新責任者。
- stable manifest以外の更新indexが必要か、そのfilename/schema/signature契約。
- GitHub Releaseのtag/version対応、Release assetの最終一覧と公開手順。
- Maven Central bundleの最終版、公開時期。#97のinstaller経路は別途決める。
- 公開後に実施する対象hostごとの空cache consumer受入条件。
