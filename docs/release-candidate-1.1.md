# 1.1.0リリース候補の署名前組立て（R1）

Refs #91〜#95（closeしない）。起点は2026-11-05 JSTのfetchで確認したorigin/main `e8e5d348cb23a92145fe0b8c09fb37c608fdceee`。worktreeは `H:/MCIconGenerator/worktrees/milestone-release-candidate-r1/MCIconGenerator`、branchは `feature/issue-97-release-candidate`。成果物はbuild/r1へ保全し、archive・署名前descriptorを本番値として収録しない。

[配布手順](release-distribution-1.1.md)の手順1〜3に対応する。測定値は[QA JSON](../qa/release-candidate-2026-11-05.json)と[checksum](../qa/release-candidate-2026-11-05.json.sha256)。全archive2回・既存QA一致を確認したが、最終採用flagはfalseである。

## 再現した最終basenameと実bytes

Release tag想定はv1.1.0。各実fileはbuild/r1/candidate-v2/artifacts/配下。URLは `https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.0/<basename>`。未公開URLの可用性は未検証。

| basename | bytes | SHA-256 | 結果 |
| --- | ---: | --- | --- |
| `mcicon-helper-1.1.0-windows-x86_64.zip` | 36029106 | `efc2c38e735cf359ea751931ce7d2f746e8b082a758cd2f32ea885ad48257ab8` | 2回・既存QA一致 |
| `mcicon-helper-1.1.0-macos-aarch64.zip` | 125853859 | `48ddab0fa68844a269e4ac0982cbffbfa2d67806207efdeb187c0900d05efbde` | 2回・既存QA一致 |
| `mcicon-guest-1.1.0-windows-x86_64.tar.xz` | 98146824 | `f68070d5b702a00916ecd1108de1c3e8f4b644ebeb603ccf7f6b7b881ffb15c5` | 2回・既存QA一致 |
| `mcicon-guest-1.1.0-macos-aarch64.qcow2` | 620224512 | `1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55` | 2回・既存QA一致 |
| `mcicon-renderer-1.1.0-linux-amd64.tar.gz` | 593385993 | `dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4` | 2回・既存QA一致 |

## 現在の署名対象bytes候補

以下の2fileだけを列挙する。最終採用・署名gateを満たしたとは扱わない。inventoryはdescriptorのSHA参照へ間接結合され、単独の署名対象にはしない。

| 正確なpath（repository root起点） | bytes | SHA-256 |
| --- | ---: | --- |
| `build/r1/candidate-v2/descriptors/windows-x86_64.json` | 120459 | `1ebde182cbdecb0cd3004d56ee41d071cd7d5128a63f77384ce5b5b85cbed32c` |
| `build/r1/candidate-v2/descriptors/macos-aarch64.json` | 466787 | `864b89f6da5a5ec52b628492a0d4eba090656888104d85b483304b335969d826` |

## 現状調査と選定

既存のJCA Ed25519、RuntimeDescriptor / RuntimeStore / RuntimeArchive / RuntimeResourceGenerator、host packager、rootfs生成器、renderer測定器・export器を再利用した。追加のlibraryはない。Python標準libraryだけでinventory/descriptorと後続payload/stableを組み立てる。

- descriptor schema 1は `schemas/managed-runtime-v1.schema.json`、input/pinsは既存Java生成器の3field契約を維持する。公開API・protocol・保存形式は変更しない。
- inventory本体には独立したJSON schema/内容validatorがない。Javaは最大4MiBの元bytesとdescriptor内SHAを照合する。候補inventoryにはfile/package情報、既存QAへのsize/SHA参照、2回再現結果と未解決gateを入れた。この内容がlicense/source閉包の受入済みであるとは扱わない。
- 正式な2host inventory/descriptorの一括生成器と署名前Java検証入口が不足していたため、`scripts/assemble-release-candidate.py` と `:core:verifyRuntimeCandidate` を追加した。前者は5資材の2回分と既存pinを全件確認してから出力する。planの `runs` は実file2件、`baseline` は既存QAの `bytes/sha256`。artifact IDとbasenameは配布規則に固定する。入力planは `build/r1/plan.json` に保全した。
- 署名なし候補のvalidatorはpackage内部の形式parserを使い、実agent・adapter・全Java major・inventory・全artifactのsize/SHA・helper展開を確認する。本番の公開 `RuntimeDescriptor.verify` は従来どおり署名を検証してから形式parserへ渡す。候補検査からpins/resourcesを生成する経路はない。
- 正式resource生成は `finalBytesConfirmed=true` に加え、64byte署名、全host共通96byte trust、互換pin、inventory hash、実archiveを必要とする。guest/rendererはhostへ展開しない。URLへの到達・license/sourceの法的充足まではローカルJava検証では証明しない。
- 署名後のpayload/stable一括生成も不足していたため、`scripts/prepare-release-payloads.py` を追加した。現core JARと生成resource/input pins、公開trustによる署名、agent/helper実bytesを照合し、候補helper内のhost別JREを再利用する。未採用inputのflagを自動変更しない。seed/packageは既存Java検証器とpackagerを再利用する。

