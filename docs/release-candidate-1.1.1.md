# 1.1.1 配布契約（段階A）

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
