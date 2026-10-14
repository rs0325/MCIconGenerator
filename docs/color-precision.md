# PNGの色とalphaの精度

Issue #32は、1.20.6と1.21.4の既知RGBA診断パックを、64px・GUI位置(80,121)・固定Linux Mesaで比較した記録です。16pxのgenerated-modelを1層/2層で描画し、alpha 0/1/127/128/254/255に加えてshader cutoutの境界25/26を含む16段階、黒・白・暗色・明色を含む16色を使います。背景は黒、白、灰、青緑、赤、緑の6種類です。wheatはパックで変更しない対照です。

診断パックは専用QA workerのコピーにだけ同梱します。production配布物や公式clientは変更しません。通常GUIのfixtureはfixed-v1のhook・registry初期化を共有しますが、通常GUIの描画先・Screenshotと、独立数値oracleはproductionのPNG復元を使いません。完全な無計装Worldの全状態受入ではありません。

## 測定した誤差

値はRGB/alphaを0〜255で表した単位です。以下は今回の試料で観測した最大値で、全色・全モデル・全GPUに対する一律の上限ではありません。

| 比較 | 最大差 | 意味 |
|---|---:|---|
| 通常GUIと復元PNGのbyte RGB再合成 | 2 | 2版・各3ID・6背景の36比較。既存許容値2を維持。 |
| 通常GUIと浮動小数byte RGB oracle | 1.502 | texture/shader・層合成・RGBA8経路の量子化を含む。 |
| 1層PNGの復元alpha | 0 | shader cutout後の実効alphaとの比較。 |
| 2層PNGの復元alpha | 0.875 | 各層の量子化を経たblack/whiteからの推定差。 |
| 1層PNGのstraight RGBとshader後の元色 | 5.024 | 低alphaでのunpremultiplyによる誤差増幅を含む。 |
| 2層PNGのstraight RGBと理想合成色 | 2.130 | byte RGBで層を合成した理想値との比較。 |
| linear-lightでの再合成と通常GUI | 73 | 合成する色空間が異なる。画質PASSへ読み替えない。 |

量子化を分けて確認するため、ゲーム資産・shaderを使わない独立GPU gridも測定しました。

| synthetic対照 | 最大差 |
|---|---:|
| RGBA32Fと浮動小数byte RGB oracle | 0.00001909 |
| RGBA8と同じoracle | 0.997 |
| RGBA8 black/whiteからの復元alpha | 0 |
| RGBA8復元PNGのbyte RGB再合成 | 1 |
| RGBA8のstraight RGB、alpha>=127 | 1 |
| RGBA8のstraight RGB、alpha=1を含む | 127 |
| SRGB8_ALPHA8・sRGB有効とlinear-light oracle | 0.849 |

float演算だけの差はRGBA8経路の差より十分小さく、RGBA8の差を単一の最終丸め誤差とは扱いません。8bitのblend経路内の量子化も含む測定です。GLのsRGB対照は今回のproduction状態とは別に作っています。その対照へ既存のbyte RGB復元式を誤適用すると、alphaに60、byte再合成に44の差が出ました。linear-light用の復元としては保証せず、productionの設定や復元方式を変更していません。

## PNGが保証するもの

出力は8bit RGBA、straight alpha、sRGB chunkのPerceptual（値0）です。今回の出力でPNG CRC・chunk・画素を確認し、alpha=0の隠れたRGBはすべて0でした。入力の透明RGBを保存する契約ではありません。

通常item shaderの`alpha < 0.1`はsource fragmentをdiscardします。1層のalpha 0/1/16/25は透明になり、26以上は描画されます。2層では、片方のfragmentが破棄されてももう片方が可視なら合成結果は可視です。このcutoutをPNG復元の不具合と同一視しません。

alphaが小さいと、8bitのblack/white観測から元のstraight RGBを一意に戻せません。syntheticのalpha=1では、RGB(128,128,128)と(254,253,252)が同じblack/white観測になり、どちらもRGBA(255,255,255,1)へ復元されました。元色との大きいstraight RGB差があっても、低いopacityを掛けたbyte再合成の差は1以内でした。alphaを不必要に0へ丸める修正や、元色の無誤差復元を保証する変更は行いません。

sRGB chunkはRGBの符号化を示すもので、合成式をbyte RGBに指定するものではありません。通常GUIの一致を再現するにはbyte RGBでsource-overします。sRGB EOTFでRGBを線形化して合成し、OETFで戻すlinear-lightでは同じ画素にならない場合があります。今回の最大73/255はこの条件差であり、PNGの宣言変更・許容値の拡大・色変換による補正で消していません。

production readback入口のblack/white計12回と通常GUI各背景では、RGBA8、GL_LINEAR、sRGB無効、各channel 8bitを読み取りました。観測中に一時変更したtexture bindingは復帰し、GL errorは0でした。観測追加前後のexport 6枚とGUI crop 36枚、計42 PNGはbytes/hashが一致しました。ゲーム内の観測QAではsRGBの有効・無効を切り替えたり、復元式を共有helperに置き換えたりしていません。

## 再実行

専用の準備済みcacheと、新しい出力先を使います。同時native数は割り当てた枠以内に保ってください。元JAR・診断PNG・GPU読み取り画像・生logはprivateに保存します。

```sh
python3 scripts/verify-alpha-textures.py --version 1.20.6 --cache .local/color-cache \
  --out .local/color-1.20.6 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-alpha-textures.py --version 1.21.4 --cache .local/color-cache \
  --out .local/color-1.21.4 --docker-image mcicon-render:0.1.0-preview.1
python3 scripts/verify-native-alpha.py --cache .local/color-cache \
  --out .local/color-synthetic --docker-image mcicon-render:0.1.0-preview.1
```

入力・binary・QA・画像hash、背景/alphaごとの数値、初期系列と最終系列の区別は[数値記録](../qa/validation-2026-10-14-color-precision.json)を参照してください。JUnit 68件・ビルドと、alpha=1の情報損失を含む回帰が成功しています。production・公開API・PNG形式は変更せず、既存PNGの再生成は不要です。全版experimental・deterministic=false・verified 0版を維持します。
