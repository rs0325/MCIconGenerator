# macOS managed helper 1.1

第1段はapp-image-v6、第2段はv7の当時sourceを限定受入した。host照合・reboot終了を加えたv9では、実再起動後の正常releasedからの欠落cache再構成と3PNG比較・通常closeを確認した。元active leaseの再起動終了・回復もanchor欠落のfresh分岐で実行し、この範囲の限定独立結果auditはPASS（追加指摘0）である。anchor現存の旧boot cold-startは未受入である。以下は実装の契約を記し、全OS・全版・製品配布受入の完了を意味しない。

初期対応はApple Silicon・macOS 15.7.7以降を候補とし、実機確認済みのOSは15.7.7だけである。Intelと15.7.7未満は初版の正式対象に含めない。Lima 2.2.1/VZ、導入済みのVM用Rosetta、aarch64 Ubuntu guest、linux/amd64 rendererを使用する。Developer ID、notarization、隔離属性による初回起動は受入未完了。単体fixtureとpackagingは実VMの成功を意味しない。

host判定は署名済みdescriptorの`minimumOsVersion`を比較する。最終Mac descriptorは`15.7.7`に設定し、native probeのcompile target `13.0`を対応下限と混同しない。`native-acceptance.py`のfixture descriptorも`15.7.7`を使用する。preflightでは、Rosettaの未導入・非対応・観測不明とVZの非対応・観測不明を異なるsetup-required診断で伝える。未導入時はApple公式の案内と利用規約を確認し、導入への明示同意を必要とする。製品での同意・公式導入・完了後の再確認導線は#97と接続して検証する。本helperは導入操作を実行せず、未知状態を導入済みとして継続しない。

## 配布と固定入力

Java 21の共通helperをjlink/JREとjpackage native launcherへまとめる。host側Java/Docker/Limaの手動導入を前提にしない。coreからは単一絶対entrypointをTMP/TEMP/TMPDIRだけの環境で起動する。Lima内部のsystem tool探索には固定PATHと専用HOMEを構成し、ユーザーHOME/PATH/SSH agent/proxy設定を継承しない。

jpackageの既定ad-hoc署名は同梱Mach-Oの署名とentitlementsを除去するため、生成後に公式limactlの元bytesを復元する。outer appだけを非deepのad-hoc sealで整合し、内側limactlを再署名しない。元SHAとnetwork/client・server・virtualization entitlementsの完全一致、元manifestの全payload一致、inner strict/outer deep strictを生成時に確認する。アプリ本体へのVZ entitlement追加、Developer ID、ユーザー鍵・production鍵は使わない。

Lima公式Darwin-arm64 archiveは38,328,082 bytes、SHA-256 `9e9eacce88f37e185c346bad73aa6136f738d8cdf8c3bb23cd42b071824bc66e`。同archiveにLinux-aarch64 guestagent.gzが含まれるため、固定guest構成のために別arch追加guestagent archiveを取得しない。固定YAMLではVZ/aarch64、containerd自動導入無効、default dependency解決無効、固定offline debだけを使う。`prepare-payload.py`は取得済みbytesを検証して別出力に準備し、導入やVM操作を行わない。 固定3debの原copyrightは`--deb-copyrights <package名/copyrightを持つdirectory>`で渡し、対応版のsize/SHAを照合して`share/doc/ubuntu/<package名>/copyright`へ無改変で配置する。参照するfull license/NOTICEは別配布のsource/notice bundleとinventoryへ結合し、このcopyだけで個別条件の完了とは扱わない。debの配置basenameは`runc.deb` / `containerd.deb` / `docker.io.deb`に固定し、元版を含むfilename・公式snapshot URLはmanifestに保持する。これにより版の`~`を共通host archiveの相対pathへ持ち込まない。

Ubuntu baseはcloud releaseの同一署名SUMSがqcow2とmanifestを結ぶ。Ubuntu snapshotは`20261017T000000Z`、noble/noble-updates/noble-securityのarm64を固定する。採用候補はdocker.io `29.1.3-0ubuntu3~24.04.2`、containerd `2.2.1-0ubuntu1~24.04.3`、runc `1.3.4-0ubuntu1~24.04.1`。署名索引・実deb/control/source bodyの照合証拠は私用保全資料にあるが、固定3debのoffline dpkgは第1段で成功した。kernel/base全体とLima同梱依存/JRE/rendererのlicense/source網羅監査は未完了である。

## 永続登録と短いVM cache

永続runtimeRootにはimmutable inputs、lease、登録、出力と公式cacheを置く。VM diskとLima内部SSH認証keyは、`/private/tmp/mcicon-<uid>-<識別hash20桁>`という実体の短い0700 cacheに置く。LIMA_HOMEをsymlinkにする方式は、LimaがEvalSymlinksで物理pathに戻すため採用しない。

