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

初回の全件検証は2並列で25,665.659秒（約7時間8分）、再実行は1並列で49.397秒でした。起動タイムアウトの根本原因は未特定で、障害受入はIssue #13に残します。後続Issue #34の診断採取と短期対照も下記のとおり未再現で、原因未確定を維持します。[全IDと再実行の数値記録](../qa/validation-2026-10-11-single-all.json)に各IDのhash・パス・初回の失敗・再照合結果を保存し、[代表8IDの開始時記録](../qa/validation-2026-10-11-single-all-pilot.json)も維持します。誤色、同一RGBAでのパス違い、入力/設定/環境/catalogの違い、複数entry、指定ID違い、PNGの差し替えを拒否する7件のQAテストとJUnit61件が成功しました。


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

### Issue #34の起動診断採取とcold/warm対照（2026-10-14）

2026-10-15の利用者承認により、#34は診断整備と検証記録の確定を完成範囲とし、原因調査は[Issue #78](https://github.com/rs0325/MCIconGenerator/issues/78)へ分離しました。下記の未達・未再現は旧設計と各測定時点の履歴です。初期6＋shield17＋遅延観測24の計47ゲーム起動と、ゲームMain/GLを起動しないlogger初期化20対照を区別して保持します。診断整備の完了を原因特定・解消と扱わず、production・timeout・retry・API・保存形式を変更しません。

当時保存したCLI/core/workerのhashと旧imageを確認し、最新main `7fdb40e9`上のQAで1.21.4・netherite_ingot・64pxを計6起動しました。専用cacheの初回ゲームをcold、同じcacheの後続の新ゲームprocessをwarmと呼びます。公式入力は準備済みで、OS pagecacheは消去していません。当時2並列の対照は、起動済みの共有専用cacheを使う短期2起動です。元の約7時間の連続系列や失敗時点のVM状態を再現するものではありません。

| binary/image | 条件 | Docker runからreadyまで | 実CLI/worker終了 |
| --- | --- | --- | --- |
| 当時snapshot | solo cold / warm | 15.799 / 15.227秒 | 各0 |
| 現行snapshot | solo cold / warm | 15.173 / 15.265秒 | 各0 |
| 当時snapshot | 共有cacheのwarm・2並列 | 22.948 / 23.590秒 | 各0 |

専用wrapperはstdout bytesをそのまま転送し、CLI→Docker PID→container→worker directoryの対応、最初のstdout・ready/protocolとwall/monotonic時刻をprivateに記録します。host JFRの例外採取、所有containerのevents/stats、起動前後のhost/game thread dumpを保存しました。保存済み6起動ではCLIの`--debug`は未有効で、原例外の採取はhost JFRによります。反証後、以後のdriverにグローバル`--debug`を追加し、当時/現行CLIのflag受付とoffline cache欠損エラーのstack保存をDocker未起動で確認しました。修正後driverのnative再試験は行わず、実行済みdriverのSHAと修正後SHAを数値記録で分けています。Main初期化からRender threadのclassloading・shader compile等への進行を観測しましたが、原初回の停止位置を示す証拠ではありません。2並列のreadyはsoloより遅い結果でしたが、180秒timeoutの原因と結び付けません。監視・JFR・wrapperの観測負荷は原初回にはありません。

全6起動のPNG監査、worker exit 0・OOM/kill9イベント0、agent snapshot/一時icon回収を確認しました。当時warm/2並列は当時coldのPNG、現行warmは現行coldのPNGとdecoded RGBA・パスが厳密一致しました。原当時imageと現行imageの画質を相互受入する試験ではありません。初回coldのQAは、`docker inspect`のlowercase診断を大文字だけで照合したためFAILでした。実CLI/PNGは成功し、die 0/destroyと別途のno-such照会でcontainer解放を確認しています。初回reportを保持し、QAを修正した別出力のwarmを明示試験しました。成功への書換えやnative自動retryはありません。分割ready bytesの厳密転送、不正protocol/終了コード保持、JFRの原IOException/stack保持を合成検証しました。独立反証で、旧観測wrapperがJSON非object4種類とunpaired surrogateを含むrendererで転送を壊すことを確認し、元SHAのsourceで5条件FAILを保存しました。object以外をinvalid観測へ分類して転送を続け、private観測JSONをASCII escapeで保存する最小修正を行いました。続く反証では、SIGTERMを無視する所有childによってBrokenPipe後の無期限waitとevents終了待ちのtimeoutを合成再現し、初回FAILを保存しました。各箇所を期限付きwait→所有PIDだけのkill→期限付きwaitへ変更し、CLI強制終了後のwaitにも期限を付けました。修正後の合成7テストが成功しました。protocol.stdout/CLI stdoutの元bytesとDocker終了コードは維持します。保存6nativeの実行時wrapper SHAは保持し、保存した6起動の再実行はしていません。CLI buildが成功しました。

#4のJava診断containerとの互換性のため、観測対象をdocker run・`mcicon-worker-`名・`/mcicon/worker.jar:ro` mountの3条件を満たすworkerだけに限定しました。非worker名、worker名だがJAR無し、JAR有だが非worker名、name無しの4合成条件で、stdin・binary stdout・stderr・終了コードが透明転送され、worker観測ファイルが作られないことを確認しました。旧分類の3条件FAILを保存し、通常workerのready/ownership観測を含む合成8テストが成功しました。互換修正単独の確認はこの時点では合成で行い、既存6trialと新shield17trialの実行時source SHAを保持します。同じ修正済wrapperによる後続24起動は、遅延観測を加えた別世代driverとして下記に記録します。

原初回に強く対応するworker log候補は2,511 bytesで、Main初期化中のLog4j DNS診断を11:38:17 UTCに出力しています。CLI/containerの直接対応は未保存で、時刻と唯一の異常logによる候補です。同cacheの1,658ログに同じDNS警告があり、1,657ログにはStoppingがあります。DNS警告を原因とする根拠はありません。原初回の失敗瞬間のthread/CPU/memory/protocolは回収できず、**WORKER_TIMEOUTは未再現・根本原因未確定**です。当時の完成条件だった原因特定は未達で、現在は#78へ分離した未解決事項です。再試験成功を解消と扱いません。production・timeout・retryは変更せず、今回の起動失敗注入は未実施です。[条件・初回QA FAIL・入力/binary/診断artifact hash・数値](../qa/validation-2026-10-14-startup-diagnostics.json)を保存します。

その後#36の1.21.4・shield単品で新しいWORKER_TIMEOUTが発生しました。CLI開始02:34:29.731900 UTCから02:37:30.504943 UTCまで180.773秒で、core `eec62d03`・worker `8b85e11a`のimmutable配布物でした。自然timeout後のcleanupを確認しましたが、失敗前のthread/CPU/memoryとgame raw stdoutは未採取です。2,511 bytesのworker logは02:34:35 UTCのMain/Log4j DNS診断で終わり、同じ警告は正常起動にもあるため原因と判断しません。元#8との原因同一性も未確認です。

同じcandidateの公式入力だけを別の診断cacheへSHA確認copyし、旧worker directoryや実行データは混ぜませんでした。反証修正後のdriverでdebug/JFR/所有PID対応/自動thread dump/events/statsを採取したsolo 1起動はready 15.899秒で成功し、#36の20件目の診断成果として返しました。続く同じ共有cacheの2並列×各8warm、計16起動も全件成功しました。warm readyは21.045〜35.937秒、全17 CLI/workerはexit 0・OOM/kill9は0、所有container/snapshotを回収しました。17 PNGはold all・latest allの両基準とdecoded RGBA・パスが厳密一致しました。

readonlyの実JAR SHA/javap照合では、このcandidateがMainへ到達する前にpremain設定・mapping・transformer・reader開始を通過する構成と確認しました。失敗logには`render-thread hook installed`がなく、同candidateの正常logはDatafixer→hook installed→Environmentの順です。stderrが継続して保存される前提で、クラス変換から初回hook以前を優先採取する根拠になりますが、ログ欠落だけで未到達とは断定しません。hook設置と`Bridge.frame`実行は別段階で、ready後のshield処理をstartup原因とする根拠もありません。

既存自動QAの親/ゲーム`Thread.print`とraw stdout/stderr保存が最小の再発採取に対応します。必要時の追加候補は初回hook、loadFinished通過、renderer初期化完了の時刻です。この追記では追加計測の実装・実行は行わず、6/17起動の成功を原因解消と扱いません。

新timeoutも未再現・原因未確定です。観測負荷とホスト実装/buildの並行があり、OS空負荷・元longseriesや失敗時の他job/VM状態の完全再現とは扱いません。productionのtimeout延長・fallback・retryは追加せず、初回失敗と先行した無観測再試験の成功を診断付き結果へ置き換えません。[新FAILの証拠hash・immutable入力・17起動の段階/数値/診断artifact hash](../qa/validation-2026-10-14-shield-startup.json)を、以前の6trialと別の記録に保存します。原因特定は旧完成条件では未達のままで、現在は#78で継続します。

