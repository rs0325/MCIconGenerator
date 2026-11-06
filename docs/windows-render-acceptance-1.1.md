# Windows候補資材の実Minecraft描画受入

Refs #91 #95。2026-11-04。基準commit `c5a46b01a1c4dc4dba6be038f00dd13ae1dc0f33`、branch `feature/issue-95-render-acceptance-win`。Issueはcloseしない。

固定51版のうち**51版合格**。本受入の失敗版は0版。対象は各版の `minecraft:stone`、64×64、別出力先への独立した2回のCLI実行（計102回）。全登録アイテムを網羅する受入ではない。正本は[QA JSON](../qa/windows-render-acceptance-2026-11-04.json)と[SHA-256](../qa/windows-render-acceptance-2026-11-04.json.sha256)。

## 手順と採用した既存機能

固定集合は `adapters/releases.json` の51 ID。[validation](validation.md)・[usage](usage.md)・過去の `qa/validation-2026-10-15-canonical-acceptance.json` に位置付けを確認した。既存 `scripts/verify-matrix.py` は固定集合とCLI受入を持つが、Windows専用distroの所有・回収や2回実行を扱わないため、[F1 runner](../helpers/windows/scripts/render-acceptance.py)を追加した。CLIのrender・RuntimePreparer・WorkerClientと、`scripts/audit-export.py` のPNGデコーダを再利用し、製品コード・共有契約・依存ライブラリは変更していない。E2のarchive検証・XZ展開を再利用した。

metadataは既存VersionServiceと同じく現行公式manifestのURL・SHA-1を使用した。固定ファイルのmetadata SHA-1との差はQAの `metadataSelection.driftVersions` と各版の両hashへ記録した。固定51 ID自体は変更していない。

QAの取得記録にあるSHA-256は公式配信bytes、各回settingsの `metadataSha256` は製品のJackson再シリアライズ後のhashである。最終照合ではこの表現差を区別し、公式SHA-1照合と2回のsettings全体一致を確認した。

1. 当worktreeのCLI/workerを既存JDK21・Gradle 9.8.1・依存キャッシュで `--offline --no-daemon :cli:installDist` によりビルドする。
2. 公式manifestから固定IDのmetadataを取得し、manifestのSHA-1を照合する。RuntimePreparerと同じlinux/amd64ルールでclient・mappings・libraries・natives・asset index・非ogg assetsを `build/f1/` 内に用意する。資産ごとに公式SHA-1・宣言サイズを照合し、URL・サイズ・SHA-256はローカル `downloads.json` に記録する。許可外URL・HTTPS以外とそのredirectは拒否する。
3. C1 rootfsとE1 renderer archiveのサイズ・SHA-256をE2記録に照合する。専用 `mcicon-f1-<乱数>` distroだけを `build/f1/run-<乱数>/distro` にimportし、登録GUID・BasePath・既定・既存登録を照合する。既存distroは起動・停止しない。
4. 専用distroのdockerdでbridge・iptables・IP forwarding・IP masqueradeを無効にし、候補rendererをloadする。候補imageのJava21を専用guestへ取り出してCLIをヘッドレスで起動する。ゲームworkerは製品のJava選択とコンテナ起動をそのまま使う。
5. 1.21.4のpilot成功後、残り50版を最大2版並列で各2回実行する。各コンテナはCPU 4・memory 3072MiB・heap 2048MiB・network none。CLIは `--offline --runtime docker --docker-context default` を明示する。これは資産事前取得後のQA実行であり、未準備hostやoffline製品経路の受入ではない。
6. CLI exit 0、PNG CRC/復号、64×64、非空・非全透明、manifest上の実使用Javaを確認する。別プロセスの観測器でもPNG本体を直接比較し、SHA-256をrunner記録と照合した。ゲームjar・assets・mappings・PNG本体はQAへ含めない。
7. 自分の登録GUID・BasePathを再照合して当該distroだけをunregisterする。専用BasePathと取得資産・生成PNGを削除し、WSL一覧・statusと既存登録/既定の前後一致を確認する。ログとQAは保全する。

実行は指定 `run_logged.py --` と権限審査を通した。コマンド・stdout/stderr・時間・取得hash・workerログは `build/f1/run-a2b0d76ab703` に保全する。runnerを再実行する場合も、現状ではこの承認済みworktreeと既存候補archive・事前照合済みmappingsを使う専用QAである。

## 環境と合否基準

Windows 11 Pro 10.0.26200.9711、Ryzen 7 5700X、RAM 68,640,256,000 bytes、Python 3.12.14、WSL 3.0.3.0、kernel 6.18.54.1-1。候補rootfs・rendererの固定hashはQAのinputs、image IDは `sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc`。E2の[Xvfb/GLXとJava環境](windows-smoke-1.1.md)を使った。

