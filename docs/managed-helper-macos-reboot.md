# macOS管理runtimeのhost照合・再起動と残存データ

## host照合の実装範囲

native probeはIOPlatformUUIDをメモリ内で読み、次のUTF-8 bytesをSHA-256化する。

`mcicon.macos.host-binding.v1 + NUL + runtimeRootのhostId UUID + NUL + 小文字の正規machine UUID`

raw machine UUIDは保存・argv・応答・診断へ出さない。登録用digestとboot識別はローカル内部だけで使用し、公開QA・公開ログ・tool応答へ転記しない。private job tmp内のprobe stdoutはローカル内部用途としてのみ保存し、QA copy/exportへ含めない。saltは既存rootのhostIdで、同じhostでも別rootを横断する共通IDを作らない。これは暗号attestationや意図的な同権限改竄への防御ではない。UUID取得不能や不正値にはfallbackしない。

CacheStateの内部登録版2は`hostBindingSha256`と`bootSessionId`を追加し、anchor markerにも同じobjectを保存する。VM生成より前に永続登録を公開する。公開API、helper protocol、共通lease/resources/schemaは変更しない。旧fieldのない登録は移行・補完せず保持して拒否する。過去の未release fixtureも上書きしない。

同binding・同bootでは既存の所有確認を行う。別binding、root/UID/descriptor/ownerの不一致、boot差、同bootのcache欠落は通常prepareを拒否し、unknownを解消したことにしない。未着手の失敗cleanupは従来どおり自身jobのresources/生成intent不在とlive ownerを検査し、VM/cacheを操作せず閉じる。

再起動終了と再開の最小分岐を実装している。実host再起動後に正常releasedから欠落cacheを再構成する分岐は実行済みで、元active leaseの終了・回復もanchor欠落fresh分岐で実行し、この範囲の限定独立結果auditはPASS（追加指摘0）である。anchor現存の旧boot cold-startは未受入である。対象は同host/同世代の正当なready/releasingと、正常create/完全IDへ結合したbounded startに限る。未確定create/prepare、partial、現在bootのRunning/不明資源は保持する。

旧bootの元core死亡・全同世代参照・当該limactl実行物の不在をlifecycle下で検査し、元journal snapshotとresources/receipt/登録hashへ結合した別の`macos-ended-by-reboot.json`を保存してからresourcesだけを空にする。正常client/daemon完了印は補完しない。coreだけがreleasedを記録する。同時recoveryhelperの生存や別job fence保持をVM生成者と同一視しない。

全旧参照がreleased/lockfreeになってから旧登録をowner別のboot履歴へ保持する。完全owned/Stopped anchorならowner/disk/configの選択fieldを維持してbootだけ整合しcold-startする。新workerより前に、旧終了証拠のfullID/name/元owner labelsを再照合し、実rmと正常NotFoundを確認する。掃除未確定なら現在資源の正常closeも成功化しない。anchorが明確に欠落している場合だけ、同じ正規pathへ新ownerと固定baseのfresh VMを準備できる。fresh生成資格はstarting資源公開・native生成より前に使用済み証拠を確定して一度だけ消費する。同bootでその後instance/VM記録が消失しても再生成しない。登録/markerの書込途中不一致を推測修復しない。

観測は当該managed実行物に限定し、任意VZの不存在や同権限改竄への耐性を主張しない。nativeは同UID processの実行物pathをメモリ比較し、無関係argvやpathを保存しない。観測失敗/overflow/ESRCH以外の未分類errnoはunknown。fixtureと非VM検証を実再起動/実daemon掃除の代用にはしない。

## 再起動後に引き継ぐデータ

再開対象はhostへ確定公開した入力・PNG・manifestであり、旧VMのRAMやworker実行を継続する意味ではない。確定PNGは元cacheへsize/hash照合とatomic copyを終えてから応答している。anchor欠落時のfresh VMには古いmutable diskやsaved stateを使わない。完全owned/Stoppedの場合だけ専用diskをcold-startで再利用し、RAM復元を追加しない。旧host・boot・資源の観測が不明なら保持し、新pathや別VMで迂回しない。

## 通常アンインストールと残存

#97のpackage removalはpackage所有のlauncher等だけを対象とする。任意API runtimeRoot、公式cache、PNG、lease、partialやOSS/source資料を一括探索・削除しない。#98のpayload GCは全参照不在を確認したowned payloadだけを対象とし、active/unknownが参照するhelper/JRE/descriptor/trustを保持する。

#94 backendは通常job releaseで自身の資源を停止・参照解放する。アンインストールからname/prefix/PIDだけのkillやroot purgeを追加しない。VMcacheは短い専用OS領域、登録・lease・journalはruntimeRootに残る。unknownがある間は、その本人性と回復に必要なbinding/登録/helperも保持する。完全削除は停止・所有・全参照不在と対象を明示する別操作であり、本変更でcleanup API/CLIを追加しない。

fixture成功は実再起動、installer uninstall、全OS/51版の受入には広げない。実値を使った検証結果は一致・分離・正規形式などの真偽だけを保存する。

## 実機で確認した範囲

v9のprebootは1PNG公開を独立照合した後、手動再起動を待つ15分の窓が満了して通常closeした。3PNG一致、11子process回収、Stoppedを保存した。後の実再起動でsame host/boot変更とanchor欠落を確認したが、旧jobは既にreleased空だったため固定postbootは停止した。これをactive reboot回復へ読み替えない。

続く別許可の試験では、旧正常closeのidentity/receipt/全journalを保持し、同root/descriptor/pathの新owner VMを固定入力から再構成した。3PNG独立RGBA差分0、6子process回収、旧history/fresh資格消費、全2lease released空、同境界Stoppedを確認した。新署名keyや追加のOS再起動は使用していない。この範囲の独立結果auditはPASSであり、その後のactive終了証拠・旧boot cold-start・uninstallの受入とは分ける。