続く対照では`--delayed-observation`を追加し、host JFRと45秒以前のthread dumpを外しました。起動が長時間停止した場合だけ45/120/165秒のhost/game dumpを採ります。元のcore `eec62d03…`・worker `8b85e11a…`・image `c498eda7…`を維持し、別専用cacheの初回game各1回・後続各3回の8起動と、同じwarm cacheを2 workerで共有する16起動を実施しました。24起動とも正常PNG・基準RGBA/path一致・終了0・所有container/snapshot/一時PNGの回収を確認し、runからreadyは19.71〜24.40秒でした。最初の45秒より前に全起動が完了したため、game thread dumpは採られていません。wrapper・stats監視は残り、OS pagecache未消去、共有系列には別Issueのホストbuild/testが重なり得る条件です。ゲーム未起動cacheをcoldと呼び、公式HTTPS取得時間は含めません。追加の合成テストを含む9件が成功しました。[24起動の条件・数値・artifact hash](../qa/validation-2026-10-14-startup-delayed.json)は先の6/17起動と分けて保存し、これらの成功を原因解消とは扱いません。

実clientの`hkl`はGameLoadTimesEventで、保存失敗logはlogger初期化中です。protocol readerの最初のstdin待機経路はMinecraft・hkl・Log4jを呼ばず、transformerのmapping参照も`Symbols.type`によるゲームクラスロードを行いません。readerとhklの間にagentが作る具体的なロック循環は確認できませんでした。これはJVMや他のゲームスレッドのdeadlock全体を否定する結果ではありません。再発時は実際の待ち先と保持スレッドを失敗前dumpで対応付けます。

公式clientとlibrariesを使い、Minecraft Main・GL・core・readyを起動せず、`hkl`のclass初期化だけを隔離した対照も行いました。元workerを付けた10回とagent無し10回を最大2並列で実行し、全20回が1.78〜3.37秒で正常終了しました。同じhostname警告は全20回で出ているため、警告だけを停止原因とは判断できません。これはゲーム起動停止の再現や停止箇所の特定ではありません。最初のfixtureは署名済みclientと同じdefault packageによる証明書衝突で失敗し、fixtureだけを専用packageへ移した対照を別に保存しました。agent無しの警告はstdoutに出るため、stderrだけを見るdriverの観測値を採用せず、保存済み両streamで確認しました。元raw結果は保全し、再判定のためのnative再実行はしていません。[20回の条件・数値・入力と診断fixtureのhash](../qa/validation-2026-10-14-logger-init.json)を保存しています。

診断整備の確定時には、最新main `96d09cc`との非Git統合snapshotでdocsの3way競合0、JUnit97件・build/installDist成功を確認しました。最新の`Diagnostics`が作る`mcicon-doctor-*`・`--entrypoint`付きJava診断runも、最終合成12テストと統合CLI doctorのfake Docker試験でstdin/stdout/stderr/終了codeを透明に扱い、worker観測ファイルを作らないことを確認しました。最終化で本物のDockerやゲームは起動していません。この統合buildのcore/workerは過去47起動・logger20対照の測定binaryと分け、元FAIL・診断source・測定世代のhashを保持します。原因解消は#78で未解決のままです。

最終反証では、初期のimage inspectとJava version取得に期限がなく、monitor終了確認も不足する故障境界を修正しました。外部呼出しを15秒・10秒以内とし、失敗時は初期化FAILを保存します。monitorは停止通知を各呼出しの間で確認し、20秒のjoin後も生存していれば結果を読まずFAILにします。初期化停止と所有PID回収、進行中監視の終了待ち、join後生存による結果消費拒否を合成で確認しました。最初の0.2秒fixtureはPID証拠の作成前に切れたため、その試験FAILを保存してfixtureだけ2秒へ変更しています。このQA修正を過去47起動で実測したとは扱わず、統合buildと追加合成のsource hashを分けています。最終限定反証と、メイン担当による9ファイルhash・統合JUnit97件・最終QA sourceの独立照合も成功し、追加指摘はありません。#34の診断整備と検証記録の確定は完了し、原因調査は#78で未解決のまま継続します。

専用cacheでの診断再実行例です。2並列対照は他nativeを終了し、専用2枠を確保してから実行してください。

```sh
python3 scripts/test-startup-diagnostics.py
python3 scripts/verify-startup-diagnostics.py --cache .local/startup-cache --out .local/startup-diagnostics
# 初期attach/JFRの観測負荷を減らす対照。既存outを再利用しない。
python3 scripts/verify-startup-diagnostics.py --cache .local/delayed-cache --out .local/delayed-diagnostics --delayed-observation
```


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

## Issue #35の特殊renderer resource・cache監査（2026-10-14）

起点はmainのc7c878f3e8686880305c6cdca09ba900c6beb920、前提#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。1.13・1.21.4・26.3のchest/dragon_head/player_head/shieldを、既定stack・64px・固定Linux amd64 Mesa Dockerで監査しました。[数値・hash記録](../qa/validation-2026-10-14-special-resource.json)に、通常workerと観測workerの別実行、実resourceの入口、cache hitと既存guardの対応を残します。

| 版 | chest | dragon_head | player_head | shield |
|---|---|---|---|---|
| 1.13 | entity/chest/normal.pngを直接bind | entity/enderdragon/dragon.pngを直接bind | entity/steve.pngを直接bind | entity/shield_base_nopattern.pngを直接bind |
| 1.21.4 | entity/chest/normal sprite、chest atlas | entity/enderdragon/dragon.pngを直接取得 | entity/player/slim/steve.pngを直接取得 | entity/shield_base_nopattern sprite、shield_patterns atlas |
| 26.3 | entity/chest/normal sprite、chest atlas | entity/enderdragon/dragon.pngを直接取得 | entity/player/slim/steve.pngを直接取得 | entity/shield/shield_base_nopattern sprite、shield_patterns atlas |

PNGの直接パスはassets/minecraft/textures/以下、sprite名はminecraft:以下です。atlas化されたPNGはSpriteResourceLoaderのロード入口と公式JAR内のresource hashを照合し、描画pass中のatlas取得・guard:spriteへ対応させます。1.13のdirect bind、後続版のTextureManager取得は黒/白の2背景passで初回取得とcache hitを観測し、cacheに保存済みの同一texture objectにもguard:textureが呼ばれました。26.3では実際のFrontendRenderPass.setUniformのSampler0へ渡るGpuTextureViewをAbstractTextureの既存textureView fieldへidentityで紐付け、取得・guard・GPU消費の対応を確認しました。新規のgetTexture/getId/bind/loadやmissing生成は観測のために呼びません。

通常workerの独立基準12 PNGとQA観測12 PNGはdecoded RGBAが一致しました。観測対象の12経路で確認された欠損検出漏れはなく、production worker・公開API・診断契約・contextは変更していません。以前のPNGの再生成は不要です。JUnit67件とoffline build/installDist、QA Java8対象compile・Python構文検査が成功しました。

#12の150成功faultケースは今回再実行していません。当時のworker/hash・条件・10初回失敗を含む公開記録を参照し、ResourceGuard/ResourceTransformerとfault harnessの4ファイルが今回起点とbyte一致することを確認して再利用します。当時は破損対象PNG未出力、同workerでwheat継続・基準RGBA一致を150ケースで受け入れています。今回の12正常監査を、12種の新しい破損pack試験と数えません。

専用cacheの初回実行はlegacy mapping入力不足でOFFLINE_CACHE_MISSとなり、ゲームは起動しませんでした。初回stderrとQA JARを保全し、不足mappingを検証・複製した再試験と区別します。観測入口の継承名・obfuscated lambda補正前、GPU観測追加前の中間成功も別worker/source hashで記録し、最終harnessの証拠へ混在させません。

重点反証で、1.13のQA bind観測が難読化名aの別overloadであるTextureManager.reload(ResourceManager)まで選択していたことを確認しました。Identifier引数・void戻りの厳密descriptorへ限定し、get/atlas/resource/GPU getter・sprite loaderも正規の引数/戻り型へ限定しました。修正前hook・source/QA hash・採点は別記録として保全し、修正後はreload hookがないことを実ログで確認しました。1.13の4passのeventはobject identity番号を除いて修正前後で一致し、今回の12対象の採点は変わりません。修正後の同一QA sourceで3版を再照合しています。

descriptor修正前harnessの26.3観測workerでは、描画開始前にWORKER_EXITEDが1件発生しました。所有ログ末尾はwrite /dev/stdout: broken pipe、Dockerにはkill15/kill9・exit127が記録され、OOMイベントは観測されませんでした。終了原因は未確定で、cleanup後のexitだけでは根本原因を説明できません。初回FAILを保持し、同じsource/production worker/input/imageで26.3だけ明示再試験した結果は成功しています。再試験成功を起動原因の解消と扱わず、#34の切り分け対象として記録します。

仕上げとしてQA sourceの未使用Type[] args計算だけを削除し、Java8対象compile・Python構文を確認しました。native試験は削除前c0fd10f5 source、起動FAILはdescriptor修正前ab999a88 sourceです。削除後の公開source hashとcompile証拠は別フィールドに記録し、削除後sourceでnative再試験したとは扱いません。挿入するhook・観測・採点ロジックは変更していません。

保証は、この3版・既定4 stackの固定描画条件と#12の既存fault条件に限ります。任意のcustom skin/profile、染色・模様付きshield、季節性texture、他版・他GPU、全resourceを受け入れたものではありません。GPU uniformへ渡す前に外部が独自cacheへ保持したraw viewを、requested pass内の取得・sprite/guardを経由せず使う経路は今回の保証外です。生成GUI atlas・lightmap等の全補助GPU資源、非render-thread更新・外部agentの競合・reloadの全組み合わせは完全監査していません。実際の漏れが確定した場合には、対象破損packの未出力・具体診断・同worker継続と独立RGBA基準を追加検証します。全版experimental/deterministic=false・verified版0を維持します。

