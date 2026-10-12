# ローカル検証記録

## 描画workerの成立条件と正常終了（2026-10-13、#2）

1.13.2の公開済みpreview workerはPNG生成・監査には成功しましたが、shutdown応答後の10秒待機を超え、DockerのSIGKILL・終了137で回収されました。親CLIのthread dumpはWorkerClient.closeのprocess.waitFor、workerは通常フレームの秒単位の追従処理を示しました。描画外のUtils時計をGLFW経過時刻からSystem.nanoTimeへ変更していたためです。元の時計の戻り値を維持し、描画中だけ固定値を選ぶよう修正しました。

修正workerで1.13・1.13.2・1.21.4・26.3、16/64/512pxの各6種類を生成し、12ジョブ・72PNGの監査、背景alpha=0、指定サイズ、着色ガラスの半透明、worker終了0を確認しました。OOM・SIGKILLイベントはありません。通常のglassはalpha 0/255の素材なので、半透明の受入にはwhite_stained_glassを使います。1.13.2の16px・5種類は修正前のPNGとRGBAが完全一致しました。

1.13・1.21.4・26.3では25種類を連続生成し、16リクエスト後の通常フレーム復帰を含む3ジョブも終了0でした。75PNGは旧workerの全件基準とRGBAが完全一致しました。JUnit64件が成功し、独自の時計供給元の値を描画外で維持し、描画中だけ固定する実bytecode変換も検証しました。初回FAIL、版別入力hash・renderer・contextと再検証結果は[数値記録](../qa/validation-2026-10-13-worker-lifecycle.json)へ保存しています。

ホスト成立範囲は既存#13のmacOS Apple Silicon・Linux x86_64・Windows AMD64のJava＋Linux amd64 Mesa Docker検証を参照します。今回の新workerをWindows/Linuxホストで再実行した結果ではありません。ホストnative/GPU描画とLinux ARM64 imageは未対応です。画質全体とcanonical昇格は別の受入条件であり、全版experimental・deterministic=false・verified=0を維持します。agent hashが変わるため、以前のiconsは新しい出力先へ再生成してください。

専用の準備済みcacheを使い、同じcacheを使う他のworkerを動かさずに実行します。出力先は新規ディレクトリです。ゲーム資産・生成PNG・thread dump・Docker生ログは公開しません。

```sh
python3 scripts/verify-worker-lifecycle.py --cache .local/cache \
  --out .local/worker-lifecycle --docker-image mcicon-render:0.1.0-preview.1
```

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

目視比較では1.13〜1.14.3のチェストが不自然な多色表示になっていることを確認しました。原因はワールド入場時に設定されるBlockEntity dispatcherのTextureManagerがGUI専用起動ではnullになり、vanillaのbindTextureが何もしなかったことでした。83d2382でMinecraftのTextureManagerを供給し、旧7版・5,866件の全件監査とチェストの正常なテクスチャ表示を確認しました。1.13/1.14.3の特殊モデル・時計等18代表IDも修正後の全件結果とRGBA一致しました。修正後workerはSHA-256が変わります。この追加検証時点の51版反復・性能は以前のworkerの記録です。次節に新workerの全51版再試験を記録します。全件生成・反復一致だけで画質を受理しません。

startup timeout=1秒と不正agentの実CLI試験では、専用agent snapshotと一時PNGが回収されました。対応コミットは64ff362です。core SIGKILLの即時cache snapshot回収は保証しません。

## fallback診断・新workerの全件比較（同日）

CLIの診断は8ccdadb、描画中のresource観測は63ce8d9 / 5c6719a / 521302c、26.x検証fixtureのatlas指定修正は1d49f77です。[数値記録](../qa/validation-2026-10-10-resource-guard.json)に、各ジョブのcore/agent/source/image hashと以前の記録への参照を保存します。

チェスト修正後のworkerで全51版・60,265枚を再生成・PNG監査しました。さらにresource観測を加えたworkerで全51版を独立生成し、全60,265枚のdecoded RGBA・パスがこの基準と一致しました。比較する二つのaggregate agent hashは異なります。これは追加した観測処理が出力画像を変えないことの検証で、全IDを別々のsingleプロセスで描画した試験ではありません。

requested inventory passで選択されたfallback BakedModel / MissingItemModelをMISSING_MODEL、消費されたquad/materialのminecraft:missingno spriteをMISSING_TEXTUREとして診断します。全51版の実bytecodeにobserverの依存を照合しました。1.15〜1.16.xの9版の公式mappingsにはgetSpriteが載りますが、release JARでは未使用getterが削除されていたため、保持されたspriteフィールドから観測する方式へ修正しました。元のstack mapがreturn地点のreceiverを破棄するケース、同じ難読化名で返り値が異なるgetter、mapped getterが実JARにないケースも回帰テストに含めます。

9 adapterの18 native fault試験では、stoneを期待したコードで失敗させ、同じworkerで続くwheatの正常PNGを生成し、全件基準とRGBA一致しました。失敗したstoneのPNGは出力しません。モデルfixtureは解決済みモデルをバニラ自身のfallbackへ置換します。texture fixtureは消費spriteの観測入力を、バニラ自身のmissing atlas spriteへ置換します。公式ゲームJAR/assetsは変更せず、検証用agentはproduction配布物に含めません。resource packの破損ロードや特殊rendererの全テクスチャ取得を受け入れたという意味ではありません。26.x fixtureのgetAtlasOrThrowはtextureパスでなくatlas定義IDを受け取るため、AtlasIds.BLOCKSを使用します。fixtureの修正前後のhashを区別して保存します。

JUnit54件成功。実CLIでcacheを通常ファイルにしてIO_FAILEDを起こした場合、通常/--debugともexit 1・stdoutは空でした。--debugだけが根本のFileSystemExceptionとstack traceをstderrへ出します。CLI引数の前後どちらでdebugを指定しても、通常のエラーコード・終了コードを保持します。

同じ1.13 cacheを2つの独立JVMから4回ずつoffline準備し、確定したmapping/native bytesのhashが基準と一致しました。検査では他プロセスが書き込み中のツール専用partを確定ファイルに数えません。以前の一過性IO_FAILEDは今回も再現せず、原因は未特定です。今後は任意の--debugで準備例外の原因を追跡できます。

```sh
python3 scripts/verify-resource-faults.py --out .local/resource-fault-repeat \
  --compare .local/qa-output
python3 scripts/verify-concurrent-prepare.py --out .local/prepare-repeat
python3 scripts/verify-resource-capabilities.py --out .local/capability-repeat
```

特殊rendererの直接texture取得やGPU cacheを含むmissing resourceの完全監査、実ゲーム基準画像・色空間・全IDの個別/all・他hostの受入は継続します。以前のbenchmarkやSIGKILLは当時のbinaryの実測を保持し、新workerの性能・障害保証へ流用しません。

## 通常GUIフレームとの比較（同日）

検証用wrapperの実装は4b240e0です。[数値記録](../qa/validation-2026-10-10-gui-reference.json)に、入力・production/QA agent・Docker image・PNGのhash、比較誤差と条件を保存します。production workerは521302cのままで、今回の追加はQAツールと記録です。

1.13 / 1.21.4 / 26.3の各20代表アイテムを、灰色 `#7f7f7f` と青緑 `#206080` の背景で比較しました。チェスト・ベッド・旗・盾・頭・shulker、ガラス・氷、草・葉・ポーション・スポーンエッグ・皮防具、時計・コンパス・enchanted_bookを含みます。通常GUI座標 `(32,32)` の120比較に加え、原点 `(0,0)` の10代表IDを3版で比較し、合計150比較が許容誤差内でした。RGB各channelの最大誤差は1.13で1/255、1.21.4で2/255、26.3で1/255。許容値2/255を超えたpixelは0です。

比較用画面はQA wrapperでTitleScreenの描画部分を置換し、ゲーム自身の通常GameRenderer、1280×960のmain target、GUI scale 4、native Screenshotを使います。16 GUI pixelのアイテム部分を64pxで切り出し、export PNGを背景へbyte RGBで再合成した結果と照合します。referenceの描画にはWorkerRenderer.render、専用export target・projection、PngOutputのalpha復元を使いません。full-frameとcropの画像はローカルのみに保持します。

最初の検証画面では起動ロゴのoverlay fadeを混ぜていたため、overlay終了を待つよう修正しました。1.13ではInventoryMenuScreenのslotと同じdepth testと、GameRenderer.renderWorldからGUIが引き継ぐcullingを設定します。これを欠くTitleScreen fixtureでは裏面やglintが不正に見えるため、その失敗を製品の描画不良として扱いません。修正した条件は数値記録に明記します。

