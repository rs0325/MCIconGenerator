# Linux user managerの限定観測reader

通常helperを非rootに保ち、capabilityを持つ標準systemd user managerを毎回freshに観測するための独立コンポーネント。対象はUbuntu 24.04 amd64、systemd 255の固定5path。#126の成果であり、#91/#96のLinux rootless方式・製品1.1.0・全51版受入を完了するものではない。

## 権限と固定範囲

root実行物は`/usr/libexec/mcicon-manager-reader`だけ。stdinに渡された固定AF_UNIX accepted socketのkernel UIDから、system busの`user@UID.service`を読取問い合わせする。固定MainPIDとfixed5file/2libraryだけを観測する。argvなし、PATH/任意環境継承なし、exec/file作成変更/signal/unit操作の機能なし。親processのpidfd後付けによる本人性を主張しない。

sudoers、ユーザー領域scriptのroot実行、helper全体root化は使用しない。CAP_SYS_PTRACEを限定serviceへ与え、通常helperのcapは増やさない。LSM拒否や不明をcap剥奪・service restart・広いsysctl/AppArmor緩和で救済しない。

socketは全UIDが接続できる0666だが、対象は接続のkernel UIDから固定される。global同時active接続8、listen backlog1、per-UID公平性なし。1 UIDが8枠を占有する局所DoSは残る。unitはType=simple。systemd 255.4ではservice_spawnが戻った後にRUNNINGへ進み、その起点からruntime 2500ms、停止回収250msを設定する。TimeoutStartSec=250msをfork前のstartup上限とは扱わない。3秒は設計上の設定予算であり、spawnを含む全寿命の硬保証ではない。reader自身の1500ms期限は固定system busの接続・認証待ちにも適用し、client側2秒期限を延長しない。kernel同期I/O/OS schedulingを含む実時間の硬保証や実OSでの上限成立は未実証であり、不明時は成功扱いにしない。

## BuildとローカルQA

共通Gradle/settings/bootstrapには接続していない。C++20 compilerとLinux開発用libsystemd/OpenSSLが必要。

```sh
make -C helpers/linux-manager-reader test OUT=/絶対専用出力
make -C helpers/linux-manager-reader all OUT=/絶対専用出力
```

公開候補はg0・repo prefix-map・link strip-debugで作成し、ELFにdebug sectionと明示した元source/toolchain/workspace prefixが残れば包装を拒否する。対象prefixの検査を任意の個人情報不存在の保証には広げない。

Macのportable core testはprotocol/parser/UID/unit/process/library照合だけを検証する。Linux native main、SO_PEERCRED、sd-bus、service制約、実manager観測の受入ではない。Linux ELFは固定cross toolchainまたは明示したLinux toolchainで別途buildし、source/binary/loader/依存licenseを同世代で記録する。buildを実機試験へ読み替えない。

## 配布候補

`scripts/package.py`は検証済みLinux amd64 ELFと`build.json`を入力にDEBを作成する。DEBには固定reader/2unit/docs/同世代build metadataとhash一致した8license/noticeだけを含め、maintainer scriptや自動socket開始は含めない。

```sh
python3 helpers/linux-manager-reader/scripts/package.py \
  --binary /固定ELF --build-json /同世代build.json \
  --runtime-licenses /同世代固定license --private-prefix /元buildroot --output /未使用の専用出力
```

管理者は信頼した配布hash・source由来・loader closureと既存依存を確認し、root所有の排他stagingへ保存してから同じ固定hashを再検証する。ユーザー書換可能fileを事前hashだけで信用してroot導入しない。ホストpackage導入やsocket有効化は、このソース実装依頼とは別の具体許可が必要。旧user managerを再起動する必要はない。

新規導入ではroot管理祖先/衝突/依存を確認し、承認済みDEBを導入してdaemon-reloadとこのsocketだけの開始を明示的に行う。通常helperは導入・昇格を自動実行せず、未導入は必要操作を表示して停止する。候補段階では正式署名済み配布物と称さない。