再実行には対象3版の公式入力・checksumとlegacy mappingを専用cacheへ用意し、CLIをbuildします。通常workerとQA workerは別ゲームプロセスで順番に起動します。

```sh
python3 scripts/verify-special-resources.py --cache .local/cache \
  --out .local/special-resource-audit --versions 1.13 1.21.4 26.3 \
  --docker-image mcicon-render:0.1.0-preview.1
```

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

ユーザー承認により、#13の完成範囲は上記3ホスト・4代表版の全件/反復、1.20.6のWorld比較、既存受入記録との整合性に確定しました。以前の残件は次の独立Issueへ分離し、各Issueに設計・完成条件・検証方法を記載します。この一覧は分離時点の計画です。追加受入の結果は本書後半のIssue別記録を参照してください。

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

## Issue #23のlegacy-1.14通常ワールド検証（2026-10-13）

起点はmainの00d33e8（#22のmerge）、依存#13の確定commitはf6f0b82です。独立World QAを1.14・1.14.1・1.14.2・1.14.3へ対応し、NativeImageのクラス名変更と、サーバー側スロット更新のExecutor APIを各版の実mapping・bytecodeで確認しました。1.13系は従来のCallable APIを維持します。production renderer・canonical hook・透過復元を共有しません。

通常flatワールドのSurvivalInventoryScreen・slot9、1280×960・GUI scale4・GUI位置(80,121)・count1・既定stackで、stone、diamond、grass_block、oak_leaves、chest、player_head、glassを撮影しました。64pxのexport PNGを空スロット背景へ合成した28比較は、すべて最大channel誤差0でした。比較基準2/255を維持し、許容例外は使っていません。CPU/GPU full-bright texelはともにRGBA(252,252,252,255)で、対象スロットのモデル描画入口ではlightmap texture unitが無効です。active textureとbindingは観測後に復帰し、モデル内部の全描画状態の保証とは区別します。完全な無計装clientは#31に残ります。

描画・照明・PNG・manifest/contextは変更不要でした。64px全件3,504 PNG（各版876枚）の登録集合・PNG hash/CRC・RGBA8/sRGB・透過・空画像監査が成功しました。別プロセスの7種類一括28枚と、各ID別CLI・ゲームプロセスの28枚は、全件出力のdecoded RGBA・PNG hash・パス・source/設定/context/renderer/catalogが厳密一致しました。全IDの個別生成・全件の独立再生成ではありません。

16/512px・各版7種類・計56枚は、専用cacheでPNG・透過とworker exit0を確認しました。サイズ別のWorld画像比較ではありません。既存1.13.2の独立World QAも同じ7種類で誤差0でした。今回は初回から成功し、失敗を消して成功へ書き換える再試験はありません。公式入力670ファイル×2cacheのhashは不変です。

CLI installDist、Java8対象QAコンパイル、Python構文確認が成功し、最終反証で新しい確定問題は見つかりませんでした。production変更がないため、JUnitは今回は再実行していません。[各版の数値・入力/worker/QA hash](../qa/validation-2026-10-13-legacy-114-world.json)に条件を保存します。ゲーム画像・worldデータ・JAR・個人パスは公開記録へ含めません。全版experimental/deterministic=false・verified版0を維持します。全モデル・動的状態・全サイズ・他GPUのWorld受入は残ります。

再現手順は上記#22と同じhelperを使用します。`--versions`は1.14・1.14.1・1.14.2・1.14.3、World撮影の`--version`はそのexact版へ替え、試験ごとに新しい出力先を指定してください。終了監視中は同じcacheで別workerを起動せず、Docker image IDと公式入力を揃えます。

## Issue #24のgui-fixed通常ワールド検証（2026-10-13）

対象exact releaseは1.14.4、1.15、1.15.1、1.15.2、1.16、1.16.1、1.16.2、1.16.3、1.16.4、1.16.5です。起点はmainの96c365b236841b9aa4cfeef92851e35657ff036a（#23 merge）、依存#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。Linux amd64・同一Mesa Dockerで検証しました。

通常flatワールド・slot9・1280×960・GUI scale4・GUI位置(80,121)・count1・既定stack・overlayなしで、stone、diamond、grass_block、oak_leaves、chest、player_head、glassを撮影しました。最終QAの64px・70比較は、すべて最大channel誤差0です。許容値2/255と既存の限定例外を広げず、今回の例外使用は0画素です。全モデル・全状態・全サイズ・他GPUとのWorld一致へ一般化しません。

初回1.14.4は誤差0でしたが、1.15・1.16.5のdiamondは864画素で最大3/255となりFAILでした。WorldのCPU/GPU full-bright texelはともにRGBA(252,252,252,255)、worldなしproductionは初期白255です。1.15以降のItemRendererはbuffer内部のLightmapStateShardでlightmapを有効にします。1.15〜1.16.5の9版だけ描画前NativeImageを252へ初期化・uploadし、context.lightmapFullBrightRgbへ記録しました。1.20.6の既存初期化と同じ処理を共有します。1.14.4のrender/2経路、投影・モデル位置・PNGの透過復元は変更しません。PNG保存後のRGB補正は行っていません。

QAの初期observerは一律texture unit1を読んでいましたが、1.14.4のLightTextureはunit1、1.15以降はunit2です。全10版の公式client bytecodeで対応を確認し、正しいunit番号も撮影記録へ追加しました。旧observerの画像誤差0は色の診断として保持し、誤ったunitの観測を照明の最終受入へ流用しません。対象スロットだけを観測し、active texture/bindingを復帰します。描画入口の状態はモデル内部の全状態を示しません。buffer経路の照明判定はCPU/GPUとmanifest252の一致、1.14.4とlegacyは正しいunit1の入口無効状態を使います。

また、1.16系のWorldGenSettings.createはPropertiesのseed「0」をランダム扱いするため、初回1.16.5の保存seedは-2749481002061984119でした。この撮影をseed0条件の受入とは扱いません。withSeed(false, OptionalLong.of(0))で明示設定し、作成前のseed/flatと最終全10版の保存level.datを確認しました。完全な無計装clientとの照合は#31です。

全件9,373 PNG（1.14.4:876、1.15系各883、1.16/1.16.1各974、1.16.2〜1.16.5各975）の登録集合・hash/CRC・RGBA8/sRGB・透過・空画像監査が成功しました。選択一括70枚と、各IDを別CLI・ゲームプロセスから生成した70枚は全件基準へ厳密RGBA、PNG hash、パス、source、settings/digest、context、renderer、catalogが一致しました。16/512px・各7種類・20ジョブ・140枚ではworker exit0、強制終了なし、PNG監査が成功しました。サイズ境界はWorld画像比較ではありません。

修正前後の代表70枚では1.14.4の7枚が同一、1.15以降の63枚が色変化し、alphaと透明領域は不変でした。これは代表画像の結果であり、全モデルのalpha不変を保証しません。worker JAR全体のhashが変わるため、照明変更のない版も旧agentのiconsへ再開できません。新しい出力先へ再生成してください。全版experimental/deterministic=false・verified版0を維持します。

JUnit64件、CLI installDist、Java8対象QA compile、Python構文確認が成功しました。共有処理の1.20.6とlegacyの1.14.3は、同じ7種類のWorld回帰で誤差0でした。[数値・入力/worker/helper hash・初回FAILと観測修正の記録](../qa/validation-2026-10-13-fixed-world.json)へ保存しています。JAR・assets・World・画像・個人パスは公開記録に含めません。

既存cacheで対象版を準備した後、再現例は次のとおりです。単品比較は各IDを別CLI呼び出しで出力し、audit-export.pyへ全件manifestを渡します。終了試験には別cacheを使用します。

```sh
python3 scripts/verify-matrix.py --versions 1.14.4 1.15 1.15.1 1.15.2 1.16 1.16.1 1.16.2 1.16.3 1.16.4 1.16.5 --offline --out .local/fixed-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 1.16.5 --ids stone diamond grass_block oak_leaves chest player_head glass --compare .local/fixed-all --out .local/fixed-world/1.16.5 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-worker-lifecycle.py --versions 1.14.4 1.15 1.15.1 1.15.2 1.16 1.16.1 1.16.2 1.16.3 1.16.4 1.16.5 --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass --cache .local/size-cache --out .local/fixed-sizes --docker-image mcicon-render:0.1.0-preview.1
```

## Issue #25のgui-classic通常ワールド検証（2026-10-13）

対象exact releaseは1.17、1.17.1、1.18、1.18.1、1.18.2、1.19、1.19.1、1.19.2、1.19.3、1.19.4です。起点はmainの48ff61847f535338d9ad8fa5b602e551fddc4dd7（#24 merge）、依存#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。同一Linux amd64・Mesa Docker、ゲームJava17で検証しました。

通常flatワールド・SurvivalのInventoryScreen・seed0・slot9・1280×960・GUI scale4・GUI位置(80,121)・count1・既定stack・overlayなしで、stone、diamond、grass_block、oak_leaves、chest、player_head、glassを撮影しました。保存level.datのseed/flat・GameTypeも全10版で確認し、64px・70比較はすべて最大channel誤差0です。許容値2/255を維持し、例外使用は0画素です。全モデル・動的状態・全サイズ・他GPU・完全な無計装clientとのWorld一致へ一般化しません。