共有renderer inventoryはRelease上で1種類の元bytes/basenameを維持し、core同梱時だけ `inventories/windows-x86_64/` と `inventories/macos-aarch64/` の別resource pathへ複製する。既存生成器は同bytesでも同一resource pathの二重登録を拒否するためである。5種類のinventory元bytesと6個の同梱resource pathを区別する。署名前validatorにも既存の配置衝突・job予約path検査を反映した。初稿 `build/r1/candidate` は衝突の反証対象として保全し、署名対象にしない。

## 再現の条件と途中失敗

固定JDK、native toolchain、snapshotと圧縮条件は各候補文書・QAの値を再利用した。Windows helperはJavaとnativeを各2回再buildし、packagerと固定ZIP生成を行った。Mac helperは専用領域でoffline build・native包装・既存包装検証を各2回実行した。Mac guestは公式固定qcow2を無改変で2回取得したもので、guest OSをsourceからbuildしたという意味ではない。rootfsとrendererは `--no-cache --provenance=false`、SOURCE_DATE_EPOCH=1793664000で各2回独立buildした。rendererの最終tar.gzは固定image IDを指定して2回exportした。

初回rootfsはWindows checkoutのlockfileがCRLFとなり、aptへ渡る版番号へCRが混入して失敗した。専用contextで既存C1と同じLF lockfile、CRLFのLICENSE/guestbridgeを構成して再実行した。2lockfileのLF指定を.gitattributesへ追加し、通常checkoutでの再発を防いだ。Git archiveは端末のcore.eolにも影響されるため、R1で必要な元bytesはGit blobと実SHAで照合した。

Mac初回2回はLICENSEがCRLF化し、ZIPが125,853,872 bytesになった。差はLICENSEとそれに依存するlauncher/CodeResourcesの3fileだった。専用領域のLICENSEだけをGit元bytesへ補正し、run3/4で既存D1の固定ZIPと一致した。失敗・差分証跡はbuild/r1へ保全し、成功値へ混ぜていない。OS保護回避や属性削除、Developer ID署名は行っていない。既存packagerのad-hoc sealは既存候補の包装手順として再現した。

Windows descriptorの下限は既存F1実測の `10.0.26200` に限定した。Windows 11の古いbuild全般への保証は追加しない。Mac下限は既定どおり15.8.1。rootfsのunpackedBytesは非圧縮tarの403,507,200 bytes、Mac qcow2は無展開の実file 620,224,512 bytes、rendererはimage累積size 2,056,219,398 bytesを予算とする。helperは全通常fileの論理bytes合計を使う。これらはVM disk・空き容量・実起動の受入を置き換えない。

## finalBytesConfirmedの判断

**falseを維持する。** 全5資材の実bytesの再現・size/SHA一致は確認できたが、このflagはJava側では「最終bytes採用」を表す。再現成功だけで未採用candidateを通すためにtrueへ変更しない。

本番公開trust-setの指定・descriptor署名、component別source/notice bundleの閉包と配布形態、Microsoft runtimeとUbuntu改変版の再配布判断、正式経路の受入が残る。Mac qcow2内のlicense本文参照も本作業では直接検証していない。候補inventoryはこれらの未完了を明記する。trueへ進める採用判断は主担当へ返す。inventory/descriptorのbytesを変更する場合はhash一覧を再生成し、変更前の署名を流用しない。

