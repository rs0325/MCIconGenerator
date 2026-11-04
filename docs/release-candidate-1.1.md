# 1.1.0リリース候補の署名前組立て（R1・R2）

最新の採用判定は末尾の「R2: 最終input採用の判定」を参照。現在もfalseで、最終署名対象は未確定。

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

## R2: 最終inputと署名対象の確定（2026-11-05）

**`finalBytesConfirmed=true`。署名対象は以下のdescriptor2件で確定した。署名は未実施。** R1および前回R2のfalse候補を置き換える記録であり、R1の未指定trust・未受領の再配布許可・Microsoft条件未確定という記載は当時の履歴である。起点はPR #163の `41dfb1a91a9f91c1ad08e24d50771565f4fe4c04`、branchは `feature/issue-97-release-final-input`。Refs #91〜#95はcloseしない。

### true化の条件と判定

| 条件 | 充足状況 |
| --- | --- |
| 5artifactの実bytes・2run・QA pin | 成功。Windows helperだけはR1の各runへ同じ6文書を追加し、2run一致と元の全メンバー不変を確認。派生前後のpinをinventoryとQAに保持。他4資材はR1と同一 |
| license/notice/source閉包 | 全componentの原文・noticeとsource bundle2本を固定。Microsoft原文と下流条件をWindows helperにも同梱。全inventoryのlicenseClosureConfirmed=true |
| Java8対応source全差分 | 48,141fileに変更・削除なし。公式release sourceの追加は19byteの `.hgtip` のみ |
| 本番公開trust-set | 指定の公開DER2件から再生成し、96byteの指定trustと一致。秘密鍵を参照していない |
| 採用flag・inventory hash | assemblerの明示採用入力でinputと全7inventoryをtrue化。descriptorはinventory SHAに結合し、独自の採用flagは追加しない |
| Java実資材検証 | `:core:verifyRuntimeCandidate` 成功。署名検証と公開URL到達性は証明しない |
| 本番pins/resources | 署名未受領のため生成を停止。署名がないことを理由に拒否することを確認。true化と署名・公開受入は別段階 |

正式配布経路のOS警告、host setup、実reboot、正規lease、fresh cache consumer受入は後続gateである。最終bytesの採用を、製品受入・Central・tag・Release公開完了として扱わない。

### Microsoft 49 DLLの出所と条項

| 分類 | 実測・適用根拠 |
| --- | --- |
| UCRT / APISet 46件 | 10.0.26100.1742。Microsoft Corporation署名は全件Valid。公式NuGet `Microsoft.Windows.SDK.CPP` 10.0.26100.1742の `c/Redist/10.0.26100.0/ucrt/DLLs/x64/` と全件バイト一致。同packageのnuspecが指す `https://aka.ms/WinSDKLicenseURL` の原文RTFを保存・同梱 |
| VC++ runtime 3件 | msvcp140.dll / vcruntime140.dll / vcruntime140_1.dll、14.40.33810.0。Microsoft Windows Software Compatibility Publisher署名は全件Valid。固定Temurin build recipeのVS2022 devkitと全件一致。VS2022のDistributable Code条項とVC/redist一覧へ対応 |
| 共通の採取元 | Temurin build commit `e6ba7dec3d07654074559310376a3ae89da5f4ac` の `sbin/prepareWorkspace.sh` が指定するdevkit（SHA `ac6060f5f8a952f59faef20e53d124c2c267264109f3f6fabeb2b7aefb3e3c62`）を実取得し、49 DLLすべて一致 |

