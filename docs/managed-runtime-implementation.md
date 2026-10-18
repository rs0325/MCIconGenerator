# 管理runtime内部実装の準備状況

Issue #93の作業記録。共有契約の起点は `9c01311c78fad487b46c60e71de0d1d3f27b6396`。#92の確定merge `3c001f678363dd7a9e38b6e47058d3edf239589e` を許可済みfast-forwardで取り込み、同じworktreeで接続した。

`internal.managed` は固定版ライブラリ向けの内部実装であり、stable更新manifestやCLIのhighwaterを参照しない。descriptorは同梱期待SHA-256とEd25519を元bytesで検証した後に読み、未知field・protocol、重複key、worker版/hash、adapter集合、host、Java major、artifact参照と相対pathを検証する。inventoryは同梱resourceのhashを検証する。実componentとlicense/sourceの対応確認は、実bundleが確定するまで未完了である。

取得ファイルは `downloads/<artifact-sha256>/artifact.part` に分離し、正確なsize/hash検証後に同じdirectory内で原子的に確定する。Range再開は206の開始位置を確認し、200なら先頭から取り直す。取得元が変わった場合も署名済み最終hashに合格するまで使わない。offlineは検証済み完成ファイルだけを使う。取得lockはprocess間file lockで、同一JVMの競合も待機する。準備期限にはlock待機、通信、body読み取りを含める。

leaseの `hostId` は管理root内で生成する登録UUIDであり、機械固有IDや利用者名ではない。別hostからコピーしたrootの本人性を証明せず、それだけで停止や回収を許可しない。`processStartedAt` が取得不能ならnullを保持し、PIDだけで生存・所有を推測しない。coreは初期leaseを書いた後、helper実行中には更新しない。helperはimmutableなfieldを保持し、`resources` だけを原子的に更新する。複数helper processの更新排他とOS固有resource schemaは各OS helperが実装する。

coreが保持する `lease.lock` はジョブの稼働証拠であり、helperが取得する更新lockではない。control helper終了後にcoreはimmutable fieldを再照合する。release成功とresources空を確認した時だけ `state:released` を原子的に記録する。失敗・timeout・取消時にはactiveなleaseを残し、file lockとprocessは有限に解放する。残存・破損・不明leaseはidleに扱わない。PIDと開始時刻が一致しても単独でVMやprocessを停止しない。

OS固有所有schema、専用VM/distribution/socket/containerの照合、監督process回収は #94/#95/#96の前提であり、現段階で本番のVM停止・世代削除機能はない。`idle` は確認補助であり、削除許可ではない。

## 未完了gate

- 本番の固定pins/信頼鍵と、実runtime artifactのexact version/hash、inventory・SBOM・LICENSE/NOTICE・必要source・上流署名・公開許諾。
- 実packagerとのadapter digest・展開上限・XZ memory制限の結合検証。
- 各OS helperのresource所有schema・回収と、通常/取消/並列ジョブの実機受入。
- 新しい管理runtimeによる固定51版の画像受入。

fixtureの鍵はテスト内でのみ生成し、`fixture.invalid` は通信に使わない非配布テスト値である。fixture成功を本番artifactの検証済み・公開可能という結果には使わない。

## archiveの内部制約と依存

host helperの形式はdescriptorのzip・tar.gz・tar.xzだけを実装し、link・特殊file・重複entry・正規化前の危険なZIP名を拒否する。guest rootfs内部linkはhostに展開しない。TARの `unpackedBytes` はfile内容だけでなくheader/paddingを含む解凍TAR streamの上限にも使う。XZ decoderのmemory上限は64 MiBである。実packagerが同じ計算で宣言し、正規helper配布物が両条件を満たす結合検証は未完了gateである。

先行段階は一時Gradle init scriptで検証した。#92確定後は以下をcoreの通常build依存へ統合し、実selectedのCodec 1.19.0・IO 2.20.0・Lang 3.18.0も通知に保持した。通常のMaven Central依存として取得し、実runtime bundleの公開を行ったものではない。

