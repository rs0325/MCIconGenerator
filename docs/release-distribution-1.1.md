# 1.1.0 配布方式と署名手順

## 配布経路

| 内容 | 配布先 | 利用方法 |
| --- | --- | --- |
| `io.github.rs0325.mcicon:mcicon-core` と同版 `mcicon-worker` | Maven Central | Gradleで `mavenCentral()` とcore依存だけを指定する。workerは推移依存で解決する。 |
| managed runtime artifact、host descriptorと署名、inventory、stable manifestと署名、tool payload | GitHub Release assets | descriptor内artifact URLはHTTPSの `https://github.com/rs0325/MCIconGenerator/releases/download/<tag>/<asset>` 形式とする。 |
| CLI/toolのstable自動更新 | GitHub Release上の `stable.json` と `stable.json.sig` | packageに明示設定されたHTTPS manifest URLをbootstrapが参照する。未設定時の既定製品URLはない。 |

Central公開はcore JARへpins・descriptor・trust-setが正しい最終bytesで収録されてから行う。現行1.1.0 bundleは公開しない。Centralのartifact署名はMaven公開用PGPであり、managed資産のEd25519署名とは別である。OS署名・notarizationは行わない。installer、Homebrew、wingetは後続Issue #97。

## Release assetの名前と構成

Release assetは直下へ平坦に配置する。descriptor内部のresource path `descriptors/<os>-<arch>.json` は維持し、Release上のbasenameを `windows-x86_64.json` / `macos-aarch64.json` とする。

- helper: `mcicon-helper-1.1.0-<os>-<arch>.zip`
- guest: `mcicon-guest-1.1.0-windows-x86_64.tar.xz`、`mcicon-guest-1.1.0-macos-aarch64.qcow2`
- 共通renderer: `mcicon-renderer-1.1.0-linux-amd64.tar.gz`（両hostで同一bytesを参照）
- descriptorと署名: `windows-x86_64.json` / `.json.sig`、`macos-aarch64.json` / `.json.sig`
- tool payload: `mcicon-tool-1.1.0-<os>-<arch>.zip`
- stable更新manifest: `stable.json` / `stable.json.sig`
- component別source/notice bundle: 配布componentごとに分けてReleaseへ含める。

inventoryはdescriptor内の既存参照pathを維持し、Release asset名は入力bundle確定時にそのpathと照合する。別の更新indexは1.1.0へ追加しない。version別資材はimmutableとし、同じversionの異なるhashへの差替えを禁止する。修正は次versionで発行する。

全host共通の96-byte `trust-set.bin` を使う。署名は64-byte raw Ed25519。

## Ed25519署名

release作業時にユーザーがMacの運用鍵で署名する。運用鍵が使えない場合はWindowsの回復鍵を使用する。CIへ秘密鍵を置かない。署名対象は整形・改行変換を追加せず、確定したUTF-8 JSON全体のbytes。署名出力は64-byte rawである。例では秘密鍵のパスを環境変数で渡すが、鍵内容・パスフレーズを引数、ログ、repositoryへ置かない。パスフレーズ付き鍵はOpenSSLの対話promptで入力する。

```sh
# descriptorを確定した元bytesのまま署名
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" -in descriptors/macos-aarch64.json -out descriptors/macos-aarch64.json.sig

# stable manifestはtool payload ZIPの最終hash確定後に署名
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" -in stable.json -out stable.json.sig

# 公開鍵DERで検証（公開鍵はSubjectPublicKeyInfo DER）
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey trust-public.der -in descriptors/macos-aarch64.json -sigfile descriptors/macos-aarch64.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey trust-public.der -in stable.json -sigfile stable.json.sig
wc -c descriptors/macos-aarch64.json.sig stable.json.sig
```

検証は公開trust-set内の各公開鍵で行い、受理鍵のいずれか一つで成功すること、署名が各64 bytesであることを確認する。秘密鍵の内容やパスフレーズを引数に書かず、OpenSSLの対話promptを使う。鍵選択・trust-set生成・JSON生成の手順はここでは定めない。

## 公開順序と確認点

1. 公開対象runtime artifactの最終bytesを確定する。
2. artifactのsize/SHAを含むinventoryを生成・確定する。
3. inventoryを参照するhost descriptorを確定する。
4. descriptorだけをユーザーが署名し、公開DERで検証する。
5. 全host共通のtrust-setを確定し、runtime入力とbootstrap seed/packageの信頼集合が一致することを確認する。
6. pins/resourcesを組み立て、core JARを生成する。descriptor元bytes、署名、trust、pinsがJAR内で一致することを確認する。
7. Central bundleを作成し、PGP署名・checksum・bundleを監査する。**Centralへの公開はユーザー確認後**に行う。現1.1.0 bundleは公開対象にしない。
8. tool payload ZIPの最終bytes/hashを確定し、stable manifestを作成する。
9. stable manifestをユーザーが署名し、公開DERで検証する。
10. seed/packageを組み立て、descriptor・payload・manifest・署名・trustの参照とhashを確認する。対応するRelease assetをまとめる。**tag作成とGitHub Release公開はそれぞれユーザー確認後**に行う。
11. 公開後、空のGradle cacheによるCentral consumer検証と、空のruntime/bootstrap cacheによるGitHub Release取得・署名検証・managed consumer検証を行う。再取得bytesを公開前候補と照合する。

順序の根拠となる既存生成契約は[managed runtime resources](managed-runtime-resources-1.1.md)、[trust-set](../bootstrap/docs/trust-set.md)、[Maven Central](maven-central.md)、[自動更新](../bootstrap/docs/auto-update.md)。ここに記したユーザー確認は公開操作の実行承認であり、事前準備・候補生成・ローカル監査を止めるものではない。

## 未確定事項

- 最終runtime artifact bytes、各hostの公開可否と受入、descriptorの本番値。
- trust-setをReleaseから配布する方法、およびinventoryの最終asset basenameとURL。
- stable manifestの本番URL、sequenceの初期値・更新責任者。
- GitHub Releaseのtag/version対応、署名を含む最終Release asset一覧と公開手順。
- Maven Central bundleの最終版、公開時期。#97のinstaller経路は別途決める。
- 公開後に実施する対象hostごとの空cache consumer受入条件。