OS再起動後は過去3anchorの11資料も欠落し、永続31資料は当時pinと一致した。削除者/時刻のtraceはなく、OSによる削除とは断定しない。過去freezeは当時bytesのまま残し、現在の欠落baselineを不変成功と混同しない。OS組込みhier(7)は/tmpを一時領域、/var/tmpをreboot間で保持する領域と区別しており、短cacheの永続性を前提にしない。

active reboot試験を再計画する際は、ユーザーの再起動可能時機を先に確認する。待機上限内に再起動しなければ通常closeして未達とし、active状態を作るための強制kill・boot値偽装・自動再起動を追加しない。

## #94の保持対象とuninstall接続gate

通常のhelper releaseは自身の完全所有containerを除去し、全他leaseが非activeかつ既知の場合だけ専用VMを停止する。tombstoneを保存してresourcesを空にし、coreがreleasedを記録する。別jobが稼働中ならそのjobを止めず、unknownがあれば停止を許可しない。この資源解放をアンインストールやdisk削除と同一視しない。

| 対象 | #94 release後の扱い | package/GCとの境界 |
| --- | --- | --- |
| ホストへ確定したPNG/manifest、公式入力cache | 保持。releaseでは削除しない | 通常uninstallで削除しない |
| runtimeRootのhost-id、登録、boot履歴、binding | 保持。API指定rootを探索・purgeしない | #97のpackage所有物と混同しない |
| lease/receipt、操作journal、終了証拠、失敗診断 | 保持。資源状態と証拠を残す | #98の明示的な参照解放・GC契約が必要 |
| 展開helper/JRE、descriptor/trust、immutable artifact | #94では削除しない | active/unknownが参照する世代をpackage removal/GCで消さない |
| job staging/snapshot | #94 releaseでは保持 | 公式cache/PNGと区別したowned GC対象の契約が必要 |
| 短anchorのVM disk・Lima内部SSH認証key | 所有確認してVMを停止するが削除しない | OSによるcache消失とuninstallの削除を区別する |
| package所有launcher等 | #94の管理対象外 | #97の実package removalで確認する |

host側の削除はsnapshot copyやjournal publicationで自作成した一時fileの後片付けに限り、上表をpurgeする入口は追加していない。stopped専用diskが残ることは通常closeの失敗ではない。反対に、active/unknownを残したままdiskや回復用helperを削除して解決したとは扱わない。

#94単体では、実描画の通常close後に入力/PNG、旧lease/receipt/journal、登録/history、停止VMが保持されること、unknown/busyで共有停止しないことを確認している。既存単体はreleaseUnstarted/lease分類/reboot履歴を、実機証拠は正常close前後の不変を覆う。package removalやGCの入口を呼んだfixtureはなく、無関係な模擬uninstallを追加して成功扱いしない。

残る実接続gateは、#97の実package削除で任意API root/公式cache/PNG/失敗資料が残り、#98の実GCでactive/unknown参照のhelper/JRE/descriptor/trustが保持されること、全参照解放後の対象選択が所有外を含まないことを確認することである。完全削除の新APIや権限はここで確定しない。これらの依存実装・実uninstallの検証前は、Issue #94の残存データ扱いを全面受入としない。

active再試験前のmissing再構成の通常releaseでは、残存inventoryの小記録35件に入力/PNG、disk、内部keyの存在・UID/type/statを結合した。内部key本文は読取/hashせず、大容量入力のhashも繰り返していない。旧partial activeは保持している。これはbackend非purgeの確認で、実uninstallとは区別する。#97はpackage/bootstrapper JREとuser/API rootの境界を実removeで確認し、#98はactive/pending/previous、全lease、登録/history/VM設定、descriptor/trustの参照閉包とOS lifecycle排他を確定する必要がある。idleやresources空だけを削除権限にはしない。

## 実active rebootのmissing分岐

時機を再確認した試験では、現在時刻20:07:54 JST・期限20:22:46 JSTを通知し、その窓内にユーザーが手動再起動した。postbootの実samehost/boot変更と旧core死亡/開始identity、元activeの全集合を照合し、旧13操作を別ended証拠で閉じた。未settled start1件のclient/semantic完了を追加せず、元journalを保持した。

anchorは明確NotFoundだったため同path新ownerのfresh VMを準備し、3PNG独立RGBA差分0、全5lease released空、history/fresh資格消費、旧3job144pinと旧baseline不変、通常close/同境界Stoppedを確認した。preboot coreはOS終了でfinal/reap記録がなく、正常reapとはせず実boot/process identity死亡gateに分ける。prebootの他5子とpostboot6子は正常reapした。限定独立結果auditはPASS（追加指摘0）。現存anchorの旧disk rm/NotFoundとcold-startはこの試験で実行しておらず、未受入のまま残す。

## 同boot cold-startと旧boot分岐の受入境界

v2 prebootの既保存記録では、StoppedからRunningへの実起動と、前後のowner登録・machine ID・固定configのhash一致、1PNG独立RGBA差分0を確認できる。同bootの専用disk cold-startは実測済みであり、再度VMを起動しても未実測の旧boot登録切替を埋めることにはならない。

anchorを保持した実旧bootでの登録切替と旧fullID掃除からのcold-startは、直接fixture/重点反証のみである。実active missing-freshの成功と組み合わせて一連の実OS結果へ読み替えない。boot値偽装、OS設定変更、anchor移動/復元や別pathで実証を作らず、追加実機の要否と受入範囲は完成条件に照らして判断を残す。