更新では新規接続の受付をこのsocketの停止で止め、既instanceが正常終了または有限停止したことを確認してから固定新packageへ更新する。旧instance不明なら更新を開始しない。source/ELF/2unit/依存を新世代で一括検証し、承認済みの時だけdaemon-reloadと同socketの再開を行う。旧応答を新readerの受入に使わない。

撤去でもこのsocket停止→instance終端確認→このpackageだけを撤去→daemon-reloadの順。既user manager、他socket/service、アプリjob/lease/cache/runtimeは削除・停止しない。不明なinstanceを任意PID killや広いunit patternで回収しない。旧同版DEBの導入と、非active状態での同版置換は別QAで確認済み。稼働中更新・撤去手順の実OS受入は未実施。

## 検証済みの範囲と限界

同じ固定ELF・unitを使う複数のUbuntu QAで、通常2要求、拒否4条件、idle接続8本の独立したactive状態と有限回収、root peer拒否・finish ACKを確認した。同じ接続FDを別processへ移譲し、元peer終了後に行う要求も別runで確認した。各runの資源設定・本人性・前後の配布物照合を結合した受入であり、全条件を一度に通したfullsuiteではない。

CAP_WAKE_ALARMを持つ隔離user managerに対し、通常peerのcap0とmaps/exe読取拒否、nonce単一要求・固定5file/UID一致、root Oracle前後の同manager/cap一致を実確認した。このQA全体はmanagerのfailed状態が元の終了判定を満たさずFAILとして保持した。後続の読取り専用postrunでは、同managerの元PID終了・固定CG不在、runtime/sliceの終了・固定CG不在、対象UIDのtask不在とsocket path不在を確認し、新しい停止要求は不要だった。failed記録・不明なexit codeを正常終了へ書き換えていない。

同ELFの正常2実行について、固定traceの全行判定とnative sourceを結合し、fileのwrite-open/pwrite/共有書込mapの拒否、IPC書込み先の限定、GetUnit/GetPropertyだけのunit問い合わせを限定受入とした。自己abstract socket・8MiB socket buffer・TLS/自己signal handler等の設定はあり、全OS状態を変更しないという保証ではない。getterのpointee/長さ、D-Bus・read/recv・環境文字列の本文、全libraryの公式bytes、全分岐は未証明。ptrace/TasksMax/root log等の監査contextと、cap付きmanager QAの全context同一性も主張しない。

別の置換陰性対照では、通常UID・cap0のactorがHOME/bin/lib内の固定5objectを、同bytesの別objectへ置換した。A/Bの保持FDとroot管理objectを区別し、2nonce要求の固定5file/UID・root Oracle前後一致、rootのexe/mapsが期待する実行元・読込み元を参照することを確認した。本人書換可能なscriptとEnvironmentFileも実qualification入口で各1件拒否し、その対照ではreader開始0だった。reader環境にactorのshadow設定は混入せず、LD_LIBRARY_PATH/LD_PRELOADは不在。readyは各要求でC2未受入3回の後、4回目に全strict条件が成立し、期限は延長していない。mapsの固定11objectは取得時点の観測であり、要求全期間の閉鎖や任意root環境・内容改変耐性一般を動的に証明するものではない。今回reader2件の自然終端、本人socketの開始/停止・参照解放、actor回収を確認し、対照用4directory/7fileは保持した。旧FAILや歴史owner未完成、GC後reader exit code不明をこの成功へ付け替えていない。

旧DEBでの実機受入と、文書だけを更新して再包装したDEBは区別する。後者は同ELF/unit/sourceの静的差分確認だけで、新DEBの実機導入は行っていない。同期I/O・scheduling込みの硬い寿命上限、あらゆるLSM/loader、稼働中更新・撤去、正式署名配布は未受入。QA account/home・cap設定・証拠は保持し、一般的な管理者資格や本番運用全体の受入には広げない。

#96の製品統合・継続再観測/9秒release・描画等は本コンポーネントの確定commit後の別成果。旧Python診断の拒否をJavaの確定失敗とは扱わない。