短い名前はcanonical root、hostId、descriptor SHA、UIDから決める。永続登録と短cache markerには完全な値とgenerationOwner UUIDを保存し、lstat/canonical path/UID/0700/全marker一致を確認する。既存の別target、未知marker、link、衝突を上書きしない。instanceDirは短cache内の実pathである。SSH/socketの末尾長を含めてUTF-8 bytesの上限を計算する。

短cacheの消失は旧VMや元core死亡の証拠にはならない。active/unknown leaseを保持して診断し、releaseではcacheを再生成しない。初回生成は登録とanchorがともに明確に不存在で、全他leaseが厳格liveかreleased、resources空、過去生成journal無しと分かる場合だけに限定する。同bootの登録済みanchor消失、登録のみの中断、所有不明はunknownとして保持する。hostId単体を機械IDとせず、root別salt付きhost bindingとbootによる最小終了/再開を追加した。旧field無しは移行しない。詳細と未受入条件は[再起動・残存契約](managed-helper-macos-reboot.md)を参照する。

## 排他と孤児操作

preflight等で生成未着手の自身jobは、元coreの厳格live、resources空、生成intent完全不在を同じ排他内で再確認し、VM/cacheへ触れずtombstoneを先に保存して正常closeできる。この未着手static経路はlive専用である。通常release側では完全な現boot/host/anchorを照合した後、資源空・intent無しのjobだけをVMへ触れず閉じられる。

同じruntimeRootに別descriptorの正常released履歴があっても、対象descriptorの初回登録を妨げない。初回資格の生成履歴だけを対象descriptorへ限定し、他世代の記録は保持する。active・unknown・不一致は拒否し、共有VMの停止可否は全rootの参照で判定する。

排他の取得順はjob `tmp/helper-operation.lock` の0/1 byte、global `locks/macos-lifecycle.lock`、共通resources CASの順で固定する。他jobのleaseはnonblockingで観測し、busy/unknownを共有VM停止禁止として扱う。

生成commandのspawn前に、job/owner/descriptor/operation UUIDを含むintentをforceして保存する。guardは元core PID/startと実際の親helperを監視する。回復release用commandは実際の親helperだけを監視し、死亡済み旧coreを生成監視へ流用しない。

native guardは自作成PGだけに最後のKILLを送り、直接子を未reapで保持してPGID再利用を防ぐ。reap後は再signalせず、`kill(-pg,0)`のESRCHだけを不在証拠にする。残存/EPERM/期限超過は完了印を残さない。完了印はintent SHAを結合したclient群の回収証拠である。

client群の消滅だけではdaemonが受理したcreate/startの終端を証明できない。正常daemon応答と完全ID/所有照合の別証拠が必要で、異常操作はunknownとして保持する。guard自身SIGKILLにも成功を推論しない。

startはcreateと分け、登録済みfull64IDだけを対象とし、新規createへ戻らない。固定Docker 29.1.3の`engine/daemon/start.go`はcontainer lock下でRemovalInProgress/Deadを拒否し、`daemon/delete.go`は同lock下でDeadを保存する。Ubuntu採用revisionのquilt patch全6件はtini/buildkitのみを変更し、当該start/remove/state契約を変更しないことを読取照合した。readyをdaemon終端と同一視せず、client回収後のfull ID rm-f正常応答と正常な不存在確認を受入条件にする。実daemonでの遅延start試験は未実施である。

releaseは同job fence内で全操作の両証拠を検査し、durable tombstoneを保存した後にだけresourcesを空にする。古いprepare/createはtombstoneにより再開できない。

## 入出力

VMへ固定mountするのは専用job staging親と検証済みruntime/offline入力だけである。元HOMEやcache全体はmountしない。worker argv、agent.json、asset indexからJAR、native、mapping、game options、非ogg asset objectを選び、job別snapshotへsize/SHA確認して固定する。参照表の読み取り時hashもコピー後に確認する。

worker応答は改行を含む元bytesを保持する。requestId、応答順序、own run directory内のresult.pathを照合し、完成PNGを元host cacheへatomic copyしてsize/SHAを確認した後に応答を転送する。error、途中line、他job/traversal、欠落応答では部分PNGを公開しない。

## 検証状態