さらに、ゲームクラス・GPU・固定化hookを使わず、元PNGとgenerated-modelの宣言から平面画像を計算する独立検査を追加しました。3版のdiamond、1.21.4と26.3のcreeper_spawn_eggの計5ケースでRGB・alphaが完全一致しました。1.21.4はモデルJSONの2つの定数tintを使ったlayer合成です。16pxの静的テクスチャ、alpha 0/255、既定GUI変換という限定した検査で、汎用モデルrendererではありません。実装ad7ff9e、使用したJSON/PNGのhashを数値記録に含めます。

fixed-v1の時間・乱数・animation hook、registry/componentのbootstrapはproductionと共有しています。world/playerなしのGUI fixtureであり、無改変のワールド内inventory screenshotとの照合や共有hookの独立検証は未完了です。2背景・64pxの一致を、すべての色空間・サイズ・版・状態の画質保証へ広げません。experimental/deterministic=falseを維持します。

```sh
python3 scripts/verify-gui-reference.py --out .local/gui-reference-repeat \
  --compare .local/qa-output
python3 scripts/verify-gui-reference.py --out .local/gui-reference-origin-repeat \
  --compare .local/qa-output --position 0 0 --backgrounds 7f7f7f \
  --ids chest shield glass ice grass_block potion leather_chestplate clock compass enchanted_book
python3 scripts/verify-flat-textures.py --out .local/flat-texture-repeat \
  --compare .local/qa-output
```

## 実際の破損リソースパックによる検証（2026-10-11）

Issue #6の継続作業です。起点はPR #15の593c745、担当ブランチはfeature/issue-6です。描画・欠損観測を置き換えず、QA専用パックに破損したモデルJSONまたはPNGを配置して、ゲーム自身の読み込みとfallback処理を通します。パック形式は元JARのversion.jsonまたはpack.mcmetaから取得します。取得Resourceの提供元がQAパックであること、所有runのパックSHA、公式入力JARが変化しないことを確認します。ゲーム素材・QA agent・破損パックは公開配布物へ含めません。

初期workerでは1.13 / 1.21.4 / 26.3の破損チェストを代替画像のまま成功出力していました。通常quadの観測に加え、TextureAtlasのsprite取得、26.xのAtlasManagerのcached sprite取得、旧版Textureの直接bindを観測します。旧版のmissing textureは保持フィールドで比較し、生成getterを呼んで初回bindへ再入しないようにします。失敗は描画処理終了後に通知し、同じworkerの正常アイテムの継続を検証します。

モデルJSONが破損した場合も版によりMISSING_MODELまたはMISSING_TEXTUREとなります。バニラがmissing spriteを持つ代替形状へ解決した場合は後者です。この検証はfallback PNGを成功として返さないことを確認し、元ファイルの破損種別を常に識別する保証にはしません。

```sh
python3 scripts/verify-resource-faults.py --injection resource-pack \
  --faults model texture special-texture --out .local/resource-pack-check \
  --compare .local/qa-output
```

最終workerの27破損ケースで、全て期待した欠損診断とPNG未出力、同じworkerで続くwheatの基準RGBA一致を確認しました。既存の観測値置換fixtureも3版・6件で回帰確認し、missing spriteを描画passの前に取得することでQA自身がatlas観測へ再入しないようにしました。正常全51版・60,265枚は基準のRGBAと出力パスが一致し、PNG監査も成功しました。入力・worker・QA helperのhashと版別結果は[数値記録](../qa/validation-2026-10-11-resource-pack.json)に保存しています。JUnit56件、実bytecodeの依存照合51/51版は成功しています。重点反証で指摘された旧版getterの遅延生成による再帰と並列QAログの混入は修正しました。版によって起動ログが省略されたりSTDERRにprefixが付くため、パック選択の証明は取得Resourceの提供元で行います。modernの直接非atlasテクスチャ、全てのキャッシュ済み特殊描画、画像の意味・色空間の受入は引き続き未完了です。

## 直接テクスチャの欠損とキャッシュ取得（2026-10-11）

Issue #6 / PR #16の継続で、起点はb317c60です。1.21.4・1.21.5・26.3で、ドラゴンの頭が使用するtextures/entity/enderdragon/dragon.pngを破損させると、以前のworkerは代替画像を成功PNGとして返しました。modernではTextureContents.createMissingの内容を元のtextureへ適用するため、atlas sprite名や共有missing textureとの比較だけでは識別できません。

生成された代替内容と適用先textureを弱参照で追跡し、requested passのTextureManager取得・直接bind・GPU getterでMISSING_TEXTUREを記録します。状態はpass間で保持し、render executorでの正常な内容の適用で解除します。取得後のバニラ処理と戻り値は維持し、対象PNGを出力せず、同じworkerのwheatは継続します。描画前に破損textureをロードするQAでは、ロード時には失敗判定せず、キャッシュからの取得を対象passで検出します。

```sh
python3 scripts/verify-resource-faults.py --injection resource-pack \
  --faults direct-texture --versions 1.21.4 1.21.5 26.3 \
  --out .local/direct-texture-check --compare .local/qa-output
python3 scripts/verify-resource-faults.py --injection resource-pack \
  --preload-direct-texture --faults direct-texture \
  --versions 1.21.4 1.21.5 26.3 --out .local/direct-texture-cached-check \
  --compare .local/qa-output
```

JUnit59件と実bytecodeの依存照合51/51版は成功しました。1.21.5はGPU view getterをまだ持たないため、実際に存在するgetterだけを照合します。直接PNGの破損9境界版、事前ロード3版、既存の欠損診断12件、観測値置換6件は成功しました。全30件で対象PNGの未出力と同じworkerのwheat成功・基準RGBA一致を確認しています。正常全51版・60,265枚は基準のRGBAと出力パスが一致し、PNG監査も成功しました。入力・worker・QA helperのhashと版別結果は[数値記録](../qa/validation-2026-10-11-direct-texture.json)に保存しています。

1.14.4のdragon_headは破損パックあり・なしの個別描画でともにEmpty inventory iconとなり、同じworkerのwheatは基準と一致しました。以前の65388d58 workerでも正常個別描画の失敗を再現したため、今回の観測追加による回帰ではなく既存問題と確認しました。欠損診断成功には数えません。この時点の全件基準には成功PNGがありました。個別と全件の差は下記の1.14.4初期化修正で調査・解消しています。非render threadからapplyだけを呼んでGPU適用を後送する外部経路との並行性、全てのGPU cache・特殊リソース、無改変ワールド内の画像・色空間の受入は未完了です。全版experimental/deterministic=falseを維持します。

## 1.14.4の特殊描画の初期化（2026-10-11）

Issue #6 / PR #16の継続で、起点は1a5f394です。以前のworkerでは正常なdragon_headの個別描画がEmpty inventory iconとなり、全件では直前のテクスチャが特殊アイテムへ貼られていました。1.14.4実JARのBlockEntityRenderer.bindTextureはdispatcher.textureManagerがnullなら切り替えを省略します。バニラのワールド開始時に渡す同じTextureManagerをFixedRendererの初期化で供給します。bindTextureを持つgui-fixed版だけに適用し、現行対象は1.14.4のみです。

最終workerで1.14.4・1.15.2・1.16.5の全2,734枚を生成・PNG監査しました。1.14.4の59種類の特殊アイテムは誤テクスチャから修正され、残り817種類と全パスは以前の基準と一致します。1.15.2の883枚・1.16.5の975枚も以前のRGBA・パスと一致しました。影響59種類とwheatを個別指定した60件、およびdragon_headだけを最初に描画した単独起動は、新しい全件出力とRGBA・パスが一致しました。

通常GUI fixtureを1.14.4へ拡張し、影響59種類とwheatの2背景・120件で、透明PNGを再合成したRGBと画面からのcropとの差が全て0でした。通常のnative GUI projection・画面target・Screenshotを使い、WorkerRenderer.renderやPngOutputは使いません。ただし同じFixedRenderer constructorとfixed-v1 hookを共有するため、bootstrap自体の独立証明や無改変ワールド内inventoryの最終受入には数えません。実bytecodeでの原因確認、正常個別・全件の一致、画像の確認を合わせた限定検証です。

```sh
python3 scripts/verify-matrix.py --offline --versions 1.14.4 \
  --ids dragon_head --out .local/classic-single --compare .local/qa-output
python3 scripts/verify-gui-reference.py --versions 1.14.4 \
  --ids dragon_head chest white_bed white_banner shield player_head white_shulker_box \
  --out .local/classic-gui --compare .local/qa-output
```

