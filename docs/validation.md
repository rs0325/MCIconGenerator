# ローカル検証記録

実施日: 2026-10-10。GitHub Actionsは使用していません。

環境: macOS 15.7.7 / Apple Silicon、Colima mcicon / Virtualization.framework / Rosetta、Linux amd64 container、VM 4 CPU / 6GiB。worker上限heap 2GiB / container memory 3GiB。core Java21.0.9、game Java8/17/21/25。Java16最低要件の1.17系はJava17 LTSで実行します。最大2 workerを並行実行しました。

描画image ID: `sha256:d48946bb12109a4008c48ee103c03a15c5c0f96980f754bcc05e436a95307977`。Mesa25.2.8 / llvmpipe LLVM20.1.2 / OpenGL4.5。26.3はSDL_VIDEO_FORCE_EGL=1です。

今回のfixed-v1・64pxの全件生成と監査は51/51版、合計60,265枚が成功しています。失敗版: なし。未完了: なし。版別件数は[対応表](compatibility.md)、入力・agent・image hashと時間は[数値記録](../qa/validation-2026-10-10.json)をご覧ください。

全版を同一worker binaryで再試験し、版ごとにagent・client・mappings・image hashを記録します。rawは先行ビルドの検証記録、cancel/resumeとworker crash/resumeは今回の同一worker binaryで検証しています。

監査はPNG hash/CRC、decoded RGBA8、size、alpha、alpha=0の隠れたRGB、空画像、重複、実レジストリ母集団と全件出力ID、no_item_formを確認します。意味的なmissing texture/modelの検出や色空間の正しさは、この監査だけでは保証しません。

deferred GUIのアトラス位置をpassごとに固定して、single/allで投影されたglintのUV位置が変わる問題を修正しました。native textureを再利用し、色・深度を消去してスロットを初期化します。GUI frame/uniform storageの終了と16リクエストごとのnative frame復帰により、GPUリソースの遅延解放を進めます。代表IDのRGBA比較結果は数値記録で確認してください。

独立したゲームプロセスでの代表ID再生成比較: 21版を実行、21版で一致。成功した比較は数値記録にcomparedとcomparisonVerifiedで記録します。比較対象は各版の全件出力で、decoded RGBAと同じ出力パスを照合します。比較にはenchanted_book、時計、コンパス、特殊モデル、着色、半透明などの共通25IDを使用しました。代表IDの比較は全IDの反復一致を意味しません。

実Java APIで3件生成後にcancelし、新tokenで再開した全784件のPNG監査と全件基準とのRGBA比較に成功しました。

JUnit 35件が成功。cache破損・offline・bounded retry、path/symlink、raw bytes/animation/alpha、出力所有権、再開、alias/no_item_form、CLI排他、exact profile、agent snapshot、ASM isolation、固定context、alpha復元を合成fixtureで検証します。iconsのクラッシュと途中cancelでは、予約済み未完了IDの再描画、成功PNGのhash再利用、unowned PNGの保護を確認しました。ゲーム資産をfixtureに含めません。

今回のdoctor 1.17はhealthy=True、cache verifyは記録済み2843入力を照合し、失敗0件でした。cacheの総bytes/filesは処理中の一時ファイルを含む時点値です。

CLI zipとsources/javadoc付きJavaライブラリをローカル生成しました。配布ZIPにゲームJAR・assets・PNGは含まれません。依存ライセンスを同梱します。正式release/Mavenへの公開は未実施です。

今回の元テクスチャ全件抽出は51/51版、合計72,638 PNGと付随mcmetaが元JAR bytesと一致しました。数値記録に版別件数を保存します。以前のコミットではdoctor、記録済み541入力のcache hash、startup timeout=1秒後のcontainer cleanupも確認しています。

実Java APIで3件生成後に、そのJavaプロセス自身が起動したworker containerのみを強制終了しました。成功3件・予約済み失敗781件を記録し、新instanceで再開した784件が全件基準とRGBA一致しました。

## 再実行

```sh
sh scripts/local-qa.sh
python3 scripts/verify-matrix.py --versions 1.13 1.21.4 26.3 --out .local/repeat \
  --compare .local/qa-output --ids stone diamond clock compass chest shield glass \
  --cache .local/qa-cache --offline
python3 scripts/audit-export.py .local/qa-output/java/1.21.4/icons/manifest.json --require-all
```

local-qa.shは全51版を順番に生成します。verify-matrix.pyはCLI配布物を開始時にsnapshotし、別ビルドの影響を避けます。初回は公式資産の取得にネットワークとdisk容量が必要です。elapsedSecondsは準備・描画・終了・監査を含み、cold/warmや最大RSSを統制した性能測定ではありません。

## 追加検証（同日）

復旧・CLI help・API終了の実装コミットは `b5380a7`。追加の[数値記録](../qa/validation-2026-10-10-followup.json)へ保存します。描画workerのSHA-256は初回全件検証と同一です。

