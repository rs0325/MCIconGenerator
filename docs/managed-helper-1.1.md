# helper protocol 1の共通実装契約

Issue #93のiteration 2。公開APIとprotocol版は増やさず、未リリースleaseにrequirementsを加える。旧leaseの移行や省略時fallbackは実装しない。

## 配置と呼出し

`helpers/common`（Gradle `:helper-common`）はJava 21/Jacksonの内部moduleであり、Mavenへ公開しない。core/CLI/workerの依存へ追加せず、通常配布/署名候補にhelper classが混ざらないことを監査する。各OS専用Mainが`HelperProtocol.parse`、`LeaseFile.open`と4operationの明示switchを使う。`LeaseFile.descriptor()`はopen時に元bytes hashを照合したJSONのdeep copyを返し、OS preflightはminimumOsVersion/host/rendering等を参照できる。JSON型を使うOS moduleのcompile用にJacksonをapi依存とするが、共通module自体は公開しない。provider SPIや反射discovery、共通moduleのOS起動/停止処理は持たない。

entrypointは署名descriptorのhost-helper archive内の絶対native executableを直接起動する。OSごとのJava 21 `jpackage --type app-image`と自己完結runtimeを採用し、CLI/toolpayloadのJREやhost Java/PATHへ依存しない。同じ入力runtime版を#97と揃えることと、設置済みpathを共有することは区別する。OSごとのapp-image build、runtime/license/source、Lima等の補助binary、OS署名/実機受入は別の未完了gateである。

coreが与える環境はjob専用TMPDIR/TMP/TEMPとWindowsの必要なOS環境だけ。native launcherやOSがDYLD等を追加する場合があり、helper内の全environmentがcore作成Mapと完全一致するとは仮定しない。利用者のHOME/PATHや資格情報は継承しない。helperが専用HOME/LIMA_HOMEと同梱toolの絶対pathを設定する。

host archiveのlink拒否は維持する。jlinkが生成するlegal等の内部linkは管理staging内の実fileへ置き換え、外向き/循環/特殊fileは拒否する。実体化と最終app-image配置の後にOS署名を行い、その最終bytesからtree/hash/inventoryを固定してarchive化する。inventoryは署名済bundle外のdescriptor参照資料とし、固定後のbundleを書き換えない。最終archiveのlinks 0、実native起動と移動/展開後起動を各OSで検証する。Macの限定実験は他OSの受入を示さない。

## requirementsとreceipt

leaseのimmutable `requirements` は`containerMemoryMiB`、`heapMemoryMiB`、`cpus`、`preparationDeadline`だけを持つ。memoryはManagedOptions値、heapはcontainer未満、cpusは既存workerと同じ4、deadlineは準備開始時のUTC InstantにpreparationTimeoutを加えた期限。VMの余裕や並行job合計はOS helperがlifecycle排他の下で算定し、coreはVM実予算を推測しない。

UTC deadlineはhelperの事前確認用であり、準備全体の権威はcoreのmonotonic deadlineである。時計変更を許可期間の延長に使わない。準備期限をdocker/render/releaseや過去leaseのidle判定へ適用しない。preflightはhost要件/既存所有/容量/予算の検査、prepareは固定guest/imageの構成後に実amd64経路・SSE4.1等を検査する。

receiptはstate/resourcesを除くidentity全体を全階層でfield名順に並べ、空白無しUTF-8 JSONのSHA-256を小文字ASCII 64bytesで保持する。数値は小数や浮動小数へ変換せず整数10進、UUID/版/digestは既定のASCII形式を保つ。固定入力・canonical bytes・SHAは`schemas/managed-helper-v1.examples.json`に置き、2^53超のPIDとnested requirementsの順序をcore/common両方で確認する。同権限利用者の意図的な全証拠改竄への防御は保証しない。

## lockとatomic更新

