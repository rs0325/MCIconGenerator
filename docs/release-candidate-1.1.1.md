# 1.1.1 配布契約（段階A）

> 現在の統合判定は[段階H](#stage-h-20261108)を参照。A〜Fの署名前・未着手等は当時の記録として保持し、最新の状態へ読み替えない。

Refs #94 / #95 / #99。起点は2026-11-06 18:10 JSTにfetchした
origin/main **1c616e678c08c453e2b82ca8a7d806b765a2c4f7**（#174、#175を含む）。
task-id=milestone-111-a、branch=feature/issue-99-release-111-contract、
単一書込owner=段階Aのcomplex_workerセッション。
実資材の再build・署名・公開は後続工程である。

[配布仕様](release-distribution-1.1.md)・[構成](managed-runtime-composition-1.1.md)・
[旧候補とsource閉包](release-candidate-1.1.md)・[Central手順](maven-central.md)を参照する。
既存生成器・Python標準機能・Gradle・jpackageを拡張し、依存は追加しない。

## 固定値と再利用資材

全製品表示版・core/worker/CLI/helperは **1.1.1**、tagは **v1.1.1**。
Gradleは全工程で -PreleaseVersion=1.1.1 を指定する。
stable sequenceは **2候補**（公開直前にlatest/high-waterと照合し既発行値を上回る値へ確定）。
minimumBootstrapProtocol/package protocol/state schema 4、receipt schema 3、
descriptor/helper/worker protocol 1、Windows guest ABI 1を維持する。

再利用4資材は**元basename・同bytes**でv1.1.1へ再掲し、旧Releaseを不変で残す。
以下は将来の公開先であり、公開済みとは扱わない。
pinは[最終QA](../qa/release-final-2026-11-05.json)・
[公開後QA](../qa/post-publish-2026-11-05.json)から確認した。
段階Aで大容量本体の再取得はしていない。

| basename | bytes | SHA-256 |
|---|---:|---|
| mcicon-guest-1.1.0-macos-aarch64.qcow2 | 620224512 | 1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55 |
| mcicon-renderer-1.1.0-linux-amd64.tar.gz | 593385993 | dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4 |
| mcicon-sources-1.1.0-part-01.tar | 1341122560 | 6e2e101c9700ac5b09571f9fefd0386872f8f06392645355feedbe05e2546133 |
| mcicon-sources-1.1.0-part-02.tar | 1384468480 | cf984cad246b1572e04cf0ed013b61343772e698016dcad59f88885d28344f51 |

- https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.1/mcicon-guest-1.1.0-macos-aarch64.qcow2
- https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.1/mcicon-renderer-1.1.0-linux-amd64.tar.gz
- https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.1/mcicon-sources-1.1.0-part-01.tar
- https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.1/mcicon-sources-1.1.0-part-02.tar

renderer image IDは sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc、
Java 8/17/21/25を維持する。trustは既存96 bytes、
SHA-256=8529ace9f49e4b46c915e51d0cad5b576e6a9c536afe665ec779dd279ed51283 を再利用する。
helper2本・Windows guestは独立2runで再生成し、新baselineを採用する。
旧1.1.0 hash一致を要求しない。agentも再生成後の実hashで同一性を判断する。
descriptor/署名・core/worker・payload・stable/署名・bootstrapは再生成する。
URLをv1.1.1へ変えるinventoryも再生成し、旧inventoryの同bytes再利用とは区別する。

## 生成器契約とB/Cの開始条件

assemble-release-candidate.pyはJSON計画のversion/tagを使用する。
artifactのbasenameは省略時に版・role・hostから生成する。
再利用時は旧idとbasenameを明示する。urlは個別指定可、省略時は指定tagのRelease URL。
basenameは id + 拡張子 と一致させる。
各rowのruns 2本とbaselineのsize/SHA照合、閉包flag・未採用flagのgateは維持する。
再利用物は元QAの2run証拠＋公開再取得hashを結合し、今回の再buildとは呼ばない。
sourceArchivesのURL/SHAは入力を保持するため、全5資材の計画で再掲先を明示する。

inventoryのRelease basenameは <artifact-id>.json、candidate内は inventories/<artifact-id>.json、
core内は META-INF/mcicon/runtime/inventories/<artifact-id>.json。
共有rendererだけ inventories/<host>/<renderer-id>.json へ同bytesを複製する。

    python scripts/assemble-release-candidate.py --plan build/111/plan.json --output build/111/candidate
    python scripts/prepare-release-payloads.py --version 1.1.1 --tag v1.1.1 --runtime-input build/111/final-input --cli-lib cli/build/install/mcicon/lib --worker worker/build/libs/worker-1.1.1-agent.jar --core core/build/libs/core-1.1.1.jar --resources core/build/generated/resources/managedRuntime --sequence 2 --output build/111/payloads

B/Cは上記起点と段階A差分の確定commitをメインから受領して開始する。
段階Aはcommit禁止のため、差分のcommitはメイン側で確定する。

B（Mac）は[固定入力と手順](mac-helper-candidate-1.1.md)を再利用。
macOS15.8.1 arm64、JDK21.0.13+9、Python3.12.16、既存Apple toolchain、
専用root /Users/ryuman/tmp-mcicon-111。各runで出力を分ける。

    ./gradlew :helper-macos:installDist :helper-macos:test -PreleaseVersion=1.1.1 --offline --no-daemon --rerun-tasks
    python3 helpers/macos/scripts/package-helper.py --jdk "$JDK21" --input helpers/macos/build/install/helper-macos/lib --payload "$PAYLOAD" --native helpers/macos/src/main/native --app-version 1.1.1 --output "$RUN_OUTPUT"

既存prepare-payload.pyで準備したpayloadを使い、verify-packaging.pyで包装する。
tree/mode/ZIPの2run一致、CFBundle両値1.1.1、codesign deep strict、
qcow2公開再取得hashとsource/notice対応を確認する。

C（Windows）は[helper固定入力](windows-helper-candidate-1.1.md)・
[rootfs手順](windows-guest-rootfs-1.1.md)を再利用。
Temurin21.0.12.1+1、LLVM-MinGW20261006/LLVM23.1.3、Python3.12.14/zlib1.3.2を固定。
段階A単体試験用Python3.12.15を資材再現buildの代替入力にしない。
rootfsは固定Ubuntu digest/snapshot/packages.lock、SOURCE_DATE_EPOCH=1793664000、
同じPython/lzma条件で生成する。各runでhelper出力を分ける。

    .\gradlew.bat :helper-windows:installDist :helper-windows:test :helper-common:test -PreleaseVersion=1.1.1 --offline --no-daemon --rerun-tasks
    python helpers/windows/rootfs/build.py run111-a
    python helpers/windows/rootfs/build.py run111-b
    python helpers/windows/rootfs/build.py --compare run111-a run111-b --output build/111/rootfs-compare.json
    python helpers/windows/scripts/package-helper.py --jdk "$JDK21" --input helpers/windows/build/install/helper-windows/lib --payload "$PAYLOAD" --native "$NATIVE/windows-host.exe" --native-build "$NATIVE/build.json" --native-source helpers/windows/src/main/native/windows-host.cpp --app-version 1.1.1 --output "$RUN_OUTPUT"

Gradle :helper-windows:appImageも版をpackagerへ渡す。
helper support内bridge・rootfs内bridge・guestBase.bridgeSha256は実収録bytesで一致させる。
再配布6文書等の欠落、Git blob LFとcheckout CRLFの取り違えを防ぐ。

## source・検証gate・公開範囲

source2本は第三者source1580件と旧artifact別notice対応を保持する。
新inventoryで旧notice path対応・依存不変・本プロジェクトの変更sourceに対応する
最終commit/v1.1.1 tagを記録する。起点commitを変更sourceの最終commitと取り違えない。
notice本文・依存・対応が変われば該当manifest/bundleを再生成し、追加sourceを公開する。
旧bundleが新helper修正sourceまで含むとは表示しない。

後続gate:

- 変更3資材の2run一致、再利用4資材の公開再取得size/SHA、source全member・license/notice閉包。
- 最終descriptorへのユーザーEd25519署名・共通trust検証、core/worker/CLI/resource/payload同版・同bytes、
  stable最終署名・seed正式Java検証。署名後に元bytesを再生成しない。
- 両OS固定51版×2、stone64x64、PNG CRC/RGBA/可視画素/反復hash・既存基準・Java選択の全再受入。
- 両OS空cache/再利用/offline、代表1.14.4/1.21.4/26.3、初回取得取消し・実worker running後の
  CLI単独終了→同一コマンド再開・PNG、旧資源回収と他job拒否。Ctrl+Cは別に確認する。
- 健全1.1.0 rootと詰まりrootを分け、background/pending/次回managed適用、active=1.1.1、
  旧job世代維持、無効/offline/pin不適用、署名改変・旧sequence拒否を確認する。
- 関連Python/Gradle試験・重点反証。既知失敗を成功扱いせず、修正は別範囲で判断する。
- 公開後は空Gradle cacheのmodule/POM consumer・候補core/worker一致、
  Central全10成果物+10.asc再取得/gpgv、両OS全Release asset（source/notice含む）と
  stable/descriptor/trust/runtime/payloadの再取得・署名/hash、公開bootstrap実renderを確認する。
  consumer既定成功はraw/worker/API契約と正式runtime resourceの存在・pin/size一致。
  --managed-runtime省略時はmanaged API未実施とし、署名暗号検証・実renderの代用にしない。
- 未改変1.1.0の固定latest URLによる更新は公開後の最終確認とする。
  公開前の更新fixture・署名候補試験配信とは区別する。

公開範囲はCentral core/worker各1.1.1、unsigned bootstrap archiveを含む全25資材相当の
v1.1.1 Release/Latest。追加sourceが必要なら増やす。旧1.1.0を差し替えない。
installer/winget/Homebrew、Developer ID/notarization/Authenticodeは追加しない。
未準備host・実reboot・MOTW/quarantineの既存未検証を保証へ変えない。
GUI・ユーザー介入試験は内容を提示して別途許可を得る。
tag/Central/Release/Latest実公開は最終候補一覧と手順を示して別途承認を得る。
段階Aではcommit/push/PR/Issue操作も行わない。

## 旧lease利用者向け案内草案（実機手順は未検証）

1.1.1はdescriptor SHA/toolVersionの異なる1.1.0 job leaseや旧VM/WSL世代を自動救済しない。
新版で別世代の描画が成功しても旧資源の解消とは区別する。

1. 追加取消し・並行起動を止め、lease/descriptor/receipt/owner/logとruntime/data rootを保全する。
2. 別program directoryへ1.1.1を導入する。unknown等で更新が止まる場合は新しい専用
   MCICON_DATA_HOMEと別outputで回避を試す。元raw/PNGを削除せずstate/receiptを手編集しない。
3. 旧WSLのGUID/名前/BasePath/owner/descriptor・本人process終端、Macの専用LIMA_HOME/
   instance/owner・他job不使用を照合して対象世代だけ停止・回収する。
   データを失うunregister等は個別確認する。一括削除、wsl --shutdown、OS保護解除を案内しない。
4. 別root回避・旧世代回収は両OSで未検証。具体コマンドは実機で所有証跡を確認して確定し、
   万能復旧手順として公開しない。

## 段階Aの検証結果（2026-11-06）

関連Python23件成功（Python3.12.15/UTF-8モード）。
JDK21でconsumer Javaをcompileし、保全済み正式1.1.0 JARで成功、
runtime resource欠落で拒否。空Central cache・実managed API受入は未実施。
Gradle helper-windowsは55件中34成功・21skip。
coreは170件中3失敗・28skip（InstallationImportTest、WorkerArtifactTest、RuntimeLeaseTest）。
helper-commonは33件中DockerRequestTest1失敗で、POSIX固定bindをWindowsの絶対path判定が拒否した。
これらの製品・test本体は起点から変更していない。
SmokeTestは固定worktree条件で4件中1error。
native一括は46条件成功後、observation-fixtureのmisleading-indentation警告を-Werrorが拒否した。
以前失敗したWSL fixtureは個別compile/実行で39条件成功し、今回その失敗は再現しなかった。
全体成功とは扱わず、範囲外修正はしていない。
詳細・元ログ・公開用文面は担当worktreeのbuild/へ保全する。

## 段階Dの署名前候補（2026-11-06）

起点main 495803c（#177を含む）、task-id=milestone-111-d、
branch=feature/issue-99-descriptor-111。単一書込ownerは段階Dのcomplex_worker。
C未mergeのためb7079f7のbuild/111-cを入力に採用した。
詳細は[段階D QA](../qa/descriptor-candidate-1.1.1-2026-11-06.json)を参照。

再利用4資材のsize/SHAとsource全1580 member・notice全2023 memberを照合。
変更3資材の独立2runと新baselineを結合し、全5 inventoryをv1.1.1 URLで再生成した。
renderer/qcow2の本体・旧ID・basenameは保持し、inventoryの同bytes再利用とは扱わない。
旧notice pathをsource bundle内の有効な参照として維持し、helper内の実pathと本文hashで対応する。
source2本は第三者依存用で、新プロジェクト修正sourceを含むとは表示しない。
build元commitはc7b507a、公開先v1.1.1 tagは将来値として記録し、公開前に最終commitとの関係を確認する。

C候補はE1基準のため、R2最終版のMicrosoft再配布文書6件が欠落していた。
DではDLL同一性を確認し、両runのコピーへ旧正式6文書を追加した。
C原本と既存圧縮entryのbytesは不変、派生2runのsize/SHA一致を確認した。
採用baselineは段階D QAのwindowsNoticeSupplementとartifactsを使用する。
source bundleのnotice本文と同bytesなのでsource2本の再生成・追加は不要だった。

descriptor2本はruntimeId/toolVersion/worker.toolVersion=1.1.1。
既存Gradleのagent単体生成と正式verifyRuntimeCandidate、生成器Python試験に成功。
署名対象は担当worktreeのbuild/111-d/to-sign/、手順はbuild/111-d/SIGN.md、
Mac転送キットはbuild/111-d/descriptor-signing-kit-1.1.1.zipと.sha256。
署名/trustは未検証。ユーザー署名の前で停止し、最終resource/core/worker wrapper/CLIは未生成。
commit/push/PR/Issue操作は行っていない。

## 段階D署名結合・段階Eの固定候補（2026-11-06）

返送descriptor署名2本と原本size/SHAを照合し、運用DER成功・回復DER失敗を確認した。
正式RuntimeResourceGenerator/TrustSetで検証し、1.1.1のresource/core/worker wrapper/CLIを生成した。
同じ最終coreを両OS tool payloadとCentral binaryへ結合し、全entry・JRE・agent・POM/moduleの同版/同bytesを監査した。
固定coreは450366 bytes、SHA-256 `294b798fb1f975344722b80b9fc8ec484b408966d8693b0c6d42eb788eeaa0ba`。
全size/SHA・Central全10署名対象・ログは[段階E QA](../qa/release-payload-candidate-1.1.1-2026-11-06.json)を参照する。

stableはtoolVersion=1.1.1、sequence=2候補、minimumBootstrapProtocol=4。
公開直前にlatest/high-waterを再照合する。署名済み元bytesは再生成しない。
ユーザー署名用ZIPと.sha256、手順書はbuild/111-e/の
stable-signing-kit-1.1.1.zip / SIGN-STABLE-ON-MAC.mdと
mcicon-central-1.1.1-signing-kit.zip / SIGN-CENTRAL-ON-MAC.md。
stableのsign.shはHomebrew OpenSSL 3を優先し、3.x未満/LibreSSLで停止する。
Centralは元50file固定・PGP署名10件のMacキット方式を継承する。

最終ビルド・Python payload試験3件・候補bytes監査・署名キット往復照合・CLI版表示は成功。
関連Java試験は30件中29成功、WorkerArtifactTestのflattened JAR試験がアサーション後の
Windows一時combined.jar削除で1失敗した。既知失敗を保全し、全体成功とは扱わない。
stable/PGP署名と署名必須監査は未実施。次は署名返送・Fのbootstrap生成、Gの両OS受入/更新E2E、
Hの全体反証、別承認後のI公開・公開後照合。commit/push/PR/Issue/公開は行っていない。

## 段階E署名結合・段階F bootstrap候補（2026-11-07）

単一書込ownerを本complex_workerへ引継ぎ、stable原本16,565 bytesとCentral元50fileのsize/SHAを再照合した。
署名だけを結合し、元descriptor/core/worker/CLI/payload/stableは再生成していない。
stable署名は64 bytes、Windowsの運用DER成功・回復DER失敗。
返却公開鍵だけのgpgvでPGP指定fingerprintの10/10を確認し、署名必須Central監査に成功した。

同一の固定coreとbootstrap JAR・resource・seedをMac専用
`/Users/ryuman/tmp-mcicon-111-f`へ転送し、両OSで正式ManagedPackageVerifierに成功した。
Windowsはowner=archiveと1.1.0と同じ固定security reader、Macは既存packagerで包装した。
readerのsourceはHEAD blobと照合したLFコピーを使用し、checkoutと固定PEは変更しない。
Macの初回包装後監査は生成Python cacheの追加を検出したが、元入力52fileのhash不変を再確認した。
codesign deep strict成功はDeveloper ID/notarizationの受入を意味しない。

両archiveの全fileと内包core/worker/CLI/payload/stable/trust・runtime resourceを固定bytesへ照合した。
全25 Release asset（再掲4本を含む）の将来URL・size/SHAと29参照を固定。
一覧は担当worktreeの`build/111-f/release-assets.json`と`release-assets.md`、
署名入りstable検査用ZIP・Central ZIP・両OS archiveのsize/SHAは
[段階E/F QA](../qa/release-payload-candidate-1.1.1-2026-11-06.json)の`stageEPostSignatureAndF`を参照する。

正式候補コピーのstable原文・署名・payload改変を拒否し、関連試験の旧sequence/同版拒否に成功した。
包装/reader/payloadのPython試験は計19成功。
既知coreは27件中24成功・3失敗（InstallationImportTest、WorkerArtifactTest、RuntimeLeaseTest）。
SmokeTestは固定worktree条件により4件中3成功・1errorを再確認した。
bootstrap関連試験は固定reader指定後42件中27成功・14失敗・1POSIX条件skip。
追加14失敗はWindowsPrivateFilesの他user変更権限検査で拒否され、ACL/SID詳細と実運用root影響は未判定。
全体成功・公開gate達成とは扱わず、製品・テスト本体の範囲外修正はしていない。
Mac clangのnative一括（46/36/17/39条件）とWSL fixture単体39条件は成功。
以前のWSL失敗は今回再現しなかったが、別toolchainの警告修正済みとは扱わない。

段階E署名結合とF作成・監査は完了。Gの実機受入/更新E2E、Hの失敗判断・全体反証、I公開は未実施。
sequence=2は公開直前のlatest/high-water再照合が必要な候補のまま。
commit/push/PR/Issue/tag/Release/Central uploadは行っていない。起動プロセスは終了し、成果物を保全した。

## Lxss修正取込みによる再生成（2026-11-07、r2）

PR #179を含むmain `dc3417992bee714e1e5d48c9d42870a9afab2c62` を `git merge origin/main` で衝突なく取り込んだ。
未コミットのD/E/F記録は保全。新出力は `build/111-c2/` と `build/111-d2/` に分離した。
Windows helperだけを前回と同じ固定JDK/Python/LLVM-MinGW、app-version=1.1.1で独立2run生成した。
原ZIPは 36030278 bytes / `65d46f68ab546171ee75e34885fc215ffdc3ff641649ececaa90cd1a066586c1`、
Microsoft再配布6文書補完後は 36167923 bytes /
`a767728f0c8601c3eb0f653aa7d7d64827d47243c6bfbf453626344349caea4d`。どちらも2run完全一致、既存圧縮entryも保持した。
guest rootfs・Mac helper・renderer・qcow2は再生成せず、前回候補とのsize/SHA一致を確認した。
第三者source全1580 member・notice全2023 memberを再照合し、全5 inventoryを生成した。
未変更4 inventoryとMac descriptorは前回のbytesに一致し、新source commitはWindows helperに記録した。

Windows descriptorは 278044 bytes / `d526883cdacc81ff0119af5bf215afdd23ef6e0f4deaa9dd7bd7c07b8f51ba41` で再署名対象。
Mac descriptorは374723 bytes / `23757eae57959c789d1baa3cbd37a23592ed8c508d6b030ea740caf7ba1cc857` のままで、
既存署名 `8bf72da3cfa5733902a13bacb2355929a2ef3e4d3c4273ba434e109b2dbe521e` を再利用する。
Mac既存署名は運用DERで成功・回復DERで想定どおり失敗を確認した。
署名キットは `build/111-d2/descriptor-signing-kit-1.1.1-r2.zip`（19725 bytes、SHA-256 `3fc3c9b2018312ddf8f0a1ef9c68e7fc87e8c94402604c4cc4dd6d252eeb4ec3`）。
Windows descriptorだけを含み、隣接 `.sha256`、`SHA256SUMS`、手順 `build/111-d2/SIGN.md` を用意した。
sign.shはHomebrew OpenSSL 3を優先、3未満で停止し、両公開DER検証・返送ZIP作成を行う。

同梱nativeはCodex書込root外の新規専用dirでfacts試験に成功（40 WSL登録一致、不完全キー1件除外）。
Lxss全レジストリ値・WSL・Dockerは作業開始前後も一致し、専用dirは回収済み。
helper-windowsは55件中34成功/21skip、helper-commonは33件成功。正式Java候補検証と関連Python試験は成功。
coreは既知3クラス27件中3失敗、Smokeは4件中固定pathによる1errorを再確認した。
native fixtureは46回収・36観測・17起動path条件成功後、WSL fixtureの未使用変数警告で-Werror失敗。
guestbridge Python試験はWindowsにfcntlがなく起動失敗し、Linux試験は今回未実施。全体成功とは扱わない。
詳しい試験結果と採用値は[Windows QA](../qa/windows-helper-candidate-1.1.1-2026-11-06.json)と
[descriptor QA](../qa/descriptor-candidate-1.1.1-2026-11-06.json)のr2記録を参照する。

**現在はWindows descriptorのユーザー署名前で停止中。** 旧 `build/111-d`〜`111-f` は旧候補の証拠として保全し、
その署名済みcore/payload/stable/Central/bootstrap・受入結果をLxss修正版の公開候補へ流用しない。
署名返送後に共通trust検証、最終resource/core/worker/CLI、両OS payload・stable/Central・bootstrapの
再生成と再署名、新候補での両OS受入・全体反証が必要。commit/push/PR/Issue/公開操作は行っていない。

## r2段階D署名結合・段階E固定候補（2026-11-07）

返送Windows署名ZIPのSHA-256 `f9593b31780b9245ebbc8a628c706ee83424310e1799e24681f70db23384110d`、
descriptor原本2本のsize/SHA、Windows新署名とMac既存署名64 bytesを再照合した。
両descriptorで運用DER成功・回復DER失敗を確認し、正式TrustSet/RuntimeResourceGeneratorで検証した。
最終resource/core/worker wrapper/CLIを1.1.1で生成し、同じ最終coreから両OS payload・stable・Central候補を固定した。
coreは450316 bytes、SHA-256 `93595123b8b6127c4368e2c4f5f286e9515b3b0dfff6c014fa1b07573a32c980`。
全size/SHAとCentral署名対象10件は[段階E QA](../qa/release-payload-candidate-1.1.1-2026-11-06.json)と
`build/111-e2/MEASUREMENTS.md`・`SHA256SUMS` を参照する。

両payload全entry/inventory、JRE bytes/mode、runtime resource集合、CLI/lib・distribution、agent、
Central binary/sources/javadoc/POM/moduleの同版・同bytes監査は成功。旧build/111-e・111-fは保全した。
関連Python試験9件成功、Javaは30件中29成功でWorkerArtifactTestのWindows一時JAR削除1件が既知失敗。
前段C2の他の既知失敗は今回再実行せず、前段記録を維持する。全体成功・公開gate達成とは扱わない。

stableはtoolVersion=1.1.1、sequence=2候補、minimumBootstrapProtocol=4。
公開直前のlatest/high-water再照合が必要。両署名キットはbuild/111-e2/に作成した:

- `stable-signing-kit-1.1.1-r2.zip`、SHA-256 `5fab1ed2767c95847f3402081a2b12b9e62ec4e32c121b0fcae879646b6df842`。
- `mcicon-central-1.1.1-r2-signing-kit.zip`、SHA-256 `bc2529d971f2e093f206376df11e840aecf20238498c6d6936873d87ec28f66d`。

各隣接.sha256、SIGN-STABLE-ON-MAC.md、SIGN-CENTRAL-ON-MAC.mdを用意した。
転送は調整役が行い、Mac展開先は `/Users/ryuman/tmp-mcicon-111-sign/stable-r2/kit` と
`/Users/ryuman/tmp-mcicon-111-sign/central-r2/kit`。stableはHomebrew OpenSSL 3を優先し3未満で停止、
返送ZIPは `stable-signatures-1.1.1-r2.zip`。Centralは従来のsign-central.sh/return-central.sh方式で、
返送ZIPは `mcicon-central-1.1.1-r2-signatures.zip`。
**現在はstable/Central署名前で停止中。** 署名返送後の共通Java/gpgv・署名必須監査、F再生成、
両OS受入・全体反証が残る。commit/push/PR/公開・Mac転送は行わず、秘密鍵・tokenは参照していない。

## r2署名結合・段階F bootstrap候補（2026-11-07）

元stable 16565 bytesとCentral元50fileのsize/SHAを再照合し、.sig/.ascだけを結合した。
stable署名64 bytes、運用DER成功・回復DER失敗、返却公開鍵だけのgpgv 10/10、署名必須Central監査に成功。
両OSで正式ManagedPackageVerifierを通し、同じ最終core/bootstrap JAR/resource/seedからarchiveを生成した。
host別JAR再buildは行わず、全fileとcore/worker/CLI/payload/stable/trustのE2固定hash一致を確認した。
全25 Release assetと29参照の将来URL・size/SHAを `build/111-f2/release-assets.json` / `.md` へ固定した。
署名入りstable/Central・両archiveの値と試験原記録は[段階E/F QA](../qa/release-payload-candidate-1.1.1-2026-11-06.json)の
`stageEPostSignatureAndF2` と `build/111-f2/HANDOFF.md` を参照する。旧build/111-fは保全した。

固定reader試験はCodex書込root外の新規C:/Users/ryuma配下へTMP・作業先・Gradle出力・reader stageを分離し、
require_escalated + run_logged.pyで実行した。42件中失敗1・error0・skip1。
失敗method再確認は1件、失敗0・error0。 初回の期限超過を保全し、原因を負荷と断定しない。従来のACL拒否14件とは区別する。
UpdatePreparationは19件中失敗0・error0・skip0。
旧sequence/同版拒否、候補stable原文・署名・payload改変拒否は成功。
包装/reader/payload Python19件、Mac native46/36/17/39条件とWSL単体39条件は成功。
coreは既知27件中24成功・3失敗、Smokeは固定worktree条件による4件中1errorを再確認した。
全体成功・公開gate通過とは扱わない。外側専用試験rootはXML/log回収後に削除した。

Mac作業は `/Users/ryuman/tmp-mcicon-111-f2` のみ、sudo・既存Lima/Homebrew/サービス操作なし。
観測したLima設定・既存ディレクトリ・Homebrew導入一覧は不変。
関連プロセス一覧には並行する別担当tmp-mcicon-111-g配下のlimactl/Virtualization起動が現れたため、一覧不変は主張しない。
他担当processを停止せず、before/afterと差分を保全した。Mac受入担当の成果物は変更していない。
**r2署名結合とF archive生成・監査まで完了、段階Gへは進めていない。**
G両OS受入・H全体反証・公開前high-water/source/tag照合・別承認後の公開/再取得が残る。
commit/push/PR/tag/Release/Central uploadは実行せず、秘密鍵・tokenは参照していない。

## rootfs unpackedBytesの契約修正・r3署名前（2026-11-07）

Windows最終受入で、guest-base上限398,954,693 bytesがtar内通常file総量であり、
WindowsRuntime.rootfsが制限する解凍後tar stream全長403,507,200 bytesを4,552,507 bytes下回る誤りを確認した。
1.1.0公開descriptorと同じtar全長へ復元し、build/111-d3/に候補を再生成した。
ZIPはentry内容総和、qcow2は無展開file長、rendererは従来のimage load予算を維持する。
runtime5資材とr2 tool payload2本の監査表は `build/111-d3/UNPACKED-AUDIT.md`、
全field差分は `field-differences-110.json` / `FIELD-AUDIT.md` に保存した。
schema/protocol/host/OS下限/rendering/adapter契約に意味の変更はなく、Mac license追加3参照の実entryも確認した。
rendererのlayer通常file内容総量1,430,895,743と予算2,056,219,398は別概念で、今回Docker load/inspectは実行しない。
tool payloadの監査値は旧r2のものであり、r3版の確定値ではない。

r2→r3のdescriptor差分はWindows guest-base.unpackedBytesの1値だけ。全5 inventory・配布archiveは同bytes。
Windowsは278044 bytes / `80b4aafb2afa29d6734105a66cb9e693b6f1b2306b6dfb6753c99afc1c35ef68` で再署名対象。
Macは374723 bytes / `23757eae57959c789d1baa3cbd37a23592ed8c508d6b030ea740caf7ba1cc857` のまま、既存署名 `8bf72da3cfa5733902a13bacb2355929a2ef3e4d3c4273ba434e109b2dbe521e` を再利用する。
Windowsのみの署名キットは `build/111-d3/descriptor-signing-kit-1.1.1-r3.zip`、
19796 bytes / `ba71ff212b949157bf050ba780c3cdf8b573cbf1aad6fd9b06154cf670936dde`。隣接.sha256・SHA256SUMS・sign.sh・SIGN.mdを用意し、
返送名はdescriptor-signatures-1.1.1-r3.zip。OpenSSL 3要件・両DER検証は前回と同じ。

build/111-d3/verify-budgets.pyでtar.xz上限と解凍tar全長、ZIP上限とentry総和の一致を機械検査し、
旧r2は拒否・r3は通過した。正式Java候補検証・Python候補試験6件も成功。
製品rootfsメソッドを無変更で試験器へ取り出し、constructor/native/WSLを呼ばず、commands時計だけをstubした。
旧上限拒否、修正上限で403,507,200 bytes・tar SHA一致、1 byte不足拒否の3境界を確認した。
Java実行は成功したがCP932 stdoutの読取だけが失敗したため、原ログを再decodeして回収し、将来実行はUTF-8を明示した。
本タスクでは実WSL登録・実helper受入・G再受入は行わず、既知core/Smoke失敗は前段記録を維持して再実行していない。

**現在はWindows descriptor再署名前で停止。** build/111-d2・e2・f2は証拠として変更せず、
旧r2 core/payload/stable/Central/bootstrapをr3の公開候補へ流用しない。
署名返送後に共通trust検証・E/F再生成/必要な再署名、新規rootの製品受入と全体反証が必要。
ソース側への提案はassemble-release-candidate.pyとRuntimeCandidateVerifierにも同じ上限検査を追加すること。
今回は指定どおりソースを変更せずbuild内の監査に限定した。commit/push/PR/公開操作なし。

## r3署名結合・段階E固定候補（2026-11-07）

返送Windows署名ZIP `164c0d238f9ec7e24ba1acdf636e05deb783107bc56a4876d8b4e546a7440f1f` と
原descriptor2本のsize/SHAを照合し、新Windows署名・Mac既存署名の両DER検証に成功した（運用成功・回復失敗）。
正式TrustSet/RuntimeResourceGeneratorを通し、最終resource/core/worker/CLIを1.1.1で生成・固定した。
coreは450316 bytes / `77a42d1a99ee0a65b6ed0cd08623742c3d05bce0e72576d8537be2078a87a3a5`。
r2との差分はcoreのWindows descriptor・新署名・対応pinの3entryだけで、class/他resourceとworker/agent/CLI JARは同bytes。
同じ最終coreから両payload/stable・Central10署名対象を再生成した。Central変更対象はcore.jar/core.moduleと対応checksumだけ。
CLI distributionは新coreに加え同梱docs/QA更新も反映する。全測定値は[段階E QA](../qa/release-payload-candidate-1.1.1-2026-11-06.json)と
`build/111-e3/MEASUREMENTS.md`・`r2-differences.json`を参照する。

D3の展開量機械監査を実行し、新r3 payloadにもZIP entry総和一致を確認した。
Windows rootfs上限はtar全長403,507,200 bytes、payloadはWindows93,934,911 / Mac97,032,250 bytesで宣言と実測が一致。
両payload全entry/JRE/resource/CLI/lib/agent、Central source/javadoc/POM/module、キット往復/checksum/構文の監査に成功。
Python9件成功、Javaは30件中29成功・既知WorkerArtifactTestのWindows一時JAR削除1失敗を保全した。
今回未再実行のcore他2件/Smoke等は旧証拠を維持し、全体成功とは扱わない。

stableはtoolVersion=1.1.1、sequence=2候補、minimumBootstrapProtocol=4で公開直前latest/high-water再照合が必要。
キットはbuild/111-e3/の `stable-signing-kit-1.1.1-r3.zip`（SHA `43fdd06cb399ddf2ece52dd92488a9be99b0bcdcdab766c4e1a7823bf42c4655`）と
`mcicon-central-1.1.1-r3-signing-kit.zip`（SHA `7fa73ebaa755922eb918f6879c05353e66b19f0dbe1ebb7685d11f84c792c78e`）。隣接.sha256と署名手順を用意した。
Mac展開先は `/Users/ryuman/tmp-mcicon-111-sign/stable-r3/kit` と `central-r3/kit`。
返送名はstable-signatures-1.1.1-r3.zipとmcicon-central-1.1.1-r3-signatures.zip。
**stable/Central署名前で停止。** 転送・実署名・公開は行わず、旧出力とMac受入担当領域を変更していない。
次は署名返送後の原本/trust/gpgv監査、F3 bootstrap再生成、新規rootでの製品受入・全体反証。

## r3署名結合・段階F bootstrap候補（2026-11-07）

元stable 16565 bytesとCentral元50fileのsize/SHAを再照合し、.sig/.ascだけを結合した。
stable署名64 bytes、運用DER成功・回復DER失敗、返却公開鍵だけのgpgv 10/10、署名必須Central監査に成功。
両OSで正式ManagedPackageVerifierを通し、同じ最終core/bootstrap JAR/resource/seedからarchiveを生成した。
host別JAR再buildは行わず、全fileとcore/worker/CLI/payload/stable/trustのE2固定hash一致を確認した。
D3のtar.xz/ZIP機械検査を全25asset監査に統合し、宣言あり7資材・bootstrap manifest・宣言なしassetを区別して検査した。
全25 Release assetと29参照の将来URL・size/SHAを `build/111-f3/release-assets.json` / `.md` へ固定した。
署名入りstable/Central・両archiveの値と試験原記録は[段階E/F QA](../qa/release-payload-candidate-1.1.1-2026-11-06.json)の
`stageEPostSignatureAndF3` と `build/111-f3/HANDOFF.md` を参照する。旧build/111-fとbuild/111-f2は保全した。

固定reader試験はCodex書込root外の新規C:/Users/ryuma配下へTMP・作業先・Gradle出力・reader stageを分離し、
require_escalated + run_logged.pyで実行した。42件中失敗0・error0・skip1。
初回失敗なしのため再確認は対象なし。 今回の初回結果を保全し、前回F2の期限超過や従来のACL拒否14件と混同しない。
UpdatePreparationは19件中失敗0・error0・skip0。
旧sequence/同版拒否、候補stable原文・署名・payload改変拒否は成功。
包装/reader/payload Python19件、Mac native46/36/17/39条件とWSL単体39条件は成功。
coreは既知27件中24成功・3失敗、Smokeは固定worktree条件による4件中1errorを再確認した。
全体成功・公開gate通過とは扱わない。外側専用試験rootはXML/log回収後に削除した。

Mac作業は `/Users/ryuman/tmp-mcicon-111-f3` のみ、sudo・既存Lima/Homebrew/サービス操作なし。
観測したLima設定・既存ディレクトリ・Homebrew導入一覧は不変。
関連プロセス一覧の今回比較は一致=True。
他担当processを停止せず、before/afterと差分を保全した。Mac受入担当の成果物は変更していない。
**r3署名結合とF archive生成・監査まで完了、段階Gへは進めていない。**
G両OS受入・H全体反証・公開前high-water/source/tag照合・別承認後の公開/再取得が残る。
commit/push/PR/tag/Release/Central uploadは実行せず、秘密鍵・tokenは参照していない。

## 期限修正#180取込みによる再生成・r4署名前（2026-11-07）

mainの793c55c0bfb0a548a3d9be414609572b89271d75をmergeし、既存未commit変更を保全した。
固定Temurin21.0.12.1+1・LLVM-MinGW20261006・Python3.12.14/zlib1.3.2・app-version1.1.1でWindows helperを独立2run生成した。
helper ZIPとMicrosoft再配布6文書補完後の派生ZIPはそれぞれbytes一致。
guestbridge.pyはhelperとrootfsに封入されるため、rootfsも前回と同じDocker固定snapshot/正規化/XZ手順で独立2runしbytes一致。
旧rootfsからのmember差分はguestbridge.pyだけで、package一覧・engine・cpu-probe・licenseは不変。
helper/rootfsのguestbridgeはcheckoutのexact bytesに一致する。

| 資材 | bytes | SHA-256 |
|---|---:|---|
| helper原ZIP | 36031091 | `969610df1ebe884d5b4e4b2829617c8cbfd75fe84a193c20e6ee0dbefecc25e7` |
| Microsoft6文書補完ZIP | 36168736 | `5059919a7b71c57a69421a8ec02ff566bdd8bae7a71c065ed2e77646c62a4c5d` |
| rootfs | 98147124 | `2fbfe4b70703a904f3da4d6108bc4ba34bfc4bb8a804706cd565319f9dd7d316` |
| windows-x86_64.json | 278044 | `08a33241bf7de06373045cabd46385f44a1499adb0bd53ca669e6e2220404e0a` |
| macos-aarch64.json | 374723 | `23757eae57959c789d1baa3cbd37a23592ed8c508d6b030ea740caf7ba1cc857` |
| r4署名キット | 20053 | `5fd28a3a4d707535144f413253f1331d8436f81d53ac6211c47d66bf8eb6106d` |

Mac helper・qcow2・rendererは再生成せずbytes不変を照合した。Mac接続は行っていない。
Mac descriptorは前回bytesと一致し、既存署名8bf72da3…521eを運用DER成功・回復DER想定失敗で検証した。
Windows descriptorだけを再署名する。runtime5資材はD3機械検査を適用し、rootfs上限は解凍後tar全長403507200を採用した。
ZIPはentry内容総和、qcow2は実長、rendererは1.1.0公開image load予算2056219398を維持する。
source bundle2本のsource1580件・notice2023件を照合し、#180の変更sourceはGit commit/checkout/LF blobへ結び付けた。

Java試験はCodex書込root外の新規C:/Users/ryuma配下でbuild/TMPを隔離し、require_escalated+run_logged.pyで実行した。
- helpers: {'tests': 92, 'failures': 2, 'errors': 0, 'skipped': 22}（exit 1）
- core: {'tests': 68, 'failures': 3, 'errors': 0, 'skipped': 10}（exit 1）
- 再検証helpers: {'tests': 92, 'failures': 1, 'errors': 0, 'skipped': 22}（exit 1）
初回helperの1件はJAVA_TOOL_OPTIONSの起動通知が子出力へ混入した試験環境起因。初回keeper期限試験TIMEOUTも保全し、環境変数を除いた再検証と区別する。
core既知3失敗とSmoke固定pathの4件中1errorは今回も再現し、成功扱いしない。RuntimeHelperTestのPOSIX専用10件はWindowsでskip。
Windows Pythonのguestbridgeはfcntl未対応で失敗、Linux側guestbridge/daemon/rootfs生成器試験は成功。
正式Java候補検証・候補Python・全runtime予算検査は成功。native factsは同梱PEを外部rootで実行し環境不変を確認した。
専用外部試験dirを回収し、自分のDockerタグだけを削除、Dockerを元の停止状態へ戻した。設定・レジストリ・WSL/Dockerの前後比較はenvironment-comparison.jsonに記録。

署名キット/手順はbuild/111-d4/に保存。返送名descriptor-signatures-1.1.1-r4.zip。
Mac展開想定は/Users/ryuman/tmp-mcicon-111-sign/descriptor-r4/kit。Homebrew OpenSSL3優先・3未満停止・両DER検証・返送ZIP生成は従来どおり。
**現在はr4 Windows descriptor署名前で停止。旧D3/E3/F3は#180未取込みの証跡であり現候補へ流用しない。**
署名返送後のE/F再生成と必要な再署名、製品E2E再受入・全体反証・公開前high-water/source/tag照合が残る。
本作業では製品ソースの追加変更・Mac接続・G受入・commit/push/PR/公開・秘密鍵/token参照は行っていない。

## r4署名結合・段階E固定候補（2026-11-07）

返送Windows署名ZIP73676027…bdb64とdescriptor原本2本を再照合し、運用DER成功・回復DER想定失敗を確認した。
共通TrustSet/RuntimeResourceGeneratorから最終resource/core/worker/CLIを生成し、同じ最終coreから両payload・stable/Central候補を固定した。
coreは451556 bytes / `93393d1c367700549f2e055c499487c7e9b6e69fa32a54169fc2dc705ea1090b`。全測定値はbuild/111-e4/MEASUREMENTS.mdとSHA256SUMS。
r3/f3からcoreの差はRuntimeHelper.classとWindows runtime情報5entry、Centralはcore binary/sources/moduleと各checksum。
worker/agent/CLI JARとMac descriptor/署名は不変。CLI ZIPはビルド時点のdocs/QAと新coreを反映する。
runtime5資材と新payload2本へD4のunpackedBytes検査を適用し通過。rootfsはtar全長403507200、ZIPはentry総和、rendererは旧公開image load予算を維持する。

#181（9d1925524043cc80062cae404b5f85d791a75485）はPrepareIntegrationTestのfixture初期化だけを修正するためmergeした。
製品資材・D4 descriptorは変わらず、keeper期限試験1件失敗は解消。外部rootのhelper/commonは92件中70成功・22skip・失敗0。
keeperの6秒3試行は3677/3582/3595ms、2.5秒は879msで期待拒否。製品期限を緩めていない。
coreは68件中55成功・既知3失敗・10skip。Smoke固定path1error、Windows guestbridge fcntl未対応も再確認し成功扱いしない。
製品Java試験はCodex書込root外の新規C:/Users/ryuma配下へbuild/TMPを分離し、require_escalated+run_logged.pyで実行・証跡回収後に専用dirを削除した。
旧C3失敗と署名当時inventoryは履歴として保持し、D4の現行SIGN.md/HANDOFFとQAには#181解消を明記した。

stableはtoolVersion1.1.1・sequence2候補・minimumBootstrapProtocol4。公開直前のlatest/high-water再照合が必要。
キットはbuild/111-e4/stable-signing-kit-1.1.1-r4.zip（SHA `2f5fd419ea182b3cbc4d35818d36340da250fc4424e570b64c1586d4a4cbb0de`）と
mcicon-central-1.1.1-r4-signing-kit.zip（SHA `c7a2b4257fff4aa9476b74bfb831173e1a0cfc016545766eeef80722c1620147`）。隣接.sha256と実行順の手順書を用意した。
stable手順に既存Mac運用鍵パスをED25519_PRIVATE_KEYへexportする手順、Central手順に既存GNUPGHOME・GPG_TTYとローカルpinentry前提を明記した。
Mac展開想定は/Users/ryuman/tmp-mcicon-111-sign/stable-r4/kit と central-r4/kit。
返送名はstable-signatures-1.1.1-r4.zipとmcicon-central-1.1.1-r4-signatures.zip。
**現在はstable/Central署名前で停止。** Mac接続・転送・署名・公開・秘密鍵/token参照・G作業は行っていない。
署名返送後の原本/正式Java/gpgv検証、F4生成・製品E2E再受入・全体反証・公開前照合が残る。

## r4署名結合・段階F bootstrap候補（2026-11-07）

元stable 16565 bytesとCentral元50fileのsize/SHAを再照合し、.sig/.ascだけを結合した。
stable署名64 bytes、運用DER成功・回復DER失敗、返却公開鍵だけのgpgv 10/10、署名必須Central監査に成功。
両OSで正式ManagedPackageVerifierを通し、同じ最終core/bootstrap JAR/resource/seedからarchiveを生成した。
host別JAR再buildは行わず、全fileとcore/worker/CLI/payload/stable/trustのE4固定hash一致を確認した。
D4のtar.xz/ZIP機械検査を全25asset監査に統合し、宣言あり7資材・bootstrap manifest・宣言なしassetを区別して検査した。
F3から25asset中12件を更新し13件は不変。Windows bootstrapはseed3fileのみ、Macはseed3fileとad-hoc署名/CodeResourcesを更新しMach-O署名領域外・他fileは不変。詳細はf3-differences.json。
#181のkeeper期限試験解消はE4の外部root検証を参照し、今回も解消記録を維持する。
全25 Release assetと29参照の将来URL・size/SHAを `build/111-f4/release-assets.json` / `.md` へ固定した。
署名入りstable/Central・両archiveの値と試験原記録は[段階E/F QA](../qa/release-payload-candidate-1.1.1-2026-11-06.json)の
`stageEPostSignatureAndF4` と `build/111-f4/HANDOFF.md` を参照する。旧build/111-f・111-f2・111-f3は保全した。

固定reader試験はCodex書込root外の新規C:/Users/ryuma配下へTMP・作業先・Gradle出力・reader stageを分離し、
require_escalated + run_logged.pyで実行した。42件中失敗9・error0・skip1。
失敗method再確認は9件、失敗0・error0。 今回の初回結果を保全し、前回F2の期限超過や従来のACL拒否14件と混同しない。
UpdatePreparationは19件中失敗0・error0・skip0。
旧sequence/同版拒否、候補stable原文・署名・payload改変拒否は成功。
包装/reader/payload Python19件、Mac native46/36/17/39条件とWSL単体39条件は成功。
coreは既知27件中24成功・3失敗、Smokeは固定worktree条件による4件中1errorを再確認した。
全体成功・公開gate通過とは扱わない。外側専用試験rootはXML/log回収後に削除した。

Mac作業は `/Users/ryuman/tmp-mcicon-111-f4` のみ、sudo・既存Lima/Homebrew/サービス操作なし。
観測したLima設定・既存ディレクトリ・Homebrew導入一覧は不変。
関連プロセス一覧の今回比較は一致=True。
他担当processを停止せず、before/afterと差分を保全した。Mac受入担当の成果物は変更していない。
**r4署名結合とF archive生成・監査まで完了、段階Gへは進めていない。**
G両OS受入・H全体反証・公開前high-water/source/tag照合・別承認後の公開/再取得が残る。
commit/push/PR/tag/Release/Central uploadは実行せず、秘密鍵・tokenは参照していない。

## 回復予算・ログ処理修正#183取込みによる再生成・r5署名前（2026-11-08）

mainの5c474f4151ba8f9dc932819a15cd3f3c4ee7657dをgit mergeでfast-forward取込み。既存未commit変更を保全した。
固定Temurin21.0.12.1+1・LLVM-MinGW20261006・Python3.12.14/zlib1.3.2・app-version1.1.1でWindows helperを独立2run生成し、原ZIP・Microsoft再配布6文書補完ZIPの各bytes一致を確認した。
guestbridge/native/Mac helperのsourceは未変更。guest rootfs・Mac helper・qcow2・rendererは再生成せず前回bytesと一致した。
helper/rootfsの封入bridgeもcheckoutのexact bytesと一致。source bundle2本のsource1580件・notice2023件とGit source/LF blobを検証した。

| 資材 | bytes | SHA-256 |
|---|---:|---|
| helper原ZIP | 36031120 | `f8ce306b6762a2ca962850d4426d08732311d6789e287300ad16e5f8e3d162cb` |
| Microsoft6文書補完ZIP | 36168765 | `f0d7bf6c4a3ddedb0eaed501a00b200e8b41773609ead445e2aa6fdb102e721d` |
| windows-x86_64.json | 278044 | `e41d30a5241beac9e7276e5ad14caf9c279b5e8a9022123e8cd736221bb47d57` |
| macos-aarch64.json | 374723 | `23757eae57959c789d1baa3cbd37a23592ed8c508d6b030ea740caf7ba1cc857` |
| r5署名キット | 20240 | `af27d8f889fe4a2bc3d7f886b41322d89568c72464e86478b16b956ae23f9740` |

Mac descriptorはbytes不変で既存署名8bf72da3…521eを再利用し、運用DER成功・回復DER想定失敗を照合。Windows descriptorのみ再署名対象。
全5runtime資材へ機械検査を適用。ZIPはentry内容総和、rootfsは解凍後tar全長403507200、qcow2は実長、rendererは1.1.0公開load予算2056219398を維持。
正式Java候補検査（unsigned bytesのみ、Windows署名/trust/adoptionは返送後）とPython候補検査の結果はvalidation.json、外部rootのJava試験はproduct-tests.jsonへ保存した。

- helpers: {'tests': 94, 'failures': 1, 'errors': 0, 'skipped': 22}、exit 1
- core: {'tests': 71, 'failures': 3, 'errors': 0, 'skipped': 10}、exit 1

RuntimeHelperTestは21件中11成功・10 OS専用skip。#183追加の回復予算・ログ処理3件は成功。
helper初回は94件中71成功・1失敗・22skip。失敗は既存keeper期限試験のfacts→Commands.waitForでTIMEOUT、最初のtrialはrelease3816ms/afterEof2679msで成功後に発生した。
製品・fixture・期限を変えない新外部rootの同method再確認は1件成功。初回失敗を抹消せず環境負荷の寄与は未確定とする。#183追加のhelper回復試験2件は初回から成功。
core既知3失敗（InstallationImportTest、WorkerArtifactTest、RuntimeLeaseTest）、Smoke4件中3成功・1固定path errorを再確認した。

既知失敗とskipは成功扱いしない。PythonはguestbridgeのWindows fcntl未対応を失敗として記録し、Linux再試験は今回は未実行。
F4 reader調査のfixture10秒失敗・低負荷対照未実施は履歴として残し、今回再試験で解消したとは扱わない。
既知問題#182: Macの通常release期限超過で未確定stopが残る。1.1.2対応予定。

署名キットと実行順の鍵環境変数設定・両DER検証・返送手順はbuild/111-d5/SIGN.md。
返送名descriptor-signatures-1.1.1-r5.zip、Mac展開先想定/Users/ryuman/tmp-mcicon-111-sign/descriptor-r5/kit。
現在はWindows descriptor r5署名前で停止。旧D4/E4/F4は証拠として保全し公開候補へ流用しない。
署名返送後のE/F再生成・必要な再署名、製品再受入と全体反証、公開前high-water/source/tag照合が残る。
製品ソースの追加変更・Mac接続・G領域編集・commit/push/PR/公開・秘密鍵/token参照なし。

## r5署名結合・段階E固定候補（2026-11-08）

回復予算・ログ処理修正#183取込みによる再生成の続き。返送Windows署名ZIP41c64cd9…27f9とdescriptor原本2本を再照合し、両OSの運用DER成功・回復DER想定失敗を確認した。
共通TrustSet/RuntimeResourceGeneratorから最終resource/core/worker/CLIを生成し、同じ最終coreから両payload・stable/Central候補を固定した。
coreは453909 bytes / `2ef10533e2f6e3b0283dc68715d68e74980c26a762a5a59cd17eb62276cdd970`。全size/SHAはbuild/111-e5/MEASUREMENTS.mdとSHA256SUMS。
E4からcoreはRuntimeHelper.classとWindows descriptor/署名・helper inventory・pinsの5entry変更。Centralはcore binary/sources/moduleと各checksumの15file変更。
worker/agent/CLI JARとMac descriptor/署名、rootfs/Mac helper/qcow2/rendererは不変。CLI ZIPは新coreと生成時点のdocs/QAを反映する。
runtime5資材とpayload2本へunpackedBytes機械検査を適用した。rootfsはtar全長403507200、ZIPはentry総和、rendererは旧公開load予算を維持する。

Java試験はCodex書込root外の新規C:/Users/ryuma配下へbuild/TMPを分離し、require_escalated+run_logged.pyで実行・証跡回収後に専用dirを削除した。
- helpers: {'tests': 94, 'failures': 0, 'errors': 0, 'skipped': 22}、exit 0
- core: {'tests': 71, 'failures': 3, 'errors': 0, 'skipped': 10}、exit 1
keeper期限試験を含む原XMLはE5/test-results、概要はkeeper-retest.json。D5初回TIMEOUTとfocused成功は保全し、期限を緩めていない。
core既知3失敗、Smoke固定path error、Windows guestbridgeのfcntl未対応、OS専用skipは成功扱いしない。F4 reader調査残件とLinux再試験は今回未実行。
既知問題#182: Macの通常release期限超過で未確定stopが残る。1.1.2で対応予定。

stableはtoolVersion1.1.1・sequence2候補・minimumBootstrapProtocol4。公開直前のlatest/high-water再照合が必要。
キットはbuild/111-e5/stable-signing-kit-1.1.1-r5.zip（SHA `38715d025c87020e9c3c0f5a4a27c4e48518ce6c1ab38c298b782cbb88c4ab5c`）と
mcicon-central-1.1.1-r5-signing-kit.zip（SHA `faea3d547878488c71d0f123c81f67a1ab1346753aea82b2ba10be4e80782ffc`）。隣接.sha256と手順書を用意した。
stableのED25519_PRIVATE_KEY、CentralのGNUPGHOME/GPG_TTYとローカルpinentry前提を実行順で記載。
Mac展開想定は/Users/ryuman/tmp-mcicon-111-sign/stable-r5/kit と central-r5/kit。
返送名はstable-signatures-1.1.1-r5.zip、mcicon-central-1.1.1-r5-signatures.zip。
現在はstable/Central署名前で停止。Mac接続・転送・署名・公開・秘密鍵/token参照・G作業なし。
返送後の原本/正式Java/gpgv検証、bootstrap再生成・製品再受入・全体反証・公開前照合が残る。

## r5署名結合・段階F bootstrap候補（2026-11-08）

回復予算・ログ処理修正#183取込み候補のE5固定hashを開始前に照合し、元stableとCentral元50fileを再生成せず.sig/.ascだけを結合した。
stable64-byte署名は運用DER成功・回復DER想定失敗、返却公開鍵だけのgpgv10/10・署名必須Central監査に成功。
両OSで正式ManagedPackageVerifierを通し、同じ最終core/bootstrap JAR/resource/seedからarchiveを生成した。
archive全fileとcore/worker/CLI/payload/stable/trustのE5固定hash一致を確認。host別JAR再buildなし。

| 資材 | bytes | SHA-256 |
|---|---:|---|
| build/111-f5/stable-signed-1.1.1.zip | 2283 | `6cc5c570c6a915a2de199cd3d16c063ab77cf0c36e93da4a4b02d78d6a068b7c` |
| build/111-e5/mcicon-central-1.1.1.zip | 1499032 | `815260d33d7f0cf470d2195f1871c51adb87be6146796263fa4c24334272b151` |
| build/111-f5/mcicon-bootstrap-1.1.1-windows-x86_64.zip | 70521791 | `957019130ccd0204b313ed37f6c4068b77f9c80c8f47a4401c56e284e2ab7e54` |
| build/111-f5/mcicon-bootstrap-1.1.1-macos-aarch64.tar.gz | 67526980 | `8f83caacd705e23dc95da9424e5cee31539ce0613c73cbd06d904da01f18eec9` |

全25asset・29参照の将来URL/size/SHAはbuild/111-f5/release-assets.json/.md。unpackedBytes宣言7資材、bootstrap manifest、宣言なしassetへそれぞれの規則を適用した。
F4から10asset変更・15asset不変。Windowsはseed3file、Macはseed3fileとad-hoc署名/CodeResourcesのみ変更。Mach-O署名領域外・他全fileは不変。
詳細はf4-differences.json。旧F群とE4/F4署名済み資材は保全した。

固定readerは新規C:/Users/ryuma配下へTMP/build/専用Gradle home/同梱readerを隔離し、require_escalated+run_logged.pyで実行。
reader初回: {'tests': 42, 'failures': 10, 'errors': 0, 'skipped': 1}（580.547秒）。focused再確認: {"tests": 10, "failures": 3, "errors": 0, "skipped": 0}。preparation: {'tests': 19, 'failures': 0, 'errors': 0, 'skipped': 0}。
初回失敗と同一期限・fixture無変更の別root再確認を区別する。原XML/log・reader-investigation.jsonを保全し、再試行で初回失敗を成功へ置換しない。
改変・旧sequence/同版拒否はsecurityCases/candidate-tamper-tests.json参照。core既知試験は{'tests': 27, 'failures': 3, 'errors': 0, 'skipped': 0}、Smoke固定path errorは再確認し成功扱いしない。
包装/reader/payload Python、Mac native fixtureの成否はknown-test-results.json/native-result.jsonを参照。Windows LLVM警告とWindows guestbridgeのfcntl試験は今回未再実行。
Macは新規/Users/ryuman/tmp-mcicon-111-f5だけを使用し、成果・証跡回収後に削除済み。sudoなし、Lima設定・既存dir・Homebrew・関連process一覧は不変。
Windows Lxss/WSL/Dockerの前後不変を確認。外部試験rootも回収済み。
既知問題#182（Mac通常release期限超過で未確定stopが残る）は1.1.2対応予定。E5 keeper成功は今回製品E2E成功の代替ではない。
段階Fまでで停止し、Gへ進んでいない。全体反証・公開前latest/high-water/source/tag照合と別指示による公開が残る。
commit/push/PR/tag/Release/Central upload・秘密鍵/token参照なし。

F5例外分類補足: 初回10失敗はreader期限超過9件とbootstrap準備期限超過1件（全件fixture準備中）。focused再確認はreader期限超過3件。初回580.547秒、再確認655.703秒、preparation280.281秒。

<a id="stage-h-20261108"></a>

## 段階H 証拠統合と公開準備（2026-11-08）

達成14・未達2・ユーザー決定で範囲継承/既知問題4・公開後確認5。決定済み4件は試験達成や個別公開承認としない。最終main/tag結合・直前照合と個別公開承認・公開後gateは残る。 asset25本・署名・source対応の反証結果を継承し、Hでも全size/SHAを再照合した。

### ユーザー決定（2026-11-08）

原文: **「推奨どおり」**。調整役の推奨1〜4を採用した記録であり、未達を達成へ変更するものではない。

- **T03**: core既知3失敗・Smoke固定path error1件は既知問題として1.1.1を公開する方針とし、修正は1.1.2でIssue化。全件成功とはしない。
- **U02**: Release notesは自動救済の保証なしと所有確認付き一般的回避案内のみ。未検証の万能コマンドは載せない。
- **O01**: 1.1.0と同じ保証範囲外を継承し、今回は実画面試験をしない。
- **P02**: cache verifyのWindows相対path拒否、doctor初回TIMEOUT（host再起動後の旧lease回収）、core既知3失敗+Smoke固定path errorの3件を1.1.2向けにIssue化する方針。現依頼は草案のみでIssue作成は禁止。

既決の#182とcache verifyも1.1.2対応。Central/tag/Release作成・upload・公開化/Latestの個別承認は含まれない。公開後gateも未実施のまま残す。

| gate | 状態 | 根拠・限定と残工程 |
|---|---|---|
| A01 変更資材の独立2run・再利用資材の同bytes | **達成** | B/C/D5の原記録を採用。再利用4本を今回再buildしたとは表示しない。（`qa/windows-helper-candidate-1.1.1-2026-11-06.json`） |
| A02 第三者source/notice/license閉包 | **達成** | source1580件・notice2023件と旧ID/新inventory対応。第三者bundleが新MIT変更sourceを含むとは表示しない。（`qa/descriptor-candidate-1.1.1-2026-11-06.json`） |
| A03 変更sourceと最終main/tagの結合 | **未達** | 製品buildは5c474f4。#184は試験4fileだけで製品同bytes。D/E/F PR merge後のmainと製品tree同一性を再照合してからtagを付ける。（`build/111-h/merge-risk.json`） |
| A04 descriptor/stable署名・共通trust・Central PGP | **達成** | descriptor2本・stableの運用DER成功、回復DER想定失敗。PGP10/10、署名必須監査。鍵更新・再署名なし。（`build/111-f5/signature-merge.json`） |
| A05 同一最終core・版/pin/展開予算・25asset | **達成** | 25asset size/SHAをHでも全再照合。bootstrap/CLIは生成時docs/QAを内包し、今回H追記を同梱し直さない。最新記録は将来tagのdocs/qa。（`build/111-f5/archive-audit.json`） |
| G01 Windows固定51版×2・PNG/Java選択 | **達成** | 102試行・51版成功。代表3版各2回はbootstrap、残48版各2回は候補CLI直接。全件bootstrap起動とは表示しない。（`qa/acceptance-1.1.1-windows-2026-11-07.json#fixed51Acceptance`） |
| G02 Mac固定51版×2・PNG/Java選択 | **達成** | 102/102成功。stone64x64・CRC/RGBA/可視画素・反復hash/既存基準・Java8/17/21/25。全アイテムの保証ではない。（`qa/acceptance-1.1.1-macos-2026-11-07.json#matrix`） |
| G03 両OS代表版・再利用・offline描画 | **達成** | 1.14.4/1.21.4/26.3。固定候補bytesの正規cache投入後の描画を含む。空cacheの公開runtime取得成功や独立通信量計測の代替ではない。（`qa/acceptance-1.1.1-windows-2026-11-07.json#productE2E`） |
| G04 両OS空runtime cache取得・取得途中取消し→再開 | **公開後確認** | 未公開v1.1.1 URLの404で未実施。Minecraft入力取得取消しとは別gate。非Latest Release公開・両OSasset照合後、Central公開・Latest切替前に両OSで実測する。失敗・未実施ならCentral公開・Latest切替へ進まない。（`qa/acceptance-1.1.1-windows-2026-11-07.json#productE2E`） |
| G05 Windows kill/Ctrl+C後初回再開・daemon消失・共有違反 | **達成** | kill3/3・bootstrap Ctrl+C3/3の最初の再開成功。f4共有違反3版各2/2成功。親が継承させたCtrl+C ignore flagは別の既知制限。（`build/111-g-windows/final-f5/HANDOFF.md`） |
| G06 Mac dead empty lease・running CLI kill/Ctrl+C | **達成** | f5製品経路の旧core終端・回復・PNG。初回runtime公開URL取得中の取消しはG04に残す。（`build/111-g-macos/final-f5/HANDOFF.md`） |
| G07 Windows live/unknown/pending拒否維持 | **達成** | 正規CLI/helper実機の負例。任意破損・全不正形式網羅の保証ではない。（`qa/acceptance-1.1.1-windows-2026-11-07.json#productE2E`） |
| G08 Mac pending journal・live別job/unknown補完 | **達成** | g6の9条件をg5の102描画や旧fixtureへ加算しない。pendingはprepare/client-complete一致・settledなし。unknownはcoreのRuntimeLease拒否まで。helper内部unknown全分岐ではない。（`build/111-g-macos/final-f5/recovery-gaps/REPORT.md`） |
| T01 #184 更新38method・reader/Seed契約gate | **達成** | 更新・境界5回連続＋CPU84%対照成功。最後に変更したSeed正常系1method以外は同一を機械確認。最終Seed全10件は別途5回連続＋CPU100%成功。28 OS条件skipはMacで補完。（`build/111-h/fixture-184-summary.json`） |
| T02 署名/原文/payload改変・正しい署名の旧sequence/同版拒否 | **達成** | 候補原文sequence改変は署名不一致で拒否。正しい署名の旧sequence/同版拒否はfixture。公開実配信や本番鍵の不良candidate自動rollbackの実証へ読み替えない。（`build/111-f5/candidate-tamper-tests.json`） |
| T03 関連試験の全件成功 | **ユーザー決定で範囲継承/既知問題** | 2026-11-08 ユーザー原文「推奨どおり」。core既知3失敗・Smoke固定path error1件は既知問題として1.1.1を公開する方針、修正は1.1.2でIssue化。core73件中60成功・3失敗・10 skip、Smoke3成功・error1件は保持し全件成功としない。#181/#183前後からの既知問題で#184と無関係。旧LLVM警告・Windows fcntl等の履歴や未再実行も成功化しない。個別公開操作の承認ではない。（`build/111-h/fixture-184/core/result.json`） |
| G09 環境不変・本人専用資源/プロセス回収 | **達成** | Windows製品受入はhost再起動後の基準との比較。開始前Ubuntu Running→Stopped差はhost再起動由来として保持。#184は22外部root回収・残存0。g6は本人資源のみ回収、既存6path/anchor不変。（`qa/acceptance-1.1.1-windows-2026-11-07.json#cleanup`） |
| U01 未改変の健全v1.1.0→1.1.1実更新・旧job保持・不適用条件 | **公開後確認** | 本番latest固定。実background取得完了/pending保存→次回managed適用/active=1.1.1、旧job世代維持、無効/offline/pin不適用を両OSで確認する。#184や候補検証で代替しない。（`qa/acceptance-1.1.1-macos-2026-11-07.json#updateE2E`） |
| U02 旧1.1.0詰まりrootの具体的回避・旧世代回収手順 | **ユーザー決定で範囲継承/既知問題** | 2026-11-08 ユーザー原文「推奨どおり」。Release notesは自動救済の保証なしと所有確認付き一般案内のみ。未検証のunregister等の万能コマンドは掲載しない。g6の新HOME回避はf5同世代で旧1.1.0回収の実証ではなく、具体的回収手順の検証達成とはしない。（`docs/release-candidate-1.1.1.md#旧lease利用者向け案内草案実機手順は未検証`） |
| O01 実配布unsigned警告・初回許可/更新後GUI起動 | **ユーザー決定で範囲継承/既知問題** | 2026-11-08 ユーザー原文「推奨どおり」。1.1.0と同じ保証範囲外を継承し、今回は実画面試験を行わない。ヘッドレス成功を実配布警告・GUI成功としない。MOTW/quarantine・未準備host・実reboot・正式installer等の未検証も保証へ加えない。（`docs/release-distribution-1.1.md`） |
| I01 Central空Gradle cacheのmodule/POM・10成果物+10.asc | **公開後確認** | 候補core/workerと実取得bytes一致、全10 PGP確認。USER_MANAGED VALIDATEDは未公開であり、PUBLISHED/実取得成功とは別。（`docs/managed-runtime-consumer.md`） |
| I02 両OS全25 Release asset再取得・署名/hash/source/notice | **公開後確認** | source2本を含む全25本を両OSで再取得し候補size/SHA照合。旧1.1.0を差し替えない。（`build/111-f5/release-assets.json`） |
| I03 公開bootstrap実render・正式managed API consumer | **公開後確認** | 両OSでpublished bootstrap→helper→実render。既存検証器の--managed-runtimeはdoctorManagedまででrenderManagedは未実施。補助consumerで実doctorManaged/renderManagedを行う。補助consumerはcompileのみ確認済み、実実行は公開後。省略・反射による署名確認を管理API実行成功としない。（`build/111-h/publish-plan.md`） |
| P01 公開直前Latest/high-water・asset再凍結 | **未達** | H読み取り時Latest v1.1.0・sequence1・protocol4、候補sequence2。公開直前とLatest切替直前に再確認。候補自身を除く既発行stable、または更新試験対象の未改変v1.1.0 rootの高水位が2以上なら停止。仮鍵fixtureや既に1.1.1 seed導入済みのQA rootは比較対象に混ぜず、署名済みbytesを直接編集しない。（`build/111-h/sources/latest.json`） |
| P02 残件の採否・最終反証/個別公開承認 | **ユーザー決定で範囲継承/既知問題** | 2026-11-08 ユーザー原文「推奨どおり」。T03既知失敗の1.1.2送り、U02一般案内、O01保証範囲継承、cache verify・doctor初回TIMEOUT・既知試験失敗の3件のIssue化を採用。既決#182も1.1.2。最終main/tag結合・直前照合・公開後必須gateは残る。Central/tag/Release作成upload公開化/Latestの個別承認は未取得であり、今回の回答を公開操作の許可へ拡張しない。（`build/111-h/gate-status.json`） |

### 補完証拠の分離

Mac g6はpending journal・live別job/unknownを正規CLI/OS signalで9条件成功。g5の102/102描画や旧fixture47/47へ足し込まない。unknownはcore拒否まででhelper内部全分岐を通したとしない。client-complete一致・settledなしのprepare journal、SIGSTOP制御、元HOMEの拒否維持と新HOME回避、正規操作で作れない状態の限界は`build/111-h/mac-supplement-summary.json`とg6 REPORTを参照。

#184は`d6d33a7`へmerge済みの試験4fileだけ。試験済みsourceとmerged blobのLF一致、製品JARとF5資材の同bytesを確認した。更新38method等は新規外部root5回連続＋CPU84%対照成功。その後のCPU100%でSeed正常系1失敗を検出し、元のBootstrap構築の計測境界へ戻してJava起動不要の3 JARを除いた。10秒・実reader/tree/hash/所有assertionは保持。最終Seed全10件を別途5回連続＋CPU100%で再確認、他method/helperは同一を機械照合して成功記録を再利用。フルJRE必須試験も保持。Macは更新38+最終Seed10、境界25+5、実package caller1の79methodを被覆。旧fixture3/3の準備期限失敗とstate2/tree1の改変検出、初回実装・実行環境・追加負荷失敗は保全した。詳細・各秒/count/skipは`build/111-h/fixture-184-summary.json`と保全した原証拠。これは公開v1.1.0からの本番更新成功ではない。

### 既知問題と公開判断

- **#182 Mac通常release後の未確定stop**: ユーザー決定: 1.1.2対応。既知問題として掲載。修正済みとしない。
- **Windows cache verify相対path拒否**: ユーザー決定: 1.1.2対応。UNSAFE_PATHの診断失敗は保持。通常描画成功と区別。
- **doctor初回TIMEOUT**: 97.238秒TIMEOUT→repair79.866秒→doctor170.758秒成功。初回失敗保持。repairによる原因解消と断定しない。 2026-11-08原文「推奨どおり」により1.1.2向けIssue化方針を採用。原因確定・修正済みとはしない。
- **受入中host crash**: BugCheck0xA/WER AV_ACE_ADVT。外部driver候補で根因未断定、dump読取拒否。製品負荷の誘発可能性も未確定。同様の再発時は停止。
- **既定AppData ACL拒否・継承Ctrl+C ignore**: 既知制限として残す。安全なユーザー所有の新規絶対MCICON_DATA_HOME/通常terminalで回避し、OS ACL/保護解除やstate改変は案内しない。
- **旧1.1.0 lease・旧VM/WSL世代**: 自動救済非保証。元状態/出力保全、別program/data/output、旧世代は所有・他job不使用を照合して個別対応。具体的両OS旧世代回収手順は未検証。
- **T03 ユーザー決定（2026-11-08、原文「推奨どおり」）**: core既知3失敗・Smoke固定path error1件は既知問題として1.1.1を公開する方針とし、修正は1.1.2でIssue化。全件成功とはしない。
- **U02 ユーザー決定（2026-11-08、原文「推奨どおり」）**: Release notesは自動救済の保証なしと所有確認付き一般的回避案内のみ。未検証の万能コマンドは載せない。
- **O01 ユーザー決定（2026-11-08、原文「推奨どおり」）**: 1.1.0と同じ保証範囲外を継承し、今回は実画面試験をしない。
- **P02 ユーザー決定（2026-11-08、原文「推奨どおり」）**: cache verifyのWindows相対path拒否、doctor初回TIMEOUT（host再起動後の旧lease回収）、core既知3失敗+Smoke固定path errorの3件を1.1.2向けにIssue化する方針。現依頼は草案のみでIssue作成は禁止。

未準備host・MOTW/quarantine付き実配布・実reboot・正式installer/winget/Homebrew・OS署名/公証は保証へ追加しない。受入中のhost再起動は計画したreboot受入の成功ではない。core既知3失敗・Smoke固定path error等を成功扱いしない。

### source/tag・公開の境界

D/E/F PRのbaseはmain `d6d33a7`。現HEAD `5c474f4`との差は試験4fileだけで、docs/qa差分とのpath重複なし。merge/rebase未実行のため将来競合なしを保証しない。tagはD/E/F PR merge後のmainに付け、5c474f4との製品tree同一性・第三者source対応を直前に確認する。署名済みF5 bytesを再生成しない。生成時docs/QAを内包するCLI ZIPと、最新H記録を収録する将来tagのdocs/qaは時点を区別する。

現在の公開Latestはv1.1.0、stable sequence1/protocol4を読み取り確認。候補sequence2は公開直前再照合が必要。公開後にしか成立しないgateを事前fixtureで埋めない。Central/tag/Release作成と25asset upload・公開化/Latestは個別承認が必要。実行順・照合・公開後の必須確認は`build/111-h/publish-plan.md`、Release notes案は`build/111-h/release-notes-1.1.1.md`。今回は公開操作・Mac/WSL新規操作・秘密鍵/token参照なし。
