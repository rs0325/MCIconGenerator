# Windows候補資材の限定WSLスモーク（E2）

Refs #91、#95。2026-11-04、`origin/main` の `b3dcd07cd451e2a793c9a9eb5075f10c79a0beb5` から専用worktreeで確認した。
**a〜eとgは限定PASS、fのguest全体の無通信は未確認**。測定値・入力pin・前後のWSL出力・失敗履歴は[QA記録](../qa/windows-smoke-2026-11-04.json)を正本とする。
過去のH-C18成功は今回のbytesへ流用していない。正式配布・本番署名の受入ではなく、親Issueは終了しない。

## 環境と候補

Windows 11 Pro 10.0.26200.9711、Ryzen 7 5700X、物理RAM 68,640,256,000 bytes。
開始時の空き物理メモリは23,958,765,568 bytes、Hドライブ空き624,347,561,984 bytes。
WSL 3.0.3.0、kernel 6.18.54.1-1、WSLg 1.0.79。
HypervisorPresentとVirtualizationFirmwareEnabledはtrue。稼働中hypervisor下のCIM SLAT/VMMonitorModeのfalseだけで利用不可とは判定せず、実際のWSL2 importとguest起動を確認した。

[C1](windows-guest-rootfs-1.1.md)と[E1](windows-helper-candidate-1.1.md)が保全したarchive原本を読み取り、sizeとSHA-256を実照合した。Docker Desktopの起動や再build、ネットワーク取得は不要だった。

| 候補 | bytes | 実SHA-256 |
| --- | ---: | --- |
| C1 rootfs tar.xz | 98,146,824 | `f68070d5b702a00916ecd1108de1c3e8f4b644ebeb603ccf7f6b7b881ffb15c5` |
| E1 renderer tar.gz | 593,385,993 | `dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4` |
| E1 helper ZIP | 36,029,106 | `efc2c38e735cf359ea751931ce7d2f746e8b082a758cd2f32ea885ad48257ab8` |

helper ZIPから取り出したnative PEもSHA `1112d86bd18633004759b4d327d9cd0e9e3772ecafb472b5cc731e50e39166d8` と一致した。
Java host-helperのlauncher・署名descriptor・lease全体は呼んでいない。署名を行わないため一時鍵も生成しておらず、本番鍵や認証情報を探索していない。
QA所有markerのdescriptorSha256欄は今回のhelper ZIP SHAであり、正式descriptorのpinではない。

## 手順と安全境界

既存の[helper契約](../helpers/windows/README.md)、guestbridge ABI 1、native Job入口を再利用した。
過去の製品QAは描画や署名resources等を含み、この限定試験に一致する追跡済みrunnerがないため、
標準Pythonだけの[smoke-candidate.py](../helpers/windows/scripts/smoke-candidate.py)を追加した。公開API・製品source・保存形式は変更していない。

1. WSLの一覧・status・versionとhost能力を読み取る。既存defaultとdistroが無い場合はimportしない。
2. 3候補のSHAを照合し、rootfsを64MiB制限のXZ decoderで専用tarへ変換する。tarは403,507,200 bytes上限・SHA一致を要求し、host上へrootfsのfileを展開しない。
3. `build/e2/<UUID>/distro` だけへ `wsl --import --version 2`。host SID/root/hostId/generation、registry GUID/BasePath/WSL2/defaultを照合し、guest markerをinitする。
4. init/hello、keeper、daemon-prepare、load、create/start、releaseを順に呼ぶ。daemonは専用socket/data-rootとnetwork無効の固定argv、rendererは既定entrypoint・`--network=none --pull=never`。CPU probeだけをguestから読み取りbindし、ゲーム資産は渡さない。
5. keeperへEOFを送り、Job終端を確認する。新規本人の登録だけをunregisterし、GUID/BasePathが置換されていれば削除を拒否する。残ったBasePathを削除する前にも専用path・link不在を確認する。既存distro/default/configの修復・変更はしない。
6. 一覧・statusを比較し、全known actorのPID/startとnativeのstate/done/completionを照合する。

nativeの固定argvは `MCIcon-<24hex>` だけを受け付けるため、最終runの使い捨て名は専用prefix `MCIcon-e2e2e2` と乱数18桁を結合した。
初回の `mcicon-e2-smoke-…` と通常execではguest daemon ready後にwslhost/conhostがJobへ残り、回収してguard exit125となった。
この失敗は保全した。製品nativeを変更せず、最終runではexec-keeperと、そのclient PID/startをwitnessにするexec-bridgeへ接続した。
daemon-prepareはchildExit0・command-empty・guard実exit0・Job0、他bridge/keeperはdrain-emptyを確認した。
通常execの125を成功へ読み替えていない。

## 実測結果

