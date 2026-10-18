# 1.1.0の管理描画環境と更新契約

状態: 共有契約はPR #100、worker配布/resolverはPR #101で固定済み。Issue #93で管理APIと内部runtime機構を接続した。本番pin/信頼鍵・実runtime配布物は未発行であり、固定信頼設定がなければ取得前に拒否する。各OS方式・OS下限・実配布bytesの受入は別に追跡する。

起点は `76f79a1b13da28c669f16301c377724519899a6e`。製品の現在の動作は[契約v1](contracts.md)を正本とする。1.1.0でもJava利用側はJava 21以上、Minecraft資産は実行時に公式配信から取得し、ゲーム資産・認証情報を再配布しない。GitHub Actionsは使わない。

## 先行固定する範囲

#91の先行変更は共有API・worker artifact・descriptor schema・helper起動境界・CLI更新所有者だけを固定した。resolver/runtime managerの実装は#92/#93で行い、各OS helperやMaven Central/installerの公開とは区別する。#91の3ホスト実証と最終方式採用は未完了のまま継続する。

## 利用者の完成条件

- ライブラリは `mavenCentral()` と固定版の `io.github.rs0325.mcicon:mcicon-core:1.1.0` だけでworkerも解決する。raw取得にはVM・containerを起動しない。
- CLIはホストJavaを同梱し、描画用Java・Linux/Mesa・実行helperを自動準備する。Docker Desktop・Colimaの導入や既存環境の変更は要求しない。
- CLIと管理描画環境のstable更新は既定有効で、設定で無効化できる。稼働中ジョブは開始時の世代で完了し、更新は次回起動から使う。Java依存の版は利用側Gradleが決め、ライブラリはtool版を更新しない。

## 実行方式の評価範囲

| ホスト候補 | 専用実行環境 | 最低条件の候補 | 未確定の受入 |
|---|---|---|---|
| macOS arm64 | LimaのVZ arm64 guestとRosettaでLinux amd64を実行 | VZ自体はmacOS 13以上。製品の下限は署名・配布helper・実機試験後に確定 | 同梱helperの実行・再配布、Rosetta未準備時、画像、停止・回収 |
| macOS x86_64 | VZのLinux amd64 guest | OS下限・対応CPUはIntel実機で確定 | 実機未確認。arm64の測定を流用しない |
| Windows x86_64 | 専用WSL2 distribution内のLinux amd64 | WSL2・仮想化機能が必要。今回の観測版は最低対応版を示さない | 小規模の準備・画像・回収は2026-10-19に成功。製品helper、未準備時の承認・再起動、下限・配布条件は未受入 |
| Linux x86_64 | 専用領域のrootless container runtime | user namespace・subuid/subgid・cgroup等の条件を検査 | distributionとkernelの下限、rootless制約、画像と回収 |

最初の実証対象はmacOS arm64・Windows x86_64・Linux x86_64とする。他のOS/archは未検証であり、公表対応へ追加する際は別に実証する。新たな非対応範囲を確定したとは扱わず、黙って別rendererやホストJavaへ切り替えない。仮想化機能・Rosetta・WSL・rootless前提が不足する場合、必要操作と再開コマンドを表示する。無断の昇格、OS再起動、system daemon導入、既存VM/WSL設定変更は行わない。