初回の代表1.17・1.18.2・1.19.4は、diamond864画素、glass145画素で許容値を超える最大3/255でした。WorldのCPU/GPU full-bright texelはともにRGBA(252,252,252,255)、worldなしproductionは初期白255です。gui-classicの10版だけ描画前NativeImageを252へ初期化・uploadし、context.lightmapFullBrightRgbへ記録します。既存初期化を共有し、ClassicRendererを継承するgui-fixedにはprofile条件で適用を避けます。投影・モデル位置・PNGの透過復元は変更せず、保存後のRGB補正も行いません。

QAは版別のWorld作成API、registry、Screenshotに対応し、1.19.4の5引数GUIメソッドではItemStackのlocal 2を観測します。全10版の公式bytecodeでlightmapのshader texture番号2を確認し、RenderSystem.getShaderTexture(2)の描画入口値を記録しました。この値はbuffer内部でのbindingや全描画状態を保証しません。固定機能GL_TEXTURE_2Dの有効状態をcore profileの照明判定へ流用せず、CPU/GPUとmanifest252の一致、実画像比較を受入の根拠にします。production renderer・canonical hook・透過復元はQA agentへ同梱しません。

初回1.19.4 QAは、存在しないsetAllowCommandsを呼んで撮影前にexit1となりました。実APIのsetAllowCheatsへ変更し、未完了captureの比較を停止します。また1.17は並行試験と単独再試験で撮影前のcontainer OOM・exit137を確認しました。両失敗を保存し、gui-classic World QAだけ最大heapを2GiBから1GiBへ変更、container上限3GiBのまま単独実行しました。production workerのheapは変更しません。APIエラーのcaptureが存在する場合はerrorをreportへ保持し、native終了でcaptureが未作成の場合も二次例外なく失敗を保存します。2種類の故障注入を実native受入とは分けて確認しました。

64px全件11,363 PNG（1.17系各1,099、1.18系各1,100、1.19〜1.19.2各1,151、1.19.3は1,185、1.19.4は1,227）の登録集合・hash/CRC・RGBA8/sRGB・透過・空画像監査が成功しました。独立した選択一括70枚と各ID別CLI・ゲームプロセスの70枚は、全件基準へ厳密RGBA・PNG hash・パス・source・settings/digest・context・renderer・catalogが一致しました。全IDの単品生成や全件の独立再生成ではありません。

16/512px・各7種類・20ジョブ・140枚では専用cacheでworker exit0、強制終了なし、PNG監査が成功しました。サイズ境界はWorld画像比較ではありません。gui-fixedの1.16.5とmodernの1.20.6は、通常World14比較が誤差0、新workerの代表14 PNGは旧workerとRGBA・PNG hash・パスが一致しました。

修正前後の代表3版・21枚では色が変わり、alpha・透明領域は不変でした。全モデルのalpha不変の保証ではありません。入力とsidecar 2,208ファイル×2cache、回帰用1,096ファイルのhashは不変です。worker JARのhashが変わるため、照明を変更しない版も旧agentのiconsへ再開できません。新しい出力先へ再生成してください。

JUnit64件、CLI installDist、Java8対象QA compile、Python構文確認が成功しました。[各版の数値・入力/worker/helper hash・初回FAIL/OOM](../qa/validation-2026-10-13-classic-world.json)を記録します。JAR・assets・World・画像・個人パスは公開記録に含めません。全版experimental/deterministic=false・verified版0を維持します。

既存cacheで対象版を準備した後、再現例は次のとおりです。World試験はnative clientを単独で実行し、終了試験には別cacheを使用します。単品比較は各IDを別CLI呼び出しで出力し、audit-export.pyへ全件manifestを渡します。

```sh
python3 scripts/verify-matrix.py --versions 1.17 1.17.1 1.18 1.18.1 1.18.2 1.19 1.19.1 1.19.2 1.19.3 1.19.4 --offline --out .local/classic-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 1.19.4 --ids stone diamond grass_block oak_leaves chest player_head glass --compare .local/classic-all --out .local/classic-world/1.19.4 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-worker-lifecycle.py --versions 1.17 1.17.1 1.18 1.18.1 1.18.2 1.19 1.19.1 1.19.2 1.19.3 1.19.4 --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass --cache .local/size-cache --out .local/classic-sizes --docker-image mcicon-render:0.1.0-preview.1
```

## #26: gui-1.21.4の通常World照明・インベントリ基準

起点は#25のmerge commit d31ee558f1140d98865c24bfe18e7c10ddc5be68、前提#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。対象exact releaseは1.20・1.20.1・1.20.2・1.20.3・1.20.4・1.20.5・1.21・1.21.1・1.21.2・1.21.3です。Linux amd64・固定Mesa llvmpipe Dockerで、通常flat Worldのstone・diamond・grass_block・oak_leaves・chest・player_head・glassを64pxで照合しました。window 1280×960、GUI scale4、slot9の実座標(80,121)、count1・既定stack・overlayなし、保存Worldのseed0・minecraft:flat・SURVIVALを確認しました。70比較は最大channel誤差0、許容2/255超過0、輪郭例外0です。

初回の代表1.20・1.20.5・1.21.3では、worldなしproductionのlightmap白255とWorldのfull-bright252が異なり、diamond864画素・glass145画素で最大3/255のFAILでした。1.20・1.20.5のplayer_headは85画素で最大76/255です。照明だけの修正で他6種類は誤差0になり、ヘッド差だけが残りました。通常インベントリの投影・スロット位置へ合わせた試作は代表3版21比較が誤差0となり、ユーザーが#26の10版もGUI 320×240・(80,121)基準とすることを承認しました。許容値を広げず、projection/viewportによる切出しで修正し、PNG保存後のRGB補正は行いません。

gui-1.21.4の12版へ描画前RGB252初期化を適用します。今回対象のCPU lightPixels 8版はNativeImageを初期化・uploadし、1.21.2・1.21.3のGPU targetは既存1.21.4と同じ0.99のclearを用います。WorldのCPU画像がある8版ではCPU/GPU texel、GPU targetの2版ではScreenshot/GPU texelのRGBA(252,252,252,255)を照合しました。GPU targetに存在しないCPU値は作成しません。描画入口のshader texture番号2を読み、内部buffer描画全体の状態は保証しません。QA agentはproduction renderer・canonical hook・PngOutputを含まず、照明や参照画像をproduction値で上書きしません。観測のためのASMを使用するので完全な無計装clientではありません。

1.20・1.20.1のQAにはisGameLoadFinishedが存在せず、初回1.20は撮影前にnative exit1となりました。capture・log・実行時sourceを保全し、APIが存在する版だけ呼び、TitleScreen/overlayで初期化完了を待つよう修正しました。1.20〜1.20.4はJava17、1.20.5以降はJava21を使用します。setAllowCheats/setAllowCommandsとGuiGraphicsのrenderItem7引数内のItemRenderer8/11引数をmappings/bytecodeに照合しました。QAのheap1GiB・container3GiBでWorld試験を単独実行し、production workerのheapは変更しません。初回API失敗・修正前画像FAIL・照明のみ試作・座標試作・最終native受入を区別します。

64px全件13,125 PNG（1.20は1,254、1.20.1は1,254、1.20.2は1,254、1.20.3は1,311、1.20.4は1,311、1.20.5は1,329、1.21は1,332、1.21.1は1,332、1.21.2は1,374、1.21.3は1,374）の登録集合・hash/CRC・RGBA8/sRGB・透過・空画像監査が成功しました。独立した選択一括70枚と各ID別CLI・ゲームプロセス70枚は、全件基準とdecoded RGBA・PNG hash・パス・source・settings/digest・context・renderer・catalogが厳密一致しました。全IDの単品生成、全件の独立反復ではありません。16/512px・20ジョブ140枚は専用cacheでPNG監査とworker exit0・強制終了なしを確認し、World画像比較とは区別します。

1.19.4・既存基準1.20.6・原点基準1.21.4の回帰21 PNGは旧workerとRGBA・PNG hash・パス、agent以外のsettings、context・renderer・catalogが一致し、World21比較は誤差0でした。回帰後にPythonのscope説明1行だけをCPU/GPUとScreenshot/GPUに区別しました。比較・native実行・Java helperは不変で、実行時sourceを各試験フォルダへ保存しhashを照合しています。

修正前後の代表3版21 PNGは色が変わり、alphaと透明領域は不変でした。全モデルのalpha不変の保証ではありません。入力/sidecar 776ファイル×3cache、回帰用732ファイルのhashは不変です。新しい10版のlightmapとGUI context、worker hash/settings digestが変わるので、照明変更のない版も以前のiconsは新しい出力先へ再生成してください。

JUnit64件、CLI installDist、Java8対象QA compile、Python構文と差分確認が成功しました。[全版の数値・入力/worker/helper hash・初回失敗](../qa/validation-2026-10-13-modern-world.json)を保存します。JAR・assets・World・画像・個人パスは公開記録に含めません。全モデル・動的状態・全サイズ・他GPUのWorld受入へ一般化せず、experimental/deterministic=false・verified版0を維持します。

既存cacheで対象版を準備した後、再現例は次のとおりです。Worldは単独client、終了検証は専用cacheで実行します。各IDの個別比較には別CLI呼び出しを使い、audit-export.pyへ全件manifestを指定します。

