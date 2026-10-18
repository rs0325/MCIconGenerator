# macOS実機受入計画と限定結果

この文書は各段の実行許可を固定した計画と、実施済み範囲を記録する。以下の実行順は検証候補を含み、すべてを実行した一覧ではない。Main/VM/Dockerの接続実装と限定反証は完了した。第1段runnerは `helpers/macos/scripts/native-acceptance.py` で、`prepare` はローカルfixtureデータだけを作り、`execute-vm` は別途の明示許可後だけ実行する。実path・artifact pin・凍結SHAは私用approval planへ記録する。第1段は具体許可のもと実施し、固定offline準備・診断・通常releaseと結果auditが成功した。第2段のraw・4代表・offline・片側取消・既知異常回収も別候補で限定受入した。第3段の通常close後の実再起動と、別許可の欠落cache再構成は末尾へ記録する。

## 対象と隔離

Apple Silicon/macOS 15.7.7、VZ利用可、Rosetta導入済みを読取確認したホストだけを最初の対象にする。IntelとOS最低版の成功へ拡張しない。試験は新規の専用runtimeRoot、固定markerを持つ短LIMA_HOME、専用instance `v` の最大1VMと最大2jobに限定する。

runtimeRootは試験専用directoryに置き、既存のcache/outputを入力に使わない。rootとUID/hostId/descriptor SHAが一致する0700短anchorを計算し、未知の既存anchor、別UID、link、marker不一致なら開始せず失敗する。既存Lima/Colima/DockerのVM、context、ソケット、HOME、鍵、設定を停止・削除・上書きしない。実行前後の既存環境の読取一覧を比較する。

Limaが内部接続用のSSH認証keyを専用LIMA_HOMEに自動生成することは、このVM試験のguest輸送範囲に含む。release署名key、Developer ID、ユーザーSSH keyの生成・使用を含まない。

## 固定入力と不足

- Lima 2.2.1 Darwin-arm64 archive: 38,328,082 bytes、SHA-256 `9e9eacce88f37e185c346bad73aa6136f738d8cdf8c3bb23cd42b071824bc66e`。tasklocal実bytes照合済み。
- Ubuntu noble arm64 cloud release 20260926 qcow2: 620,224,512 bytes、SHA-256 `1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55`。同署名SUMSにあるmanifest SHA-256 `be8373ae3b3be49905c1e9984196196cc9148c6437ec96a735e90428ee8b8ac6`と組にする。本試験専用領域での元qcow2実bytes照合は実行前gate。
- Ubuntu snapshot `20261017T000000Z`、arm64のdocker.io/containerd/runc固定3deb合計65,765,174 bytes。署名元索引→版/size/hash/control→実bytesの照合証拠を保全済み。source9fileも別に保全済み。
- native Linux-amd64 CPUID probeは固定assemblyからtasklocal生成し、未解決relocation無しとELF headerを検査する。実Rosetta経路でのSSE4.1は第1段で確認した。
- 実験用renderer候補は既存私用監査のc498、593,534,813 bytes、SHA-256 `1dcf9b4363583ed6c9cf5847aaf5648aaaf80a084f978b075d1b4fe483ed2e70`を再利用する。実ファイルと既存証拠、image ID、Java/Mesa/LLVM、fixture descriptor/inventoryを第1段runnerへ固定して実行した。production trust/keyの発行や最終製品artifact採用を実験の前提にしない。全体license/source最終監査は別gateとして保持する。

3debの依存候補をsigned base manifestへ結合しただけではoffline dpkg成功とは扱わない。固定3debの実offline dpkgは第1段で別に確認した。guestではネットワーク取得・apt update・apt installを行わず、固定ro mountのdebだけでdpkgを実行する。失敗時にオンラインfallbackしない。

## 有限範囲

準備の試験上限は20分、各制御clientは操作別期限、release/明示rmはcore側10秒以内、helper内9秒の残予算で結果を確定する。期限超過や所有不明を成功へ変換しない。VMは固定4CPU、disk上限24GiB。memoryはdescriptor/lease予算と実ホストの空き予算を照合してrunnerへ固定値を記録する。未確定renderer容量を含む必要空き容量が計算できるまで開始しない。

新規downloadを試験中に増やさず、元archive/hashsize検証済みのローカル入力だけを使う。offline再実行では取得経路を無効にした同じ入力で確認する。ユーザーデータ、PNG、公式cacheをVM内だけに残さない。