破損ドラゴンPNG・チェストPNG、および描画前にロード済みの破損ドラゴンPNGの3ケースはMISSING_TEXTURE、対象PNG未出力、同じworkerのwheat成功・基準RGBA一致を確認しました。事前ロードfixtureは古いgetTextureがキャッシュ参照だけだったためbindによるロードへ修正し、1.21.4でも事前ロードの回帰を確認しました。最初のfixture失敗は成功に数えず記録します。公式入力は変更していません。JUnit59件とCLIビルドは成功し、重点反証では新しい確定問題は見つかりませんでした。対象ID・入力・worker・QA helper・画像hashは[数値記録](../qa/validation-2026-10-11-classic-bootstrap.json)に保存しています。最新workerでの全51版再実行は行っておらず、以前の51版記録と今回の3版検証を区別します。全版experimental/deterministic=falseを維持します。

## 1.21.4の色・alpha復元とsRGB指定（2026-10-11）

Issue #6 / PR #16の継続で、起点は2e280f5です。PNGの画素はRGBA8でしたが色空間の指定がなかったため、共通保存処理でsRGB chunk（Perceptual）を付けます。RGB・alphaの計算やraw抽出は変えません。PNG bytes・SHA-256は変わるため、以前のiconsを使う場合は新しい出力先で再生成してください。既存manifestは新しいagent/settings digestと区別されます。

1.21.4のガラス・氷・スライム・ハチミツ・着色・glint等15種類を黒、白、灰、青緑、赤系、緑系の6背景で通常GUIと比較します。さらに16pxの既知RGBAを持つQAパックで、1層と2層のgenerated-modelを通常のitem shaderへ渡します。実shaderのalpha<0.1 discardを含むsource-overを、ゲームクラス・GPU・PngOutputを使わない数値oracleで計算します。透明画素の隠れたRGB、上下方向、低alphaでのstraight RGB量子化、重ね順を検証します。許容範囲は今回の固定パレット・2層までのfixtureに限ります。

```sh
python3 scripts/verify-alpha-textures.py --out .local/alpha-calibration
python3 scripts/verify-gui-reference.py --versions 1.21.4 --framebuffer-probe \
  --ids glass ice slime_block honey_block enchanted_book \
  --backgrounds 000000 ffffff 7f7f7f 206080 c02040 20c040 \
  --out .local/alpha-vanilla --compare .local/qa-output
```

最終workerの1.21.4全1,384枚と9描画境界版の代表54枚は、以前の正しい基準とdecoded RGBA・パスが一致しました。全1,438枚のIHDR 8bit RGBA、sRGB Perceptual、PNG integrityを確認しました。全51版の全件再実行とは区別します。1.21.4の全件比較元は1a5f394時点のworker、1.14.4の比較元は初期化修正後の2e280f5です。

バニラ15種類×6背景の90比較、既知RGBAの2種類＋正常wheat×6背景の18比較は、全て最大RGB誤差2/255以内でした。独立数値oracleの8,192pixelでは、1層のalpha誤差0・最大straight RGB誤差5、2層のalpha誤差0.875未満・最大straight RGB誤差2.021未満です。alphaが小さいと8bit readbackの量子化を除算で拡大するため、straight RGBだけの固定誤差上限では判定しません。alpha誤差は1段階以内、premultiplied RGB誤差は最大1.245未満で、各pixelが今回の量子化上限内でした。shaderでdiscardされる透明画素のRGBも0でした。RGBA各8bit・GL_LINEARのframebufferで、sRGB変換は無効、観測GL errorは全て0でした。

共用QAパック配置を使う欠損モデル・直接テクスチャの2ケースも成功し、対象PNGの未出力と正常wheatの基準一致を確認しました。JUnit60件とCLIビルドは成功しました。重点反証で指摘された提供元のfile/ prefix判定と、GUI失敗時に全成功を残し得るQA記録は修正しました。入力・shader・worker・QA helper・画像hashと修正前の対照は[数値記録](../qa/validation-2026-10-11-alpha-srgb.json)に保存します。

framebuffer観測は通常GUIの描画後に行い、GL bindingや描画設定を変えません。glGetErrorはerror flagを読むため、観測時に0であることも受入条件にします。通常GUI fixtureはfixed-v1 hookとbootstrapを共有し、無改変ワールド内inventoryの独立基準ではありません。sRGB指定と通常GUIとのbyte RGB一致を、物理的に正しいlinear-light合成や全モデル・GPU・driverの色保証として扱いません。全版experimental/deterministic=falseとIssueの未完了条件を維持します。

## 1.21.4のワールド内インベントリと照明差（2026-10-11）

43eff9bのPNGを、通常のローカルflatワールドのInventoryScreenと比較しました。QA用agentはMinecraft.runTick末尾に画面操作・撮影callbackだけを追加し、ワールド作成画面からseed=0のflatを作成します。通常の既定stackをinventory slot 9へ入れ、空スロットと各アイテムをnative Screenshotで撮影します。GUI scale=4、1280×960画面から64pxのスロット領域を比較します。

standalone QA JARにはJSON・ASM・Symbolsによるreflectionと画面操作helperだけを含め、RenderAgent、描画renderer、CanonicalTransformer、CanonicalContext、ResourceTransformer、Bridge、PngOutputを除きます。InventoryScreen・GameRenderer・item shaderの描画メソッドを変更せず、productionのbootstrap・時刻固定・透過復元を呼びません。完全な無計装clientとは区別します。cacheは読み取り専用mount、専用game/outputだけを書き込み、ネットワークは無効です。

```sh
python3 scripts/verify-world-inventory.py --out .local/world-inventory \
  --compare .local/qa-output
```

スクリプトの既定は18種類です。最終検証では、色違いのbed/banner/shulker、各種head、trapped_chest、blue_stained_glassを加えた静的28種類を比較しました。生RGBでは17種類が許容誤差2/255以内、11種類が不一致で、最大誤差は4/255でした。全撮影のfull-bright lightmap texelはRGB 252/252/252でした。バニラのLightTexture constructorは白255で初期化し、ワールド入場後のlightmap shaderは最後にcolorを0.75へ0.04混合します。fixed-v1のworldなし描画と実ワールドの照明差を確認しました。

別の診断として、生成PNGのRGBへ各撮影で読み取ったlightmap値を乗算してから空スロット背景へbyte RGB合成すると、28種類すべてが最大誤差2/255以内で一致しました。diamondは誤差0、iceは最大2、その他は最大1でした。生RGBのpassed=falseとlightmapAdjustedPassed=trueを分けて記録します。スクリプトは生比較の不一致に対してexit 1を返し、補正比較だけを全体成功に数えません。確認前の18種類の対照も成功扱いへ書き換えません。

この時点の補正は照明差の原因を調べるもので、productionのPNGは変更していません。後続作業ではユーザー確認により実インベントリの照明を固定基準に選びました。glint、時計・コンパス、アニメーション、全ID・全版、別背景・GPUの受入は継続します。入力・QA helper・通常画面・shaderのhashと各比較結果は[変更前の数値記録](../qa/validation-2026-10-11-world-inventory.json)に保存しています。重点反証では新しい確定問題は見つからず、timeout・中断の停止処理はコード確認のみで障害注入は未実施です。ゲーム画像、worldデータ、QA agentは公開配布物に含めません。

## 実インベントリの照明への適合（2026-10-11）

ユーザー指定により、1.21.4の描画前lightmapをRGB252へ初期化しました。通常ワールドの静的28種類は、保存PNGのRGBを補正せず背景へ合成した比較で全件成功し、最大誤差は1/255でした。実ワールドの観測lightmapも全撮影でRGB252です。ワールド・プレイヤー・時刻・天候はproductionへ導入しません。

新workerの1.21.4全1,384枚をPNG監査し、8bit RGBA・sRGB Perceptualを確認しました。変更前と出力パスは同一で、1,382枚のRGBAが変わります。透明領域のmaskは不変ですが、8bit量子化により930pixelのalphaが最大1/255変わります。以前のiconsは新しい出力先で再生成してください。

照明を変更しない9境界版（1.13、1.14.3、1.14.4、1.19.4、1.21.3、1.21.5、1.21.6、26.2、26.3）の代表6種類・計54枚は既存基準とRGBA・パス一致しました。既知RGBAの1層・2層8,192pixelは量子化上限内、6背景の通常GUI18比較と着色・透過・glintの2背景24比較は誤差2/255以内です。独立flat oracleの5ケースは誤差0ですが、1.13・26.3の入力は以前の基準出力です。欠損モデル・直接テクスチャの2ケースで対象PNG未出力と正常wheatの継続・基準一致を確認しました。JUnit60件は成功しました。

数値・入力hash・QA helper hashは[照明変更後の記録](../qa/validation-2026-10-11-inventory-lighting.json)に保存します。通常GUI fixtureはfixed-v1とbootstrapを共有し、通常ワールド28種類の独立比較と区別します。全51版の全件再実行、他版の実ワールド照明、全ID・動的表現・他GPUの受入は未完了で、全版experimental/deterministic=falseを維持します。