現在のfixtureは短cache所有/衝突/再生成拒否、有限lock、snapshot範囲/link拒否、PNG同期順序/元bytes/未完了応答、operation pending/結合改変/tombstoneを扱う。native fixtureはstdio/exit、親helper強制終了、元core死亡、completion衝突/link、guard強制終了時の未確認保持を扱う。第1段はVZ/guest/daemon・CPUID・Java診断・通常releaseを受け入れた（同実行境界Stoppedとsandbox Brokenの観測差を保全）。既存候補の第2段はraw・4代表12PNG/独立RGBA・offline再利用・同VM片側取消・完成PNG後のown core異常と公開回復・known worker helper異常/通常closeを限定受入済み。予定singlecorekillの再試験を行ったとはせず、元bind誤検査のfinallyによる2core故障と後続回復を正確に再利用した。v9の第3段は完成PNG後に15分の待機が満了し、3PNGの通常close/Stopped後に実再起動を確認した。anchor欠落と旧released空により予定postbootを固定gateで止め、元activeの回復成功とは扱わない。その後の別許可による試験では、同hostの実boot差と旧正常releasedを確認し、同root/pathに新ownerのfresh VMを再構成して3PNG独立RGBA差分0・通常close/Stoppedを確認した。旧登録history・fresh資格消費・旧lease/receipt/journal不変も照合した。この範囲の独立結果auditはPASS（追加指摘0）である。

取消後の正常なguard回収中は、bounded startの完了印が明確に未出現の場合だけ最大3秒待機する。guardは最大2秒の回収後、既知pendingへ全bytesをfsyncしてからfinalへ排他atomic公開する。破損・link・未知pending・daemon生成操作の意味未確定は待機で成功化しない。releaseと明示rmは呼出し元の10秒を延長せず、helper起動から9秒以内の残予算へlock/client/待機期限を制限する。固定probeも同じ絶対期限を使い、各clientはguard回収最大3秒を先に予約し、finallyの回収待ちも残期限を超えない。この契約は同bootの実行範囲で、power loss耐久保証ではない。

新候補はZIP tree検証に加え、`helpers/macos/qa/RuntimeArchiveFixture.java`をcoreの同packageから実行し、実`RuntimeArchive.extract`で全entryを展開する。全file集合/size/SHA、owner実行bit、展開後deep strict署名、TMP-only入口がinvalid lease拒否へ到達することを非VMで確認する。fixtureの成功はVM/描画受入を意味しない。

Issue #94の残件は実再起動後のanchor現存cold-start、uninstall残存の実確認、ジョブ世代pinの更新切替等である。製品CLIのmanaged接続・最終package/trust/licenseも別gateを残す。完成条件を独断で後続Issueへ移さず、通常PR準備はRefs #94としてOPENを維持する。受入世代と旧FAILの留保は[QA記録](../qa/validation-2026-10-18-managed-helper-macos.json)を参照する。

追加のactive試験では、ユーザーがready期限内に手動再起動し、旧ready/resources・receipt・identity・journal全集合を保持したまま公開#104から別ended証拠で閉じた。旧13操作中の未settled start1件は正常完了を補わず、anchor明確欠落から同path新owner fresh VMを準備して3PNG/RGBA差分0・通常close/Stoppedを確認した。prebootの5子は正常reap、core1件のfinal/reapはOS終了で欠けるため実boot変更/元process identity死亡へ結合する。postbootの6子はすべて正常reap。限定独立結果auditはPASSで、OwnedStopped旧boot cold-startの成功とは分ける。

## 全51版の固定pixel再受入

確定main `0fd4b7d2` の分離buildとapp-image-v10を使用した。先行6版9,212PNGの保存画像と、同じ専用VMを再利用した残45版51,053PNGを正式canonical-v1受入時の両roundへ比較し、全51版60,265PNG・102比較のRGBA差分0を確認した。51lease released空、765操作終端、204完全container IDの削除/正常NotFound、206実行child回収、同実行境界Stoppedと末尾入力・旧環境不変の小監査は成功し、独立結果auditはPASS（追加指摘0）となった。

初回の旧fixed baselineとの1.21.11 1,502枚RGB差FAILは保持する。正式受入baselineへの保存画像分析と残45実描画は別世代であり、元FAILを成功へ書き換えない。今回の固定pixel受入から代表World意味確認の保証範囲を拡張しない。#97/#98の実uninstall/更新接続、macOS15.7.7以外の実測/最終配布と現存anchor旧boot OwnedStopped分岐の未実測を残し、Refs #94 OPENを維持する。

## 配布inventoryの生成

`helpers/macos/scripts/build-inventory.py`はMac host-helperの最終file manifest、component対応表、確定した公開URLから、inventoryと既存descriptorのartifact項目を生成する。対象はApple Silicon・`minimumOsVersion=15.7.7`のhost-helper ZIPだけであり、guest/rendererやtool payloadの発行、core resourceの同梱、署名は行わない。

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