```sh
python3 scripts/verify-matrix.py --versions 1.20 1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.21 1.21.1 1.21.2 1.21.3 --offline --out .local/modern-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 1.21.3 --ids stone diamond grass_block oak_leaves chest player_head glass --compare .local/modern-all --out .local/modern-world/1.21.3 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-worker-lifecycle.py --versions 1.20 1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.21 1.21.1 1.21.2 1.21.3 --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass --cache .local/size-cache --out .local/modern-sizes --docker-image mcicon-render:0.1.0-preview.1
```

## #27: gui-1.21.5の通常World照明

起点は#26のmerge commit ff79e38ace908cacac4afae051819650104dd619、前提#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。exact release 1.21.5のstone・diamond・grass_block・oak_leaves・chest・player_head・glassを、Linux amd64・固定Mesa llvmpipe Dockerで検証しました。通常flat World、保存seed0・minecraft:flat・SURVIVAL、window 1280×960、GUI scale4、slot9の実座標(80,121)、count1・既定stack・overlayなし・64pxです。最終7比較は最大channel誤差0、許容2/255超過0、輪郭例外0でした。

worldなしproductionのLightTextureは初期白255で、通常WorldのGPU full-bright texelはRGBA(252,252,252,255)でした。照明変更前はdiamond864画素・glass145画素が最大3/255のFAIL、他5種類は最大2/255・超過0です。1.21.5のGPU textureを描画前に252へclearし、manifest contextへ記録する修正だけで全7種類が誤差0になりました。既存のGUI原点基準を維持し、投影・位置の変更、PNG保存後のRGB補正、許容値や輪郭例外の追加は行いません。1.21.6以降のDeferredRendererへ照明変更は適用しません。

QAはproduction renderer・canonical hook・PngOutputを含まず、通常フレーム末尾の画面操作・バニラの非同期Screenshotを使用します。画像callbackを受け取るまでスロットを維持し、保存・close後に次IDへ進みます。1.21.5ではCPU lightmap画像が存在しないため、GlTextureからGPU texelを読みます。対象stackのItemStackRenderState.render入口ではshader texture番号2が0でしたが、後続flushでGlRenderPass.bindSamplerへ指定されたSampler2はLightTextureと同じtexture IDでした。入口だけで照明が未使用と判断しません。bindSamplerへの指定を観測した結果で、全draw内部状態の保証ではありません。GUI入口・samplerの値をID切替時にnullへ戻し、前IDの観測で欠測を通過させる検出漏れを反証で修正した後、最終World撮影を再実行しました。ASMによる観測があるため完全な無計装clientではありません。

初回QAは照明のGL読戻し後にnative SIGSEGVを記録し、不完全な修正試行も同じ失敗でした。公式GlCommandEncoderのScreenshot転送がPACK_ROW_LENGTHを画面幅に設定して残す実装を確認しました。PIXEL_PACK_BUFFER、PACK_ALIGNMENT/ROW_LENGTH/SKIP_ROWS/SKIP_PIXELSを保存し、16×16の読み取り用に正規化してから全状態を復帰することで撮影が完了しました。最終試験では読戻し前ROW_LENGTH=1280と復帰成功を確認しました。異常後に所有containerだけをstopした2試行のexit137・途中capture・log・実行時sourceを保全し、productionの描画画像FAILや正常終了成功と混同しません。pack修正後の照明変更前Worldはexit0・画像FAIL、照明変更後の中間/最終Worldはexit0・誤差0です。

64px全1,395 PNGの登録集合・hash/CRC・RGBA8/sRGB・透過・空画像監査と、別CLI・ゲームプロセスでの独立全件反復1,395 PNGが成功しました。独立代表一括7枚と各ID別CLI・ゲームプロセス7枚は全件へ、全件反復は初回全件へ、decoded RGBA・PNG hash・パス・source・settings/digest・context・renderer・catalogが厳密一致しました。全IDの個別生成ではありません。16/512px・2ジョブ14枚は専用cacheでPNG監査とworker exit0・強制終了なしを確認し、World画像比較とは区別します。

初回単品試験はstoneが成功し、続くdiamondがready/PNG前のWORKER_EXITEDで終了しました。worker logにはUnknownHostExceptionとstdout broken pipeがありますが、根本原因は未確定で、このログだけから因果を断定しません。初回失敗を保全し、同条件・同workerの7件を明示的に再試験して全件と厳密一致しました。自動retryや例外抑制は実装しておらず、起動障害の解消を証明する結果ではありません。

隣接adapterの1.21.4・1.21.6の回帰14 PNGは旧workerとRGBA・PNG hash・パス・source、agent以外のsettings、context・renderer・catalogが厳密一致しました。既存QA経路の1.21.4 World7比較は誤差0です。1.21.6のWorld対応・受入はこのIssueに含めません。入力/sidecar606ファイル×3cacheのhashは不変、JUnit64件・CLI installDist・Java8対象QA compile・Python構文・差分確認に成功しました。

[数値・入力/worker/helper hash・初回失敗](../qa/validation-2026-10-13-gpu-immediate-world.json)を公開し、JAR・assets・World・画像・個人パスはprivateに保持します。照明/context・worker/settingsDigestが変わるため、照明を変更していない版を含め以前のiconsは新しい出力先へ再生成してください。raw出力は不変です。全モデル・動的状態・全サイズ・他GPUのWorld受入、起動障害の原因特定・解消へ一般化せず、experimental/deterministic=false・verified版0を維持します。

既存cacheで版を準備した後、再現例は次のとおりです。Worldは単独client、終了検証は専用cacheを使用します。全件反復は別出力先で再生成し、各ID別比較は別CLI呼び出しを使ってaudit-export.pyへ全件manifestを渡します。

```sh
python3 scripts/verify-matrix.py --versions 1.21.5 --offline --out .local/gpu-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 1.21.5 --ids stone diamond grass_block oak_leaves chest player_head glass --compare .local/gpu-all --out .local/gpu-world/1.21.5 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-matrix.py --versions 1.21.5 --offline --out .local/gpu-repeat --compare .local/gpu-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-worker-lifecycle.py --versions 1.21.5 --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass --cache .local/size-cache --out .local/gpu-sizes --docker-image mcicon-render:0.1.0-preview.1
```

## #28: gui-deferredの通常World照明

起点は#27のmerge commit 0f1aaf898a2bc18dc0d21fb209e2490a14dd0cef、前提#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。exact 9版（1.21.6・1.21.7・1.21.8・1.21.9・1.21.10・1.21.11・26.1・26.1.1・26.1.2）のstone・diamond・grass_block・oak_leaves・chest・player_head・glassを、Linux amd64・固定Mesa llvmpipe Dockerで検証しました。通常flat World、保存seed0・minecraft:flat・SURVIVAL、window1280×960、GUI scale4、slot9の実座標(80,121)、count1・既定stack・overlayなし・64pxです。最終63比較は最大channel誤差0、許容2/255超過0、輪郭例外0でした。26.1系ではworld生成設定がlevel.datからdata/minecraft/world_gen_settings.datへ分かれるため、両保存ファイルを読んで条件を確認し、hashを保持します。

変更前の1.21.6はworldなし初期白255と通常GUIのfull-bright252が異なり、最大3/255の色差がありました。1.21系6版のLightTexture.getTextureViewが指すGPU textureを描画前に252へ初期化し、manifestへlightmapFullBrightRgbを記録します。PNG保存後の色補正やQA側の照明変更は行いません。26.1系3版の通常GUIは独立したUiLightmapの1×1白255を使用し、実使用GPU texelとCPU画像が一致します。productionの既存照明を維持して実画像へ一致したため、26.1系へ252を適用しません。確認した変更前後の1.21.6/1.21.11の各7画像はalpha値・透過maskが不変で、26.1.2の7画像はRGBAが不変でした。全9版でproduction原点のPNGが実インベントリ位置(80,121)と一致し、GUI投影基準やguiReferenceは変更しません。

独立QAはproduction renderer・canonical hook・PngOutputを含まず、通常フレーム末尾の画面操作とバニラの非同期Screenshotを使います。画像callbackを受け取るまでスロットを維持し、保存・close後に次IDへ進みます。ItemModelResolver.updateForTopItemのstackがslot9のstackと同じobjectであることを照合し、そのstateのatlasモデル描画中にSampler2へ指定されたGpuTextureViewを観測します。1.21系はGuiRenderer.renderItemToAtlas、26.1系はGuiItemAtlas.drawToSlotを対象とし、名前とdescriptorを厳密に限定します。bindSamplerまたはbindTextureで観測したtexture IDと、LightTextureまたはUiLightmapのtexture IDが一致することを確認します。CPU画像が存在しない1.21系ではGPU値だけを記録し、26.1系のLevel照明と実使用GUI照明を分けます。指定されたsamplerの観測であり、全draw内部状態の保証ではありません。ASM計装を使うため完全な無計装clientではありません。GL読み戻しではpack buffer・alignment・row length・skip状態とtexture bindingを復帰し、Screenshot後のrow length1280の残存にも対応します。

初回のresolver戻り点計装は空のstack map frameでlocal変数を読んでVerifyErrorになり、入口計装へ修正しました。26.1.2のopenFresh引数がScreenからRunnableへ、1.21.11のgetGameTime所有型がLevelからLevelAccessorへ変わったQA APIエラーも失敗として保持します。反証では難読化名a・5引数がatlasモデル描画とblitで共通する点を指摘され、descriptorを厳密化した後に9版のWorldを最終再撮影しました。途中の成功と修正後の最終成功を分け、初回のnative失敗や変更前画像FAILを成功へ書き換えません。