## 1.21.4の固定条件と診断理由の検証（2026-10-11）

起点はmainのb7c8c69です。workerの具体的な例外messageをcoreが汎用文へ置き換えていたため、McIconExceptionの理由をAPI・manifestへ保持します。空モデルは「IOException: Empty inventory icon」、drawItem入口での例外は「IllegalStateException: MCIcon QA: inventory draw failure」としてRENDER_FAILEDに記録され、同じworkerの次のwheatは正常な基準RGBAと一致しました。JUnit61件が成功しました。描画workerのhashは照明変更後の8a33d62aのままで、正常PNGの計算は変更していません。

通常ワールドの追加38種類は36種類が厳密比較に成功し、oak_fenceの輪郭2画素と時刻に依存するend_crystalが不一致でした。通常InventoryScreenのglint時計1箇所を1000msへ指定すると、glint3種類とdiamond対照の4比較は最大誤差1/255以内です。別のQAパックで49枚の元PNGを変更せず、animation metadataのframesを初期frame1件、interpolateをfalseへ指定した16比較は誤差0でした。最終的に両条件を併用した56種類は55種類が成功し、oak_fenceの2画素は厳密比較でFAILのままです。輪郭の許容変更は導入していません。productionの固定化・bootstrapコードは共有しませんが、動的状態を指定した参照であり、無計装ワールドの動的表示との一致とは区別します。撮影後にscopeの説明文を補足し、実行時のhelper hashはそのまま記録します。

world/playerのない既定stackで時計・コンパス類の針計算が0を返すことを実bytecodeで確認し、clock_00・compass_16・recovery_compass_16の元PNGを独立oracleで照合しました。6サイズ×9種類の54PNGは監査に成功しました。diamond・creeper_spawn_egg・時計・コンパス・リカバリーコンパスの16〜256px計25ケースは元PNGの整数拡大とRGBA一致ですが、512pxの5ケースは不一致でした。512pxではテクセル境界の移動が観測され、atlas UV・生成モデルを含む実GUI拡大との照合は未実施です。原因は未確定で、512pxの画質受入を成功とはしません。残る立体4種類はPNG監査のみです。

モデル・atlas・特殊チェスト・直接ドラゴンPNGの欠損4ケースは修正前coreと同じworkerで成功しました。具体理由の保存は修正後coreで空モデル・描画例外の2ケースを別途確認しています。描画例外はtarget/projection設定後のdrawItem入口へ注入したもので、native bufferを部分使用した後の復旧は未検証です。入力・helper・worker hashと失敗を含む比較値は[数値記録](../qa/validation-2026-10-11-canonical-inventory.json)に保存します。Issue #6は継続中で、全版experimental/deterministic=falseを維持します。

```sh
python3 scripts/verify-world-inventory.py --frame-zero --fixed-glint \
  --ids oak_fence end_crystal prismarine clock compass \
  --out .local/canonical-world --compare .local/inventory-lighting-normal
python3 scripts/verify-resource-faults.py --versions 1.21.4 \
  --injection resource-pack --faults empty-model \
  --out .local/empty-model --compare .local/inventory-lighting-normal
python3 scripts/verify-resource-faults.py --versions 1.21.4 \
  --injection observer --faults render-error \
  --out .local/render-error --compare .local/inventory-lighting-normal
```

## 確認済みの輪郭差の許容（2026-10-11）

ユーザーがoak_fenceの64px原寸・拡大比較を確認し、輪郭2画素の差を許容しました。単一ScreenshotのRGBだけでは、背景と同じ色の不透明pixelや半透明を透明背景と区別できないため、一般的な輪郭判定へは広げません。許容は同じID・出力PNG hash・参照RGBA hash・背景RGBA hash・2座標が一致する確認済み画像だけです。他ID、変更された出力・参照・背景・差分座標、内部穴の隣の背景色pixelや半透明の反例を拒否する10テストが成功しました。

初期animation frameとglint時刻を指定した通常InventoryScreen56種類を再撮影し、その保存画像を最終の確認済み画像条件で再比較しました。comparisonPassedは56/56です。厳密なrawPassedは55/56のままで、oak_fenceの(40,46)・(42,47)だけが許容されます。元誤差の最大104/255と2画素の記録を残します。productionの描画・PNG・worker hashは変更していません。runと比較helperのhashは[輪郭判定の数値記録](../qa/validation-2026-10-11-inventory-edge.json)に保存します。

512pxの整数拡大oracleとの不一致5ケースへこの許容を適用したわけではなく、その受入は未完了です。以前の厳密FAILを含む記録は撮影当時の結果として維持します。Issue #6は継続中、全版experimental/deterministic=falseを維持します。

## 512pxの平面テクスチャ比較の補正（2026-10-11）

512pxの5種類が単純整数拡大oracleに不一致だった原因は、比較計算にバニラのUV縮小が含まれていなかったことです。1.21.4の公式FaceBakeryはUVを中心8へ縮め、TextureAtlasSpriteの縮小率は静的16pxテクスチャでは4/max(atlas幅,高さ)です。実workerのblock atlasは1024×512で、縮小率は1/256です。低い出力解像度では同じテクセルが選ばれ、512pxでは境界付近の選択が変わります。

FlatTextureProbeに1.21.4限定の--atlas-logを追加し、実ログのatlas寸法を入力として元PNG・定数tint・針モデルからRGBAを計算します。ゲームクラス・GPU・production描画は計算に使わず、公式class bytesとログのhashを記録します。ログと既存PNGの対応は実行順、版/profile、最後のPNGからログへのmtime差で確認しており、job識別子による直接照合ではありません。

diamond・creeper_spawn_egg・時計・コンパス・リカバリーコンパスの6サイズ計30ケースはRGB・alpha誤差0でした。誤ったatlas寸法512×512を与える負対照では、512pxの5ケースとも失敗します。wrapperのオプションあり/なしの各8ケースも成功しました。他版の対照は既存PNGの再比較であり、新規native描画ではありません。PNG・worker・許容誤差は変更していません。以前の512px FAILは比較方式の不足として履歴に残します。

```sh
# 比較PNGを生成した1.21.4 workerのworker.logを指定する。
python3 scripts/verify-flat-textures.py --out .local/flat-uv \
  --compare .local/qa-output --atlas-log /path/to/worker.log
```

対象は静的16pxのgenerated texture5種類です。立体4種類の指定サイズはPNG監査のみで、全model・全版・他GPUの画質受入とは区別します。入力、公式bytecode、実ログとconfig、負対照、以前のFAILを含む値は[UV比較の数値記録](../qa/validation-2026-10-11-inventory-uv.json)に保存します。全版experimental/deterministic=falseを維持します。

## Issue #6の1.21.4実装・検証の完了（2026-10-11）

1.21.4の描画backendについて、Issue #6の完成条件を実装・検証し、重点反証で未修正の確定問題は見つかりませんでした。render threadとworker再利用、指定6サイズとGUI transform、RGBA8/sRGB/straight alpha・半透明・反転・透明背景、着色・針モデル・特殊モデル・初期animation frame・固定glint、欠損・空画像・描画例外の診断を確認しています。61件のJUnit、単一workerの全1,384PNG監査、既知RGBA8,192pixel、GUI42比較、管理条件の通常InventoryScreen56比較、平面UV30ケース、欠損4経路と空モデル/描画例外2ケースが根拠です。輪郭の許容はユーザー確認済みの同一の柵画像に限定します。

これは1.21.4のcanonicalな既定stackと明記した検証環境・基準の完了であり、全版・全model・他GPUの画質や完全に無計装のclient表示、linear-light合成まで保証しません。全IDの個別/all一致はIssue #8、全版・対応OS・より広い画質と障害受入はIssue #13で継続します。PR #17はmainへ統合するためのReady PRで、実装・検証の完了とmerge・Issue closeを区別します。全版experimental/deterministic=falseを維持し、マイルストーンは未完了です。

## Issue #8の全ID個別/all比較の完了（2026-10-11）

PR #17をmergeしたmainの1c31978を起点とします。`verify-single-all.py`は独立した全件CLIジョブの登録アイテム全体を基準に、各IDを1件だけ指定したCLIジョブを起動します。個別ジョブごとに新しい出力先とゲームプロセスを使い、manifestの入力・設定・環境・catalogを照合した上でPNGのhash、decoded RGBA、出力パスを比較します。CLI配布物は開始時にsnapshotへ複製します。単一実行の全IDが成功した場合だけ`allIdsVerified=true`を記録し、`--ids`による部分集合の成功は全ID受入に数えません。

