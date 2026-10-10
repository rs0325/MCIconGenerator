# ローカル検証記録

実施日: 2026-10-10。GitHub Actionsは使用していません。

環境: macOS 15.7.7 / Apple Silicon、Colima mcicon / Virtualization.framework / Rosetta、Linux amd64 container、VM 4 CPU / 6GiB。worker上限heap 2GiB / container memory 3GiB。core Java21.0.9、game Java8/17/21/25。Java16最低要件の1.17系はJava17 LTSで実行します。最大2 workerを並行実行しました。

描画image ID: `sha256:d48946bb12109a4008c48ee103c03a15c5c0f96980f754bcc05e436a95307977`。Mesa25.2.8 / llvmpipe LLVM20.1.2 / OpenGL4.5。26.3はSDL_VIDEO_FORCE_EGL=1です。

今回のfixed-v1・64pxの全件生成と監査は36/51版、合計41,066枚が成功しています。失敗版: 26.2。未完了: 26.1.2, 26.1.1, 1.21.10, 1.21.9, 1.21.8, 1.19.4, 1.19.3, 1.19.2, 1.19.1, 1.19, 1.18.2, 1.18.1, 1.18, 1.17.1。版別件数は[対応表](compatibility.md)、入力・agent・image hashと時間は[数値記録](../qa/validation-2026-10-10.json)をご覧ください。

検証中にdeferred GUIのconstructorとGPU bufferのint/long境界を修正したため、agent hashを版ごとに記録します。全版を同一binaryで再試験した記録ではありません。

監査はPNG hash/CRC、decoded RGBA8、size、alpha、alpha=0の隠れたRGB、空画像、重複、実レジストリ母集団と全件出力ID、no_item_formを確認します。意味的なmissing texture/modelの検出や色空間の正しさは、この監査だけでは保証しません。

既知の再現性差分: 1.21.6のenchanted_bookがsingle/all比較でdecoded RGBA不一致。描画時刻の固定だけではdeferred glintの全状態固定を保証できず、原因とshader/cache時刻の確認を次の受入課題とします。

独立したゲームプロセスでの代表ID再生成比較: 5版を実行、4版で一致。成功した比較は数値記録にcomparedとcomparisonVerifiedで記録します。比較対象は各版の全件出力で、decoded RGBAと同じ出力パスを照合します。代表IDの比較は全IDの反復一致を意味しません。

実Java APIで3件生成後にcancelし、新tokenで再開した全784件のPNG監査と全件基準とのRGBA比較に成功しました。

JUnit 35件が成功。cache破損・offline・bounded retry、path/symlink、raw bytes/animation/alpha、出力所有権、再開、alias/no_item_form、CLI排他、exact profile、agent snapshot、ASM isolation、固定context、alpha復元を合成fixtureで検証します。iconsのクラッシュと途中cancelでは、予約済み未完了IDの再描画、成功PNGのhash再利用、unowned PNGの保護を確認しました。ゲーム資産をfixtureに含めません。

今回のdoctor 1.17はhealthy=True、cache verifyは記録済み2783入力を照合し、失敗0件でした。cacheの総bytes/filesは処理中の一時ファイルを含む時点値です。

CLI zipとsources/javadoc付きJavaライブラリをローカル生成しました。配布ZIPにゲームJAR・assets・PNGは含まれません。依存ライセンスを同梱します。正式release/Mavenへの公開は未実施です。

今回の元テクスチャ全件抽出は51/51版、合計72,638 PNGと付随mcmetaが元JAR bytesと一致しました。数値記録に版別件数を保存します。以前のコミットではdoctor、記録済み541入力のcache hash、startup timeout=1秒後のcontainer cleanupも確認しています。

## 再実行

```sh
sh scripts/local-qa.sh
python3 scripts/verify-matrix.py --versions 1.13 1.21.4 26.3 --out .local/repeat \
  --compare .local/qa-output --ids stone diamond clock compass chest shield glass \
  --cache .local/qa-cache --offline
python3 scripts/audit-export.py .local/qa-output/java/1.21.4/icons/manifest.json --require-all
```

local-qa.shは全51版を順番に生成します。verify-matrix.pyはCLI配布物を開始時にsnapshotし、別ビルドの影響を避けます。初回は公式資産の取得にネットワークとdisk容量が必要です。elapsedSecondsは準備・描画・終了・監査を含み、cold/warmや最大RSSを統制した性能測定ではありません。

## 未完了の受入検証

- 全IDの反復RGBA一致、実ゲーム基準画像、特殊モデル・着色・glint・時計/コンパスの意味的な比較。
- sRGB・半透明・alpha復元の色精度、missing texture/modelの完全検出。
- Windows/Linux host、Docker Desktop、native、ARM64 image。
- disk full・SIGKILL・実worker crash・競合を含む障害受入。
- cold/warm全件の時間・最大RSS・cache容量の統制した測定。

fixed-v1の固定処理を実装しても、受入が完了するまでdeterministic=falseと--allow-experimentalを維持します。マイルストーンは完了にしません。