## 実行順と証拠

1. 保存済みapp-imageのentry/JAR/JRE/configを照合し、空白・長いruntimeRootでTMP-onlyの絶対entry起動を確認する。補助JAR/JRE/configの改変、link混入、未知anchorを拒否するfixtureは実VM前に終える。
2. rawのみの呼出しがVMを準備しないことを確認する。未導入専用状態の初回prepareでは、固定base/guestagent/offline deb/rendererだけを使い、VM/guest/daemon marker、VZ identifier hash、固定設定hash、hostAgent開始identity、socket path/dev/ino/uidを保存する。
3. aarch64 guest上のamd64 CPUID probeでSSE4.1を確認し、descriptorのrenderer ID、platform、Java major/hash、Mesa/LLVMを照合する。代表版を描画し、各resultの転送前にhost側PNGのsize/hashと存在を確認する。
4. 2回目と2jobを実行する。同一VM/世代を再利用し、snapshotとPNGをjob間で混同しないこと、VM予算が重複計上されないことを確認する。保存済み画像との比較結果を別記録にする。
5. create直前/応答前/登録前、start待機中、render中、semantic保存前、release tombstone後の各窓で自作成helper/coreだけを停止する。guard自身の異常は未確認を保持する。full ID rm-f正常応答と正常な不存在、他leaseのbusy/unknownによるVM停止拒否、旧client再開による新規生成拒否を確認する。
6. offlineの新規専用rootで再生成可能性を確認する。activeを残した短cache消失、host再起動/rootcopyでは自動clearせず診断する。現存anchorに限定したcore recoveryは確定共通commitとの統合snapshotで別に受入する。
7. 全job回収後だけ共有VMを停止する。instance/owner/machine ID/config/guest markerと全他lease観測が欠ける場合は操作せず証拠を保持する。試験専用の成功済み再生成可能cacheだけを削除候補にし、登録/lease/未確認操作/PNG/結果記録を自動削除しない。

結果は試験root外の専用artifact directoryへargv、環境のkey、元入力pin、各phaseの所有記録、期限、PID/start、stdout/stderr、exit、画像hash、回収結果、既存環境差分を保存する。秘密keyの内容は保存しない。fixture、実OS、全51版の画素受入を区別して報告する。

## 第1段の実行単位

最初の許可範囲は専用1VM・1jobでのpreflight、offline prepare（実amd64 CPUIDを含む）、image inspect、固定Java binaryのversion/hash診断、通常releaseだけとする。worker agentを起動せず、render・2job・故障窓は後段の未受入項目として残す。

runnerはproduction署名/trustとcoreのarchive取得・展開を代替するものではない。検証済みapp-imageと固定base/rendererを専用rootへ置き、private protocol fixtureのdescriptor/inventory/lease/receiptを作る。worker SHAのゼロ値はworker未起動のfixture値で、製品descriptorとして採用しない。元core代役のrunner PIDとnative開始時刻をreceiptへ結合し、64/1のcore lockを保持する。release成功でresourcesが空になった場合だけ、core代役がstateをreleasedへ変更する。

全体上限30分、prepare上限20分、各診断30秒、release10秒。通常操作には最終回収15秒を予約する。期限でhelperを停止しても、不明なdaemon操作や資源を直接kill/削除して成功化しない。releaseが失敗した場合はactiveと専用VM/cacheを証拠付きで保持し、許可範囲を超える追加回収へ進まない。runner終了後の任意cleanup/retryは含まない。

Lima内部のSSH認証keyは専用短LIMA_HOME内だけで生成され、公開鍵・秘密鍵の内容をログへ複製しない。既存 `.lima` / `.colima` / `.docker` の登録名・型、設定/hostAgent PID等のhashを読取比較し、既存VMを停止・変更しない。比較差があれば成功にしない。

実行例（実pathとpinを私用planで固定してから使用）:

```text
python3 helpers/macos/scripts/native-acceptance.py prepare --image <app-image> --packaging <packaging.json> --helper-zip <helper.zip> --base <固定qcow2> --renderer <固定renderer> --output <新規fixture領域>
# 次の操作には実VM作成・起動・guest操作・内部SSH鍵生成の明示許可が必要
python3 helpers/macos/scripts/native-acceptance.py execute-vm --plan <凍結plan.json>
```

## 第1段の結果と残りの受入

