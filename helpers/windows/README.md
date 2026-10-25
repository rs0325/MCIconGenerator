# Windows managed helper（Issue #95）

Java 21の自己完結app-imageと同梱native Job入口から、検証済みprepared rootfsの専用WSL 2だけを利用する。hostのJava/Python/Docker Desktop/PATHに依存しない。ビルド用Python/LLVM-MinGW/JDKとguest内Pythonは別の依存であり、利用時のhost導入・apt・OS機能有効化・昇格・再起動は行わない。現時点では本番descriptor/trust/rootfsを発行していない。

## 所有と回復

`Main`は共通protocol/`LeaseFile`をそのまま検査する。`WindowsRuntime`はpreflight→prepare→docker→releaseを実装し、host環境はjob専用TMP類とSystemRoot/WINDIRだけとする。WSL executableはSystemRoot配下の固定絶対pathを使い、shell/PowerShellを経由しない。guest要求は短い固定argvとUTF-8のstdin envelopeに分ける。

`WslDistribution`はroot/windows/descriptor SHA配下に、SID・正規root・hostId・descriptor・generationを保存する。名前だけで既存distroを採用せず、HKCU LxssのGUID/BasePath/WSL versionとguest markerを照合する。import意図だけ残った登録、別owner、欠落・置換したdiskは自動clearしない。通常prepareは、既定WSL登録が無い場合にsetup-requiredで停止する。初回importがdefaultを変える可能性についてsetup側の明示同意と準備が必要で、通常経路では暗黙に実行しない。

`windows-host.cpp`はnative APIのSID/boot GUID/OS版/能力とWSL登録を読み取る。boot GUID取得不能は失敗とする。`PROC_THREAD_ATTRIBUTE_JOB_LIST`で子作成とJob登録を一体化し、`KILL_ON_JOB_CLOSE`を設定する。各起動前のJava intent、native pid/start/boot record、正常Job終端recordをjob tmpに残す。通常子の実exit採取後に自身のthread/process handleを解放し、Jobの実active数0だけを正常終端とする。child終了後の既存最大2秒を、自然drain最大500msと同じ終了時刻までの強制回収に配分する。親終了はdrainを行わず回収へ進み、例外時も2秒を再開始しない。Java側の絶対期限・既cleanup9秒・argvは変更しない。API失敗はdoneを残さず、accounting診断は有限のstderrへ出す。親死亡・timeout・PID再使用・record欠落を正常終了へ置き換えない。host JobとWSL内processの所有は別に検査する。同bootのdone欠落はguardが既に死亡していてもunknownを保持し、強制終了guardを正常Job終端recordの代用にしない。

guestbridge ABI 1は専用daemon/socket/data-root、元job/owner/descriptor/generation label、完全container IDを扱う。create前にintentを保存する。正常full ID応答が無いcreateはdaemon側の終端証明が無い限りunknownを保持し、一時的なname一覧0件でもintentを消さない。start supervisorとDocker clientはjob専用cgroup v2へ入り、releaseはfenceを閉じて`cgroup.kill`後の`populated 0`を確認する。元guest bootが変わったrecordは旧actorの終了として分類し、旧container diskは再照合して回収する。guard終了だけではguest終了にしない。

guestの`init`/`hello`はowner・bridge/engine pinの照合後、同じlifecycle lock内で専用distro-local cgroup rootを毎回確認する。初期emptyで現在所属・namespace/mount・root/leaf構造が一致するときだけcpu/memory/pidsを供給しreadbackする。既3controller供給済みならBのbuilder子やactive jobがあっても検証のみとし、partial供給は自動修復しない。hello失敗後のreleaseを含む経路でも、新daemon生成直前に同じ確認を必須とし、既存daemonの本人照合・回収は妨げない。root供給は個別jobのreleaseで解除せず、kernel状態をrootfsや永続ready recordへ保存しない。専用namespaceの同定はWSL実装と現viewの整合に基づく判断であり、他distro無影響の絶対証明ではない。H-C18 v3では本sourceとexact bytesが一致する固定guest bridgeをprepared rootfsに収録し、Windows同bootのready本人死亡後のprepare回復、別coreの同worker描画継続と正常releaseを限定受入した。ACK前死亡/partial import・曖昧createの終端・同時release・実reboot・全51版は未受入である。

他leaseは全descriptorを走査し、busy/unknownがあれば専用distroを止めない。専用distroの停止にはGUID/BasePath照合、専用`--terminate`、正常`--list --running --quiet`による不在確認を要求する。停止receiptを残し、同じguest kernel bootのまま再起動した場合のdaemon再準備と、説明不能なdaemon消失を分ける。登録解除・VHD削除・既存distro/default/global config変更・global shutdownは行わない。

## Prepared rootfsとpayload