1.21.4・64pxの全1,384PNG監査と、1,384IDの独立個別CLIジョブを実行しました。初回は1,383IDが厳密一致し、netherite_ingotだけが描画前の起動応答180秒でWORKER_TIMEOUTとなりました。初回のexit 1・failed=1・allIdsVerified=falseを保存し、失敗を成功へ置き換えません。単独再実行では成功し、そのPNG・入力・設定・環境・catalogを初回の全件基準へ再照合しました。再実行側の全1,384PNGも初回基準と一致しました。両実行の成功IDの和集合が登録全1,384IDと一致することを確認し、数値記録の`acceptance.allIdsComparedSuccessfullyAcrossRuns=true`に保存します。画素差の許容は0で、柵の輪郭許容も使いません。

初回の全件検証は2並列で25,665.659秒（約7時間8分）、再実行は1並列で49.397秒でした。起動タイムアウトの根本原因は未特定で、障害受入はIssue #13に残します。[全IDと再実行の数値記録](../qa/validation-2026-10-11-single-all.json)に各IDのhash・パス・初回の失敗・再照合結果を保存し、[代表8IDの開始時記録](../qa/validation-2026-10-11-single-all-pilot.json)も維持します。誤色、同一RGBAでのパス違い、入力/設定/環境/catalogの違い、複数entry、指定ID違い、PNGの差し替えを拒否する7件のQAテストとJUnit61件が成功しました。

```sh
python3 scripts/test-single-all.py
python3 scripts/verify-single-all.py --version 1.21.4 --size 64 \
  --cache .local/cache --out .local/single-all --workers 2
# 調査用の再実行。部分集合の成功だけで全ID成功とは扱わない。
python3 scripts/verify-single-all.py --version 1.21.4 --size 64 \
  --cache .local/cache --out .local/single-retry --workers 1 \
  --ids minecraft:netherite_ingot
```

事前にCLIをbuildし、同じworktreeのcacheへ公式入力を準備してください。検証はofflineで実行し、途中結果は指定出力先の`report.json`へ原子的に保存します。画像・ゲーム入力・workerログは公開リポジトリへ含めません。2並列はこの検証に使った上限で、他環境の速度・メモリ保証ではありません。今回の受入は1.21.4・64px・ローカルDocker/Linux amd64/Mesaに限定し、全版・全サイズ・他OS/GPUの品質受入と区別します。experimental/deterministic=falseを維持します。実装・検証の完了と、PR公開・merge・Issue closeは別の段階です。

## Issue #10の旧38版adapterの受入（2026-10-12）

PR #18をmergeしたmainのa51bf0aを起点に、1.13〜1.21.3の正式38版を検証しました。現workerで全41,056PNGを生成し、全版の登録アイテム数・出力・RGBA8/sRGB・透過を監査しました。代表26種類を別のCLI・ゲームプロセスで版ごとに再生成し、988PNGすべてが全件基準とdecoded RGBA・PNG hash・パスの厳密一致です。入力・設定・固定context・環境・renderer・catalogも一致しました。これは各IDごとにゲームを起動する全ID個別比較とは区別します。

通常GUI参照をlegacy、fixed、classic、GuiGraphicsの実API差へ対応させました。GUI原点(0,0)、guiScale4、1280×960画面、64px、灰色・青色の2背景で、38版×26種類×2背景の1,976比較が成功しました。PNGを背景へbyte RGB合成し、各channel誤差2/255以内・参照alpha255を条件とします。参照はproductionの固定化とregistry/bootstrapだけを共有し、exportのtarget・view・render・PNG保存を使いません。既定stackのbow、時計・コンパス・animation・glint、着色、半透明、GUI transformと特殊モデルを含みます。任意のNBT/componentsや引き絞り状態、実ワールド・無計装clientの一致は保証しません。

参照QAの初回では1.14.3のoverlayフェードと1.17.1の背景へのshader色の持ち越しが不一致を起こしました。overlay終了待ちと、公式InventoryScreenと同じ背景描画前の白shader色設定で解消しました。productionの描画・出力PNG・workerは変更していません。初回の失敗、修正時点のhelper hash、再検証を区別して保存します。

1.20.6のplayer_headはGUI位置(32,32)で33画素、最大78/255の色差があり、輪郭は同じです。原点の比較は成功しました。ユーザーが提示画像を確認し、この確認済みの位置差を許容して#10を原点基準で受け入れました。位置(32,32)のraw FAILは維持し、原因は未確定です。比較の許容値や一般的な例外条件は変更せず、他位置・実ワールド・GPUの比較は#13に残します。

元JARの無着色diamondを、ゲームクラス・GPU・固定化hookを使わず計算した38ケースもRGB・alpha誤差0でした。既定3版の計算8ケースは成功しましたが、26.3は以前のworkerの保存PNGを再比較した回帰確認で、現workerの26.3 native受入とは区別します。通常GUIの既存1.21.4・26.3は2背景80比較が成功しました。26.3のmappingsなし分岐を旧版用の照合条件へ混ぜた初回失敗を保存し、専用分岐を復元しました。最終helperでも旧版3境界の各20種類・灰色背景60比較が成功しました。全38版の比較時と最終helperのhashを分けて記録します。JUnit61件とCLI buildが成功しています。[版別入力・監査・比較・初回失敗の数値記録](../qa/validation-2026-10-12-classic-adapter-acceptance.json)に根拠を保存します。

```sh
# 最新版を流用せず、固定した正式版一覧から対象38版を選ぶ。
python3 - <<'PY'
import json, subprocess
from pathlib import Path
releases = [v['id'] for v in json.loads(Path('adapters/releases.json').read_text())['versions']]
versions = list(reversed(releases[releases.index('1.21.3'):]))
ids = 'stone cobblestone_stairs oak_slab oak_fence diamond bow chest ender_chest white_bed white_banner shield player_head dragon_head white_shulker_box glass white_stained_glass ice grass_block oak_leaves potion creeper_spawn_egg leather_chestplate clock compass enchanted_book prismarine'.split()
subprocess.run(['python3', 'scripts/verify-matrix.py', '--offline', '--cache', '.local/cache', '--out', '.local/classic-all', '--versions', *versions], check=True)
subprocess.run(['python3', 'scripts/verify-matrix.py', '--offline', '--cache', '.local/cache', '--out', '.local/classic-repeat', '--compare', '.local/classic-all', '--versions', *versions, '--ids', *['minecraft:' + i for i in ids]], check=True)
subprocess.run(['python3', 'scripts/verify-gui-reference.py', '--cache', '.local/cache', '--out', '.local/classic-gui', '--compare', '.local/classic-all', '--position', '0', '0', '--versions', *versions, '--ids', *ids], check=True)
subprocess.run(['python3', 'scripts/verify-flat-textures.py', '--cache', '.local/cache', '--out', '.local/classic-flat', '--compare', '.local/classic-all', '--versions', *versions], check=True)
PY
```

CLIを事前にbuildし、各版の公式入力とDocker imageを同じworktreeへ準備してください。出力先は新規に指定します。今回の実測環境はmacOS Apple Silicon・Colima/Rosetta・Linux amd64・Mesa llvmpipeです。全版experimental/deterministic=false、verified版0を維持し、実装・検証の完了とPR公開・merge・Issue closeは別の段階です。

## Issue #11の新13版adapterの受入（2026-10-12）

PR #19をmergeしたmainの1671c2aを起点に、1.21.4〜26.3の正式13版を検証しました。現workerで全19,209PNGを生成し、登録数・RGBA8・透過・sRGB Perceptualを監査しました。代表35種類を版ごとに別のCLI・ゲームプロセスで再生成した455PNGは、全件基準とdecoded RGBA・PNG hash・パスが厳密一致し、入力・設定・context・環境・renderer・catalogも一致しました。各IDごとの独立起動や全ID個別比較とは区別します。

通常GUI参照を1.21.5のGPU即時描画、1.21.6以降のdeferred描画、26.xのGuiGraphicsExtractor、26.2/26.3のmainRenderTarget・overlay APIと非同期Screenshotへ対応させました。参照は固定化とregistry/bootstrapだけを共有し、exportのtarget・view・render・PNG保存を使いません。既定stack・fixed-v1・64px・GUI原点(0,0)・guiScale4・1280×960画面で、13版×共通35種類×2背景の910比較がchannel誤差2/255以内・参照alpha255の条件で成功しました。形状・着色・半透明・GUI transform、チェスト・頭・盾・bed/banner/shulker・decorated_pot/conduit・時計/コンパス・animation/glint等を含みます。