Apple Siliconの専用1VM・1jobで、8応答すべてexit 0と子process回収を確認した。offline dpkg、guest daemon、amd64 SSE4.1、renderer c498、Java25/21のversion/hash、18操作intentと5完全container IDの削除・正常NotFound、lease released/resources空、VZ停止ログ、既存環境と旧partial不変を照合し、結果の限定auditはPASSとなった。

実行後のdefault sandbox読取はBroken/errors[]を返し、初回assert FAILを保存した。同じbinary/argv/envをVM本体と同じ実行境界で読取するとStoppedとなった。直接kern.bootsessionuuid照会はsandboxでEPERM、同実行境界では成功する。LimaのPID読取前BootSessionID照会とエラー表示処理に整合するが、Lima内部errnoは直接traceしていない。両観測を保存し、VM再起動で修正した結果とは扱わない。

## 第2段と第3段の結果・残件

第2段はapp-image-v7と実ビルドtool/worker/adapter pin、private test trust、#104を含む専用consumerの公開renderManagedで実施した。raw、1.13/1.20.6/1.21.4/26.3の12PNG独立RGBA差分0、2回目offline、同VMの2job片側取消後の他job継続、known helper異常/通常closeを限定受入した。bind表示誤検査で終了したown core2件と公開回復の証拠を合成し、予定singlecorekillを新たに実行したとは扱わない。製品CLIにはmanaged接続がなく、production CLIの受入とは区別する。

第3段はhostBinding/reboot入りapp-image-v9で1PNG公開確認後に手動再起動を待ったが、15分窓満了で3PNG通常close/Stoppedを完了した。その後の実再起動ではanchorが欠落し、旧jobもreleased空だったため固定postbootを停止した。元active leaseの再起動回復は未達である。

別許可の最小試験は同root/descriptor/pathの旧正常releasedから新owner VM1台を固定base/offline closureで再構成した。全体30分/prepare20分/最終30秒予約、4CPU/9GiB/24GiB、空き40GiB以上に限定した。3PNG独立RGBA差分0、6子process回収、旧history/fresh資格消費、旧証拠不変、通常close/Stoppedを確認し、この範囲の独立結果auditはPASSとして記録する。署名を再利用し、新keyやOS再起動は追加していない。

過去3anchor側の11資料欠落と永続31資料一致を現在baselineとして分離する。旧partialをclearせず、旧当時freezeと全FAILを保全する。anchor削除者/時刻は未trace。実再起動後のanchor現存cold-start、アンインストール残存実確認、世代pin更新、Intel/最低OS/最終配布は未完了であり、Refs #94 OPENを維持する。受入SHAと実行世代は[QA記録](../qa/validation-2026-10-18-managed-helper-macos.json)へ記載する。

追加active試験(v9)は、旧3released/144pinを固定して既Stopped VMを再利用した。ユーザーが完成PNG readyの15分窓内に手動再起動し、公開#104で元activeの別ended証拠を保存して同path新ownerのmissing-freshへ進んだ。元identity/receipt/resources/journalを保持し、旧未settled start1件を正常化しない。3PNG/RGBA差分0・全5released・postboot6子reap・Stoppedを確認し、この範囲の限定独立結果auditはPASS（追加指摘0）である。preboot coreのfinal/reap欠落はOS終了の別分類とし、旧boot OwnedStopped cold-startは未実行である。

## 全51版の固定pixel再受入

確定main `0fd4b7d2` の分離buildとapp-image-v10を使用した。先行6版9,212PNGの保存画像と、同じ専用VMを再利用した残45版51,053PNGを正式canonical-v1受入時の両roundへ比較し、全51版60,265PNG・102比較のRGBA差分0を確認した。51lease released空、765操作終端、204完全container IDの削除/正常NotFound、206実行child回収、同実行境界Stoppedと末尾入力・旧環境不変の小監査は成功し、独立結果auditはPASS（追加指摘0）となった。

初回の旧fixed baselineとの1.21.11 1,502枚RGB差FAILは保持する。正式受入baselineへの保存画像分析と残45実描画は別世代であり、元FAILを成功へ書き換えない。今回の固定pixel受入から代表World意味確認の保証範囲を拡張しない。#97/#98の実uninstall/更新接続、Intel/最低OS/最終配布と現存anchor旧boot OwnedStopped分岐の未実測を残し、Refs #94 OPENを維持する。