現在の候補は「ユーザーが署名するだけで正式配布できる状態」には達していない。秘密鍵・認証・トークンを探索せず、本番署名・Central・tag・Release・commit/push/PRは実行していない。

## 署名前の再生成・実bytes検証

以下はrepository rootからのコマンド本体。端末規約どおりrun_logged.pyを通す。Windowsでは指定の共通Pythonを使い、Gradle引数はPython subprocessの引数配列で渡すかPowerShellで引用する。既存出力は上書きしないため、再実行時は未使用のoutput directoryを指定する。

```text
python scripts/assemble-release-candidate.py --plan build/r1/plan.json --output build/r1/candidate-v2
gradlew --offline --no-daemon -PreleaseVersion=1.1.0 -PruntimeCandidateInputDir=build/r1/candidate-v2 :core:verifyRuntimeCandidate
```

planのtop-levelは `version`、`tag`、`agent`（実agent JARのpath）、`rendering`（既descriptor契約のimageArtifactId以外）、`unresolved`（未完了gate配列）、`artifacts`。各artifactは `id`、`role`、`host`（例windows-x86_64）、`runs`（実fileのpath2件）、`baseline`（bytes/sha256）、`unpackedBytes`、`contents`（file/package監査内容）、`evidence`（repository起点の既QA path配列）、`licenseFiles`、`noticeFiles`、`sourceArchives` を持つ。planにあるローカルrun/agent pathはdescriptor/inventoryへ出力しない。新しいversionへの汎用発行器にはしていない。

正式resources用の `managedRuntimeInputDir` と、署名前検査用の `runtimeCandidateInputDir` を区別する。署名・trust未配置での正式生成成功は本作業の検証に含めない。

## 署名コマンドと公開鍵での確認

上表のdescriptor元bytesだけを、改行変換・整形せずに署名する。運用鍵が使えないときは回復鍵を使用する。同じfileを両方の鍵で署名する必要はない。以下の変数名は既存手順と同じで、秘密鍵の実path・内容は本書へ記載しない。秘密鍵はユーザー自身が扱い、agentは署名しない。

```sh
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" -in build/r1/candidate-v2/descriptors/windows-x86_64.json -out build/r1/candidate-v2/descriptors/windows-x86_64.json.sig
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" -in build/r1/candidate-v2/descriptors/macos-aarch64.json -out build/r1/candidate-v2/descriptors/macos-aarch64.json.sig
```

指定された運用・回復の**公開DER2件だけ**から既存 `bootstrap/scripts/trust_set.py` で共通trust-set.binを生成する。96bytesの先頭8bytesの後に正規順の公開DER44bytesが2件ある。確認用にこの2件を `public-0.der` / `public-1.der` として取り出し、各署名が64bytesであり、次のいずれか一方で成功することを確認する。

```sh
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey public-0.der -in build/r1/candidate-v2/descriptors/windows-x86_64.json -sigfile build/r1/candidate-v2/descriptors/windows-x86_64.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey public-1.der -in build/r1/candidate-v2/descriptors/windows-x86_64.json -sigfile build/r1/candidate-v2/descriptors/windows-x86_64.json.sig
wc -c build/r1/candidate-v2/descriptors/*.json.sig
```

Mac descriptorも入力・sigfileをmacos-aarch64へ置き換えて同じ検証を行う。署名は64bytesを超えるwrapper/base64/hexへ変換しない。本番公開DER/trust-setの正確な入力場所は未指定であり、仮の鍵・テスト鍵を採用しない。

## 署名後の手順5〜11

1. 採用gateを解消して最終inputを確定する。同じtrust-set.binと署名済みdescriptorを全host入力へ配置し、既存生成器でpins/resourcesを作る。candidateのfalseを機械的にtrueへ書換える手順にはしない。
2. 次の既存build入口で現coreとCLI/bootstrap JARを生成する。ここで署名・inventory・実bytes・互換条件のJava検査が行われる。

```text
gradlew --offline --no-daemon -PreleaseVersion=1.1.0 -PmanagedRuntimeInputDir=build/r1/final-input :core:jar :cli:installDist :bootstrap:installDist
```

3. 現coreのJAR全体とruntime resourcesを固定し、tool payload2件とstable.jsonを次のツールで生成する。host別JREは同じfinal-inputのhelper ZIP内から採る。Windowsで生成したJava artifact/adapter bytesを他hostで再buildして入れ替えない。Mac包装へはこの確定JAR/resources/seedを転送する。改行により同梱LICENSEやagent hashが変わり得るためである。