追加モデルは、1.21.9以降8版に登録されているcopper_chest、copper_golem_statue、copper_chestplate、copper_spear、straw_bedの各版の部分集合を、同じ2背景で62比較しました。全件成功し、全35種類の910比較とは別に版別IDとhashを保存します。任意のcomponentsや選択条件の全分岐、手持ち状態・全model/サイズ・他GUI位置・実ワールド・無計装client・別OS/GPUの受入ではありません。

ゲームクラス・GPU・固定化hookを使わない元JARの計算29ケースも、RGB・alpha誤差0です。item definitionsの単純model・定数tintを対象版自身の定義から選び、元のgenerated textureを比較します。1.21.4の時計・コンパス類だけは既存のentityなし・向き0の針モデル計算を含みます。他版の動的modelを一般化して計算するoracleではありません。境界5版の初期GUI100比較は成功しましたが、26.xは以前のworkerの保存PNG基準を使ったQA動作確認です。13版の受入は、現workerの全件基準へ新規比較した上記の結果によります。

初回の通常GUI比較では、1.21.11でResourceLocationからIdentifierへの改名をQAが選択できず、2背景とも描画前に失敗しました。対象mappingsのクラス名に従って選択するよう修正し、1.21.11と対照1.21.6を最終helperで各35種類・2背景再比較して成功しました。上記910比較は初回12版の840成功比較と、修正後1.21.11の70成功比較の集約です。初回のraw FAIL・終了結果と前後のhelper hashを維持し、他12版の分岐結果は変わりません。

JUnit61件・CLI buildが成功し、production描画/core/workerは変更していません。[版別入力・監査・GUI・再生成・元テクスチャの数値記録](../qa/validation-2026-10-12-modern-adapter-acceptance.json)を参照してください。全版experimental/deterministic=false・verified版0を維持します。実ワールド照明・無計装client・全状態/サイズ・他OS/GPU・色空間精度・障害受入は#13等で継続し、マイルストーンは未完了です。実装・検証の完了とPR公開・merge・Issue closeは区別します。

```sh
# 公式入力とCLI/Docker imageを事前に準備し、新しい出力先へ再実行する。
python3 - <<'PY'
import json, subprocess
from pathlib import Path
releases = [v['id'] for v in json.loads(Path('adapters/releases.json').read_text())['versions']]
versions = list(reversed(releases[:releases.index('1.21.4') + 1]))
ids = 'stone cobblestone_stairs oak_slab oak_fence diamond bow chest ender_chest white_bed white_banner shield player_head dragon_head white_shulker_box glass white_stained_glass ice grass_block oak_leaves potion creeper_spawn_egg leather_chestplate clock compass enchanted_book prismarine decorated_pot conduit armor_stand trident spyglass bundle end_crystal pale_oak_leaves copper_door'.split()
subprocess.run(['python3', 'scripts/verify-matrix.py', '--offline', '--cache', '.local/cache', '--out', '.local/modern-all', '--versions', *versions], check=True)
subprocess.run(['python3', 'scripts/verify-matrix.py', '--offline', '--cache', '.local/cache', '--out', '.local/modern-repeat', '--compare', '.local/modern-all', '--versions', *versions, '--ids', *['minecraft:' + i for i in ids]], check=True)
subprocess.run(['python3', 'scripts/verify-gui-reference.py', '--cache', '.local/cache', '--out', '.local/modern-gui', '--compare', '.local/modern-all', '--position', '0', '0', '--versions', *versions, '--ids', *ids], check=True)
subprocess.run(['python3', 'scripts/verify-flat-textures.py', '--cache', '.local/cache', '--out', '.local/modern-flat', '--compare', '.local/modern-all', '--versions', *versions], check=True)
PY
# 追加modelの例。他版は数値記録の登録済みIDを指定する。
python3 scripts/verify-gui-reference.py --versions 26.3 --cache .local/cache \
  --out .local/modern-additions --compare .local/modern-all --position 0 0 \
  --ids copper_chest copper_golem_statue copper_chestplate copper_spear straw_bed
```

## Issue #12の正式51版カバレッジ受入（2026-10-12）

PR #20をmergeしたmainの507e372を起点に、固定した正式51版の登録集合・欠損・空画像を検証しました。修正後のworkerで全60,265PNGを新規生成し、登録母集団・no_item_form・件数・ID/パス重複・PNG hash/CRC・RGBA8・size・隠れた透明RGB・空画像を監査しました。#10/#11の正常基準とdecoded RGBA・PNG hash・パスが厳密一致し、入力・catalog・context・環境・rendererも一致しました。

catalogの重複判定は(id,kind)を単位にします。以前の(id,kind,status)では、同じ種別・IDの状態だけが違う重複を見逃しました。未知のkind/statusもskipせず拒否します。1.13・1.13.1・1.13.2のwheat/nether_wartには、登録itemとno_item_form blockが同じIDで別kindを持つ正常なケースがあるため、IDだけの一律重複判定は使いません。available itemのID重複はkindにかかわらず拒否します。正常な種別横断ID、同種別の状態違い、未知状態、登録アイテム欠落、重複ID/パス、PNG差し替え、全画素透明PNGを確認する9テストが成功しました。

実bytecodeでの欠損観測APIの依存照合は51版すべて成功しました。実際の破損パックで、atlasのstone PNGと直接参照されるdragon PNGを各51版で破損させ、欠損診断を確認しました。さらに12境界版で破損モデルJSON、特殊チェストPNG、空モデル、直接textureの事前ロード後のキャッシュ取得を確認しています。合計150ケースで、選択対象のPNG未出力・診断・公式入力不変・QAパック由来・同じworkerのwheat継続と正常基準RGBA/パス一致が成功しました。描画や欠損observerを置換するfixtureではなく、ゲーム自身にパックを読み込ませます。モデル破損の診断は、バニラが選んだfallbackによりMISSING_MODELまたはMISSING_TEXTUREになります。

初回のatlas/直接textureには、1.17〜1.18.1でQAの提供元確認に失敗した各4版を保存します。getResourceの宣言元ResourceProviderへmappingsを適用する修正後、該当4版を各faultで再実行して成功しました。さらに1.21.11のatlasでは、実際に破損stone PNGのfallbackを正常出力してしまうproductionの欠損検出漏れが見つかりました。同版のBakedQuadは頂点配列getterを持たず、position(int)/packedUV(int)を消費します。これらの入口でも保持spriteを観測する修正で、MISSING_TEXTURE・対象PNG未出力・正常wheatの継続とRGBA一致を確認しました。元の戻り値と描画処理は維持します。

150成功ケースは初回93成功と、提供元QA修正後8ケース、production修正後の1.21.11再試験1ケース、12境界版の初回47成功と事前ロードQA修正後1ケースを集約します。初回失敗10ケースを含む実行数は160です。1.16.5ではgetTextureがキャッシュ参照だけでも型がAbstractTextureへ変わっていたため、QAの事前ロードをTextureManager.bindの有無で選択するよう修正しました。実bytecode照合で追加getterの対象は1.21.11だけと確認し、他版の変更していない観測経路では初回成功を再利用します。ケースごとのworker/helper hashと初回FAILを保存し、全ケースを新workerで実行したとは扱いません。新workerの正常全件生成は51版すべて再実行しています。

空画像の判定は全画素alpha=0です。空モデルのnative描画ではRENDER_FAILEDの具体理由を確認し、PNGを残さず正常アイテムへ継続します。部分的な輪郭や透明領域の意味的な誤りを、空画像判定で検出できるとは扱いません。QAは1.21.11のIdentifier改名と、旧版のbind/getによる事前ロードへ対応し、空モデル検証の版制限を外しました。coreは変更していません。正常PNG bytes・RGBA・パスは不変ですが、worker hashとsettingsDigestが変わるため、以前のmanifestへ再開せず新しい出力先で再生成してください。未リリースのため旧設定への互換処理は追加しません。

JUnit62件とCLI buildが成功しました。[版別登録・除外・bytecode・正常PNG・破損パックの数値記録](../qa/validation-2026-10-12-release-coverage.json)に根拠を保存します。全版experimental/deterministic=false・verified版0を維持します。全model/状態/サイズ・無計装world・画像の意味・全GPU cache/OS経路・色精度・障害受入は#13等で継続し、マイルストーンは未完了です。実装・検証の完了とPR公開・merge・Issue closeは区別します。

```sh
python3 scripts/test-export-audit.py
python3 scripts/verify-resource-capabilities.py --cache .local/cache --out .local/coverage-capabilities
# 入力cache・CLI・Docker imageを事前準備し、compareへ現workerの正常全件出力を指定する。
python3 scripts/verify-resource-faults.py --injection resource-pack \
  --faults texture direct-texture --out .local/coverage-textures \
  --versions 1.13 1.14.4 1.21.11 26.3 --compare .local/coverage-normal
python3 scripts/verify-resource-faults.py --injection resource-pack \
  --faults model special-texture empty-model --out .local/coverage-models \
  --versions 1.13 1.14.4 1.21.4 26.3 --compare .local/coverage-normal
python3 scripts/verify-resource-faults.py --injection resource-pack --preload-direct-texture \
  --faults direct-texture --out .local/coverage-cached \
  --versions 1.13 1.14.4 1.21.4 26.3 --compare .local/coverage-normal
```