旧c498とのピクセル一致は[再現性調査](renderer-reproducibility-1.1.md)の理由で合否に使わない。同一入力の2回のexit 0・有効PNG・直接バイト一致・版別Java選択を必須とする。1.17/1.17.1の公式要求Java16は既存の契約どおりJava17へ対応付け、その他は公式metadataのmajorを要求した。Javaバージョンと選択hashは各回のmanifestから取得した。

## 1.13〜1.14.3の7版と取得元

前回はこの7版の第三者mappings取得が未許可で描画前に停止した。再開時の明示許可により、製品 `LegacyMappings.prepare` が既に使う `https://maven.ornithemc.net/releases/net/ornithemc/` のFeather/CalamusだけをHTTPS取得した。全14 artifactで `adapters/legacy-mappings.json` のSHA-1 pinと、同URLの `.sha1` に一致した。URL・サイズ・SHA-1・SHA-256はQAの `acquisition.mappings` に全件記録した。取得物は公開せず作業後に削除する。

## 版ごとの結果

| 版 | 実使用Java major | exit（1/2） | 判定 | PNG bytes | 描画秒（1/2） |
| --- | --- | --- | --- | --- | --- |
| 26.3 | 25 | 0/0 | 合格 | 一致 | 37.922/38.422 |
| 26.2 | 25 | 0/0 | 合格 | 一致 | 35.594/37.203 |
| 26.1.2 | 25 | 0/0 | 合格 | 一致 | 37.485/35.0 |
| 26.1.1 | 25 | 0/0 | 合格 | 一致 | 37.765/35.188 |
| 26.1 | 25 | 0/0 | 合格 | 一致 | 35.454/35.859 |
| 1.21.11 | 21 | 0/0 | 合格 | 一致 | 33.219/34.891 |
| 1.21.10 | 21 | 0/0 | 合格 | 一致 | 38.546/34.407 |
| 1.21.9 | 21 | 0/0 | 合格 | 一致 | 37.218/34.562 |
| 1.21.8 | 21 | 0/0 | 合格 | 一致 | 33.89/36.016 |
| 1.21.7 | 21 | 0/0 | 合格 | 一致 | 33.797/36.672 |
| 1.21.6 | 21 | 0/0 | 合格 | 一致 | 39.094/41.813 |
| 1.21.5 | 21 | 0/0 | 合格 | 一致 | 38.75/41.578 |
| 1.21.4 | 21 | 0/0 | 合格 | 一致 | 30.844/28.938 |
| 1.21.3 | 21 | 0/0 | 合格 | 一致 | 35.234/35.578 |
| 1.21.2 | 21 | 0/0 | 合格 | 一致 | 33.188/35.312 |
| 1.21.1 | 21 | 0/0 | 合格 | 一致 | 35.547/42.453 |
| 1.21 | 21 | 0/0 | 合格 | 一致 | 34.906/42.453 |
| 1.20.6 | 21 | 0/0 | 合格 | 一致 | 34.64/36.438 |
| 1.20.5 | 21 | 0/0 | 合格 | 一致 | 34.219/36.453 |
| 1.20.4 | 17 | 0/0 | 合格 | 一致 | 34.375/33.046 |
| 1.20.3 | 17 | 0/0 | 合格 | 一致 | 34.359/32.984 |
| 1.20.2 | 17 | 0/0 | 合格 | 一致 | 31.187/29.11 |
| 1.20.1 | 17 | 0/0 | 合格 | 一致 | 33.625/31.718 |
| 1.20 | 17 | 0/0 | 合格 | 一致 | 34.907/34.25 |
| 1.19.4 | 17 | 0/0 | 合格 | 一致 | 34.375/34.516 |
| 1.19.3 | 17 | 0/0 | 合格 | 一致 | 44.766/40.375 |
| 1.19.2 | 17 | 0/0 | 合格 | 一致 | 51.469/48.078 |
| 1.19.1 | 17 | 0/0 | 合格 | 一致 | 47.5/47.234 |
| 1.19 | 17 | 0/0 | 合格 | 一致 | 47.484/45.953 |
| 1.18.2 | 17 | 0/0 | 合格 | 一致 | 51.437/55.75 |
| 1.18.1 | 17 | 0/0 | 合格 | 一致 | 52.203/55.828 |
| 1.18 | 17 | 0/0 | 合格 | 一致 | 48.359/60.422 |
| 1.17.1 | 17 | 0/0 | 合格 | 一致 | 58.328/58.25 |
| 1.17 | 17 | 0/0 | 合格 | 一致 | 54.531/55.313 |
| 1.16.5 | 8 | 0/0 | 合格 | 一致 | 60.984/55.281 |
| 1.16.4 | 8 | 0/0 | 合格 | 一致 | 55.031/56.954 |
| 1.16.3 | 8 | 0/0 | 合格 | 一致 | 55.875/43.063 |
| 1.16.2 | 8 | 0/0 | 合格 | 一致 | 41.953/41.422 |
| 1.16.1 | 8 | 0/0 | 合格 | 一致 | 42.062/39.625 |
| 1.16 | 8 | 0/0 | 合格 | 一致 | 39.985/45.453 |
| 1.15.2 | 8 | 0/0 | 合格 | 一致 | 41.672/55.969 |
| 1.15.1 | 8 | 0/0 | 合格 | 一致 | 53.657/48.0 |
| 1.15 | 8 | 0/0 | 合格 | 一致 | 47.203/50.563 |
| 1.14.4 | 8 | 0/0 | 合格 | 一致 | 45.063/54.782 |
| 1.14.3 | 8 | 0/0 | 合格 | 一致 | 54.281/47.125 |
| 1.14.2 | 8 | 0/0 | 合格 | 一致 | 46.359/44.563 |
| 1.14.1 | 8 | 0/0 | 合格 | 一致 | 42.75/41.453 |
| 1.14 | 8 | 0/0 | 合格 | 一致 | 40.016/42.469 |
| 1.13.2 | 8 | 0/0 | 合格 | 一致 | 33.859/35.516 |
| 1.13.1 | 8 | 0/0 | 合格 | 一致 | 33.969/31.719 |
| 1.13 | 8 | 0/0 | 合格 | 一致 | 27.187/29.25 |