最終workerで全件13,237 PNGと独立反復13,237 PNGを監査し、登録母集団・除外理由・ID/パス重複・CRC/hash・RGBA8・透過・空画像を確認しました。独立全件反復、代表一括63枚、各IDを別CLI・ゲームプロセスで生成した63枚は、decoded RGBA・PNG hash・パス・source・settings/digest・context・renderer・catalogへ厳密一致しました。16/512pxの126枚はPNG監査とworker終了0・強制終了なしの確認で、World画質受入ではありません。隣接1.21.5/26.2の14枚は旧workerへ厳密一致（agent hash/settingsDigest差を除く）、1.21.5の独立Worldハーネス回帰7比較も誤差0でした。JUnit64件・build/installDistが成功しています。

[数値・入力/worker/helper hash・初回失敗](../qa/validation-2026-10-13-deferred-world.json)を公開し、JAR・assets・World・画像・個人パスはprivateに保持します。worker/settingsDigestが変わるため、照明を変更しない版を含め以前のiconsは新しい出力先へ再生成してください。raw出力は不変です。全モデル・動的状態・全サイズ・他GPUへ一般化せず、experimental/deterministic=false・verified版0を維持します。


再実行例です。World撮影は`--version`を各exact版へ変えて9回実行し、比較元はその版の64px全件出力を指定します。各ID別の比較は、同じ全件基準へIDを1つずつ指定した別CLI起動で行います。サイズ検証用cacheは他ジョブのcacheから分離してください。

```sh
python3 scripts/verify-matrix.py --versions 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.1.1 26.1.2 --offline --out .local/deferred-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 26.1.2 --ids stone diamond grass_block oak_leaves chest player_head glass --compare .local/deferred-all --out .local/deferred-world/26.1.2 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-matrix.py --versions 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.1.1 26.1.2 --offline --out .local/deferred-repeat --compare .local/deferred-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-worker-lifecycle.py --versions 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.1.1 26.1.2 --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass --cache .local/size-cache --out .local/deferred-sizes --docker-image mcicon-render:0.1.0-preview.1
```


## Issue #33の一過性準備IO_FAILED調査（2026-10-13）

#13のmerge結果f6f0b82を含む最新main、6f38fb902a7cea1b6b977876244a7c4415c6408dを起点に調査しました。10/13時点の結論は**未再現・根本原因未確定**でした。[数値記録](../qa/validation-2026-10-13-prepare-io.json)に初回障害、QAの別障害、修正後の結果を分けて保存します。10/14の本番再現・原因機構・修正は次節に記録します。

過去chatの実行コマンド・初回stderrから、初回はofflineの16px/512pxの2ジョブが同じcacheを並行使用していたと確認しました。公開記録には1.13/16pxの準備IO_FAILEDと再試験成功が残りますが、根本例外と失敗時cache/input snapshotは残っていません。個別ログ・結果は再実行で置き換わっており、並行実行との因果関係は確定できません。表示された「prepare」はruntime準備だけでなくworker起動/catalog取得にも先行するため、失敗した個別段階は不明です。#13のWindows JSON/catalog・metadata転送問題とも同一視しません。

AssetStoreの入力単位FileLock・hash/size検証・checksum確定、LegacyMappingsのatomic確定、RuntimePreparerのnative展開・asset並行取得、FilesSupportのpart/moveを確認しました。本番準備処理に根拠のないretry・例外抑制・lock変更は加えていません。

専用cacheのoffline試験で、既存QAの準備後native列挙が3ラウンド目にNoSuchFileExceptionで失敗しました。Files.walkはfilterより前に属性を取得するため、他JVMが確定して消失した`.mcicon-*.part`をfilterでは除外できません。QAをwalkFileTreeへ変更し、ツール専用partの消失だけを許容しました。確定ファイルの消失や他のI/O例外は引き続き失敗です。これは準備成功後のQA障害であり、元のIO_FAILEDの原因特定として扱いません。

修正後の独立JVM準備は2並列×10回、4並列×10回の計60回成功しました。各系列の基準準備2回を別に数え、各ラウンドの全確定ファイルhash・準備結果が一致、終了後の残留partは0でした。macOS 15.7.7/arm64・Java21.0.9で、1.13の入力277件を既存cacheから専用cacheへ複製し、派生ファイルを準備しています。この60回はオンライン取得・他OS・disk fullの保証には広げません。別調査CLIと時間帯が重なる可能性のある探索系列40回は受入60回から除外して保全し、4並列系列は別の専用cacheで実行しました。

#13の固定Mesa imageで1.13の16px/512pxを同じ専用cacheから同時生成し、stone/diamond/chestの計6PNGが透過・CRC/hash・RGBA8・サイズ等の監査に成功しました。native検証の最初の2ジョブはコマンドのworker JAR指定誤りでWORKER_NOT_FOUNDとなり、そのログを保全して正しいパスで再実行しました。準備IO_FAILEDと区別します。JUnit64件・ビルドとQA回帰4件が成功。cacheを通常ファイルにした合成失敗ではexit1、根本FileSystemExceptionとreportが残ることも確認しました。

verify-matrix.pyはQAのCLI呼び出しに常時`--debug`を付け、同じ出力先で再試験する前に既存のresult/cli log/auditを`reports/<version>-previous-*/`へ保存します。通常ファイルcacheの合成失敗2回で、初回根本例外と元ログの保持を確認しました。変更後の通常matrixによる1.13/16px・3PNG生成も成功しています。

初回coreとSHA-256が一致するprivate snapshotも確認できました。AssetStore/RuntimePreparer/FilesSupport/LegacyMappings/VersionServiceは現coreとbytecodeが一致します。このcoreと、並行512px成功時のmanifestに一致するworker/imageでofflineの16px/512pxを最大2並列・3ラウンド再試験しました。6ジョブがruntime準備・worker起動・catalog取得・exportを通過し、36PNGが当時の各サイズの成功基準へRGBA/パス一致しました。当日の初期環境出力でhostはTemurin 21.0.9+10/arm64と確認できましたが、失敗した各CLIプロセスの実効環境と初回失敗時cacheは未保存であり、完全に同一の環境を再現したとは扱いません。

追加探索として現core/当時coreの公式HTTPS更新を含む準備も各2並列×4回と各基準1回で成功しました。これは初回offline障害の再現試験とは区別します。

基準準備後の並行試験に加え、当時coreによる初回同時作成も調べました。277件の元入力とchecksumだけでmapping/nativeの派生がない554ファイルのcache、さらにmanifest検証用1件を除くchecksumもない278ファイルのcacheをそれぞれ新規作成し、基準準備なしで4独立JVM×2ラウンドずつ、計16回を実行しました。全準備結果・終了後575確定ファイルhashが既存基準に一致し、残留partは0でした。過去の失敗時cache自体を再現したものではありません。

当時のcacheに残る526 workerディレクトリも調べました。初回開始17:44:14.641 JSTからの時間帯に1.13のworkerは17:44:15.929作成の1つだけで、並行成功512pxと時系列上整合します。16px再試験のworkerは17:52:52.617でした。当時coreもworkerディレクトリ自体を削除しないため、16pxが作成前に失敗した可能性を支持しますが、外部削除や失敗時の別cache状態は否定できません。作成前にはruntime準備のほかagent読み込み・Docker image検査もあり、準備処理だけに限定しません。現存する入力9,005ファイルのbirth/mtimeに17:43..17:45の一致はありませんでしたが、後の置換・削除まで復元できず、当時の入力整合性を証明しません。

当時のtool出力から、再試験前に採取した1.13のCLI標準出力は空で、stderrはprepareとIO_FAILEDの2行だけだったと再確認しました。JDKの起動補助プロセスの警告もありません。当時の配布物再ビルドは初回失敗より後で、失敗と重なるcache削除操作は保存済み操作記録では見つかりませんでした。当時coreのworker作成前処理（agent読込・Docker image検査）を2独立JVM×32回、計64回実行し、全成功・agent/image hash一致でした。これは起動前処理の追加探索であり、初回原因の否定や解消ではありません。

初回時刻の30秒間についてmacOSのjava/docker/sandboxd関連保存ログを照会しましたが記録0件でした。保存済みクラッシュレポートにもその時刻のjava/jspawnhelper/docker障害はなく、利用可能なローカルsnapshotはOS更新用だけでした。ログの保持期間や記録対象は初回当時の状態を保証せず、OS・起動失敗を否定する証拠にはしません。原cause chainを回収できる未確認の保存記録は見つかっていません。

IO_FAILEDはIOExceptionの証拠でもありません。asset objectsの並列取得ではFuture.getが実行時例外をExecutionExceptionに包み、MCIconGeneratorがgeneric IO_FAILEDへ変換します。当時CLI/coreでasset objectを1件欠落させた場合とDocker実行ファイルがない場合の制御実験は、いずれもexit1・「prepare 1.13 0/1 → IO_FAILED」となり、workerディレクトリ作成前に失敗しました。当時CLIにはdebugオプションがないため、各制御条件のcauseは同じ当時coreを現CLI経由で別に採取し、OFFLINE_CACHE_MISSとプロセス起動IOExceptionを区別しました。最初の当時CLIへのdebug指定は引数エラーとして保全し、成功した診断実験から除外します。これらは異なる原因で初回表示が一致することの確認であり、初回事象の原因として採用しません。再試験回数を増やしても、消失した初回cause chainを復元する証拠にはなりません。