上のnativeコマンドは部分集合の再実行例です。全51版と12境界版のexact IDは数値記録に保存します。欠損対象のCLI終了値3を期待するテストであり、通常生成の成功とは区別します。画像・公式入力・破損パック・QA agent・workerログは公開配布物に含めません。実測環境はmacOS Apple Silicon＋Colima/Rosetta・Linux amd64・Mesa llvmpipeです。

## Issue #13のホスト移植性と1.20.6インベントリ基準（2026-10-12）

[数値記録](../qa/validation-2026-10-12-host-lighting.json)に、失敗から修正までのbinary・image・入力hash、ホスト、比較条件、件数を保存します。画像・公式入力・QAパック・接続先・個人の絶対パスは含めません。GitHub Actionsは使用していません。

Windowsのprotocolはpretty JSONのCRLFからLFだけを除去してCRを残していたため、workerが1行のJSONを読めずcatalog待機で失敗しました。protocol writerだけcompact JSONに変えます。さらにmanifest/settingsのpretty JSONをUTF-8/LFに固定し、OS改行によるsettings digest差を解消しました。以前のWindows出力は新規出力先へ再生成してください。Mac/LinuxのLF形式は維持します。

Macのllvmpipe 128bitとWindowsの256bitで、26.3のwritten_bookの1画素にRGB 1/255差がありました。vector幅128だけでは残り、CPU capsをSSE4.1へ固定して解消しました。SSE2はMacのglint色を最大4/255変えるため採用しません。SSE4.1条件は修正前Macの3版3,825枚と照明修正後1.20.6の1,329枚をRGBA/パス一致で維持します。image更新でsettings digestが変わるため新規出力先へ再生成してください。任意のCPU/Mesa/LLVMやGPUの一致保証ではありません。

1.20.6のlightmapは実ワールドのfull-bright RGB252へ初期化しました。その段階で残ったplayer_headの85画素・最大76/255差は、通常GUIを同じ位置(80,121)にしても再現します。原点では誤差0、位置(32,32)では33画素の差です。同位置のGUIとWorldの不透明1,794画素は完全一致しました。UV識別色のQAパックでは106画素の参照が変わり、色差85画素すべてがその中に含まれます。同じモデルのUV・投影・model-view matrixは不変ですが、位置の平行移動を差し引いたfloat32頂点座標には最大約2.1e-5 GUI単位の差を観測しました。GPU補間と丸め各段階の寄与までは分離していません。

ユーザー指定の固定基準を1.20.6のGUI 320×240・インベントリ位置(80,121)とし、モデル位置と投影をそろえました。size四方のFBOと移動したviewportでそのスロットを切り出し、PNGへのRGB補正や新たな差分許容は追加しません。512pxで必要なviewport 10240×7680はMac/WindowsのMesa上限16384×16384以内です。実設定値、GL error=0、元viewportへの復帰もQA observerで確認しました。原点や任意位置へ同じPNGを完全一致させる保証はありません。agent hash/contextが変わるため旧出力へ再開せず、新規出力先へ再生成してください。

修正後に実ワールドで24種類を再撮影しました。head・stairs/slab/fence・半透明・着色・特殊モデル等20種類は誤差0、glint付き4種類は最大1/255で比較基準2/255以内です。glintだけQAで時刻1000msへ指定し、production rendererや他canonical hooksは使いません。元PNGの初期フレームを保持するQAパックではprismarine/sea_lanternも誤差0でした。公式JARは不変です。初回FAILは修正前の根拠として保持し、以前のGUI(32,32)やoak_fenceの許容をheadへ流用していません。

1.20.6全1,329枚の登録集合・透過・hash/CRC・空画像監査、16/32/64/128/256/512px各6種類の監査が成功しました。フルサイズGUI FBOの私有試作と切り出しviewportの1,325枚は厳密RGBA一致です。glint4種類の5画素は最大1/255・alpha不変で、実ワールド比較でも同じ基準内です。他host/独立反復では許容値を広げずRGBAを厳密比較します。

最終binaryの各hostでraw6,319 PNG、icons5,154 PNG、独立反復96 PNGを確認しました。Linux/Windowsの全件はMac基準へRGBA/パス・設定/context/digest/rendererが厳密一致しました。最終workerで1.13/1.21.4/26.3の全3,825枚が以前の正常基準とRGBA/パス一致し、24種類×3版の独立反復も一致しました。JUnit63件、host監査7件、既存PNG監査9件、実manifest 9件のJSON Schema検証が成功しています。照明・投影の変更は1.20.6だけで、他版の通常ワールド照明を確認したとは扱いません。

core SHA-256: `b13da0531c544e300879bc6d476d2a892aaba94a35e7de1d36f8b91afc200a8c`。worker SHA-256: `50961e54de759da4397cb5ad5da2c6fdbdf0f09edeb803788e375f9981bf56af`。Docker image ID: `sha256:64c2a99ca5ee94105277fbeaace3ed7ba1eb80b710a02111ec2476e8c2e7f09c`。

```sh
# 同じbinary・imageで生成した64px全件出力をcompareへ渡す。
python3 scripts/verify-host.py --versions 1.13 1.20.6 1.21.4 26.3 \
  --out .local/host-repeat --compare .local/normal --cache .local/cache \
  --docker-context colima-mcicon --docker-image mcicon-render:qa
python3 scripts/verify-world-inventory.py --version 1.20.6 \
  --out .local/world-repeat --compare .local/normal --cache .local/cache \
  --docker-image mcicon-render:qa --ids stone player_head chest
```

Windowsは既存Java21を明示し、Docker DesktopのLinux backendで実行します。SSHでは既存credential helperのセッション条件で匿名pullが失敗したため、タスク専用匿名configでDockerfileをビルドしました。既存資格情報は変更していません。Linux確認はColima VM上のLinux amd64 container内Javaであり、bare-metal全distributionやnative/ARM64描画の保証ではありません。

## 分離した追加受入検証

ユーザー承認により、#13の完成範囲は上記3ホスト・4代表版の全件/反復、1.20.6のWorld比較、既存受入記録との整合性に確定しました。以前の残件は次の独立Issueへ分離し、各Issueに設計・完成条件・検証方法を記載します。分離先の実装は未着手です。

