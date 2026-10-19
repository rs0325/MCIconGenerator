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

containerのnameは作成意図であり、実生成後は完全IDと実inspect上のjob/owner/descriptor labelを照合する。label/imageの期待値はlease/descriptorから得て冗長保存しない。create→recordの窓は意図/CIDと所有labelで照合し、名前prefixだけで削除しない。Linuxは以下のrootless候補方式の閉recordを使用する。recordの保存成功は実OSの所有や停止権限を証明しない。

## Linux rootlessの所有record

専用rootless Dockerとsystemd user unitを#96の実装候補とし、最初の受入候補をUbuntu 24.04/systemd/cgroup v2/x86_64 nativeとする。arm64 Linux guest＋Rosettaの方式実証は限定成功であり、正式OS範囲・最低版・物理Linux/amd64受入・製品helper実装の完了を示さない。固定amd64配布物・必要source/license/trustと実受入はrelease gateとして残す。

`resources.linux` は次の閉objectとする。公開API・protocol 1・lease identity/receipt・CAS/lockは変更しない。未リリースの旧Linux形式への移行・省略fallbackは追加せず、未知branch/fieldを拒否する。

| field | 保存する識別と必須条件 |
| --- | --- |
| phase / generationOwner / runtimeDir | starting/ready/releasing、世代UUID、正規化済みの絶対専用directory。全phaseで必須 |
| hostBootId / uid / containers | Linux boot_id UUID、非rootの実UID、container意図/実体の配列。全phaseで必須 |
| userUnit | name/path/sha256を持つ作成意図。nameは `mcicon-rootless-<generationOwner>.service`、pathの末尾も同名。readyでは実ControlGroupを必須とし、対象UIDのuser@UID.service配下かつ末尾が同unit名であることを検査 |
| rootlesskit / dockerd | 実host PIDと/procのstartTicksの組。数値は64bit整数のまま保持し、PIDだけや異なるprocessの同一PIDを拒否。readyで両方必須 |
| userNamespace | 実user namespaceのid、uid_map/gid_map元bytesのSHA-256。readyで必須 |
| dockerSocket | path/dev/ino/uid。readyで必須、socket UIDはrecordの実UIDと一致。専用親directory0700と実socket型/ownerはOS側が確認 |

Linux絶対pathは空segment・`.`・`..`・NUL・末尾slashを拒否する。unit/socket/世代rootはそれぞれ実path、専用marker/owner、期待body/modeをOS helperが確認し、他ユーザー設定や既存daemonの記録を取り込まない。UID501、GID1000、実証時の巨大subid rangeは製品の固定値にしない。

containerはnameだけの作成意図と、name/id/startedAtを持つ実体を区別する。idとstartedAtは一方だけを保存できず、名前/IDの重複を拒否する。実体は完全IDとinspectのStartedAtで再照合し、job/owner/descriptor label・imageの期待値をlease/descriptorから得る。名前prefixやphaseだけでrmを許可しない。create/start/recordの窓では意図/CIDと実inspectを結合し、StartedAtの変化を無条件に上書きしない。

starting/releasingはunitのcontrolGroupや実process/namespace/socketがない部分記録を保存できる。readyは全実identityを必要とするが、**readyの必須fieldやhashが揃ったことだけでは停止を許可しない**。OS helperはhost boot/UID、世代marker、unit file/実ControlGroup、両processのUID/PID/startTicks、namespace/maps、socketを実体と照合する。daemonとrootlesskitの初回identityをmap/namespace/socketの読取より前に採り、各読取後に同じPID/startTicks/UIDとnamespace、unit MainPID/ControlGroupを再確認してからready保存や回収へ進む。採取後に初めて開始識別を取る順序ではPID再利用の窓が残るため不可とする。

停止時も同じ実測をやり直し、全他leaseの不在確認からunit停止まで専用lifecycle lockを保持する。欠落・再利用・別boot・未知/破損lease・unit/socket不一致では状態を保持して失敗し、partial recordを停止可能へ補完しない。release失敗時のactive保持と、coreだけがreleasedへ変更する契約は共通のままである。共通validatorは実OSを操作せず、#96がこの実測・停止・有限子孫回収を実装し受入する。

OS前提不足はsetup-requiredで必要操作を示す。helperからsudo、OS依存導入、user manager全体restart、AppArmor/sysctl変更、rootful fallbackを自動実行しない。許可された実証初回のOS準備を製品の既定動作へ流用しない。

固定fixtureは `schemas/managed-helper-linux-v1.examples.json` でJavaの直接validator/CAS試験とJSON schema試験を共有する。schemaは型/閉field/ready必須情報を検査し、UID/unit/cgroupの対応、process重複、container ID/名前の一意性は共通validatorでも意味検査する。これらのfixture成功をLinux実OSの所有・回収受入に数えない。

## transportと残件

`docker -- <argv>`は現callerのimage inspect、run（診断含む）、rm -fを同じleaseで扱い、stdin/stdout/exitとworker protocolを透過する。helper診断はstderr/専用記録へ分ける。Windows bind sourceは専用guestへ同じsnapshot bytes/hashを同期して変換し、任意host pathや他jobへ範囲を広げない。共通moduleはDockerを実行せず、各OSの輸送/監督/所有回収を実装しない。

構造fixture成功はVM/WSL/rootless所有や停止成功ではない。本番bundle/trust/pins/公開先、OS helper実装と実機/並行/取消/異常終了の受入、Ubuntu署名chain/実配布条件、固定51版の画像受入は未完了のまま維持する。