各PNGのSHA-256・寸法・可視pixel数・Java版・合計所要時間はQAのversionsに記録した。失敗版のstderr要点・例外も同じ行の記録に残す。

## 初回pilotの失敗と修正

初回1.21.4の2回はCLIのImageIOがGUI用 `libXext.so.6` を要求してexit 1となった。CLI起動の `-Djava.awt.headless=true` 指定で解消した。初回を成功扱いせず別記録とし、その専用distro・資産も回収済み。修正後pilotの2回を本受入102回に含める。

## 後始末と未検証

WSL一覧・status・既存登録/既定の前後一致: **True**。専用登録不存在: **True**。BasePath不存在: **True**。runner資産・PNG削除: **True**。追加のmappings・metadata・展開用tarも67ファイル回収し、`build/f1/` 内にjar・PNG・VHDXは残っていない。hostのDocker Desktop/backend/dockerd/containerdも観測されなかった。独立観測器と受入runnerの終了を確認した。daemon用WSL clientのexit 1は専用distro unregisterに伴う切断であり、描画exitとは区別する。既存distro・既定・`.wslconfig`・WSL設定は変更せず、shutdown/update/機能有効化/rebootは行っていない。

未検証: 未準備host setup、OS警告、実reboot、offline製品経路、正式配布経路、Mac、全登録アイテム。commit・push・PR・release・Issue closeは行っていない。

## 公開物だけによる空cache E2E（2026-11-05）

Refs #94 #95 #98 #99。上記F1とは別に、公開済みv1.1.0のWindows bootstrap ZIPとMaven Central 1.1.0だけを新規取得し、製品bootstrapから管理環境を自動準備した。基準は origin/main d33583f、branchは feature/issue-95-e2e-windows。実測正本は [E2E QA](../qa/e2e-windows-2026-11-05.json) と [LF blob基準checksum](../qa/e2e-windows-2026-11-05.json.sha256)。

- MCICON_DATA_HOMEとTMP/TEMPを新規専用領域へ隔離した。署名seed・slot設置、runtime取得・署名/hash検証、世代設置、製品専用WSL・Docker準備を経て、1.14.4 / 1.21.4 / 26.3のminecraft:stone（64×64）を描画した。初回・再利用・offlineを含む5/5回が終了0で、PNGのCRC・RGBA・可視pixelを確認し、全PNG SHA-256が既存Windows/Mac受入と一致した。
- 所要時間: render-cold 236.266秒、render-reuse 147.094秒、render-offline 135.39秒、render-old 172.406秒、render-latest 137.125秒。初回help/署名slot設置は46.031秒。取得本文の保存量下限は **1,258,218,546 bytes**。HTTP/TLS、Git、公式版一覧の再取得、背景更新manifest等はこの下限に含まない。
- 2回目は管理runtime・ゲーム資産を再利用した。公式版一覧だけは同じ内容で再取得された。--bootstrap-auto-update falseは終了0で設定を保存し、以後の更新準備worker増加なし。初回の背景準備は同版・旧sequenceとして未適用（exit 1）で終端した。
- --offlineとプロセス限定のHTTP/HTTPS拒否proxyで既存1.21.4を継続でき、proxyへの取得試行は0件。物理ネットワーク切断や全通信監査の代わりとは扱わない。
- 製品が作成した専用distroは MCIcon-cdcd7ce552f0be140a57eae5。全leaseのreleased・資源空と本人プロセス不存在を確認し、所有記録・GUID・BasePath一致後に当該登録だけをunregisterした。既存40登録・既定・WSL一覧/status/稼働状態と既定保存先不存在は前後で完全一致。専用取得物・cache・PNG・VHDX・tempを削除し、ログ・hash・所有証拠だけをbuild/e2eに保全した。
- 未署名（Authenticode NotSigned）の公開launcherは今回の非対話経路で起動でき、警告・ブロックを観測しなかった。ただしPython取得・展開ではZone.Identifierが付いていない。**ブラウザ取得/MOTW付き初回・GUIのOS警告は未検証**。属性削除、OS保護・既存distro・Docker・システム設定の変更、reboot、機能有効化は行っていない。

