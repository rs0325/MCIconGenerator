# ローカル検証記録

実施日: 2026-10-10。GitHub Actionsは使用していません。

環境: macOS 15.7.7 / Apple Silicon、Colima mcicon / Virtualization.framework / Rosetta、Linux amd64 container、VM 4 CPU / 6GiB。worker上限heap 2GiB / container memory 3GiB。core Java21.0.9、game Java8/17/21/25。Java16最低要件の1.17系はJava17 LTSで実行します。最大2 workerを並行実行しました。

描画image ID: `sha256:d48946bb12109a4008c48ee103c03a15c5c0f96980f754bcc05e436a95307977`。Mesa25.2.8 / llvmpipe LLVM20.1.2 / OpenGL4.5。26.3はSDL_VIDEO_FORCE_EGL=1です。

今回のfixed-v1・64pxの全件生成と監査は18/51版、合計19,331枚が成功しています。失敗版: なし。未完了: 26.3, 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11, 1.21.10, 1.21.9, 1.21.8, 1.21.7, 1.21.6, 1.21.5, 1.21.4, 1.21.3, 1.21.2, 1.19.4, 1.19.3, 1.19.2, 1.19.1, 1.19, 1.18.2, 1.18.1, 1.18, 1.17.1, 1.17, 1.16.5, 1.16.4, 1.16.3, 1.16.2, 1.16.1, 1.16, 1.15.2, 1.15.1。版別件数は[対応表](compatibility.md)、入力・agent・image hashと時間は[数値記録](../qa/validation-2026-10-10.json)をご覧ください。

監査はPNG hash/CRC、decoded RGBA8、size、alpha、alpha=0の隠れたRGB、空画像、重複、実レジストリ母集団と全件出力ID、no_item_formを確認します。意味的なmissing texture/modelの検出や色空間の正しさは、この監査だけでは保証しません。

独立したゲームプロセスでの代表ID再生成比較: 0版。成功した比較は数値記録にcomparedとcomparisonVerifiedで記録します。比較対象は各版の全件出力で、decoded RGBAと同じ出力パスを照合します。代表IDの比較は全IDの反復一致を意味しません。

JUnit 35件が成功。cache破損・offline・bounded retry、path/symlink、raw bytes/animation/alpha、出力所有権、再開、alias/no_item_form、CLI排他、exact profile、agent snapshot、ASM isolation、固定context、alpha復元を合成fixtureで検証します。iconsのクラッシュと途中cancelでは、予約済み未完了IDの再描画、成功PNGのhash再利用、unowned PNGの保護を確認しました。ゲーム資産をfixtureに含めません。

CLI zipとsources/javadoc付きJavaライブラリをローカル生成しました。配布ZIPにゲームJAR・assets・PNGは含まれません。依存ライセンスを同梱します。正式release/Mavenへの公開は未実施です。

以前のコミットでraw1.21.4の1,683 PNGと付随mcmetaが元JAR bytesと一致し、doctor、記録済み541入力のcache hash、startup timeout=1秒後のcontainer cleanupを確認しています。これらは今回の51版描画試験と別の実測です。

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
