# ローカル検証記録

実施日: 2026-10-10。GitHub Actionsは使用していません。

環境: macOS 15.7.7 / Apple Silicon、Colima mcicon / Virtualization.framework / Rosetta、Linux amd64 container、4 CPU / VM memory 6GiB。worker上限heap 2GiB / container memory 3GiB。coreはJava 21.0.9、gameはimage内Temurin Java 8または21/25です。

描画image ID: `sha256:d48946bb12109a4008c48ee103c03a15c5c0f96980f754bcc05e436a95307977`。Mesa 25.2.8 / llvmpipe LLVM 20.1.2、OpenGL 4.5。1.13系はCompatibility Profile、1.21.4/26.3はCore Profileを使用しました。26.3ではSDL_VIDEO_FORCE_EGL=1を適用しています。

| 版 | icons成功 / 登録item（air除外） | 失敗 | no_item_form | audit |
| --- | --- | --- | --- | --- |
| 1.13 | 784 / 784 | 0 | 46 | PNG hash・size・alpha・空画像・件数合格 |
| 1.13.1 | 789 / 789 | 0 | 46 | PNG hash・size・alpha・空画像・件数合格 |
| 1.13.2 | 789 / 789 | 0 | 46 | PNG hash・size・alpha・空画像・件数合格 |
| 1.21.4 | 1,384 / 1,384 | 0 | 76 | PNG hash・size・alpha・空画像・件数合格 |
| 26.3 | 1,657 / 1,657 | 0 | 78 | PNG hash・size・alpha・空画像・件数合格 |

全件は64px、preview-v1です。1.21.4/26.3/1.13.2では単品CLI経路も実行し、stone/diamond/chest/shield/glassを生成しました。stoneの向き・形状は目視でも確認しています。raw 1.21.4は1,683 PNGが成功、失敗0で、付随mcmetaも含め元JARのbytesと一致しました。

doctorでDocker image / Java25 / Mesa診断が成功。cache verifyでは記録済み541入力のSHA-256が一致しました（検査時点）。startup timeout=1秒のCLIはWORKER_TIMEOUT / exit 1を返し、専用コンテナが残らないことを確認しました。

合成fixtureのJUnitでcache破損・bounded retry・offline metadata照合、正式版の日付フィルタ、path/symlink、raw bytes/animation/alpha保存、破損PNG/JAR/mcmeta、unowned出力、再開修復、block alias/no_item_form、CLI排他・ID・sizeを検証しています。公開fixtureにゲーム資産を含めません。テスト結果はビルドのtest reportsに保存します。

CLI zipとsources/javadoc付きJavaライブラリをローカルに生成し、Minecraft JAR・元assets・PNGが混入しないことを確認しました。依存ライセンスをCLIとagentに含めます。正式release/Mavenへのpublishは行っていません。

## 再実行

Docker imageを利用ガイドの手順で作成し、Java21を用意して以下を実行します。ローカルにゲーム資産を取得するため、初回はネットワークとdisk容量が必要です。

```sh
sh scripts/local-qa.sh
python3 scripts/audit-export.py out/java/1.21.4/icons/manifest.json
python3 scripts/audit-export.py out/java/1.21.4/raw/manifest.json --source-jar cache/versions/1.21.4/client.jar
```

auditはhash、CRC、decoded RGBA8、サイズ、空画像、alpha、件数を確認します。画像の意味的な正しさやcanonical contextを保証するテストではありません。`--compare <別のmanifest>` で同じパスのdecoded RGBAを比較できます。単品・全件を別出力先で生成してから比較してください。

## 未完了の受入検証

- canonical-v1: tick、partialTick、乱数、暦日、glint、clock/compass、atlas animationの固定。現在は全版deterministic=false。
- 半透明blendのstraight alpha変換、sRGB、黒縁、missing texture/modelの完全検出。
- 実ゲーム基準画像との代表セット差分、single/all一致、固定環境の反復RGBA一致。
- 残り46版のadapter実装と各版の全件検証。verifiedへの昇格は未実施。
- Windows/Linux host、Docker Desktop、native経路、ARM64 imageの検証。
- disk full、途中cancel、worker crash、並列output競合を含む実プロセス障害テスト。
- 64px cold/warm全件の正確な時間・最大RSS・cache容量測定。現在の件数結果を性能保証として扱わない。

対応表とissueで未完了を追跡し、上の条件が揃うまでマイルストーンを完了にしません。