- 独立した全件ジョブを51版で再実行し、60,265枚すべてのdecoded RGBA・パスが初回結果と一致しました。これはall/allの反復です。全IDを別々のsingleプロセスで起動した検証ではありません。個別/all比較は前述の21版・525代表アイコンです。
- 最終core/CLI snapshotでraw全51版・72,638 PNGと存在する付随mcmetaが元JAR bytesと一致しました。
- rawの実Java API cancel・core SIGKILL・Docker tmpfs ENOSPC後に、各891件を新しいCLIプロセスで再開し、元JARとbytesが一致しました。出力競合は実プロセスのFileLockでOUTPUT_BUSY、exit 1、manifest未作成を確認しました。
- 合成入力で本番atomicWriteの書き込み中にSIGKILLし、残ったpart 1件を次回のrawエクスポートで回収・正常再生成しました。リンクと利用者ファイルを保持する単体検証も実施しました。
- native iconsのcore JVMを3件後にSIGKILLしました。healthyなDocker daemonでは専用workerがstdin EOFから3.16秒で終了し、新しいCLIで再開した784件が全件基準とRGBA一致しました。daemon・通信経路が壊れた際の即時cleanupを保証する結果ではありません。
- 既知のRGB/alphaをRGBA8 FBOにGPU描画した合成試験では、alpha誤差0、alpha≥64のstraight RGB最大誤差2/255、灰色背景への再合成最大誤差1/255でした。sRGB framebuffer conversionは無効です。ゲーム自身の色空間・特殊blend/glintの受入とは区別します。
- JUnit47件成功。rawの未所有ファイル/付随metadataの保護、overwrite直後のクラッシュ、fail-fastの未処理予約、API close時の実行中/待機中Futureの完了、close後のtyped failure、全サブコマンドのhelpを追加検証しました。

1.21.4 / 64px / 全1,384件の単一worker計測を実施しました。coldは空のMCIcon cacheへ公式HTTPS取得を含めて38.68秒、warmは同じ入力をofflineで使い、新しい出力先へ再描画して27.96秒・28.60秒でした。warm全件がcoldのRGBAと一致しました。workerのVmHWMはそれぞれ1,216,925,696 / 1,152,536,576 / 1,167,818,752 bytes、準備済みcacheは211,762,689 bytes / 777 filesです。

coldでもDocker image/VMは既に準備済みで、OS page cacheは消去していません。時間はAPI開始からworker終了までで、最終icon後のDocker memory samplingを含み、PNG監査を含みません。VmHWMとcontainer memory.peakは最終icon後・shutdown前に読みます。cache容量はworker作業ディレクトリを除き、hash/lock/native展開を含みます。1版・cold 1回/warm 2回の実測で、全版や別環境の速度保証ではありません。

再実行は、描画matrixを終了してからbenchmarkを開始してください。

```sh
python3 scripts/verify-matrix.py --mode raw --out .local/raw-repeat --offline
python3 scripts/verify-export-faults.py --out .local/fault-repeat
python3 scripts/verify-icon-sigkill.py --out .local/icon-sigkill-repeat --compare .local/qa-output
python3 scripts/verify-native-alpha.py --out .local/alpha-repeat
python3 scripts/benchmark-native.py --out .local/benchmark-repeat
```

16/512pxの端点は9 adapter・各6代表ID、18ジョブ/108 PNGが監査に成功しました。1.13/16pxの初回は準備段階でIO_FAILED、単独再実行で成功し、並行準備8回では再現しませんでした。最初の失敗も追加記録に保存します。

目視比較では1.13〜1.14.3のチェストが不自然な多色表示になっていることを確認しました。原因はワールド入場時に設定されるBlockEntity dispatcherのTextureManagerがGUI専用起動ではnullになり、vanillaのbindTextureが何もしなかったことでした。83d2382でMinecraftのTextureManagerを供給し、旧7版・5,866件の全件監査とチェストの正常なテクスチャ表示を確認しました。1.13/1.14.3の特殊モデル・時計等18代表IDも修正後の全件結果とRGBA一致しました。修正後workerはSHA-256が変わります。前述の51版反復・性能の記録は以前のworkerの結果で、新binaryで全51版を再実行したという意味ではありません。全件生成・反復一致だけで画質を受理しません。

startup timeout=1秒と不正agentの実CLI試験では、専用agent snapshotと一時PNGが回収されました。対応コミットは64ff362です。core SIGKILLの即時cache snapshot回収は保証しません。

## 未完了の受入検証

- 修正後の全51版の新worker再試験、一過性の準備IO_FAILED、全IDの個別/all一致、実ゲーム基準画像、特殊モデル・着色・glint・時計/コンパスの意味的な比較。
- sRGB・半透明・alpha復元の色精度、missing texture/modelの完全検出。
- Windows/Linux host、Docker Desktop、native、ARM64 image。
- daemon障害・書き込み経路の競合など、今回のSIGKILL/ENOSPC/lock試験を超える障害受入。
- 1.21.4以外の版・別環境でのcold/warmとメモリ・cache容量の追加測定。

fixed-v1の固定処理を実装しても、受入が完了するまでdeterministic=falseと--allow-experimentalを維持します。マイルストーンは完了にしません。
