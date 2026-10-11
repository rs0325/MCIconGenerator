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

## 未完了の受入検証

- 一過性の準備IO_FAILEDの原因特定、全IDの個別/all一致、1.21.4以外の照明基準、完全な無計装clientの基準画像、通常ワールド比較の対象拡張、glint・時計/コンパス・animated textureの意味的な比較。
- sRGB・半透明・alpha復元の色精度、missing texture/modelの完全検出。
- Windows/Linux host、Docker Desktop、native、ARM64 image。
- daemon障害・書き込み経路の競合など、今回のSIGKILL/ENOSPC/lock試験を超える障害受入。
- 1.21.4以外の版・別環境でのcold/warmとメモリ・cache容量の追加測定。

fixed-v1の固定処理を実装しても、受入が完了するまでdeterministic=falseと--allow-experimentalを維持します。マイルストーンは完了にしません。
