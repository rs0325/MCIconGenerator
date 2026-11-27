# macOS managed helper 1.1

#220で状態と終了処理を単純化した。現在の契約を以下に示す。過去の実機確認は変更後の受入を代替しない。

初期検討ではApple Silicon・macOS 15.7.7以降を候補とし、15.7.7の過去限定確認記録がある。正式検証基準と最小下限を15.8.1へ揃える方針を採用する。15.7.7保証は残さない。inventory生成器・Formula・fixture・descriptorの数値を15.8.1へ整合する。Intelは初版対象に含めない。Lima 2.2.1/VZ、導入済みのVM用Rosetta、aarch64 Ubuntu guest、linux/amd64 rendererを使用する。Developer ID、notarization、隔離属性による初回起動は受入未完了。単体fixtureとpackagingは実VMの成功を意味しない。

host判定は署名済みdescriptorの`minimumOsVersion`を比較する。現行descriptor・fixtureは`15.8.1`を使う。native probeのcompile target `13.0`を対応下限と混同しない。preflightでは、Rosettaの未導入・非対応・観測不明とVZの非対応・観測不明を異なるsetup-required診断で伝える。未導入時はApple公式の案内と利用規約を確認し、導入への明示同意を必要とする。製品での同意・公式導入・完了後の再確認導線は#97と接続して検証する。本helperは導入操作を実行せず、未知状態を導入済みとして継続しない。

## 配布と固定入力

Java 21の共通helperをjlink/JREとjpackage native launcherへまとめる。host側Java/Docker/Limaの手動導入を前提にしない。coreからは単一絶対entrypointをTMP/TEMP/TMPDIRだけの環境で起動する。Lima内部のsystem tool探索には固定PATHと専用HOMEを構成し、ユーザーHOME/PATH/SSH agent/proxy設定を継承しない。

jpackageの既定ad-hoc署名は同梱Mach-Oの署名とentitlementsを除去するため、生成後に公式limactlの元bytesを復元する。outer appだけを非deepのad-hoc sealで整合し、内側limactlを再署名しない。元SHAとnetwork/client・server・virtualization entitlementsの完全一致、元manifestの全payload一致、inner strict/outer deep strictを生成時に確認する。アプリ本体へのVZ entitlement追加、Developer ID、ユーザー鍵・production鍵は使わない。

Lima公式Darwin-arm64 archiveは38,328,082 bytes、SHA-256 `9e9eacce88f37e185c346bad73aa6136f738d8cdf8c3bb23cd42b071824bc66e`。同archiveにLinux-aarch64 guestagent.gzが含まれるため、固定guest構成のために別arch追加guestagent archiveを取得しない。固定YAMLではVZ/aarch64、containerd自動導入無効、default dependency解決無効、固定offline debだけを使う。`prepare-payload.py`は取得済みbytesを検証して別出力に準備し、導入やVM操作を行わない。 固定3debの原copyrightは`--deb-copyrights <package名/copyrightを持つdirectory>`で渡し、対応版のsize/SHAを照合して`share/doc/ubuntu/<package名>/copyright`へ無改変で配置する。参照するfull license/NOTICEは別配布のsource/notice bundleとinventoryへ結合し、このcopyだけで個別条件の完了とは扱わない。debの配置basenameは`runc.deb` / `containerd.deb` / `docker.io.deb`に固定し、元版を含むfilename・公式snapshot URLはmanifestに保持する。これにより版の`~`を共通host archiveの相対pathへ持ち込まない。

Ubuntu baseはcloud releaseの同一署名SUMSがqcow2とmanifestを結ぶ。Ubuntu snapshotは`20261017T000000Z`、noble/noble-updates/noble-securityのarm64を固定する。採用候補はdocker.io `29.1.3-0ubuntu3~24.04.2`、containerd `2.2.1-0ubuntu1~24.04.3`、runc `1.3.4-0ubuntu1~24.04.1`。署名索引・実deb/control/source bodyの照合証拠は私用保全資料にあるが、固定3debのoffline dpkgは第1段で成功した。kernel/base全体とLima同梱依存/JRE/rendererのlicense/source網羅監査は未完了である。

## 状態と短いVM cache

VM diskとLima内部のSSH認証keyは従来どおり`/private/tmp/mcicon-<uid>-<識別hash20桁>`に置く。短い名前はcanonical root、hostId、descriptor SHA、UIDから決め、socket pathの104byte上限を確認する。再起動で短cacheが消えた場合は次回prepareでVMを作り直す。

管理rootの`macos/<descriptorSha256>.json`が唯一の状態ファイルである。descriptor digest、instanceDir、generationOwner、作成時memoryMiB、bootSessionId、実行中ownerだけを保存する。ownerはjobId・ownerId・PID・processStartedAtを持ち、解放後はnullにする。短cacheのowner marker、VM snapshot、history、再起動証明は作らない。

## 起動と解放

描画は管理rootの`locks/render.lock`で1件ずつ実行する。Mac helperの操作は`locks/macos-lifecycle.lock`で直列化する。他jobの走査と世代横断のメモリ予算は行わず、VM起動に必要な量と、搭載メモリから2048 MiBを引いた容量を比較する。新規VMは従来の単独要求に基づく容量とし、既存VMの作成時容量が要求に足りなければ対象と削除手順を示して止める。

前回ownerのprocessが終了しているかbootが変わった場合、動いているVMを止める。既存VMを起動し直した後、generation labelで残ったcontainerを列挙・削除してから描画を始める。同bootでownerが生存していれば待機中の描画の終了を案内する。未登録cache・読めない状態・不明なVM状態は自動修復せず、専用instanceとcache・状態ファイルの削除手順を含むエラーにする。