| 項目 | 判定 | 観測 |
| --- | --- | --- |
| a import・所有 | PASS | C1 tar SHA一致、専用WSL2登録、GUID/BasePath/default保持、guest marker一致 |
| b ABI 1 | PASS | init/hello応答、bridge SHA `508ea9fb…e90ff`、guest memory 33,617,231,872 bytes・16 CPU |
| c 実行ファイル | PASS | Python 3.12.3、Docker CLI/dockerd 29.1.3、containerd 2.2.1、runc 1.3.4 |
| d daemon・能力 | PASS | 専用daemonの本人/世代label、cgroup v2・cgroupfs、cpu/memory/pids供給、overlayfs、data-root `/var/lib/mcicon/docker` |
| e renderer | PASS | tar.gz load、指定image ID・linux/amd64一致、Xvfb :99、GLX、llvmpipe LLVM 20.1.2/128 bits、Mesa 25.2.8、SSE4.1実probe、Java 4系統成功 |
| f apt・無通信 | 一部観測／未確認 | init前・試験後のapt/dpkgログはともに空。明示apt処理なし、container network=none。guest全体の通信遮断・無通信は証明していない |
| g 回収・保全 | PASS | guest fence closed・actors/containers 0、最終runの14 native Jobのstate/done一致、6 completion照合、2回合計23 known actor終端、両専用distro/BasePath削除 |

Javaは `/opt/java/java8/bin/java`（1.8.0_504-b01）、`java17`（17.0.20.1+1）、
`java21`（21.0.12.1+1-LTS）、`openjdk`（25.0.4.1+1-LTS）でそれぞれ実行した。
`set -eu` 内の全コマンドが成功し、実guard exit0だった。SSE4.1だけの別CPU上での受入ではない。

開始時・終了時とも既存40 distro、Ubuntu-24.04だけRunning、他39件Stopped。
既定は `MCIcon-82ec446b986db27cff993a27`、既定WSL versionは2で、一覧とstatusの出力全文が一致した。
global shutdown/update、既存distroの起動/停止/削除、.wslconfigやWindows機能・registryの直接変更は行っていない。
Docker Desktopは起動しておらず、終了時にDesktop/backend processは見つからなかった。
自分のguest daemon/image/dataは専用distroとともに削除し、共有image/cacheや過去QA保全先は変更していない。

## 再実行と証跡

コマンドは必ず記録wrapperを通す。同じrunnerは毎回別UUID・別distroを作り、既存結果を上書きしない。
実行先は承認された専用worktreeだけに限定している。

```powershell
$smokePython = 'C:/Users/ryuma/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$smokeLogger = 'C:/Users/ryuma/.codex/scripts/run_logged.py'
$smokeRepo = 'H:/MCIconGenerator/worktrees/milestone-windows-smoke-e2/MCIconGenerator'
& $smokePython $smokeLogger -- $smokePython -X utf8 "$smokeRepo/helpers/windows/scripts/test-smoke-candidate.py"
& $smokePython $smokeLogger -- $smokePython -X utf8 "$smokeRepo/helpers/windows/scripts/smoke-candidate.py" --rootfs 'H:/MCIconGenerator/worktrees/milestone-windows-rootfs-c1/MCIconGenerator/build/windows-rootfs/fixed1/rootfs.tar.xz' --renderer 'H:/MCIconGenerator/worktrees/milestone-windows-helper-e1/MCIconGenerator/build/e1/renderer-1/renderer.tar.gz' --helper 'H:/MCIconGenerator/worktrees/milestone-windows-helper-e1/MCIconGenerator/build/e1/final-1/MCIconHelper.zip'
```

安全性4テストは、XZの上限・SHA・末尾拒否と、GUID/BasePathが異なる登録の削除拒否、回収失敗を成功終了にしない判定を実WSL操作なしで確認する。
実機成功後はdocstring、native起動直後への記録移動、失敗チェックの非zero終了を修正した。guest実行と正常回収の経路は変更していない。最終scriptの安全テスト4件と構文を再確認し、実機は再実行していない。
製品のbuildは再実行していない。既存C1/E1候補を実SHAで照合して使った。

成功runは `build/e2/d0d9d4e6-a4b0-4fe9-b536-e81503a4343e/`、
初回失敗は `build/e2/94e0e4f8-fafa-4db8-8c19-6f450703d898/`。
raw stdout/stderr、commands.json、owner、native state/done/client/completion、rootfs tar、PEを保全した。
前提・終了後の観測は `build/e2/preflight.json` と `postflight.json`。
これらの主要ファイルのsize/SHAはQAへ結合した。残しているのは証跡と候補コピーであり、distroのBasePathは残していない。

## 未検証と残るgate

**実Minecraft描画・全51版・未準備host setup・OS警告/SmartScreen・実reboot・offline製品経路・正式配布経路での導入は後続である。**
最小OS/WSLのサポート範囲、本番trust/署名、Java host-helperの署名descriptor/leaseを通す全経路、
ACK前死亡/partial import/同時releaseも受入していない。C1/E1のsource/notice公開や法務判断をこのスモークで解消したとは扱わない。
実画面操作、ユーザー介入、Minecraft取得・実行はしていない。commit・push・PR作成・release・Issue closeは行わず、公開文面だけbuildへ準備した。