| 対象 | 範囲 | 所有者・用途 |
| --- | --- | --- |
| `lease.lock` receipt | offset 0..63、file長は厳密64bytes | coreが一度だけ書き、helperはこの範囲だけを読む |
| `lease.lock` 稼働lock | offset 64、length 1、exclusive | coreが保持。idleは同じ範囲を取得してからreceiptを読む |
| `tmp/helper-lease.lock` | offset 0、length 1、exclusive | 共通helperの短いresources更新。core稼働lockと別 |
| OS共有環境のlifecycle lock | OS担当が同じ共有環境に対して固定 | 並行prepareと全他lease確認→環境停止の間を排他 |

coreは稼働lock取得→receipt書込/force→lease atomic公開→job descriptor/inventory/tmpの確定→helper起動の順で可視化する。helperは稼働lock範囲を読まない/取得しない。Windows mandatory byte-range lockを考慮した契約であり、Windows実機成功を示さない。

`LeaseFile.replaceResources`は更新lock取得→現identity/requirements/receipt再照合→期待resourcesと比較→resourcesだけ置換→tmp内stagingを同一filesystemでatomic replace→lock解放と進む。lock待機は最大2秒で、待機前・lock取得後・atomic replace直前にthread中断を拒否する。最後の中断確認とatomic replaceは不可分ではないため、その間やcommit後の中断で完了済み更新を巻き戻すことはしない。長いOS I/Oをlock中に行わず、その前後で短いCAS更新を行う。競合した結果を無条件に上書きしない。coreだけがrelease成功/空resourcesを確認してstateをreleasedへ変える。

## schemaとOS所有

`schemas/managed-helper-v1.schema.json`はlease/controlをrootで検証し、$defsのidentity/requirements/resourcesは固定fixtureの参照先とする。重複key、trailing JSON、予算の大小、ID重複/参照整合や実OSの所有はschemaだけで完結せず、共通validatorとOS側で検査する。`processStartedAt:null`は記録できるが、PID単独で停止・回収しない。

Macはstarting/ready/releasingの閉objectとする。readyには世代owner、instanceDir、実`vz-identifier` bytesのhash、hostAgentのPID/開始/boot session、socket path/dev/ino/uid、固定forward設定hash、guest owner marker hash、containersを要する。socketの値は輸送の照合でありsocket peerを直接killする権限ではない。ha.pidをDocker socket daemon本人性へ流用しない。全他lease不在確認からlimactl stopまでlifecycle lockを保持し、部分起動・識別子不足・不明leaseでは停止せず証拠を残す。

Windowsはimporting/ready/releasingの閉objectとする。distributionの名前/BasePathだけは生成前の意図、GUID/marker hashを持つものが登録済record。readyでは登録済情報とsocket/guest job pathを必須にする。意図やregistry差分が不明なdistributionを取り込まない。releasingでも削除にはready相当の実registry GUID/BasePath、host記録、guest markerの一致を必要とし、phase名だけで削除しない。transportはidだけの意図とPID/processStartedAtを伴う実体を区別し、containerのtransport IDを照合する。Job HANDLEは永続保存しない。

containerのnameは作成意図であり、実生成後は完全IDと実inspect上のjob/owner/descriptor labelを照合する。label/imageの期待値はlease/descriptorから得て冗長保存しない。create→recordの窓は意図/CIDと所有labelで照合し、名前prefixだけで削除しない。Linux rootlessは方式未採用なので成功resources branchを定義せず拒否する。

## transportと残件

`docker -- <argv>`は現callerのimage inspect、run（診断含む）、rm -fを同じleaseで扱い、stdin/stdout/exitとworker protocolを透過する。helper診断はstderr/専用記録へ分ける。Windows bind sourceは専用guestへ同じsnapshot bytes/hashを同期して変換し、任意host pathや他jobへ範囲を広げない。共通moduleはDockerを実行せず、各OSの輸送/監督/所有回収を実装しない。

構造fixture成功はVM/WSL/rootless所有や停止成功ではない。本番bundle/trust/pins/公開先、OS helper実装と実機/並行/取消/異常終了の受入、Ubuntu署名chain/実配布条件、固定51版の画像受入は未完了のまま維持する。