```text
python scripts/prepare-release-payloads.py --runtime-input build/r1/final-input --cli-lib cli/build/install/mcicon/lib --worker worker/build/libs/worker-1.1.0-agent.jar --core core/build/libs/core-1.1.0.jar --resources core/build/generated/resources/managedRuntime --sequence 1 --output build/r1/payloads
```

初回sequenceは1とする。既存公開stableのsequenceとの整合は公開直前に主担当が再確認し、将来版は単調増加させる。packageに渡すstableManifestUrlは `https://github.com/rs0325/MCIconGenerator/releases/latest/download/stable.json` とする。immutableなruntime/payload URLは `releases/download/v1.1.0/<asset>` のまま。latest asset URLの仕様は[GitHub公式文書](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)に基づく。Releaseをlatestとして公開する判断はユーザーに残す。

4. 生成後のstable.jsonについて別途size/SHAを確定し、ユーザーが署名する。これはpayload hash確定後にしか作れない**次の署名対象**であり、現在の確定2file一覧へ仮のhashを加えない。

```sh
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" -in build/r1/payloads/stable.json -out build/r1/payloads/stable.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey public-0.der -in build/r1/payloads/stable.json -sigfile build/r1/payloads/stable.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey public-1.der -in build/r1/payloads/stable.json -sigfile build/r1/payloads/stable.json.sig
wc -c build/r1/payloads/stable.json.sig
```

5. host別seed directoryへ同じstable.json/.sigと対応payloadを `tool.zip` の名で置く。共通trustを渡し、既存 `:bootstrap:verifyManagedRuntimePackage` とMac `managedAppImage` / Windows `windowsAppImage` の包装検査を実hostで行う。Java未導入・cold-start・setup/更新・offline・異常回収・OS警告/実reboot等の未受入を埋める。Windows用native security readerの確定入力も別途必要である。seed/package検査は現在core JAR全体と全runtime resourceの一致を必須とする。
6. Central bundleは既存[maven-central.md](maven-central.md)のPGP署名・監査に従う。managed Ed25519署名とは別で、今回Central関連操作は実行しない。
7. Release assets候補を平坦に集約してsize/SHAを監査する。tag作成・Release公開・Central公開はユーザー判断後だけ実行する。公開後は手順11の空cache consumerと再取得bytes照合を実施する。

Releaseへ並べる予定は、上表のruntime5件、inventory5件（`inventoryPath` のbasename）、host descriptor2件と64byte署名2件、共通trust-set.bin、tool payload2件、stable.json/.sig、および未確定のcomponent別source/notice bundleである。inventoryのresource path（共有rendererだけhost別prefix）とRelease basename `<artifact-id>.json` の対応はcandidate-report.jsonへ記録した。tool inventoryは各payload内へ同梱する。現在のpayload生成器はsourceArchivesを空配列で出力するため、正式stable採用前にcomponent別source/notice bundleの確定参照をpayload metadataへ結合する工程が残る。空配列を再配布条件確認済みとは扱わない。source/notice bundleとseed/packageの最終basename・size/SHAは後続工程で確定する。

## 検証範囲と後始末

関連Javaテスト、候補生成/payload生成と既存archive正規化のPythonテスト、実候補に対する署名前Java検査・実archive/inventory改変拒否、git diff --checkの結果はQAに記録した。Mac helperのbuild内テストは45件中44成功・1skip（実probe stdoutのproperty未指定）であり、このskipを実probe受入成功として扱わない。payloadテストの署名検証はmockであり、本番署名の受入ではない。署名済み正式resources、core/payload/seed/package結合の実検証は未実施である。

Mac専用領域は成果物・ログ回収後に削除し、残存processなしを確認した。MacでLima/VMは起動していない。Windows側の専用WSL distro作成や既存distro・設定変更は行っていない。Dockerは自作tagだけを削除し、開始前から存在したrenderer tag/imageと共有cacheは保持して通常停止する。実施結果はQAのcleanupで確認する。build/r1の成功/失敗証跡・候補bytesは保全する。commit/push/PR、Issue close、tag、Releaseは行わない。