並行QAは配布物をsnapshotし、最大同時数・回数・timeoutを固定します。各JVMの開始時刻・終了コード・JSON・例外stack、OS/Java・binary/probe hash、基準前と各ラウンド前後のcache hash/partを指定出力先へ保持します。既定はofflineで、`--online`は公式HTTPSから更新/不足入力を取得します。保存済み配布物は`--cli <distribution>/bin/mcicon`で指定できます。状態採取はJVM終了後であり、失敗瞬間のpart状態を完全に記録するものではありません。失敗またはtimeoutで後続ラウンドを止め、試験結果を成功へ書き換えません。出力にはprivateな絶対パス・入力情報があるため、公開リポジトリへ含めません。

```sh
# CLIを事前buildし、1.13の検証済み入力を専用cacheへ複製してから実行する。
# Pythonは3.11以上、Java/Javacは21以上。新しい出力先を指定する。
python3 scripts/verify-concurrent-prepare.py --cache .local/prepare-cache \
  --out .local/prepare-io-2 --version 1.13 --rounds 10 --concurrency 2 --timeout 120
python3 scripts/test-concurrent-prepare.py
```

再発時は初回からCLIの`--debug`を有効にし、再試験を別出力先へ保存してください。根本例外のcause、操作・同時数・開始終了時刻、OS/Java、入力/core/worker/QA/image hashを保存し、cacheの他利用JVMが終了した後に確定hashとpart状態を採取します。取得・checksum・mapping/native確定のどこで失敗したかを例外から特定し、その発生条件に対応した回帰を追加する必要があります。初回証拠を失った今回の再試験成功だけでは原因確定としません。

## Issue #33の並行asset取得lock障害の再現・修正（2026-10-14）

当時CLI/core/worker/imageで、初回の9版連続・16px/512pxの2系列を専用cacheへ復元しました。取得した6,161入力/派生ファイルは元cacheとbytes一致を確認し、JFRの例外記録を有効にしています。最初の1.13/16pxは1.29秒で初回と同じ「prepare 1.13 0/1 → IO_FAILED」、並行512pxは成功しました。失敗で後続を止め、根本IOException `Resource deadlock avoided`を15件採取しました。stackは`FileChannel.lock → AssetStore.obtain → RuntimePreparer`のasset取得スレッドを示し、Dockerプロセス起動前の失敗です。[数値・hash記録](../qa/validation-2026-10-14-asset-lock.json)に保存します。