| World基準・照明のadapter | Issue |
| --- | --- |
| legacy-1.13 | [#22](https://github.com/rs0325/MCIconGenerator/issues/22) |
| legacy-1.14 | [#23](https://github.com/rs0325/MCIconGenerator/issues/23) |
| gui-fixed | [#24](https://github.com/rs0325/MCIconGenerator/issues/24) |
| gui-classic | [#25](https://github.com/rs0325/MCIconGenerator/issues/25) |
| gui-1.21.4 | [#26](https://github.com/rs0325/MCIconGenerator/issues/26) |
| gui-1.21.5 | [#27](https://github.com/rs0325/MCIconGenerator/issues/27) |
| gui-deferred | [#28](https://github.com/rs0325/MCIconGenerator/issues/28) |
| gui-26.2 | [#29](https://github.com/rs0325/MCIconGenerator/issues/29) |
| gui-26.3 | [#30](https://github.com/rs0325/MCIconGenerator/issues/30) |

- [非計装clientの基準画像と固定状態の比較条件を確定する（#31）](https://github.com/rs0325/MCIconGenerator/issues/31)
- [RGBA8・sRGB・straight alphaの色精度を追加検証する（#32）](https://github.com/rs0325/MCIconGenerator/issues/32)
- [一過性の準備IO_FAILEDの原因を特定する（#33）](https://github.com/rs0325/MCIconGenerator/issues/33)
- [一過性のworker起動WORKER_TIMEOUTを切り分ける（#34）](https://github.com/rs0325/MCIconGenerator/issues/34)
- [特殊rendererの直接texture・GPU cache監査範囲を確定する（#35）](https://github.com/rs0325/MCIconGenerator/issues/35)
- [native描画の途中失敗後の継続・資源解放を検証する（#36）](https://github.com/rs0325/MCIconGenerator/issues/36)
- [Docker daemon切断時の終了・再開を検証する（#37）](https://github.com/rs0325/MCIconGenerator/issues/37)
- [境界版のcold/warm時間・メモリ・cache容量を測定する（#38）](https://github.com/rs0325/MCIconGenerator/issues/38)

#13完了は全51版・全model/状態/サイズの無計装World比較、任意のGPU/native/ARM64 backend、全色精度や全障害の保証を意味しません。全版experimental/deterministic=false・verified版0を維持します。マイルストーンは未完了で、releaseは実施しません。

## Issue #14のローカル配布候補（2026-10-12）

#13のmerge commit f6f0b82を起点に、`0.1.0-preview.1`の配布候補をローカル生成しました。[配布ガイド](distribution.md)と[数値記録](../qa/validation-2026-10-12-distribution.json)を参照してください。2026-10-13にWindowsの新しい配布launcherも確認し、ユーザー指定の初回配布形式をGitHubプレリリース＋Maven形式ZIPに確定しました。releaseはまだ行っていません。

CLI ZIPへDockerfile/entrypoint・schema・利用例を加え、ソースbuildなしで環境を用意できます。別のMaven形式ZIPにcoreのbinary/sources/javadoc/POMと文書をまとめ、worker agentは単独でも用意します。GradleのreleaseVersionからCLI表示とicons/raw manifestのtoolVersionを生成します。既定の開発版は0.1.0-SNAPSHOTです。

展開済みCLIのMac Java21でhelp/version・offline versions・doctor、1.20.6 raw全1,614 PNG・diamond単品・stone/player_head/enchanted_bookの描画3 PNGが成功しました。描画3枚は#13の基準へRGBA/パスが厳密一致しました。配布物のDockerfileから新しいimageをビルドできました。新image IDは#13と異なるため、同じimageだとは扱いません。任意の別buildでのRGBA一致へ保証を広げません。Homebrewの初回buildx plugin検出失敗はガイドの直接呼出手順で解消しました。

独立Gradle consumerはZIPのMaven repositoryからcore、Maven Central由来のcacheからJackson依存を解決し、同梱ExportExample.javaをコンパイル・実行できました。diamond raw1枚とstone/player_headのicons2枚がCLIのbytes/RGBA・パスと一致し、CLI/API/manifestはすべて候補版番号を記録しました。Linux amd64 container内のJava21でもPOSIX launcherのhelp/version/offline raw1枚が成功しました。Linuxの新launcher経由の描画はこの配布試験では実行していません。

JUnit63件、配布監査の正常候補1件と実改変9ケースが成功しました。checksum改変、ゲームPNG/class、QA agent追加、Dockerfile/launcher/worker欠落、POM版不一致、偽のjavadoc画像を拒否します。JDK21文書用UI画像2枚はmodule内のbytesとhashを照合し、javadoc JARに限り区別します。元のlegal文書も保持します。SNAPSHOT再publishで旧timestamp JARが混入した点は、版専用のbuild/maven出力をpublish前に清掃して修正し、連続2回生成後のPOM1件・JAR3件を監査しました。清掃先に使う不正な版名は書き込み前に拒否します。

worker SHA-256は#13と同じ`50961e54de759da4397cb5ad5da2c6fdbdf0f09edeb803788e375f9981bf56af`です。描画実装・固定contextは変えていません。全51版を候補版で再生成したとは扱わず、全版experimental/deterministic=false・verified版0を維持します。配布ZIPのhashは添付SHA256SUMSで管理し、ZIP内の数値記録へ自己参照hashを埋めません。元ゲーム入力・生成PNG・QA agent・個人パス・接続先は公開配布へ含めません。

Windows Java21の展開済みmcicon.batでもchecksum・help/version・offline raw全1,614枚が成功し、元JAR bytesと一致しました。stone/player_head/enchanted_bookの描画3枚は透過・manifest/hashを監査し、Mac出力にRGBA/パスが厳密一致しました。context・renderer・agent hashも一致します。Windowsは既存#13 imageを使用したため、Macの新build imageとはimage IDとsettings digestが異なります。

初回とDocker起動後の別SSH接続ではdaemonが不在でDOCKER_IMAGE_UNAVAILABLEとなり、同一接続内で既存Docker Desktopの起動から描画まで実行すると成功しました。接続終了後にdaemonが利用できなくなる機序までは特定していません。失敗ログを保持し、成功した再試験は新規出力先で実行しました。softwareの導入・OS再起動・既存出力の上書きは行っていません。

## Issue #22のlegacy-1.13通常ワールド検証（2026-10-13）

起点はmainの17835349（#2のmerge）、依存#13の確定commitはf6f0b82です。通常のflatワールドを各版のバニラAPIで作成し、SurvivalInventoryScreenのslot9を撮影しました。1.13・1.13.1・1.13.2のstone、diamond、grass_block、oak_leaves、chest、player_head、glassは、1280×960・GUI scale4・GUI位置(80,121)・count1・既定stackで、64pxのexport PNGを空スロットの背景へ合成した21比較すべてが最大channel誤差0でした。判定基準は既存の2/255のままで、今回の7種類には許容例外を使っていません。

QA agentはproduction renderer、canonical hook、透過復元を含みません。通常フレーム末尾でゲーム状態・画面・撮影を操作し、対象スロットと同じItemStackのモデル描画入口でlightmap texture unitの有効状態だけを読み取ります。active texture unitとGPU texel読取時のbindingはfinallyで復帰します。完全な無計装clientとの比較は#31に残します。描画入口の観測からモデル内部の全描画状態を保証しません。

3版のfull-bright texelはCPU/GPUともRGBA(252,252,252,255)で、対象スロットの描画入口ではlightmap texture unitが無効でした。旧QAの「manifestに値がなければGUIも白255」という推定はこの経路に適合しません。1.13.2の初回は画像誤差0でも照明判定がFAILでした。この失敗と、1.13のIdRegistry.get/1を呼んで撮影が中断したQAエラーを保持しています。レジストリ列挙で実IDを取得し、対象スロットに限定した観測で再試験しました。productionの照明・投影・PNG・manifest/contextは変更していません。

64px全件2,362枚（784/789/789）の登録集合・PNG hash/CRC・RGBA8/sRGB・透過・空画像監査に成功しました。別プロセスの7種類一括生成21枚と、各IDを別CLI・ゲームプロセスで生成した21枚は、全件出力のdecoded RGBA・PNG hash・パス・設定/context/rendererと厳密一致しました。全IDの個別生成や全件の独立再生成をこの試験で行ったとは扱いません。

16/512pxの各版7種類・計42枚はPNG監査とworker exit0に成功しました。これはサイズ境界の生成・透過・終了試験で、World画像のサイズ別比較ではありません。最初のサイズ試験は別試験と同じcacheを共有したため、終了監視が他workerも所有対象へ数え、1.13.1の512pxがFAILになりました。PNG監査・CLI exit0・観測した終了exit0の記録は保持し、workerディレクトリを複製しない専用cacheで全6ジョブを再試験しました。終了監視を使う試験中は同じcacheの別workerを起動しないでください。

既存1.20.6の撮影経路も、同じ7種類で最大誤差0・client exit0でした。[数値・入力/worker/QA hash・初回失敗と再試験](../qa/validation-2026-10-13-legacy-world.json)に条件を残します。公開記録にはゲーム画像、worldデータ、JAR、個人パスを含めません。全版experimental/deterministic=false・verified版0を維持します。その他adapter・動的状態・全モデル・他GPUのWorld受入は残作業です。

```sh
python3 scripts/verify-matrix.py --versions 1.13 1.13.1 1.13.2 \
  --cache .local/cache --out .local/legacy-all --offline \
  --docker-image mcicon-render:0.1.0-preview.1

# versionごとに、別の出力先で代表7種類を生成してからWorldと照合する。
python3 scripts/verify-matrix.py --versions 1.13.1 \
  --ids stone diamond grass_block oak_leaves chest player_head glass \
  --cache .local/cache --out .local/legacy-selected-1.13.1 --offline \
  --compare .local/legacy-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 1.13.1 \
  --ids stone diamond grass_block oak_leaves chest player_head glass \
  --cache .local/cache --out .local/legacy-world-1.13.1 \
  --compare .local/legacy-selected-1.13.1 --docker-image mcicon-render:0.1.0-preview.1

# IDごと・versionごとに別CLIとゲームプロセスを起動して全件と照合する。
python3 scripts/verify-matrix.py --versions 1.13.1 --ids player_head \
  --cache .local/cache --out .local/legacy-single-1.13.1-player-head --offline \
  --compare .local/legacy-all --docker-image mcicon-render:0.1.0-preview.1

# このcacheを使う別workerが動いていない状態で実行する。
python3 scripts/verify-worker-lifecycle.py --versions 1.13 1.13.1 1.13.2 \
  --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass \
  --cache .local/size-cache --out .local/legacy-size-boundaries \
  --docker-image mcicon-render:0.1.0-preview.1
```