一次情報は [SDK条項](https://learn.microsoft.com/en-us/legal/windows-sdk/license)、[SDK Redist一覧](https://learn.microsoft.com/en-us/legal/windows-sdk/redist)、[UCRT配置](https://learn.microsoft.com/en-us/cpp/windows/universal-crt-deployment?view=msvc-170)、[VS2022条項](https://visualstudio.microsoft.com/license-terms/vs2022-ga-community/)、[VS2022 Redist一覧](https://learn.microsoft.com/en-us/visualstudio/releases/2022/redistribution)、[VC++ Runtime利用者条項](https://visualstudio.microsoft.com/license-terms/vs2022-cruntime/)。取得日は2026-11-05、各取得bytes・size・SHAはQAのmicrosoft.referencesと `build/r2/microsoft-references/` に保持した。VS原文は `VS_Comm_2022_ENU.1033`、利用者補足は `Cpp_2015-2022_ENU.1033`。後者だけを再配布許諾の根拠にはしない。

2024-10-21更新のSDK Web一覧には追加5件（console-l1-2-0、fibers-l1-1-0、fibers-l1-1-1、kernel32-legacy-l1-1-1、sysinfo-l1-2-0）が明記されていない。同版SDK実体のRedistフォルダと公式UCRT配置文書に照らして分類し、46件全件の同一性を確認した。出所不明・SDK Redist外のDLLは0件。Microsoft DLLのソース提供権を仮定せず、独立バイナリとして保持する。

| 配布条件 | 技術的な対応 |
| --- | --- |
| 有効なSDK/VS利用資格と適用条項 | ユーザーの再配布許可を受領済み。同梱条件で配布者の義務を明示し、原文への参照と版・hashを固定 |
| アプリの主要機能と一緒に配布 | 描画・managed実行機能を持つWindows helper ZIPの一部。DLL単独のassetは作らない |
| DLLと著作権・商標・特許表示を変更しない | 49 DLLを含む全元ZIPメンバーを再比較。追加は文書6件だけ |
| 外部利用者・再配布者に同等以上の保護条件への合意を求める | helper直下の `MICROSOFT-RUNTIME-TERMS.txt` と `licenses/microsoft/NOTICE.txt` に、合意が必要であること、拒否時の利用・再配布禁止、再配布先への同条件付与を明記。原文3件を同梱 |
| 免責・損害制限・地域法・輸出規制・補償義務 | noticeに明記し、強行法規の例外と原文を保持。SDK2.a.iiの配布者の補償・防御・免責義務も明記 |
| Windows限定・商標・悪意ある配布・Excluded Licenseの禁止 | noticeに明記。Windows用ZIPだけへ収録し、MITやGPLをMicrosoft DLLへ適用しない |
| 有効なアプリ著作権表示 | 既存MIT表示と同じ `Copyright (c) 2026 MCIconGenerator contributors` を保持 |

条件文の正本は [helpers/windows/licenses/Microsoft-runtime.txt](../helpers/windows/licenses/Microsoft-runtime.txt)。LICENSE本体は変更していない。これは法的助言ではなく、条項の技術的な収録・対応確認である。配布者は利用資格、補償義務、契約成立と地域法上の効力を最終確認する。noticeの存在だけで全地域の同意成立を実証したとは扱わない。除外が必要なDLLは今回の技術調査では見つからなかった。

### source/notice閉包と最終資材

| component | 結果 |
| --- | --- |
| Windows helper / Microsoft / native / Java21 | 原文と条件を追加した6文書、全DLL版・署名・hash一覧を同梱。LLVM/MinGW source、runtime source、元のlicense/noticeも保持 |
| Mac helper / Lima / Go / Java21 | 既存103文書、Lima source、Go標準source、90依存moduleのsumとlicenseを確認。資材本体はR1不変 |
| Windows rootfs / Ubuntu | 実tar.xzから140文書を照合し、package対応source・patch・dscをbundleへ収録 |
| Mac qcow2 / Ubuntu | guestをbootせずread-onlyで663/663 packageのcopyrightを抽出、共通licenseを含む677文書を確認 |
| renderer / Debian・Ubuntu / Mesa / LLVM / Java8・17・21・25 | 994文書、920packageのcopyright、対応sourceを確認。Java8は上記全差分確認済み |

対応sourceは1,580file。2本の同じReleaseのsource assetsから提供し、各artifactの `sourceArchives` にURL/SHAを設定した。各sourceのbasename・size・SHAとarchive内pathはQAへ収録した。source bundleのMANIFESTはtrueで、component対応、取得元、全sourceとnoticeのsize/SHAを持つ。`licenseFiles` / `noticeFiles` にある補足 `notices/...` はsource bundle展開root起点、元artifact内の参照も保持する。GPL/LGPL等の取得手段を保持するため、**バイナリと同時に2本とも公開することが必要**。まだ公開しておらず、公開URLの到達性は未検証。

| Release source asset（`build/r2/source-assets/`） | bytes | SHA-256 |
| --- | ---: | --- |
| `mcicon-sources-1.1.0-part-01.tar` | 1341122560 | `6e2e101c9700ac5b09571f9fefd0386872f8f06392645355feedbe05e2546133` |
| `mcicon-sources-1.1.0-part-02.tar` | 1384468480 | `cf984cad246b1572e04cf0ed013b61343772e698016dcad59f88885d28344f51` |

| 最終artifact（`build/r2/final-input/artifacts/`） | bytes | SHA-256 |
| --- | ---: | --- |
| `mcicon-helper-1.1.0-windows-x86_64.zip` | 33863906 | `58dd0b651b8a93557104c53f933568b2373cd7afe41fa548071279c05315d0a8` |
| `mcicon-helper-1.1.0-macos-aarch64.zip` | 125853859 | `48ddab0fa68844a269e4ac0982cbffbfa2d67806207efdeb187c0900d05efbde` |
| `mcicon-guest-1.1.0-windows-x86_64.tar.xz` | 98146824 | `f68070d5b702a00916ecd1108de1c3e8f4b644ebeb603ccf7f6b7b881ffb15c5` |
| `mcicon-guest-1.1.0-macos-aarch64.qcow2` | 620224512 | `1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55` |
| `mcicon-renderer-1.1.0-linux-amd64.tar.gz` | 593385993 | `dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4` |

Windows helperの追加文書はrootの利用条件、`licenses/microsoft/` 内のnotice・SDK原文RTF・VS2022原文DOCX・VC++ Runtime原文DOCX・inventory JSONの計6件。既存assemblerへは明示的な `finalBytesConfirmed` と全資材の閉包条件だけを追加した。既定はfalse、未解決事項やlicense/notice/source参照不足なら出力前に拒否する。実行済み組立て入力は `build/r2/plan.json`、補足生成・監査スクリプトとログは `build/r2/` に保存した。元R1候補と失敗試行を保全し、公開するのは本表の最終bytesだけとする。

| 最終inventory | bytes | SHA-256 |
| --- | ---: | --- |
| `build/r2/final-input/inventories/macos-aarch64/mcicon-renderer-1.1.0-linux-amd64.json` | 458587 | `6fa7c6b7cf59053fe44aae8bea5fe180d75a88ccd49cb065e4a3d765c2728408` |
| `build/r2/final-input/inventories/mcicon-guest-1.1.0-macos-aarch64.json` | 419206 | `7f439b243cd781198ede6b70d9ccaad8a11686a2a658bc3233081f307274e392` |
| `build/r2/final-input/inventories/mcicon-guest-1.1.0-windows-x86_64.json` | 162114 | `f05a9ac3ce42db46c8bf7f6feaad5f2e0da01369e8affdfcaeedf5801bc2752b` |
| `build/r2/final-input/inventories/mcicon-helper-1.1.0-macos-aarch64.json` | 80921 | `d5db2f6306d55b4149081a52c836c392a6753f62e4ba49e7db116168c1512c1d` |
| `build/r2/final-input/inventories/mcicon-helper-1.1.0-windows-x86_64.json` | 78623 | `b1eb2eec77eef369884203bd034bf306f67fd1d7949d64b7fa640a61bf203f06` |
| `build/r2/final-input/inventories/mcicon-renderer-1.1.0-linux-amd64.json` | 458587 | `6fa7c6b7cf59053fe44aae8bea5fe180d75a88ccd49cb065e4a3d765c2728408` |
| `build/r2/final-input/inventories/windows-x86_64/mcicon-renderer-1.1.0-linux-amd64.json` | 458587 | `6fa7c6b7cf59053fe44aae8bea5fe180d75a88ccd49cb065e4a3d765c2728408` |

### trustと署名対象

公開DER2件とtrust-setの信頼の正本はcore JARのresources（配布手順5・6）。同じ3ファイルをRelease assetsにも公開する。リポジトリにはバイナリをコミットしない。

| build入力の公開trust | bytes | SHA-256 |
| --- | ---: | --- |
| `build/r2/final-input/mcicon-operating.pub.der` | 44 | `8dc54c096f222fb1665e19446debec844e2c726d20cc91eab2a392f1e6902f5a` |
| `build/r2/final-input/mcicon-recovery.pub.der` | 44 | `0e468cc27d1fdad02bc90e2fb8de58a39152333c408a940f27f99df591dd9ccf` |
| `build/r2/final-input/trust-set.bin` | 96 | `8529ace9f49e4b46c915e51d0cad5b576e6a9c536afe665ec779dd279ed51283` |

署名対象の正確な絶対pathを以下に示す。署名端末へ移す場合も元bytesと相対配置を維持し、署名前にsize/SHAを再照合する。

| ユーザーが署名するdescriptor | bytes | SHA-256 |
| --- | ---: | --- |
| `H:/MCIconGenerator/worktrees/milestone-release-final-r2/MCIconGenerator/build/r2/final-input/descriptors/windows-x86_64.json` | 278044 | `cdcd7ce552f0be140a57eae56c0497e4156238a1914f68a3a5bd2654e87c3294` |
| `H:/MCIconGenerator/worktrees/milestone-release-final-r2/MCIconGenerator/build/r2/final-input/descriptors/macos-aarch64.json` | 374519 | `2103835ca14e88d50cff70162585304af5aa13351babb0284b2f13dad2998101` |

ユーザー自身が署名端末で実行するコマンドは以下。秘密鍵のpathをユーザーがローカルで `ED25519_PRIVATE_KEY` に設定し、passphraseはOpenSSLの対話promptへ入力する。[配布手順](release-distribution-1.1.md)と同じ引数を使用する。**stable manifestの署名はpayload確定後の別段階であり、今回は署名しない。**

~~~sh
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" -in build/r2/final-input/descriptors/windows-x86_64.json -out build/r2/final-input/descriptors/windows-x86_64.json.sig
openssl pkeyutl -sign -rawin -inkey "$ED25519_PRIVATE_KEY" -in build/r2/final-input/descriptors/macos-aarch64.json -out build/r2/final-input/descriptors/macos-aarch64.json.sig

openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey build/r2/final-input/mcicon-operating.pub.der -in build/r2/final-input/descriptors/windows-x86_64.json -sigfile build/r2/final-input/descriptors/windows-x86_64.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey build/r2/final-input/mcicon-recovery.pub.der -in build/r2/final-input/descriptors/windows-x86_64.json -sigfile build/r2/final-input/descriptors/windows-x86_64.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey build/r2/final-input/mcicon-operating.pub.der -in build/r2/final-input/descriptors/macos-aarch64.json -sigfile build/r2/final-input/descriptors/macos-aarch64.json.sig
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey build/r2/final-input/mcicon-recovery.pub.der -in build/r2/final-input/descriptors/macos-aarch64.json -sigfile build/r2/final-input/descriptors/macos-aarch64.json.sig
wc -c build/r2/final-input/descriptors/*.json.sig
~~~

各64byteの署名を、運用/回復のいずれか一方の公開鍵で検証できればよい。両鍵での成功や両鍵での署名は要求しない。実行はrepository root起点の例であり、署名端末上に同じ相対配置を用意する。

### 署名受領後に進める手順5〜11

5. 両descriptorと64byte署名、公開DER・共通96byte trustのbytesを照合し、runtime入力とbootstrap seed/packageのtrustを一致させる。公開trust3ファイルをRelease候補へ置く。
6. `-PreleaseVersion=1.1.0 -PmanagedRuntimeInputDir=build/r2/final-input` で `:core:jar :cli:installDist :bootstrap:installDist` を実行し、core JAR内のdescriptor・署名・inventory・pins・trust・DERが入力と一致することを検証する。
7. 現core/workerからCentral bundleをローカルで準備・監査する。PGP署名とCentral公開は別の実行許可を確認し、旧bundleを流用しない。
8. `scripts/prepare-release-payloads.py --runtime-input build/r2/final-input --resources core/build/generated/resources/managedRuntime --sequence 1 --output build/r2/payloads` に現CLI lib・worker・coreの引数を付けて実行し、payload2件とstable manifestの最終bytes/hashを確定する。
9. **payload確定後**にユーザーがstable manifestを署名し、同じ公開DERで64byte署名を検証する。
10. seed/packageのJava検証でcore・payload・manifest・descriptor・署名・trustを照合。source2本、notice、inventory、公開trust3ファイルを含む最終asset一覧を確定する。tag/Release公開は別の明示許可後。
11. 公開後にfresh cacheで正式URL再取得・署名検証・consumer受入を行う。正式経路のOS警告・実reboot・lease受入は後続gateとして完了を確認する。

### 検証・反証・後始末・残作業

Java関連35件、assembler4件のテストが成功。payload準備の既存3件成功は前回記録を再利用。実最終入力の `:core:verifyRuntimeCandidate`、source1,580fileとnotice2,023fileの再hash、公開trustの再生成が成功。真の入力からresourcesを生成すると、未受領の `windows-x86_64.json.sig` を要求して停止し、pinsは生成されない。本番署名検証、core JAR、公開経路のconsumer受入は未実施。合成署名fixtureによるresources検証を本番成功とは扱わない。

初回Java検証で、条件文書を共通licensesへ置くとworker JARに自動収録されR1 agent hashが変わる問題を検出。Windows専用の正本へ移して影響を解消し、R1のworker hashを保持した状態で実資材検査を再成功させた。共有API・input schemaは変更していない。失敗時出力、DER欠落・不一致・resource衝突、false既定と明示採用の伝播、元ZIPメンバー不変を重点反証した。

前回R2のDocker監査image削除・既存image一覧一致・Docker停止の記録を保持。再開後はDocker/Mac/WSLを使わず、Docker engine pipeがないことを確認した。Macに作業ディレクトリを作らず、既存状態を変更していない（Macの前後実測は未実施）。自分が起動した処理はすべて終了。資材・source・ログ・試行1はbuild内に保全した。

QAは [release-final-input-2026-11-05.json](../qa/release-final-input-2026-11-05.json) と [checksum](../qa/release-final-input-2026-11-05.json.sha256)。変更ファイル全一覧・各資材/source/descriptorのsize/SHA・未完了工程を収録。公開準備文面はUTF-8の `build/commit-message.txt`、`build/pr-title.txt`、`build/pr-body.md`。commit・push・PR・署名・Central・tag・Releaseは実施していない。残作業は上記2件のユーザー署名受領から始まる。