macOSのPOSIX record lockはプロセス単位です。P1のスレッド1がAを保持しスレッド2がBを待ち、P2のスレッド1がBを保持しスレッド2がAを要求すると、保持スレッドが独立して進行できる場合でもXNUの循環検出がEDEADLKを返します。[Apple XNU](https://raw.githubusercontent.com/apple-oss-distributions/xnu/main/bsd/kern/kern_lockf.c)と[OpenJDK 21.0.9のfcntl呼び出し](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.9%2B10/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c)を確認しました。IOExceptionはasset taskのUncheckedIOException、Future.getのExecutionExceptionを経てIO_FAILEDになります。

AssetStoreの取得は同一キーのJVM内monitor・単一channel・ファイル単位の排他を維持し、tryLockが競合を返した場合だけ10ms待機する方式へ変更しました。OSのblocking待機queueに入らず、並列取得と検証・atomic確定を保持します。I/O例外は伝播し、取得待ちの各試行前に取消を確認します。sleep中断ではinterruptを復帰して失敗させ、channelを閉じます。公開API・cache形式は変更していません。

制御した2 JVM・各2スレッドでも、当時coreは4取得中3件成功・EDEADLK1件、修正版は4件成功・0件でした。input/checksum一致・part0を確認しています。待機開始通知はfixtureでJVM stack上のlock待機またはsleepを観測した後に出し、試行前通知の懸念を解消しました。交差待機、外部lock中の取消、interrupt保持と次回取得の3回帰を含むJUnit67件・Python QA4件・ビルドが成功し、重点反証で公開を妨げる問題はありませんでした。

当時core JARのAssetStore.classだけを置換し、他の全JAR entryとCLI/worker/imageを保持した比較でも、9版×16/512pxの18ジョブ・108PNGすべてが監査と旧成功基準とのRGBA/パス比較に成功しました。6,161確定ファイルhashは試験前後で不変、part0、JFRの該当例外は0件でした。

修正した正式CLI配布物と#13の固定Mesa imageでも、1.13/16px・512pxの2並列で計6PNGが監査と既存成功基準へのRGBA/パス比較に成功しました。描画workerはLinux amd64、hostのlock検証はmacOSです。

原初回のcause chain自体は未保存です。同じ当時binary・ジョブ構成で再現した障害の原因機構を特定し、制御再現と1クラス置換比較で修正を検証していますが、失われた初回例外の歴史的同一性を直接証明したとは扱いません。JFRの観測負荷はタイミングを変え、hostのWindows/Linux file lock実機検証は未実施です。

## #29: gui-26.2の通常World照明

起点は#28のmerge commit 82d529f023c1e44ad8eb2cbbe57fbdf3af483cbe、前提#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。exact release 26.2のstone・diamond・grass_block・oak_leaves・chest・player_head・glassを、Linux amd64・固定Mesa llvmpipe Dockerで検証しました。通常flat World、保存seed0・minecraft:flat・SURVIVAL、window1280×960、GUI scale4、slot9の実座標(80,121)、count1・既定stack・overlayなし・64pxです。最終7比較は最大channel誤差0、許容2/255超過0、輪郭例外0でした。level.datとdata/minecraft/world_gen_settings.datを読んで保存条件を確認し、両hashを保持します。

実使用GUIはUiLightmapの1×1白255を使用し、CPU画像とGPU texelが一致しました。通常Level照明は別に観測し、GUIのatlas描画中にSampler2へ指定されたtextureと、宣言されたUiLightmapのtexture IDが一致することを確認します。productionの既存原点PNGが実インベントリ位置(80,121)へ一致したため、描画・照明・固定context・PNG復元・比較許容値を変更しません。公開API、manifest/context、worker/settingsDigestも不変で、このQA拡張による既存出力の再生成は不要です。

独立QAはproduction renderer・canonical hook・PngOutputを含まず、通常フレーム末尾の画面操作とバニラの非同期Screenshotを使います。ItemModelResolverの対象stackとslot9のstackを同一性で照合し、そのstateのGuiItemAtlas描画中にSampler2を観測します。resolver/atlasの名前とdescriptorを厳密に限定し、撮影callbackまでスロットを維持してGL pack状態を復帰します。ASMによる観測があるため完全な無計装clientではなく、全draw内部状態の保証ではありません。

初回QAはMinecraft.screenのNoSuchFieldExceptionで終了しました。26.2の画面・overlay・setScreenはGuiへ移動しているため、その版だけ取得・操作経路を変更しました。次のQAはMinecraft.getMainRenderTargetのNoSuchMethodExceptionで終了し、GameRenderer.mainRenderTargetへ対応しました。private field取得での中間7比較は誤差0でしたが、公式public accessorへ変更して最終Worldを再撮影し、現在sourceの成功と区別します。初回2失敗のlog・report・source/agent hashを保持し、描画PNGの失敗や成功へ書き換えません。Gradleの初回起動はsandboxのローカルsocket制約で失敗し、権限を付けた同じoffline buildで成功しました。

全1,536 PNGと独立全件反復1,536 PNGを監査し、登録母集団・除外理由・ID/パス重複・CRC/hash・RGBA8・透過・空画像を確認しました。全件反復、代表一括7枚、各IDを別CLI・ゲームプロセスで生成した7枚は、decoded RGBA・PNG hash・パス・source・settings/digest・context・renderer・catalogへ厳密一致しました。全IDの個別生成ではありません。16/512pxの14枚はPNG監査とworker終了0・強制終了なしの確認で、World画質受入ではありません。26.1.2の回帰7枚は#28のworker出力へ厳密一致し、変更後QAの独立World7比較も誤差0でした。JUnit64件・build/installDistが成功しています。

[数値・入力/worker/helper hash・初回失敗](../qa/validation-2026-10-13-latest-world.json)を公開し、JAR・assets・World・画像・個人パスはprivateに保持します。全モデル・動的状態・全サイズ・他GPUへ一般化せず、experimental/deterministic=false・verified版0を維持します。

再実行例です。各ID別比較はIDを1つずつ指定した別CLI起動で行い、サイズ検証のcacheを他ジョブから分離してください。

```sh
python3 scripts/verify-matrix.py --versions 26.2 --offline --out .local/latest-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 26.2 --ids stone diamond grass_block oak_leaves chest player_head glass --compare .local/latest-all --out .local/latest-world/26.2 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-matrix.py --versions 26.2 --offline --out .local/latest-repeat --compare .local/latest-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-worker-lifecycle.py --versions 26.2 --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass --cache .local/size-cache --out .local/latest-sizes --docker-image mcicon-render:0.1.0-preview.1
```

## #30: gui-26.3の通常World照明

起点は#29のmerge commit 6f38fb902a7cea1b6b977876244a7c4415c6408d、前提#13はf6f0b82e22aca3ee3b374aec167e432b96caf55aです。exact release 26.3のstone・diamond・grass_block・oak_leaves・chest・player_head・glassをLinux amd64・固定Mesa llvmpipe Dockerで検証しました。通常flat World、保存seed0・SURVIVAL、window1280×960、GUI scale4、slot9の実座標(80,121)、count1・既定stack・overlayなし・64pxです。

RenderPearlへ移動したGpuTexture/GpuTextureView/GlTextureとFrontendRenderPass.setUniformに独立QAを対応させました。26.2と同じGuiのscreen/overlay/setScreen、GameRenderer.mainRenderTargetを使い、26.3の起動にSDL_VIDEO_FORCE_EGL=1を指定します。ItemModelResolverの対象stackとslot9を同一性で照合し、そのstateのGuiItemAtlas.drawToSlot中にSampler2へ指定されるtextureを読みます。バニラの非同期Screenshotを使用し、描画・照明初期化・canonical hook・PNG復元をproductionと共有しません。ASM観測を行うため完全な無計装clientではなく、全draw内部状態の保証ではありません。

独立Worldの7比較はすべて最大channel誤差0、許容2/255超過0、輪郭例外0でした。実使用GUIはUiLightmapの1×1白255で、CPU画像とGPU texelが一致し、Sampler2と宣言されたUiLightmapのtexture IDも一致します。通常Level照明は別に読み取ります。GL pack buffer/row length等を読み取り後に復帰します。初回の独立撮影から成功しており、照明・位置による差や欠落IDはありません。productionの既存原点PNGが実インベントリ位置へ一致したため、描画・照明・固定context・画像許容を変更しません。公開API、manifest/context、worker/settingsDigestは不変で、このQA拡張による既存出力の再生成は不要です。

全1,657 PNGと独立全件反復1,657 PNGを監査し、登録母集団・除外理由・ID/パス重複・CRC/hash・RGBA8・透過・空画像を確認しました。全件反復と各IDを別CLI・ゲームプロセスで生成した7枚は、decoded RGBA・PNG hash・パス・source・settings/digest・context・environment・renderer・catalogへ厳密一致しました。全IDの個別生成ではありません。16/512pxの14枚はPNG監査とworker終了0・強制終了なしの確認で、World画質受入ではありません。26.2の回帰7枚は#29の全件結果へ厳密一致し、変更後QAの独立World7比較も誤差0でした。JUnit64件・Python検査33件・offline build/installDistが成功しています。テスト検出0件と配布候補試験の引数不足は検証セットアップとして記録し、個別実行した検査成功や対象外の配布試験と区別します。差分と対象stack/state同一性、観測範囲、sampler overload、GL pack復帰、production除外、hashと比較条件を反証し、追加の問題はありませんでした。

[数値・入力/worker/helper/image hash・初回撮影と回帰](../qa/validation-2026-10-13-pearl-world.json)を公開し、JAR・assets・World・スクリーンショット・個人パスはprivateに保持します。通常Worldの保存seed0・minecraft:flat・SURVIVALはlevel.datとdata/minecraft/world_gen_settings.datを読んで確認し、両hashを保持しました。全モデル・動的状態・全サイズ・他GPUへ一般化せず、experimental/deterministic=false・verified版0を維持します。

再実行例です。各ID別比較はIDを1つずつ指定した別CLI起動で行い、サイズ検証のcacheを他ジョブから分離してください。

```sh
python3 scripts/verify-matrix.py --versions 26.3 --offline --out .local/pearl-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-world-inventory.py --version 26.3 --ids stone diamond grass_block oak_leaves chest player_head glass --compare .local/pearl-all --out .local/pearl-world/26.3 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-matrix.py --versions 26.3 --offline --out .local/pearl-repeat --compare .local/pearl-all --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-worker-lifecycle.py --versions 26.3 --sizes 16 512 --ids stone diamond grass_block oak_leaves chest player_head glass --cache .local/size-cache --out .local/pearl-sizes --docker-image mcicon-render:0.1.0-preview.1
```

## Issue #31の無計装client基準（2026-10-14）

Linux amd64・固定Mesa imageで、1.20.6のstone・player_head・chest・glass・grass_blockをagent無しclientのF2画像と独立照合しました。1280×960、GUI scale 4、通常InventoryScreenのslot 9、GUI座標(80,121)、count 1、components追加なしの既定stack、64pxです。各IDを2回撮影した10比較は、受入済みの通常World QAのslot RGBAと完全一致し、現workerのPNGを空slot背景に再合成した比較も最大channel誤差0でした。empty画像2回も既存QAと完全一致しました。

clientは元client JARと公式libraryだけで起動します。`-javaagent`・agent用classpath・時刻固定・描画置換・frame-zero resource packは使用しません。別JVMのAWT Robotが通常メニュー、vanilla command、E、F2を操作します。実clientの`/proc/1/cmdline`・環境・mapsをprivateに採取し、agent引数とlibinstrument/libattachがないことを確認します。controllerのclasspathはDesktopInputだけで、ゲームクラス・production rendererへアクセスしません。元saveは既存QAが生成したseed 0のflatワールドをコピーしたもので、ワールド生成自体を無計装と主張するものではありません。今回の保存結果でもseed 0・survivalを確認し、Time/DayTimeは進行していました。

`/clear`の後、`/item replace entity @s inventory.0 with minecraft:<id>`で対象を置きます。各送信直前のlog文字offset以降でclearと該当IDの置換成功を確認し、F2の静的slotが既存QAと一致しなければ停止します。公式bytecodeでは数量省略のItemCommands分岐が`createItemStack(1,false)`を呼び、components未指定のItemInputとItemのgetDefaultInstanceが既定componentsのItemStackを作ります。PlayerHeadItem/BlockItemはgetDefaultInstanceをoverrideしません。最後の保存InventoryにもSlot 9・count 1・components追加なしを確認しました。この根拠を、potion等のgetDefaultInstance overrideを持つアイテムへ一般化しません。

初期の外部ハーネスでは、join log直後のclear入力が取りこぼされ、emptyラベルの画像に元のglassが残りました。capture自体は終了0でしたが、画質受入はFAILです。元画像・log・当時のハーネスhashを保全し、command成功確認とempty/静的slot比較を加え、別出力先で再試験しました。最初のprototypeのallowCommands前提assert失敗、start.sh未生成の起動試行、Gradleのsandbox socket失敗も撮影・テスト成功へ数えていません。

1.21.4ではdiamond・clock・compass・recovery_compass・enchanted_book・sea_lanternを各2回、同じ外部方式で撮影しました。emptyとdiamondは通常World QAと一致し、diamondの生成PNGとの2比較も誤差0でした。残る5種類のraw差は診断として保存し、固定条件と異なるため画質受入には使いません。

| 代表状態 | fixed-v1との比較条件 |
|---|---|
| diamond | 静的generated model、既定stack、同じGUI条件で比較できる。今回の2比較は誤差0。 |
| clock | 通常Worldの時刻・entity・針のwobbleと、entity無しの向き0（clock_00）は異なる。対象外。 |
| compass | spawn位置、entityの向き、針のwobbleと、entity無しの向き0（compass_16）は異なる。対象外。 |
| recovery_compass | 死亡位置・entityと、対象がないときの乱数/wobbleを使う。entity無しのrecovery_compass_16と同条件を保証しない。対象外。 |
| enchanted_book | 既定のenchantment_glint_override=true。通常GUIのglint経過時刻はfixed-v1の1000msと一致を保証しない。対象外。 |
| sea_lantern | 公式metadataはframetime 5。通常atlasの更新frameとfixed-v1の初期frameは一致を保証しない。対象外。 |

対象外の一部が偶然同じ画素になっても、比較可能へ変更しません。全モデル・全状態・他GPU・他版・他サイズへ自動一般化せず、公開の状態指定APIも追加しません。glassはbinary alphaなので、sRGB・半透明・glintの全般的な受入は継続します。experimental・deterministic=false・verified 0版を維持します。productionコード・worker・PNG形式は変更しておらず、既存PNGの再生成は不要です。

JUnit 67件とビルドが成功しました。入力・binary・外部ハーネス・無計装/通常QA画像のhash、command確認、raw比較、分類、初回失敗は[数値記録](../qa/validation-2026-10-14-uninstrumented-client.json)に保存します。元JAR・save・game画像・process情報・生logはprivateに保持します。

再実行には同じ版の受入済みWorld QA出力と、flat・survival・Commands有効の保存済みワールドを専用ディレクトリへ用意します。元saveは変更せず、新規出力先へコピーします。現在の英語UIと1280×960に限定した操作です。通常撮影の終了だけではPNGの画質合格とはせず、静的slot照合と別途の生成PNG再合成比較も確認してください。

```sh
python3 scripts/verify-uninstrumented-inventory.py --version 1.20.6 \
  --cache .local/cache --world-save '.local/qa-1.20.6/game/saves/MCIcon QA' \
  --qa-reference .local/qa-1.20.6 --out .local/uninstrumented-1.20.6 --samples 2
python3 scripts/verify-uninstrumented-inventory.py --version 1.21.4 \
  --cache .local/cache --world-save '.local/qa-1.21.4/game/saves/MCIcon QA' \
  --qa-reference .local/qa-1.21.4 --out .local/uninstrumented-1.21.4 --samples 2
```