通常releaseはgeneration labelのcontainerを削除し、VMの停止を確認してlease resourcesを空にし、状態のownerをnullにする。coreがreleasedを記録し、jobを閉じた後に記録を削除する。未着手releaseは状態のownerが自jobでないことをlifecycle lock内で確認し、VMに触れず終了する。release全体は30秒を上限にする。

OperationJournal、intent/settled/released証明、container tombstone、StoppedRecovery、旧版保守入口は使用しない。機械ID・設定hash・socket dev/ino・hostAgent UIDは照合しない。Lima instanceの場所と種類、guest markerとdaemonのgeneration labelは維持する。

native guardは変更しない。coreとhelperの終了を監視する既存protocolを使い、必須の完了印は一時directoryへ置いてclient終了時に出力とともに削除する。完了印を次回起動の回復条件にしない。実装には既存のLima/Docker、Javaのprocess・file lockと既存HostFactsの搭載メモリ情報を利用し、依存ライブラリを追加しない。

## 入出力

VMへ固定mountするのは専用job staging親と検証済みruntime/offline入力だけである。元HOMEやcache全体はmountしない。worker argv、agent.json、asset indexからJAR、native、mapping、game options、非ogg asset objectを選び、job別snapshotへsize/SHA確認して固定する。参照表の読み取り時hashもコピー後に確認する。

worker応答は改行を含む元bytesを保持する。requestId、応答順序、own run directory内のresult.pathを照合し、完成PNGを元host cacheへatomic copyしてsize/SHAを確認した後に応答を転送する。error、途中line、他job/traversal、欠落応答では部分PNGを公開しない。

## 検証状態

#220の単純化後のMac実機確認は別途必要である。状態・持ち主・メモリ・一時file削除の単体試験と、fake Limaによる死亡後・再起動後・通常release・削除失敗の試験を分ける。POSIX依存のlifecycle試験はWindowsでは実行しない。以下の全51版確認は変更前の履歴であり、新実装の受入を代替しない。

## 全51版の固定pixel再受入

確定main `0fd4b7d2` の分離buildとapp-image-v10を使用した。先行6版9,212PNGの保存画像と、同じ専用VMを再利用した残45版51,053PNGを正式canonical-v1受入時の両roundへ比較し、全51版60,265PNG・102比較のRGBA差分0を確認した。51lease released空、765操作終端、204完全container IDの削除/正常NotFound、206実行child回収、同実行境界Stoppedと末尾入力・旧環境不変の小監査は成功し、独立結果auditはPASS（追加指摘0）となった。

初回の旧fixed baselineとの1.21.11 1,502枚RGB差FAILは保持する。正式受入baselineへの保存画像分析と残45実描画は別世代であり、元FAILを成功へ書き換えない。今回の固定pixel受入から代表World意味確認の保証範囲を拡張しない。#97/#98の実uninstall/更新接続、macOS15.7.7以外の実測/最終配布と現存anchor旧boot OwnedStopped分岐の未実測を残し、Refs #94 OPENを維持する。

## 配布inventoryの生成

`helpers/macos/scripts/build-inventory.py`はMac host-helperの最終file manifest、component対応表、確定した公開URLから、inventoryと既存descriptorのartifact項目を生成する。対象はApple Silicon・`minimumOsVersion=15.8.1`のhost-helper ZIPだけであり、guest/rendererやtool payloadの発行、core resourceの同梱、署名は行わない。

最終署名・packaging後のfile manifestをpinし、全fileのcomponent帰属とlicense/noticeの実path・本文size/SHAを確認する。大binaryの全tree/hash検証はpackagerの責務とし、生成器は繰り返さない。外部noticeは監査済みsource bundle manifestのpin・entry・size/SHAへ結合する。公開URLは固定asset向けの`https://ASCII DNS名/ASCII path`（正規`%HH`可）に限定し、query/fragment/userinfo/port/IP literal/Unicodeを扱わない。公開URLが未確定、この形式に適合しないURL、文書不在、link、pin不一致、未帰属file、inventoryのUTF-8 bytesが4 MiBを超える場合は発行を拒否する。通常fileとsource memberのsizeは空fileを含む非負整数、archive本体のsizeは正の整数として既存packager/coreと区別する。

```text
python3 helpers/macos/scripts/build-inventory.py \
  --tree <最終MCIconHelper.app> --manifest <最終file-manifest.json> \
  --components <component対応.json> --publication <確定公開先.json> \
  --output <未使用の生成先>
```

生成先の`inventory.json`を`inventoryPath`のclasspath resourceへ置き、`artifact-fragment.json`を同版worker/hash・helper protocolとともに共通発行側でdescriptorへ組み込む。source/notice bundleは配布資料への参照であり、runtimeに新roleやsourceの自動downloadを追加しない。最終helper bytesが未確定の候補を、flagや仮URLだけで正式成果へ昇格させない。実URL、正式trust/署名、core/payload同梱、#97/#98との実配布接続は別工程である。

生成/拒否の合成条件は、`python3 helpers/macos/scripts/verify-inventory.py --output <未使用のfixture保存先>`で確認する。実artifact・VM・公開URL・署名を使わず、fixture URLを正式発行へ流用しない。

配布app-image生成では`package-helper.py --app-version <release版>`を明示し、JAR artifact版・descriptor tool版・CFBundleVersion/CFBundleShortVersionStringを個別に確認する。省略時のjpackage既定版を正式版へ読み替えない。

`appImage` taskも`project.version`を`--app-version`へ渡します。リリース候補ではrootの`-PreleaseVersion=1.1.0`により表示版とJAR artifact版を揃え、生成後のCFBundle値は別途照合します。
