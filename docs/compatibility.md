# 対応表

2026-10-10の公式manifestから固定した正式51版。全版のexact adapterを実装しています。rawは元JARのPNG抽出です。iconsはすべてexperimentalで、画質・再現性の受入を完了したverified版は0です。実行待ちと検査成功を区別します。新しい版はdiscoveredから個別に追加します。

legacy-1.13のexact 3版（1.13・1.13.1・1.13.2）は、通常flatワールドの静的7種類を64pxで追加照合し、各版とも最大channel誤差0でした。Linux amd64・固定Mesa Dockerのインベントリ位置(80,121)での結果です。描画・照明・固定contextを変更せず、verifiedへの昇格は行っていません。[条件と失敗を含む記録](validation.md#issue-22のlegacy-113通常ワールド検証2026-10-13)を参照してください。

legacy-1.14のexact 4版（1.14・1.14.1・1.14.2・1.14.3）も、同じ通常flatワールド・インベントリ位置(80,121)で静的7種類を64pxで照合し、各版とも最大channel誤差0でした。描画・照明・固定contextは変更していません。[条件と数値記録](validation.md#issue-23のlegacy-114通常ワールド検証2026-10-13)を参照してください。

gui-fixedのexact 10版（1.14.4・1.15・1.15.1・1.15.2・1.16・1.16.1・1.16.2・1.16.3・1.16.4・1.16.5）も、通常flatワールドの静的7種類を64pxで照合し、最大channel誤差0でした。1.15以降の9版は描画前lightmapを実測252へ合わせ、manifestに固定値を記録します。1.14.4の照明・投影は維持します。agent hash/settings digestが変わるため、以前のiconsは新規出力先へ再生成してください。[初回FAILとQA観測修正を含む記録](validation.md#issue-24のgui-fixed通常ワールド検証2026-10-13)を参照してください。


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

gui-classicのexact 10版（1.17・1.17.1・1.18・1.18.1・1.18.2・1.19・1.19.1・1.19.2・1.19.3・1.19.4）は、通常flatワールドの静的7種類を64pxで照合し、最大channel誤差0でした。描画前lightmapを実測252へ合わせ、manifestに記録します。全件11,363 PNGを監査し、独立した選択一括・ID別プロセスの各70枚は全件基準へ厳密一致しました。旧agentのiconsは新しい出力先へ再生成してください。[初回画像FAIL・QAエラー・OOMを含む記録](validation.md#issue-25のgui-classic通常ワールド検証2026-10-13)を参照してください。

Issue #26ではgui-1.21.4の1.20・1.20.1・1.20.2・1.20.3・1.20.4・1.20.5・1.21・1.21.1・1.21.2・1.21.3を、通常flat Worldの静的7種類・64pxで検証しました。ユーザーが承認したGUI 320×240・インベントリ位置(80,121)を固定基準とし、70比較は誤差0でした。CPU lightPixelsの8版はCPU/GPU、GPU targetの2版はScreenshot/GPUのfull-bright RGBA(252,252,252,255)が一致します。全件13,125 PNGを監査し、独立した代表一括70枚と各ID別CLI・ゲームプロセス70枚は全件へ厳密一致しました。16/512pxの140 PNGは画像監査・終了検証で、World比較ではありません。既存1.20.6の基準と1.21.4の原点基準を維持し、1.19.4・1.20.6・1.21.4の回帰21画像は旧workerへ厳密一致、World21比較は誤差0です。[数値記録](../qa/validation-2026-10-13-modern-world.json)を参照してください。context/worker hashが変わるため新しい出力先へ再生成し、全版experimental/deterministic=false・verified版0を維持します。

Issue #27ではgui-1.21.5の1.21.5を通常flat Worldの静的7種類・64pxで照合し、描画前lightmapを実測RGB252へ合わせた後は誤差0でした。実インベントリ位置(80,121)へ一致したため、productionの既存原点基準を維持します。GPU texelと描画時に指定されたSampler2 textureを観測し、CPU画像の値は作成しません。全1,395 PNGと独立全件反復1,395 PNGを監査し、decoded RGBA・PNG hash・パス・source・settings/digest・context・renderer・catalogが厳密一致しました。各ID別7 PNG、16/512pxの14 PNG、隣接1.21.4/1.21.6の回帰14 PNGと1.21.4のWorld7比較も成功しました。初回QA読戻し失敗と、単品生成での起動WORKER_EXITED 1件（根本原因未確定・同条件7件の明示再試験は成功）は成功記録と分けて保全します。[数値記録](../qa/validation-2026-10-13-gpu-immediate-world.json)を参照してください。worker/settingsDigestが変わるため以前のiconsを新しい出力先へ再生成し、全版experimental/deterministic=false・verified版0を維持します。

Issue #28ではgui-deferredのexact 9版（1.21.6・1.21.7・1.21.8・1.21.9・1.21.10・1.21.11・26.1・26.1.1・26.1.2）を通常flat Worldの静的7種類・64pxで照合し、63比較は誤差0でした。1.21系6版のlightmapは実測RGB252へ合わせ、26.1系3版はGUI専用UiLightmapのCPU/GPU白255と一致する既存描画を維持します。実インベントリ位置(80,121)へ一致したためproduction原点基準を変更しません。全件13,237 PNGと独立全件反復13,237 PNG、各ID別63 PNG、16/512pxの126 PNGを監査しました。全件反復・代表一括・単品のRGBA・PNG hash・パス・source・settings/digest・context・renderer・catalogは厳密一致し、1.21.5/26.2の回帰14 PNGと1.21.5のWorld7比較も成功しました。初回QA計装/APIエラー・変更前の色差・反証後の観測範囲修正を成功記録と分けて保存します。[数値記録](../qa/validation-2026-10-13-deferred-world.json)を参照してください。worker/settingsDigestが変わるため以前のiconsを新しい出力先へ再生成し、experimental/deterministic=false・verified版0を維持します。

## 正式51版の登録集合・欠損受入（2026-10-12）

修正後workerで固定正式51版・60,265PNGを新規生成し、登録母集団・除外理由・ID/パス重複・hash/CRC・RGBA8・透過・空画像の監査と、#10/#11の正常基準へのRGBA・PNG hash・パス比較が成功しました。1.21.11で破損stone PNGの代替画像を成功出力する検出漏れを、BakedQuadのposition/packedUV消費時のsprite観測で修正しました。選択された破損atlas/直接textureを各51版、モデル・チェスト・空モデル・事前ロード済み直接textureを各12境界版で受け入れました。150成功は複数実行の集約であり、初回失敗10ケースと各worker/helper hashを保存します。[検証範囲と再実行方法](validation.md)・[数値記録](../qa/validation-2026-10-12-release-coverage.json)を参照してください。

正常PNG bytes・RGBA・パスは不変ですが、worker hashとsettingsDigestは変わります。旧manifestへ再開せず、新しい出力先で再生成してください。全版experimental/deterministic=false・verified版0を維持し、全状態・サイズ・world・OS/GPUなどの受入は#13等で継続します。

## Issue #35の特殊renderer監査（2026-10-14）

1.13・1.21.4・26.3の既定chest/dragon_head/player_head/shieldについて、直接texture、atlas sprite、cache hitと既存MissingResources guardの対応を確認しました。26.3は実GPU Sampler0のview identityも対応し、通常workerとQA観測workerの独立12 PNGはRGBA一致でした。確認された漏れはなく、production・API/context・既存PNGは不変です。[範囲・対応表・数値記録](validation.md#issue-35の特殊renderer-resourcecache監査2026-10-14)を参照してください。#12の150成功fault結果は関連4ファイルのbyte一致を確認して再利用し、今回の正常監査とは分けます。任意skin/profile・shield模様・季節性・他版/他GPU・独自raw GPU cache・非render-thread外部更新の全組み合わせは保証しません。全版experimental/deterministic=false・verified版0を維持します。

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


Issue #29ではgui-26.2のexact release 26.2を通常flat Worldの静的7種類・64pxで照合し、実インベントリ位置(80,121)との7比較は誤差0でした。GUI専用UiLightmapのCPU/GPU白255と実使用Sampler2 textureを確認し、production描画・照明・原点基準・contextは変更しません。全1,536 PNGと独立全件反復1,536 PNG、各ID別7 PNG、16/512pxの14 PNGを監査し、全件反復・代表一括・個別のRGBA/hashと設定が厳密一致しました。サイズ境界はPNG・終了検証で、World画質の受入ではありません。26.1.2の回帰7 PNGと独立World7比較も成功しました。26.2の画面・描画先API移動に伴う初回QA2失敗と再撮影を分けて[数値記録](../qa/validation-2026-10-13-latest-world.json)へ保存します。worker/settingsDigestは不変で再生成は不要です。全モデル・動的状態・全サイズ・他GPUへ一般化せず、全版experimental/deterministic=false・verified版0を維持します。

Issue #30ではgui-26.3のexact release 26.3を通常flat Worldの静的7種類・64pxで照合し、実インベントリ位置(80,121)との7比較は誤差0でした。RenderPearlのSampler2観測に独立QAを対応させ、GUI専用UiLightmapの1×1 CPU/GPU白255と実使用textureを確認しました。production描画・照明・原点基準・contextは変更していません。全1,657 PNGと独立全件反復1,657 PNG、各ID別7 PNG、16/512pxの14 PNGを監査し、全件反復・個別のRGBA/hashと設定が厳密一致しました。サイズ境界はPNG・終了検証で、World画質の受入ではありません。26.2の回帰7 PNGと独立World7比較も成功しました。[条件と数値記録](validation.md#30-gui-263の通常world照明)を参照してください。worker/settingsDigestは不変で再生成は不要です。全モデル・動的状態・全サイズ・他GPUへ一般化せず、全版experimental/deterministic=false・verified版0を維持します。

## 無計装clientとの独立照合

Issue #31では1.20.6の静的5種類（stone・player_head・chest・glass・grass_block）を、agent無しclientのF2画像で各2回照合しました。固定Mesa Linux、1280×960・GUI scale 4・slot 9の(80,121)・既定stack/count 1・64pxで、通常World QAと生成PNGへの再合成比較は誤差0です。1.21.4のdiamondも2回誤差0でした。

1.21.4のclock・compass・recovery_compass・glint付きenchanted_book・animation付きsea_lanternは、通常Worldとfixed-v1の状態が異なるため、raw差を保存して画質受入から除外します。元saveは既存QAで作ったものをコピーし、今回のclient描画にはagent・時刻固定・描画置換を入れていません。glassはbinary alphaであり、全モデル・全状態・半透明・glint・他GPUの保証には広げません。[操作条件・初回FAIL・状態分類](validation.md#issue-31の無計装client基準2026-10-14)を参照してください。

全版experimental、deterministic=false、verified 0版を維持します。productionの描画・worker・PNG形式は変更しておらず、既存PNGの再生成は不要です。