VZは同じCPU archのguestを実行するため、Apple Siliconでamd64 guestを指定しない。Rosettaはarm64 guest内のamd64実行に使用する。[Lima VZ](https://lima-vm.io/docs/config/vmtype/vz/)、[multi-arch](https://lima-vm.io/docs/config/multi-arch/)、[Docker rootless要件](https://docs.docker.com/engine/security/rootless/)を参照する。

## artifactとJava APIの境界

既存 `WorkerOptions` のrecord成分、旧5/6引数constructorと8引数constructor、`render(RenderRequest, WorkerOptions)`・`doctor(String, WorkerOptions)`・`listEntries(String, WorkerOptions)` を保持する。既存の明示Docker経路は自動取得・更新を行わない。CLIは旧Docker指定がなければ管理方式を既定にし、`--docker-context`・`--docker-image`・`--worker-jar`・`--java-home` を明示した既存利用は従来のDocker方式へ残す。管理方式の明示と旧Docker指定を混在させた場合は引数エラーとし、意図を推測して切り替えない。

共有契約は `ManagedOptions` と `renderManaged(RenderRequest, ManagedOptions)`・`doctorManaged(String, ManagedOptions)`・`listEntriesManaged(String, ManagedOptions)`。既存メソッドと別名にし、`render(request, null)` 等の既存sourceにoverload曖昧性を生じさせない。管理root、起動・個別期限、container/heapメモリを指定でき、既定factoryはOSごとの専用rootを選ぶ。`ManagedOptions` にDocker context・agentパス・ゲームJavaパスを要求しない。追加型・署名・Options・例外codeは今回の共有契約commitで固定し、Issue #92/#93の前提にする。Windows/Linux方式とOS下限の実証は#91の別残件として維持する。

### 管理APIの契約

戻り値は既存型を使い、`renderManaged` は `CompletableFuture<ExportResult>`、`doctorManaged` は `CompletableFuture<DiagnosticReport>`、`listEntriesManaged` は `CompletableFuture<List<CatalogEntry>>` とする。各メソッドにはOptionsを受け取る版と、同じ設定の `ManagedOptions.defaults()` を使う引数1個の版を用意する。既存 `render` 等へ別Options型のoverloadは追加しない。

固定する署名は次の6つとし、いずれも既存 `MCIconGenerator` へ追加する。

```java
CompletableFuture<ExportResult> renderManaged(RenderRequest request);
CompletableFuture<ExportResult> renderManaged(RenderRequest request, ManagedOptions options);
CompletableFuture<DiagnosticReport> doctorManaged(String version);
CompletableFuture<DiagnosticReport> doctorManaged(String version, ManagedOptions options);
CompletableFuture<List<CatalogEntry>> listEntriesManaged(String version);
CompletableFuture<List<CatalogEntry>> listEntriesManaged(String version, ManagedOptions options);
```

この署名は1.1.0開発sourceへ追加済みであり、1.0.1で呼べる使用例ではない。本番の固定pin・信頼鍵・runtimeが未発行の段階では、APIが存在しても描画環境の準備成功を保証しない。

`ManagedOptions` は同じpackageのpublic recordとし、成分は `Path runtimeRoot`、`Duration preparationTimeout`、`Duration startupTimeout`、`Duration itemTimeout`、`int containerMemoryMiB`、`int heapMemoryMiB`。既定はOS標準root、準備15分、worker ready 180秒、個別要求30秒、container 3072 MiB/heap 2048 MiBとする。準備期限は初回download・helper準備・専用環境起動の全体に適用し、ゲームworkerのready待機と分ける。root/期限はnull不可、期限とcontainer/heapは正、heapはcontainer未満とし、不正なOptions構築は既存 `WorkerOptions` と同様に `IllegalArgumentException`。rootは利用時に絶対pathへ正規化し、未作成directoryを許可する。指定予算を黙って増減しない。

管理APIの呼出しは、公式入力cacheに加えて `runtimeRoot` へ検証済みartifact・所有記録を書き、必要なら専用環境を起動する。`doctorManaged` も描画環境を準備してJava/Mesaを診断するが、全件描画やOS機能変更はしない。軽量なOS/arch確認と検証済みhelperの取得後、大容量guest/imageのdownload・VM起動より先に前提不足を診断する。不足時は不健康な既存 `DiagnosticReport` を返し、前提名と不足理由・必要操作をCheckへ記録する。回収失敗も重なった場合は、suppressedの回収情報を含む元例外をFutureへ保持する。入力の `offline` 設定は管理artifactの取得にも適用する。

取消は既存instanceの `CancellationToken` と `close()` の契約を引き継ぐ。個別描画の途中取消は未完了 `ExportResult`、管理環境の準備・検証・起動失敗はFutureの例外へ通知する。公開SPI、任意helper command、updater設定は管理Optionsへ加えない。この契約に沿ったAPI接続とfixture検証を#93で実装した。実配布物と各OS helper、実機の受入は未完了である。

`mcicon-core` は同版の `mcicon-worker` をruntime推移依存にする。cycleはなく、workerはMinecraft型を公開しない。worker artifactは薄いwrapper JARとし、`META-INF/mcicon/worker/agent.jar` に完全なagent JARを1個、`META-INF/mcicon/worker/version.properties` にtool版と内側agentのSHA-256を持つ。外側へagent classを重ねず、coreにもagent bytesを重複同梱しない。metadataはUTF-8/LFの2行 `toolVersion=<tool版>\nagentSha256=<小文字64桁SHA-256>\n` とし、重複keyや余分なkeyを許可しない。timestampや絶対build pathを含めない。agentJar生成後に同じbuildでmetadataとwrapperを生成し、core同梱descriptorも同じagent hashへ固定する。元JARの `CodeSource` がファイルであることを要求しない。coreはresource bytesをhash検証して専用cacheへ原子的に書き、ジョブごとのsnapshotを既存 `-javaagent` へ渡す。resource名の衝突・tool版違いは拒否する。fat JARによる単純な依存統合でresourceが失われる場合を互換保証には含めず、明確な不足診断とする。

管理方式はジョブごとにhelperの絶対パス・argv・環境変数を確定して起動する。JVM全体のPATHやDocker contextを書き換えず、同じJVMの旧Docker方式や別世代のジョブと共存させる。この内部起動指定は公開 `WorkerOptions` のrecord成分へ追加しない。

ライブラリの管理APIは同梱対応descriptorに固定され、CLIの更新設定・最新releaseを参照しない。初回の描画環境取得はあり得るが、Gradle指定版とは別のtool/workerへ更新しない。

### helperと診断の責務

coreは固定descriptorとhelper/agentの検証、ジョブlease、公式資産、worker protocol、PNG/manifestを所有する。OS helperは専用VM/WSL/rootless runtimeの起動・接続・パス変換と自身の対象の回収だけを所有し、releaseの最新版選択やPNGを変更しない。CLI bootstrapperはtool payloadの更新と同梱host JREの選択を所有する。

helperの実行prefixは検証済み世代内の絶対パスと固定argv、ジョブ固有環境だけで構成する。WorkerClient・image確認・Java/Mesa診断・取消回収はすべて同じprefixとleaseを通す。通常のworker入出力は既存JSON protocol 1を透過し、helper診断はstderr/専用記録へ分ける。Windowsはhostのcache pathをそのままLinuxのbind sourceにせず、専用領域への同期・hash照合・guest path変換をhelper側で行う。

helper protocol 1の内部起動形は `helper --protocol 1 --root <絶対root> --lease <絶対lease.json> <operation>` とし、shell文字列へ連結しない。operationは `preflight`（能力診断）、`prepare`（検証済みartifactから専用環境を準備）、`docker -- <Docker argv>`（既存worker/診断/取消の輸送）、`release`（所有jobを終了しleaseを解放）の4つに固定する。任意providerやpluginの公開SPIにはしない。

`preflight`・`prepare`・`release` はstdoutへ1つのUTF-8 JSON objectを返す。必須fieldは `protocolVersion:1`、`status`（`ok` / `setup-required` / `failed`）、`diagnostics` array。診断itemは `code`、日本語の `detail`、`requiresPrivilege`、`requiresRestart` を持つ。`docker` のstdoutだけは既存worker/Docker応答を透過する。control operation（`preflight` / `prepare` / `release`）の終了codeは0が成功、2が前提setup不足、1がそれ以外の失敗とし、coreは準備不足を上記診断または `MANAGED_SETUP_REQUIRED` へ写す。`docker` operationのprocess終了codeはDocker/worker transportの元codeを透過し、control用0/2/1へ変換しない。worker JSON内の既存error codeもhelperで書き換えない。

leaseには `schemaVersion:1`、job UUID、別のowner UUID、tool/worker/runtime/descriptor digest、host/process開始識別を固定し、OS helperが専用VM登録・socket・container IDを追記する。すべてのhelper operationは同じleaseを照合する。取消はown job container/processだけを対象とし、他jobのleaseが残る共有VMを停止しない。VM停止・世代回収は全lease idleと所有記録一致を確認した場合だけ行う。PID/nameの再利用、不一致、sampler等の監督未回収は別対象の停止で補わない。

管理環境取得は元instanceのoffline設定を引き継ぐ。CLIの自動更新offは新しいtool世代の探索/適用を止める設定であり、現在toolに固定された初回runtime準備の禁止ではない。初回runtimeもnetworkなしにしたい利用者はofflineを指定する。

renderingのCPU要件は `cpuRequirement: "sse4.1"` に固定し、helperは対象Linux amd64の実行経路で検査する。ARM hostのcpuinfoをamd64能力の代用にしない。

診断はOSの名前やversionだけで成功とせず、下表の実行能力を検査する。取得できない条件は成功扱いにしない。必要な管理者操作は対象file・package・user・再起動範囲を示した限定setupへ分け、APIが昇格を開始しない。

| 条件 | 必須の検査 | 不足時 |
|---|---|---|
| Mac VZ | host archとVZ利用可否、Rosetta availability=installed、必要disk/RAM | 未導入Rosettaをhelperが自動導入せず、必要操作を診断 |
| Windows WSL2 | host/guest arch、既存WSL2利用可否、専用登録GUID/BasePath、実memory/CPU制限 | host OS機能の導入・再起動を無断実行せず、installer setupへ分離 |
| Linux rootless | user namespace、newuidmap/newgidmap、対象userのsubuid/subgid、cgroup v2のmemory/CPU委譲、helper pathに対するAppArmor制約 | 不足だけを示す。対象UID instanceへのsystemd設定で解決できなければ停止し、全user unitやglobal sysctlを既定変更しない |

`MANAGED_SETUP_REQUIRED` は不足前提、`MANAGED_RUNTIME_INVALID` は署名・hash・世代互換の拒否、`MANAGED_OWNERSHIP_MISMATCH` は所有記録の不一致という追加error codeとする。download/IO・worker期限・ゲーム側失敗は既存error codeと原因を維持し、管理経路へ移した理由だけで成功や別backendへ変換しない。

## 配置と所有

CLIの設定・状態root候補はmacOS `~/Library/Application Support/MCIconGenerator`、Windows `%LOCALAPPDATA%\MCIconGenerator`、Linux `${XDG_DATA_HOME:-~/.local/share}/mcicon`。cacheは各OS標準cache領域に分ける。Java APIはrootを明示できる。利用者のoutputはこのrootへ取り込まない。

```text
root/
  config.json
  active.json
  updates/staging/<generation>/
  tools/<tool-version>/<content-digest>/
  runtimes/<runtime-id>/<content-digest>/
  helpers/<host>/<content-digest>/
  jobs/<uuid>/lease.json
  locks/
```

ジョブleaseはtool/worker/runtime/descriptor digest、所有者token、開始時刻、host識別、process開始識別、worker予算/準備期限と専用VM/distribution/containerの所有recordを保持する。形式と排他範囲は[helper共通契約](managed-helper-1.1.md)で定める。PID単独や名前prefixだけで停止・削除しない。共有世代はleaseがなく、所有識別・lock取得・参照の不在が揃う時だけ回収する。出力PNG・元入力cacheは自動更新・通常uninstallで削除しない。

実行時にmountするのは必要な入力・ジョブ専用領域だけ。ホーム全体・Docker socket・資格情報はゲームcontainerに渡さない。ゲームcontainerはnetworkなし。取得を担当するhost側とゲーム側を分ける。

## descriptorと署名

署名対象はUTF-8 JSONの配布bytesそのもの。再serializeしたJSONを署名検証の入力にはしない。descriptorをparseする前に同梱信頼鍵による署名を検証する。artifactごとのHTTPS URL、圧縮size・展開上限・SHA-256を署名対象に含め、鍵IDだけを信頼根にしない。鍵の新設・署名・公開は別承認とする。

| フィールド群 | 契約候補 |
|---|---|
| descriptor | schemaVersion、runtimeId、toolVersion、host OS/arch、helper protocol（更新sequenceは別manifest） |
| compatibility | worker版/hash、worker protocol=1、context=canonical-v1、adapter集合digest、対応tool exact版 |
| rendering | Linux amd64 image digest、ゲームJavaのmajor/path/hash、Mesa/LLVM情報、CPU要件、環境変数 |
| artifacts | role、HTTPS URL、size、sha256、展開size上限、format、license/notice/source情報 |

signature・size/hash・path逸脱・link・展開上限・互換条件に失敗した世代は使用しない。同じruntimeIdに異なるdigestを上書きせず、内容digest別のimmutable世代へ置く。署名済み旧sequenceの再提示は自動更新として採用せず、復旧先は既にローカルで受入済みの旧世代に限定する。未準備offlineは失敗、準備済みofflineはpin済み世代を使う。

構造は[managed-runtime-v1.schema.json](../schemas/managed-runtime-v1.schema.json)と[stable-update-v1.schema.json](../schemas/stable-update-v1.schema.json)を正本とする。schemaは#93の署名後parser/validatorが満たす入力契約である。本番のtrust/pin・実artifactの発行を示すものではない。署名検証後に構造・互換・参照を検証し、追加fieldや未知protocolを黙って採用しない。

Schemaに加え、次を意味上の必須条件とする。artifact IDとOS/archの組は重複不可、helper/imageの参照IDは同じdescriptorの正しいroleに解決し、tool/worker版とcoreに同梱するagent hashは一致する。`javaExecutables` のmajorは重複不可とし、adapter集合が要求する全majorを含める。tool版はroot buildの `releaseVersion` と同じ構文、stable更新manifestの版はプレリリースを含まない `X.Y.Z` だけを許す。runtimeにはtool payloadを入れない。全相対pathは空segment・`.`・`..`・absolute・drive・backslashを拒否し、展開後も所有root外へ出さない。hostへ展開するarchiveのlinkは拒否する一方、guest baseのrootfs内部linkはhostへ展開せずOSのimport手段へ渡す。

`minimumOsVersion` はmacOSではProductVersion、WindowsではNTのmajor/minor/build版、Linuxではkernel版を指す。Linux distribution版はinventory/検証条件へ別記し、Ubuntu版番号をkernel番号と比較しない。実OS版は数値要素で比較し、その合格だけでVZ/WSL/rootless/cgroup等の能力検査を省略しない。未実証hostの実値は未発行とする。

`inventoryPath` / `inventorySha256` はdescriptorと同梱するinventory resourceを指す。raw guest image自身をhost archiveと同一視しない。inventoryに実component/version/hashとlicense/sourceの対応を持ち、license/notice pathやsource archiveはそのinventoryと照合する。各artifactの `unpackedBytes` はhost展開またはimage loadの上限として事前に固定し、roleごとの準備に必要な追加disk予算を計算する。rootfs/renderer/JDKをまだ公開していない現段階では、Schema検証用fixtureを配布artifactと表示しない。

runtime世代IDは `runtimeId` と「検証したdescriptor元bytesのSHA-256」の組、tool世代IDはtool版とpayload元archiveのSHA-256の組とする。同じ版名やruntimeIdだけを理由に既存directoryへ上書きしない。

### immutable runtimeと更新channelの分離

runtime descriptorは発行後不変とし、tool/coreに同梱する期待SHA-256とworker版/hash、helper protocol、OS/arch、image IDに完全一致させる。runtime内のroleはhost helper、guest base、renderer imageとする。CLIとhost JREを含むtool payloadのhashは更新manifest側へ置き、同梱descriptorとpayload自身のhashが循環しない。実際に取得する各artifactのURL・size・展開上限・SHA-256・license/notice/source情報を固定する。Minecraft資産はこのartifact表へ含めない。

stable更新manifestは別の署名対象とし、`schemaVersion`・`channel=stable`・`sequence`・tool版・bootstrapper最小protocol・OS/arch別payloadとruntime descriptorのhashを持つ。sequenceの高水位はCLIの同channel更新にだけ適用する。固定版ライブラリが自身に同梱する旧runtimeを使う場合へこの高水位を適用せず、Gradle指定版の正当な実行を拒否しない。

署名はEd25519とし、`<descriptor名>.sig` / `<更新manifest名>.sig` に64byteのdetached署名を置く。更新payload内では `runtimeDescriptor.path` が指すJSONと、同じpathへ `.sig` を付けたfileを隣接同梱する。更新indexの署名も同じ配信directoryの `<index名>.sig` とする。core/tool/installed bootstrapperに固定した公開鍵でUTF-8 JSON bytes全体を検証し、JSON内に受信した公開鍵は信頼しない。PGPによるCentral artifact署名とは鍵・目的・保管を分ける。1.1では鍵の追加・置換を明示的なbootstrapper配布更新へ限定し、通常のpayload更新は信頼鍵を変更しない。鍵ID・秘密鍵・配布artifactのhashを未発行の状態で仮値のまま公開しない。

## 更新の状態遷移

`検査 → 取得 → 検証 → staging → 次回起動用pending → 有効化 → ジョブlease取得` と進む。更新確認・取得は現在起動のジョブに差し替えない。取得中断・容量不足・署名失敗はactiveを維持し、部分物を完成世代として再利用しない。active切替は同一filesystemのatomic replaceと排他lockで行う。並行起動の片方だけが切替を行い、他方は確定した世代をpinする。

更新設定は `config.json` のboolean `autoUpdate` とし、未設定はtrue。自動取得/適用対象は現在より大きい正式 `X.Y.Z` のtool payloadと、それに同梱して固定したruntime descriptorとする。公開済み同版のartifactを別digestへ置き換える更新は採用しない。CLIだけが `active.json` にactive/pending/previousのtool版・payload hash・runtime descriptor hashと、同stable channelのhighest accepted sequenceを保持する。高水位は署名確認だけでは進めず、全artifactの検証とstagingが完了したpending確定時に同じ排他/atomic replaceで進める。ローカル旧世代へ復旧しても高水位を下げない。

新版の準備完了を画面へ通知した次の起動で有効化する。起動時の互換検査・helper初期化に失敗した場合は、ジョブ開始前に限って検証済み旧世代へ戻し、理由を通知する。PNG生成開始後の失敗で別世代に黙ってretryしない。旧世代の実行中fileは更新・削除しない。

### 導入経路と更新所有者

brew/wingetの管理者はstable bootstrapperの更新を所有し、bootstrapperが署名済みtool payloadをユーザー領域の世代slotへ自動準備する。installerも同じpayload更新経路を使用する。package managerの日常的な自動実行を仮定せず、prefixやProgram Filesを自己上書きしない。payload更新とbootstrapper更新は別の所有範囲であり、両者が同じfileを更新しない。ホストJava runtimeもtool payloadの世代slotに含め、bootstrapperは有効slotのJREとCLIを起動する。初期payloadを導入packageに含めることで、利用者にホストJavaの導入を要求しない。

この方式ではpackage manager表示版と実行payload版を別表示し、`--version`で両者・更新ownerを確認できる。uninstall・repair・bootstrapper互換性と未取得offlineを定める必要がある。元Issueの「brew/wingetは管理経路でCLI本体を更新」から、ユーザーの無操作更新要望を維持するために責務を分離した（2026-10-17、オーケストレーター判断）。API利用側にはbootstrapperを導入しない。

## 小規模実証と後続の開始条件

各候補について、専用環境の準備→代表3アイテム描画→取消→所有process/container回収→専用VM/distribution停止を記録する。source・helper・image・worker・入力・結果hash、OS/arch、利用権限、容量・時間、他環境不変を残す。Mac VM内のrootful/container測定は物理Linux rootless実証として数えない。

1.0.1の画像受入は新環境へ継承しない。小規模比較が成功しても固定51版・全件/反復/境界・ホスト別受入はIssue #99で必要。既存iconsの再開は現行の `sourceSha256`・`renderer`・`settingsDigest` の3条件照合に従い、新出力先または明示overwriteを案内する。raw元bytes・保存schemaは変更しない。

共有契約だけの先行PRは `Refs #91` とし、#91をcloseしない。rootの内容承認・必要な反証・Git公開許可を経て確定した共有契約commitから #92/#93を始める案とする。#91全体のWindows/Linux実証・方式採用・OS下限・再配布条件は残件として維持し、先行PRの成功で完了にしない。OS実装は必要なcore/runtime前提commitを明示して別Issueで進める。

## 2026-10-17時点の実証状況

| 条件 | 結果 | 残り |
|---|---|---|
| worker薄wrapper/nested agent | private `1.1.0-probe` の通常classpath・resource保持flattened JARで元agent bytes一致、重複/破損拒否の計4条件成功 | 製品resolver・Maven公開は#92 |
| Mac専用VZ環境 | v5準備と単体v2/並列v4が成功。単体3PNGと全metadata一致、並列A1PNG取消/B3PNG完走、A/B入力244+metadata1不変、cgroup各2GiB/CPU4、所有回収/通常停止 | 製品helperの同梱と51版受入。初回QA FAILと修正後新規run PASSは両方保全 |
| Windows準備QA | 専用WSL bundle v4凍結、controller18/外側監督16/collector4/実Docker info gate2条件成功、wrapper12条件と2job consumerのjavac成功 | 差分反証済み。新タスク接続許可と新規WSL実証 |
| Linux rootless | Mac上のarm64 Linux guest＋既Rosettaで、専用rootless・1.20.6代表3PNG・実cgroup制限・所有回収を限定実証 | Linux amd64 host/物理Linux・製品helper・取消/並列・最終方式/配布条件は未受入 |

### 2026-10-19のWindows方式実証と残件

主実証PASSと別世代の診断補足PASSを独立監査した。[Windows方式QA](../qa/validation-2026-10-19-windows-managed-proof.json)に結果・artifact hash・未達条件を記録する。記録の基準はmain `06d44c9d238b9e22a9c4aa0ab60f1f16fb1968b1`、private probeを凍結したrepository基準は共有契約時点の `9c01311c78fad487b46c60e71de0d1d3f27b6396`。probe scriptsのbytesは公開commitとは別に固定し、既存1.0.1 CLIで実行した。現在の1.1.0管理APIを通した製品受入ではない。

既準備WSL2上の新しい専用distributionで、Ubuntu amd64・専用guest内のDocker/JREを準備した。1.20.6の代表3PNGと全metadataが一致し、2worker同時稼働でAは1PNG後に取消、Bは3PNG完走した。各containerの実cgroupは3GiB/CPU4、入力957件とmetadata1件は元・複製・guestで不変。所有engine・sampler・container/host jobを回収し、専用distributionのStopped、既登録/default/.wslconfig不変を確認した。専用disk・登録と全失敗原本は保持している。

主実証のpreflightはWSL queryのexit0後にNUL除去で整形errorとなり、元reportの `passed=true/error3` は変更していない。別の読取補足で版/status/listのraw6件を先に保存してから整形し、全exit0・errorなし・raw hash/独立decode一致を確認した。観測はWindows `10.0.26200.9711`、WSL `3.0.3.0`、kernel `6.18.54.1-1`。元ParserError・Python alias拒否・コマンド長による輸送FAILも別世代で保持する。

| 受入項目 | 状態 | 次の条件 |
|---|---|---|
| Windows専用WSLの小規模成立性 | 主実証・別診断ともPASS | 方式採用記録の確定commitを必要とする |
| OS/WSL/CPU下限、実配布bytes・再配布条件 | 未完了 | 今回の観測版やguest apt導入を、最低対応版・製品runtime buildの受入に代用しない |
| #95製品helper、host CLI/Java管理API、未準備/offline/再起動後/path境界 | 未受入 | 必要な#93前提と方式採用commit確定後に製品経路を実装・検証する |
| #99固定51版・全件/反復/境界 | 未受入 | 今回の1.20.6代表3PNGから自動継承しない |
| Linux rootlessと#91全体 | 未完了 | Linux amd64 host受入・最終方式・配布条件確定まで#91をcloseしない |

このWindows記録は `Refs #91` の先行QAとし、製品helperの実装や最終方式採用を開始したとは扱わない。後続の前提commitは、上記main基準と、このQA/docs差分がレビュー・公開権限を経て確定したcommitを明示する。

Mac準備QAはホストへRosettaを導入しない。Limaが未導入時に導入APIを呼び得るため、起動前に既存Virtualization.frameworkのavailabilityがinstalledであることを読み取り、不明・未導入なら拒否する。VM準備用のguest package導入は製品の固定runtime・再配布の実証ではない。

Lima本体のApache-2.0 LICENSE/NOTICEと、同梱helper各componentの個別条件を保持する。Rosetta/WSL platformはOS提供を使い再配布しない。Ubuntu原本は公式pinned URLと上流署名/hashで取得する案とし、private proofのguest apt導入を製品の再現可能なruntime buildと同一視しない。Temurinは各major/OS/archの実artifactを特定し、GPLv2+Classpath Exceptionのlicense/sourceを保持する。現renderer image内はJDKであり、未確認のままJRE配布と呼ばない。Ubuntuの未改変guest image・個別OSS package・改変renderer imageは別の配布単位としてlicense/notice/source提供と商標条件を調査する。[Canonical policy](https://canonical.com/legal/intellectual-property-policy)は個別OSS licenseの権利を減らさない一方、商標を伴う改変版の条件を定めている。private probeの使用と公開配布可能という判断を混同せず、1.1.0で実際に配るbytesと取得元が決まるまで公開許諾済みとは扱わない。#93でexact version/hash・SBOM・LICENSE/NOTICE・必要source提供・上流署名を固定し、release gateとする。現c498を無条件に公開可/不可と断定せず、ローカルapt buildへ勝手に切り替えない。OS署名accountを除いて、この調査から追加費用や有料契約の必要を確定したものはない。

## 公開準備の外部前提

2026-10-17にCentral Portalのnamespace `io.github.rs0325` がVerifiedであることを確認した。利用者がGPG Suiteで作成・送信した公開鍵のfingerprintは `B766BA473AF3178D42371A0F50562040DFAE0F27`。公開鍵の[取得先](https://keys.openpgp.org/vks/v1/by-fingerprint/B766BA473AF3178D42371A0F50562040DFAE0F27)はHTTP 200を確認したが、秘密鍵・passphrase・公開tokenは取得しておらず、token発行状態も未確認。Central用PGP鍵と自動更新descriptorの信頼鍵は別にする。

Apple Developer IDとWindowsコード署名証明書は未準備。ローカルの未署名候補生成・検証と公開済みの状態を分ける。署名なしCLIの配布自体は候補となるが、Gatekeeper/SmartScreen等の警告なしで導入できるとは保証せず、installerごとの制約を公開前に示す。未署名配布を正式方針に確定したとは扱わず、更新descriptorの署名検証を省略しない。

Centralは本人の公開tokenとartifact署名を利用できる状態の確認が残る。macOSのDeveloper ID/notarizationとWindows署名は本人のaccount・証明書・契約条件を確認してから公開手順へ進む。費用・署名方式・必要な本人操作を先に提示し、account登録・鍵作成・有料契約・公開を準備依頼から推定しない。秘密鍵・token・証明書秘密情報をrepositoryや検証ログへ保存しない。これらの未準備を理由に方式実証や未署名候補のローカル検証を省略しない。


### 2026-10-19のLinux rootless方式実証と残件

Mac上の専用arm64 Linux VZ VM 1台（4CPU/6GiB/24GiB）で、既installed Rosettaを使う固定amd64 rendererのrootless実行を確認した。既公開CLI1.0.1のhost Java21/Docker経路で1.20.6のstone・player_head・enchanted_bookを描画し、3PNGの全bytesとmanifest全metadataが固定参照に一致した。新しいmanaged CLI/APIや#96製品helperを通した結果ではない。

署名metadataに結合した4 OS deb（146,388 bytes）だけをguestへ導入し、固定4 Docker deb（65,565,020 bytes）は専用runtimeへ展開した。公式launcherのNET=slirp4netns、DETACH_NETNS=falseを使い、rootfulサービスを導入せず専用user unitで起動した。既subuid/subgidは変更せず、uid/gid map、初期user namespaceとの差、daemon/親PIDの開始識別を測定前後で照合した。必要な権限はguest内のuidmap/network依存導入、対象UIDのsystemd委譲・限定restart、専用実行pathのAppArmor profile追加であり、global sysctl・host software・既存VM/Colimaは変更していない。製品では不足前提を診断し、これらの変更を無断実行しない契約を維持する。

workerの実cgroup memory.max=3221225472、cpu.max=400000 100000を採取した。元入力と新cache不変・出力owner一致、17client全正常終了/reap、所有開始識別が一致するunitの停止・残process/container空、VM通常Stopped、専用socket回収、元config bytes復帰を確認した。実行は約40.40秒。3600秒処理待機と240秒cleanup待機は既有限supervisor尾部を別に持つ予算で、厳密なwallclock総上限ではない。許可済みguest package・専用unit/profile/dropin/runtime/imageは停止状態で保持し、削除していない。

初回は非rootのPID1 namespace読み取りで失敗し、固定sudo読み取りとidentity測定窓を修正した。次の再開はunit modeを一律0644としたgateで停止した。別許可の固定3path診断ではunit regular0664、root2files regular0644、owner/group/bodyが期待と一致した。unit作成sourceはopen('x')の既定0666・umask非指定で実属性と整合するが、当時のumaskや変更者を証明したとは扱わない。3pathそれぞれのexact mode/UID/GID/type/body gateへ限定修正し、18直接fixtureと独立sourceレビュー後の新世代だけを成功と記録した。原2FAIL・診断raw・各source世代を保存し、成功結果は独立監査でも限定PASSとなった。

| 範囲 | 状態 | 残件 |
| --- | --- | --- |
| arm64 Linux guest＋Rosettaのrootless単体方式 | 限定PASS | 既公開CLI経路での1.20.6代表3PNG・実limit・通常所有回収だけ |
| Linux amd64 host/物理Linux、distribution/kernel下限 | 未受入 | 今回のMac VM結果を正式対応へ拡張しない |
| #96製品helper、managed CLI/API、取消/並列/異常回収/再起動/offline再生成 | 未実装・未受入 | #91方式採用と確定前提commitの後に実装・受入する |
| #95製品helper、#99固定51版・全件/反復/境界 | 本記録の対象外 | 代表3PNGから継承しない |
| #91全体と最終artifact/配布条件 | 未完了 | 方式/schema・本番inventory/trust・license/sourceを確定し、closeは別操作 |

[Linux限定QAと世代別証拠hash](../qa/validation-2026-10-19-linux-rootless-proof.json)を参照する。この補足はRefs #91であり、製品実装や#91 close、#96の開始条件成立を宣言しない。