`guest-base`はhostでtar展開しない。tar.xzの場合はdescriptorのunpackedBytes上限とXZメモリ上限を守って専用stagingへtarだけを生成し、`wsl --import --version 2`へ渡す。原archiveは保持する。rootfsには以下を事前収録する。

- `/usr/bin/python3`と標準library、`/usr/lib/mcicon/guestbridge.py`（本sourceのexact bytes、ABI 1）
- `/usr/bin/docker`、`dockerd`、`containerd`、`runc`と必要library・設定。通常利用中の導入は禁止
- `/usr/lib/mcicon/cpu-probe`（amd64 SSE4.1検査用）。同一renderer image内で実行してexit0を要求する
- rootによるcgroup v2の専用子group作成とcgroup.kill、daemonのnetwork無効起動に必要なkernel機能

input payloadの`payload.json`は次の内部形式を持つ。値の例を配布pinに使わない。

```json
{
  "schemaVersion": 1,
  "guestBridgeAbi": 1,
  "files": [{"path": "guestbridge.py", "bytes": 1, "sha256": "<exact SHA-256>"}],
  "guestBase": {
    "format": "tar.xz", "bytes": 1, "sha256": "<rootfs archive SHA-256>",
    "bridgeSha256": "<guestbridge exact SHA-256>",
    "cpuProbeSha256": "<CPU probe exact SHA-256>",
    "engines": {
      "/usr/bin/docker": "<SHA-256>", "/usr/bin/dockerd": "<SHA-256>",
      "/usr/bin/containerd": "<SHA-256>", "/usr/bin/runc": "<SHA-256>"
    }
  }
}
```

nativeは検査済LLVM-MinGWをMacで明示指定し、固定sourceから別buildする。packagerは事前build PE・build.json・元sourceを必須入力としてPE32+ AMD64 console、許可したWindows system/UCRT import、source/toolchain/flags/artifact/noticeのpinを検査し、nativeとbuild記録・noticeを含む最終payload.jsonとapp-image inventoryを出力する。コンパイラの探索・自動取得・Windowsでのsource compileは行わない。署名検証成功やWindows実OS受入を意味しない。projectのLICENSE・THIRD_PARTY_NOTICESと関連Apache/Commons/XZ文書はrepo正本から`app/support/licenses/mcicon/`へコピーし、payloadと最終inventoryへ結合する。正本欠損時は包装を拒否する。そのhelper archive全体を後続の署名descriptorで固定する。rootfs SHA/bridge/engine/probeの不明・不一致をreadyへ落とすfallbackは無い。guest helloはABI/marker/hashとguest実memory・CPU数を検査し、全active leaseのcontainer予算合計＋1GiB overhead（下限8GiB）をguest実容量へ照合する。host物理RAMが足りることだけでは代用しない。image load後にimage ID/platformと実CPU probeを検査する。

## 入出力と検証

hostのcache bindをguestへmountせず、workerが参照するjar/native/config/index/objectだけをsnapshot/tar streamへ変換する。別drive・Unicode・空白・UNCはhost filesystemから読み取るだけで、UNCをguestへ自動mountしない。worker protocolは元UTF-8 line bytesを保持し、job固有PNGを取得・公開した後にresultを流す。guest PNGはdirfd/NOFOLLOWでcache外・link経由の読み取りを拒否する。

DockerRequest/Snapshot/WorkerProxyはhelper-commonへ集約し、Mac/WindowsはPNG fetchと有限stopだけを渡す。公開library API/protocol/schemaは変更しない。common JARが変わるためMacも新generationであり、以前の51版/包装#193の私用検証を新JARの実機受入へ拡張しない。

native buildの待機例外でもstdout/stderrと終了記録を保存する。未reapの直接child所有を確認できた場合だけ専用groupへsignalし、回収不能はunknownとする。元例外は回収/記録失敗で置き換えない。

Java fixtureはargv/protocol/snapshot、登録衝突、GUID/BasePath/SID、未確定intent、native record欠落、回収期限を検査する。`scripts/test-guestbridge.py`は実関数を隔離filesystemとDocker境界で呼び、fence/曖昧create/失敗証拠を検査する。これらはWindows Job、kernel cgroup、実WSLの実証ではない。

```text
./gradlew :helper-windows:test :core:test
python3 helpers/windows/scripts/test-guestbridge.py
python3.12 helpers/windows/scripts/test-native-completion.py --cxx /usr/bin/clang++
# Macで検査済toolchainだけを指定（自動取得なし。手順はscripts/native-build.md）
python3.12 helpers/windows/scripts/build-native.py --toolchain <verified directory> --archive <fixed archive> --source <absolute windows-host.cpp> --output <new native directory>
python3.12 helpers/windows/scripts/test-native-artifact.py
python3.12 helpers/windows/scripts/test-build-native.py
# Windowsの既存JDK21と検証済payload・事前nativeを使う包装のみ
gradlew.bat :helper-windows:appImage -PwindowsPayloadDir=<absolute payload directory> -PwindowsNativeDir=<absolute native directory>
```