| 依存 | JARのSHA-256 | ライセンス・取得先 |
| --- | --- | --- |
| Commons Compress 1.28.0 | `e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca` | Apache-2.0。JAR内のMETA-INF/LICENSE.txt・NOTICE.txtを保持。[Maven Central](https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar) |
| XZ for Java 1.10 | `95c63c1a55b22dd6453890a419cc1a640f790bbf7d8ae82db1e30aefefb08888` | 0BSD（同版POMの宣言）。[Maven Central](https://repo.maven.apache.org/maven2/org/tukaani/xz/1.10/xz-1.10.jar) |

形式・path検査の根拠は[Commons Compress公式guide](https://commons.apache.org/proper/commons-compress/examples.html)、依存版は[ApacheのMaven座標](https://commons.apache.org/proper/commons-compress/dependency-info.html)と[XZ公式](https://tukaani.org/xz/java.html)を確認した。直接・推移依存のLICENSE/NOTICEを`licenses/`と`THIRD_PARTY_NOTICES.md`へ反映した。

## 初回反証の修正

- idle判定では必須schema・UUID・時刻・PID・root登録hostId・job directory・対象世代のtool/worker/runtimeを検証する。`lease.lock` には初期immutable identityをfield名順にserializeしたSHA-256の64 ASCII bytesだけをreceiptとして保持し、後のleaseと照合する。empty lock・破損receipt・開始時刻不明は回収可能としない。receiptは作成時のlock取得中に一度だけ書き、その後はcore/helperとも更新禁止。同じ権限で全記録を意図的に改変する利用者への防御は保証しない。
- timeout・取消・不正JSON・status不整合・前提不足では、一意なjob内stdout/stderrと、exit code・process開始識別・直接process終了確認を残す。直接起動したhelperには強制終了後、追加で最大2秒待つ。非終了を記録して失敗させ、元のtimeout/取消例外がある場合はcleanup失敗をsuppressed exceptionへ保持する。これはOS helperが管理する子孫・VMの回収成功を意味しない。
- generationへのコピーとhelper treeのhashにもchunkごとの準備期限・取消確認を適用する。低速読み取り中に期限や取消が成立した場合、次のchunkを処理せず未確定stageを完成世代へ移さない。

leaseのreceiptは先に `tryLock` し、busyなら読まずfalseを返す。取得後は別handleを開かず、lockを保持する同じread/write channelから読む。Windowsのmandatory byte-range lockとの互換性を考慮した順序であり、検証はmacOS上の同一JVM・別Java processのbusy条件で実施した。Windows実機での成功を示すものではない。

別process lock試験の後始末も、子processの強制終了と有限waitを先に行い、終了確認後にreaderをcloseしてexecutorを終了する。HELDを出さず生存する故障fixtureではreadLineの待機期限を超えた後も、この順序で有限に終了することを確認する。製品のprocess回収処理とは別のテスト側の条件である。


## 固定信頼設定とAPI接続

`ManagedOptions` と `renderManaged` / `doctorManaged` / `listEntriesManaged` のOptionsあり・既定版の6署名を接続した。既存のWorkerOptionsのrecord成分と5/6/8引数constructor、既存API署名は保持する。管理方式は元instanceのoffline・CancellationToken・closeを引き継ぎ、旧Docker方式は自動取得や更新を開始しない。

固定信頼設定はcoreと同じclasspathに同梱する `META-INF/mcicon/runtime/pins.json` とする。schemaVersion=1、runtimes配列の各要素にhost（os/arch）・descriptorPath・descriptorSha256・trustKeyPathを持つ。descriptor・隣接`.sig`・X.509 DER Ed25519公開鍵・inventoryを同じresource prefixから一意に読む。pins/trustをdescriptor本文やCLI状態から受け取らず、足りなければ管理root書込・管理artifactのnetwork取得前にMANAGED_RUNTIME_INVALIDで拒否する。renderManagedでは先にrenderと同じ版受入を判定し、その公式版情報取得には元instanceのoffline契約を適用する。本番のこのresourceはまだ同梱していない。fixtureはテスト専用classloaderだけで与える。

adapter集合digestは、名前順の `adapters/legacy-mappings.json`、`adapters/releases.json` それぞれについてUTF-8の `path + NUL + 元bytesの10進長 + NUL` と元bytesを連結したSHA-256とする。tool版は生成済みToolVersion、worker hashは#92のWorkerArtifact.resolveの検証値、必要Javaは8/17/21/25で照合する。実packagerとの同じdigest計算・信頼設定生成の結合は未完了gateである。

準備はhelper取得・検証→lease取得とjob snapshot→preflight→guest/image取得→helper prepareの順。前提不足なら大容量取得を進めない。leaseの兄弟 `descriptor.json` に署名検証済み元bytesをそのまま保存し、inventoryは署名対象inventoryPathへcopyする。lease.json・lease.lock・descriptor.json・tmp・helperログ領域との衝突とinventory同士の親子path衝突を拒否する。OS helperはleaseのowner・世代を照合し、兄弟descriptorの元bytes hashがleaseのdescriptorSha256と一致すること、inventory hash、`runtimes/<runtimeId>/<descriptorSha256>/<artifactId>.<format>` とhelper世代が一致することを再確認する。OS下限・SSE4.1・VM/WSL/rootlessの実能力検査はhelper preflightの責務で、OS名だけで成功としない。

WorkerLaunchはジョブ単位の固定prefix/environmentと期待値だけを保持する。worker起動・image inspect・Java/Mesa診断・取消rmは同じhelper/leaseを通す。元agentを既存ジョブsnapshotへ渡す際のhash、image ID、選択Javaのmajor/hashを照合し、管理方式の不一致はMANAGED_RUNTIME_INVALIDとする。hostの資格情報をchild環境へ一括継承せず、専用tmpとWindowsの必要なOS環境だけを渡す。ゲームcontainerには署名descriptorの描画環境変数を与える。

前提不足はrender/listでMANAGED_SETUP_REQUIRED、doctorで不健康なDiagnosticReportへ写す。署名/hash/信頼設定不正はFutureを失敗させる。worker/protocol側の既存エラーは書き換えない。ジョブのcleanupでは新しい取消tokenで所有releaseを有限実行し、元のthread interruptを一時解除して最後に復元する。解放失敗は証拠を残し、元失敗がある場合はsuppressedへ保持する。

この接続はCLI既定経路の変更、CLI updater、OS helper本体、実画像生成の受入を含まない。通常core/CLI/workerテストとfixtureの成功を、本番runtime・署名・OS能力・画像の受入成功とは扱わない。

接続反証でrenderManagedの版受入判定を管理準備より前へ移した。unsupported/discoveredとexperimental同意不足は固定pins不足より先に拒否し、helper/artifact/lease用rootとゲーム入力を準備しない。必要な公式版manifestの取得・offline挙動は既存renderと同じとする。

doctorManagedでは、前提不足のみなら不健康reportへ変換するが、準備中のrelease/lease解放が失敗した場合はsuppressedを含む元の例外をFutureへ保持する。setup-requiredとrelease failedの複合fixtureで、利用者が回収失敗を確認でき、active leaseが残ることを検証する。