未検証は上記OS警告、WSL未導入host、実reboot、全51版・全アイテム、新版への自動更新/rollback、installer/winget。今回の機能成功で受入全体を完了扱いにしない。製品コード・API・保存形式は変更せず、commit・push・PR・Release/Central変更・Issue操作も行っていない。

## 公開v1.1.0の取消し・中断後再開・残存データ（2026-11-06）

Refs #95。origin/main 71972cf、branch feature/issue-95-e2e-cancel-resume。公開Windows bootstrap ZIPだけから実測した。正本は [追加E2E QA](../qa/e2e-cancel-resume-windows-2026-11-06.json) と [LF blob checksum](../qa/e2e-cancel-resume-windows-2026-11-06.json.sha256)。製品コードは変更していない。

| 項目 | 結果 |
| --- | --- |
| 初回準備中断→同一コマンド再実行 | 中断exit 1、再実行exit 0。取得済みhelperはsize/hash/mtime不変。guest部分取得を完成させ旧leaseをreleasedへ回復。 |
| 代表PNG | 1.21.4 minecraft:stone、64×64。SHA-256 b4bbd5b1cad5746ee377debf884df1b3ae4cfdcb91e9a0a198be67a592e443de、既存基準と一致。 |
| 破損part | 同一sizeのhelper artifact.part先頭1byte反転を検出・再取得。正規hashへ復元しpart消失。他artifactは不変。 |
| 実worker稼働中のCLI単独終了→再実行 | 終了直前にDocker State=runningと完全IDを確認。中断exit 1、再実行exit 1。release-recoverがDAEMON_RESTART_DENIEDを返し再開PNG未生成。 |
| 追加の起動途中取消し | intent時のCLIツリー全体終了ではkeeper ACK不一致、created時のCLI単独終了ではDAEMON_RESTART_DENIED。最終running試行と区別。 |
| programのみ手動削除 | 非VHDXデータfileのsize/hash/mtime・設定・ログ・cache・登録distro・生成PNG・元rawを保持。VHDXはpath保持を確認し全内容不変とは扱わない。 |
| 後始末 | 今回の専用distroだけをGUID/BasePath照合後に回収。既存40登録・既定・一覧/status/稼働状態・.wslconfig hash・既定製品pathは前後一致。 |

**実worker中断後の再開は不合格**。helperの拒否理由まで確認したが根本原因は未確定で、コード修正やIssue変更は行っていない。中断後はactive lease、専用distro、running表記のbootstrap receipt、lock fileが残った。初回準備の空stagingも残存した。lock file残存だけをOS lock保持とは判断しない。失敗状態を保全し、別rootへ移る前に当該distroを回収した。後続rootへは公開物由来のdownloads/cacheだけを再利用した。

公開タグv1.1.0の利用ガイドは起点mainと同一で、docs/usage.md:138の通常uninstallのデータ保持方針と手動program削除の結果は整合した。一方、**公開ZIP向けuninstallコマンド・全データ削除手順は未記載**。ZIP展開directoryを手動削除した試験であり、正式MSI/winget uninstallerや製品purgeの成功とは扱わない。自動回復失敗を手動回収で成功へ書き換えない。

MCICON_DATA_HOMEとTMP/TEMPを専用領域へ隔離した。専用3root・temp・配布ZIP・VHDXを除去し、証拠ログ・hash・試験用生成PNGと元rawはbuild/e2e/に保持した。再取得なしの確認は保存fileのsize/hash/mtimeに基づき、全HTTP通信監査ではない。未検証は中断後の成功PNG（再開失敗）、Ctrl+Cシグナル経路、短い破損partのRange継続、正式installer/winget uninstall・製品purge。OS reboot・OS設定変更・既存distro/Docker変更、commit・push・PR・Issue変更は行っていない。