## 未受入gate

2026-10-25のH-C18 v3では、現native PE `b72ca49b2ac725e9d555b2655e368060c043214700e959152973634a4d2e951a` のargv・子孫回収・親死亡・guard死亡・期限の5条件、固定witness拒否、app-image包装、QA署名resource生成を同じrunで確認した。guard死亡・期限のdone欠落2件はunknown記録を保持し、全17既知actorの終端と区別する。固定witness拒否はguard exit 1/noJob/noChildの肯定拒否であり通常コマンド成功ではない。

準備済みA/Bが存在する状態で、保持した本人AのPID/startを照合して一度だけ終了させ、旧AのJob0レシートを確認後、新core Cの既prepareによる回復と正常closeを確認した。Bは同じworkerを維持して1.20.6の代表3PNGを描画し、基準・出力manifest・実PNG bytesのSHAが一致した。3coreの回収、3leaseのreleased/resources空、製品本人distroの停止レシートと登録disk保持、既30登録/default/configの保持を確認した。旧guard exitは実観測せずplanned値を実exitへ読み替えない。専用distroのRunning状態を独立に追加照会した証拠ではない。

受入はこの固定PE/JAR・公式照合したJDK 21.0.12.1・同boot・QA限定asset/descriptorの範囲である。ACK前死亡/partial import、同時release、実reboot/boot差、offline、全51版や新Mac generationへ拡張しない。[数値と境界](qa/h-c18-2026-10-25.json)を参照。後続の実機確認も作業先・入力pin・期限・所有回収をreviewし、接続前のユーザー許可を必要とする。

51版・未準備WSL hostの同意導線・最小OS/WSL・rootfs再配布closure/license/source・本番trust/署名/SmartScreenは後段gateに残す。Issue #95の完成範囲を短縮せず、先行PRはRefs #95でOPENを保持する。#91の旧CLI方式実証は製品helperの受入へ流用しない。


以下は各時点の履歴であり、当時の未受入・旧PEの結果を上記新runへ転記しない。

### 引用と共通native回収の検証範囲

空・space・tab・quoteを含む引数だけを引用し、WSLの単純optionはraw tokenのまま渡す。CRT argv境界10値は旧引用訂正PEで確認した。現sourceはその引用処理を保持し、Job回収枠だけを変更している。

現source SHAは `932c366edd7d7a909e96fb482db7d7b093f6105ec58181d372d9de6f0ff58e87`、PE（1476608 bytes）は `a14c23cdbed4de8663e81aa072e3b96ec731180f91897ec169cd88a957ed4896`。2026-10-20のfacts-only対照では、旧引用訂正PEはchild0/Jobactive1から強制回収してguard125、現PEはchild0/Jobactive1→0の自然drain後guard0となった。両Jobのdone/本人PID・start/親/bootと回収を照合した。新PEの全機能受入ではない。

class90のboot GUIDを今回読取り、両caseを同じ現bootへ結合した。旧proofと今回のboot値は同一で、実boot差を跨ぐ受入は未達。総8facts fieldのうちboot以外7fieldとconfigを固定比較した。旧失敗やguest状態unknownは保持し、旧WSL import/attachedworker/包装/51版の成功へ拡張しない。

### 現PEのA-current限定受入

同じ現source `932c366e…58e87` / PE `a14c23cd…4896` に対する2026-10-20のA-current試験は、独立raw監査を含め限定PASSとなった。argvは実guard exit0、子孫回収・親死亡・guard死亡・期限は実guard exit125を採取し、17既知actorの本人PID/startと終端を確認した。argv・子孫回収・親死亡の3件は本人state/done一致、guard死亡・期限の2件はdone欠落のunknownを保持した。未知actor不在や全資源clearを証明した結果ではない。

前後facts8fieldとconfigは同じbootで一致し、実boot差は未受入。全4接続clientと外側監督の回収を確認した。実行全体は約9.30秒だった。この試験はWSLコマンドを実行しておらず、WSL import・guest・attachedworker・prepared rootfs・包装・描画/取消・51版の受入へ広げない。旧FAILと旧世代の結果を保持し、B以降は別入力・別許可で検証する。


### Job終端失敗時の限定診断候補

drain-expiredの既失敗枝だけで、今回JobのPID一覧を最大8件一回採取し、所属・開始時刻・image（不明時error）・Wait0を観測する。全観測handleを閉じた後にaccountingを再読する。最大50msの協調予算を既2秒のend内へclampし、親終了優先・強制回収・125判定を変更しない。stderr frameは4KiB以下で、上限・切断・API不明はunknownを保持する。accounting countは生存PID数の証明ではない。新PEのWindows5条件・製品経路は未受入であり、旧b38実測を自動継承しない。
