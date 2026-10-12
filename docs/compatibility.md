# 対応表

2026-10-10の公式manifestから固定した正式51版。全版のexact adapterを実装しています。rawは元JARのPNG抽出です。iconsはすべてexperimentalで、画質・再現性の受入を完了したverified版は0です。実行待ちと検査成功を区別します。新しい版はdiscoveredから個別に追加します。

legacy-1.13のexact 3版（1.13・1.13.1・1.13.2）は、通常flatワールドの静的7種類を64pxで追加照合し、各版とも最大channel誤差0でした。Linux amd64・固定Mesa Dockerのインベントリ位置(80,121)での結果です。描画・照明・固定contextを変更せず、verifiedへの昇格は行っていません。[条件と失敗を含む記録](validation.md#issue-22のlegacy-113通常ワールド検証2026-10-13)を参照してください。

| Version | Adapter | Java | 64px全件生成・PNG監査 | 独立全件のRGBA比較 |
| --- | --- | --- | --- | --- |
| 26.3 | gui-26.3 | 25 | 1,657 成功 / 0 失敗（passed） | 全件一致 |
| 26.2 | gui-26.2 | 25 | 1,536 成功 / 0 失敗（passed） | 全件一致 |
| 26.1.2 | gui-deferred | 25 | 1,505 成功 / 0 失敗（passed） | 全件一致 |
| 26.1.1 | gui-deferred | 25 | 1,505 成功 / 0 失敗（passed） | 全件一致 |
| 26.1 | gui-deferred | 25 | 1,505 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.11 | gui-deferred | 21 | 1,504 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.10 | gui-deferred | 21 | 1,487 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.9 | gui-deferred | 21 | 1,487 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.8 | gui-deferred | 21 | 1,415 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.7 | gui-deferred | 21 | 1,415 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.6 | gui-deferred | 21 | 1,414 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.5 | gui-1.21.5 | 21 | 1,395 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.4 | gui-1.21.4 | 21 | 1,384 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.3 | gui-1.21.4 | 21 | 1,374 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.2 | gui-1.21.4 | 21 | 1,374 成功 / 0 失敗（passed） | 全件一致 |
| 1.21.1 | gui-1.21.4 | 21 | 1,332 成功 / 0 失敗（passed） | 全件一致 |
| 1.21 | gui-1.21.4 | 21 | 1,332 成功 / 0 失敗（passed） | 全件一致 |
| 1.20.6 | gui-1.21.4 | 21 | 1,329 成功 / 0 失敗（passed） | 全件一致 |
| 1.20.5 | gui-1.21.4 | 21 | 1,329 成功 / 0 失敗（passed） | 全件一致 |
| 1.20.4 | gui-1.21.4 | 17 | 1,311 成功 / 0 失敗（passed） | 全件一致 |
| 1.20.3 | gui-1.21.4 | 17 | 1,311 成功 / 0 失敗（passed） | 全件一致 |
| 1.20.2 | gui-1.21.4 | 17 | 1,254 成功 / 0 失敗（passed） | 全件一致 |
| 1.20.1 | gui-1.21.4 | 17 | 1,254 成功 / 0 失敗（passed） | 全件一致 |
| 1.20 | gui-1.21.4 | 17 | 1,254 成功 / 0 失敗（passed） | 全件一致 |
| 1.19.4 | gui-classic | 17 | 1,227 成功 / 0 失敗（passed） | 全件一致 |
| 1.19.3 | gui-classic | 17 | 1,185 成功 / 0 失敗（passed） | 全件一致 |
| 1.19.2 | gui-classic | 17 | 1,151 成功 / 0 失敗（passed） | 全件一致 |
| 1.19.1 | gui-classic | 17 | 1,151 成功 / 0 失敗（passed） | 全件一致 |
| 1.19 | gui-classic | 17 | 1,151 成功 / 0 失敗（passed） | 全件一致 |
| 1.18.2 | gui-classic | 17 | 1,100 成功 / 0 失敗（passed） | 全件一致 |
| 1.18.1 | gui-classic | 17 | 1,100 成功 / 0 失敗（passed） | 全件一致 |
| 1.18 | gui-classic | 17 | 1,100 成功 / 0 失敗（passed） | 全件一致 |
| 1.17.1 | gui-classic | 17 | 1,099 成功 / 0 失敗（passed） | 全件一致 |
| 1.17 | gui-classic | 17 | 1,099 成功 / 0 失敗（passed） | 全件一致 |
| 1.16.5 | gui-fixed | 8 | 975 成功 / 0 失敗（passed） | 全件一致 |
| 1.16.4 | gui-fixed | 8 | 975 成功 / 0 失敗（passed） | 全件一致 |
| 1.16.3 | gui-fixed | 8 | 975 成功 / 0 失敗（passed） | 全件一致 |
| 1.16.2 | gui-fixed | 8 | 975 成功 / 0 失敗（passed） | 全件一致 |
| 1.16.1 | gui-fixed | 8 | 974 成功 / 0 失敗（passed） | 全件一致 |
| 1.16 | gui-fixed | 8 | 974 成功 / 0 失敗（passed） | 全件一致 |
| 1.15.2 | gui-fixed | 8 | 883 成功 / 0 失敗（passed） | 全件一致 |
| 1.15.1 | gui-fixed | 8 | 883 成功 / 0 失敗（passed） | 全件一致 |
| 1.15 | gui-fixed | 8 | 883 成功 / 0 失敗（passed） | 全件一致 |
| 1.14.4 | gui-fixed | 8 | 876 成功 / 0 失敗（passed） | 旧基準から特殊59件を修正 |
| 1.14.3 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） | 全件一致 |
| 1.14.2 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） | 全件一致 |
| 1.14.1 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） | 全件一致 |
| 1.14 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） | 全件一致 |
| 1.13.2 | legacy-1.13 | 8 | 789 成功 / 0 失敗（passed） | 全件一致 |
| 1.13.1 | legacy-1.13 | 8 | 789 成功 / 0 失敗（passed） | 全件一致 |
| 1.13 | legacy-1.13 | 8 | 784 成功 / 0 失敗（passed） | 全件一致 |

全件成功数はairを除く登録アイテム数です。no_item_formのブロックは別に記録します。実測値と入力hashは[初回記録](../qa/validation-2026-10-10.json)と[追加検証記録](../qa/validation-2026-10-10-followup.json)に保存します。独立全件比較はall/allの反復です。個別/all比較は21版・25代表IDの別検証で、全IDの個別比較とは区別します。

追加修正83d2382で、legacy-1.13 / legacy-1.14のBlockEntity dispatcherへTextureManagerを供給し、チェスト等にitem atlasが誤って使われる問題を修正しました。修正後の全51版・60,265枚を再生成・PNG監査し、さらに521302cのfallback観測を加えたworkerでも全件がdecoded RGBA・パス一致しました。[新workerの数値記録](../qa/validation-2026-10-10-resource-guard.json)に異なるagent hashと版別入力hashを保存します。表の独立反復と代表ID比較は以前のworkerの記録も含み、全IDの個別/all比較や画質・missing resource完全検出の受入とは区別します。画質の受入が完了するまでexperimentalを維持します。

1.14.4では同じ参照補完が抜けていたため、特殊59種類が直前のテクスチャへ依存していました。修正後の876件を監査し、影響59種類とwheatの個別指定・全件比較、および通常GUI fixtureの2背景比較が成功しました。以前の全件反復一致は画像の正しさを保証するものではありません。版別結果と対象IDは[1.14.4初期化の数値記録](../qa/validation-2026-10-11-classic-bootstrap.json)を参照してください。

iconsのPNGへsRGB Perceptualの指定を追加しました。最終workerで1.21.4全1,384枚と9描画境界版の代表54枚のdecoded RGBA・パスが以前の正しい基準と一致し、PNG bytes・hashはmetadata追加で変わります。通常GUIの6背景比較と既知RGBAの検証は[色・alphaの数値記録](../qa/validation-2026-10-11-alpha-srgb.json)を参照してください。全51版の全件再実行や全モデルの色空間受入とは区別します。

1.21.4の通常ワールドのInventoryScreenと、productionのbootstrap・固定化を共有しないQAで静的28種類を比較しました。変更前は生RGBで11種類が許容値を超え、worldなしlightmap初期値255と実ワールドのfull-bright値252の差を確認しました。実測lightmapを乗算した診断では全28種類が誤差2/255以内でした。補正診断を生比較の成功やcanonicalの受入完了とは扱いません。[変更前のワールド内比較記録](../qa/validation-2026-10-11-world-inventory.json)を参照してください。後続作業では実インベントリの照明を固定基準に選び、1.21.4だけ描画時に反映します。全版experimental/deterministic=falseを維持します。

この時点の確認はmacOS Apple Silicon＋Colima/Rosetta上のLinux amd64 containerです。後続のホスト検証は末尾のIssue #13の記録を参照してください。GitHub Actionsは使用しません。

1.21.4のlightmapを実インベントリのRGB252へ合わせた後、通常ワールドの静的28種類はPNGのRGB補正なしで全件成功（最大誤差1/255）しました。新workerの全1,384枚を監査し、1,382枚のRGBAが変更前から変わります。透明領域のmaskと出力パスは不変ですが、alphaには最大1/255の量子化差があります。以前のiconsは新しい出力先で再生成してください。他9境界版の代表54枚は既存RGBA・パスと一致しました。[照明変更後の数値記録](../qa/validation-2026-10-11-inventory-lighting.json)は以前の全51版記録と区別します。他版の照明、全ID・動的表現の独立受入は未完了です。

1.21.4の初期animation frameとglint時刻を指定した通常InventoryScreen比較56種類は、55種類が厳密比較に成功し、oak_fenceの輪郭2画素は不一致です。6サイズの54PNGは監査成功ですが、512pxの元テクスチャ整数拡大との比較5ケースは不一致で、画質受入は未完了です。描画の具体的な診断理由の保存と正常アイテムの継続はJUnit61件・実描画2ケースで確認しました。[固定条件・診断の数値記録](../qa/validation-2026-10-11-canonical-inventory.json)を参照してください。experimental/deterministic=falseを維持します。

ユーザー確認後、同じID・出力PNG・参照RGBA・背景RGBAのhashと2座標が一致する柵の画像だけを許容し、固定条件の通常InventoryScreen56比較は全件成功しました。他の画像へ許容を一般化しません。厳密比較では柵の2画素が不一致のままです。[輪郭判定の数値記録](../qa/validation-2026-10-11-inventory-edge.json)を参照してください。512pxの画質受入とexperimental/deterministic=falseは変更しません。

512pxの平面5種類の差は、比較計算がバニラのUV縮小を含めていなかったためでした。1.21.4の観測atlas寸法を入力にして公式UV式を計算すると、6サイズ×5種類の30ケースでRGB・alpha誤差0です。PNGや描画worker、許容誤差は変更していません。[UV比較の数値記録](../qa/validation-2026-10-11-inventory-uv.json)を参照してください。立体モデル全般・他版・別GPUの画質受入とは区別します。

Issue #6の1.21.4描画backendはPR #17でmainへmergeされ、Issueはcloseされました。Issue #8では1.21.4・64pxの全1,384IDを別々のCLI・ゲームプロセスで個別生成し、全件出力とdecoded RGBA・パスの厳密一致を確認しました。初回の起動WORKER_TIMEOUT 1件を保存し、同条件の単独再実行を初回基準へ照合しています。[全ID比較の数値記録](../qa/validation-2026-10-11-single-all.json)は他版・他サイズの受入とは区別します。全版/全model・他OS/GPU等の品質と起動障害の受入はIssue #13で継続し、experimental/deterministic=falseを維持します。

Issue #10では1.13〜1.21.3の正式38版・64pxの全41,056PNGを現workerで監査しました。代表26種類を版ごとに別のCLI・ゲームプロセスで再生成した988PNGは、全件基準とdecoded RGBA・PNG hash・パス、入力/設定/context/環境/catalogが一致しました。GUI原点(0,0)の2背景1,976比較は最大誤差2/255以内、元JARから計算した無着色diamondの38比較はRGB・alpha誤差0です。これは各IDごとの独立起動や全状態の受入とは区別します。

1.20.6のplayer_headはGUI位置(32,32)で33画素・最大78/255の色差を確認しました。ユーザーが画像を確認してこの位置差を許容し、#10を原点基準で受け入れました。当時は原因未確定で、位置(32,32)のraw FAILと比較許容値を維持しました。後続#13の位置・投影調査と新たな固定基準は末尾に記録します。[旧38版の数値記録](../qa/validation-2026-10-12-classic-adapter-acceptance.json)に版別入力・初回失敗とQA修正・最終helper回帰を保存し、全版experimental/deterministic=false・verified版0を維持します。

Issue #11では1.21.4〜26.3の正式13版・64pxの全19,209PNGを現workerで監査し、代表35種類の独立版別再生成455PNGが全件基準と厳密RGBA・PNG hash・パス、入力/設定/context/環境/renderer/catalog一致しました。原点GUIの共通35種類・2背景910比較と、銅のチェスト/ゴーレム像/装備・槍・straw_bed等の登録済み部分集合62比較は最大誤差2/255以内です。元JARのitem definitionsから計算した29比較はRGB・alpha誤差0でした。

1.21.11のIdentifier改名を選択できなかった初回QAの2背景失敗と前後helper hashを保存し、修正後の再比較と対照1.21.6の比較が成功しました。[新13版の数値記録](../qa/validation-2026-10-12-modern-adapter-acceptance.json)は初回12版の成功と修正後1.21.11の成功を集約し、初回のraw FAILは維持します。production描画は変更せず、全版experimental/deterministic=false・verified版0を維持します。任意のcomponents・全分岐/状態/サイズ・他位置・実ワールド照明・無計装client・別OS/GPU等の受入とは区別します。

## 正式51版の登録集合・欠損受入（2026-10-12）

修正後workerで固定正式51版・60,265PNGを新規生成し、登録母集団・除外理由・ID/パス重複・hash/CRC・RGBA8・透過・空画像の監査と、#10/#11の正常基準へのRGBA・PNG hash・パス比較が成功しました。1.21.11で破損stone PNGの代替画像を成功出力する検出漏れを、BakedQuadのposition/packedUV消費時のsprite観測で修正しました。選択された破損atlas/直接textureを各51版、モデル・チェスト・空モデル・事前ロード済み直接textureを各12境界版で受け入れました。150成功は複数実行の集約であり、初回失敗10ケースと各worker/helper hashを保存します。[検証範囲と再実行方法](validation.md)・[数値記録](../qa/validation-2026-10-12-release-coverage.json)を参照してください。

正常PNG bytes・RGBA・パスは不変ですが、worker hashとsettingsDigestは変わります。旧manifestへ再開せず、新しい出力先で再生成してください。全版experimental/deterministic=false・verified版0を維持し、全状態・サイズ・world・OS/GPUなどの受入は#13等で継続します。

## Issue #13のホスト・照明検証（2026-10-12）

描画backendはLinux amd64・Mesa llvmpipeのDockerに限定します。ホストJavaは21以上、CPUまたはエミュレーションにはSSE4.1が必要です。ホストnative/GPU描画、Linux ARM64 imageは未対応です。

| ホスト検証環境 | CLIとDocker | 1.13 / 1.20.6 / 1.21.4 / 26.3 |
| --- | --- | --- |
| macOS 15.7.7 / Apple Silicon | Java 21.0.9 / Colima・Rosetta / Docker 29.5.2 | raw 6,319・icons 5,154・反復96を生成・監査。1.13/1.21.4/26.3は旧基準RGBA一致、1.20.6は下記の新基準 |
| Colima Linux VM内のamd64 container | Java 21 / Docker 29.5.2 | raw 6,319・icons 5,154・反復96が成功。Mac全件RGBA・設定・renderer一致 |
| Windows 11 Pro build 26200 / AMD64 | Java 21.0.9 / WSL 3.0.1 / Docker Desktop 4.94.0、Engine 29.8.2 | raw 6,319・icons 5,154・反復96が成功。Mac全件RGBA・設定・renderer一致 |

対象exact versionは1.13/1.20.6/1.21.4/26.3です。最終binaryで確認し、未完了の他版World照明・色精度拡張・一過性障害調査はユーザー承認で別Issueへ分離します。Linuxの確認はローカルVM内のcontainerでJava CLIを動かす経路です。任意のLinuxディストリビューションやbare metalを検証したとは扱いません。比較用の同一image IDと公式入力でホスト差を確認し、独立反復ではrenderer・settingsDigestも一致させます。新しくbuildしたDocker imageや別Mesa/LLVM/CPUでのRGBA一致へ保証を広げません。

WindowsでJSON Linesの内部改行が残って描画がtimeoutになる不具合と、metadata/settings hashがCRLFに依存する不具合を修正しました。Mesaの128bit指定だけでは残るCPU最適化の量子化差を、SSE4.1指定と合わせて抑えます。以前のWindows出力と旧Docker imageのiconsは新しい出力先で再生成してください。

1.20.6のlightmapも実測RGB252へ合わせました。全1,329枚を監査し、変更前から1,327枚のRGBAが変わります。可視alpha mask・パスは不変、alpha量子化差は最大1/255です。保存した通常ワールド画像とのstone/diamond/grass_block/oak_leaves/chest比較は誤差0ですが、player_headの85画素・最大76/255の差は、GUI位置に伴うテクスチャ参照の変化として再現しました。ユーザー指定のGUI 320×240・インベントリ位置(80,121)へ投影とモデル位置を合わせ、再撮影した通常ワールドとの24種類比較が成功しました。headを含む20種類は誤差0、glint付き4種類は最大1/255（基準2/255以内）です。初回FAILは修正前の記録として保持します。最終binaryの4代表版で全件・反復と3ホスト比較が成功しました。以前に許容されたGUI(32,32)の33画素差とは別の記録です。全版experimental/deterministic=false・verified版0を維持します。
