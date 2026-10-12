# 契約 v1（開発中）

Java 21 core/CLIとJava 8 bytecodeのagentを分離し、Minecraft自身は必要Javaで起動します。公開DTOにMinecraft型は使いません。rawとiconsは別モードです。

## 出力と終了

```
out/java/1.21.4/icons/assets/minecraft/textures/block/stone.png
out/java/1.21.4/icons/assets/minecraft/textures/item/diamond.png
out/java/1.21.4/icons/manifest.json
out/java/1.21.4/raw/assets/minecraft/textures/item/diamond.png
out/java/1.21.4/raw/manifest.json
```

iconsはcanonical item IDのパスを使い、block aliasを別ファイルとして重複出力しません。rawは元JARの相対パスです。ID文法・path traversal・symlinkを検査します。同じ版・modeの出力ディレクトリをFileLockで占有し、他ジョブの書き込みを拒否します。

exit 0=成功、1=起動/IO等の致命エラー、2=不正な引数/版/ID、3=部分失敗。全件は可能なIDを続行し、fail-fast/cancel時はcomplete=falseです。単品の出力競合等、エントリ処理中に発生したエラーはmanifestに残して3を返します。入力選択前の版/ID不備は2です。

## Worker JSON Lines

UTF-8、1行1JSON、stdin=request、stdout=protocolのみ、stderr=ゲームログ。coreは最初のreadyがprotocolVersion=1、contextProfile=fixed-v1、animationFrameZero=trueであることを要求します。profile/hookの不一致はWORKER_CONTEXT_MISMATCHです。catalog/render/shutdownには一意なrequestIdを付け、返答の不一致は致命エラーにします。requestは1MiB、responseは16MiBを上限とします。待受ポートは使いません。

requestは整形なしでserializeし、文字列内の改行はJSON escapeで保持します。WindowsのCRLFもJSONの途中へ混入させず、行末だけに使います。

```json
{"type":"ready","protocolVersion":1,"renderer":"llvmpipe / OpenGL","contextProfile":"fixed-v1","animationFrameZero":true}
{"requestId":"1","command":"catalog"}
{"requestId":"1","type":"result","entries":[{"id":"minecraft:stone","kind":"block","status":"available","blocks":["minecraft:stone"]}]}
{"requestId":"2","command":"render","id":"minecraft:stone","size":64,"path":"/mcicon/cache/worker-.../icon-....png"}
{"requestId":"2","type":"result","result":{"path":"...","visiblePixels":2744}}
{"requestId":"3","command":"shutdown"}
{"requestId":"3","type":"result"}
```

エラーはtype=error、code、messageを返します。PNGは専用cache内の一時ファイルで渡し、coreでサイズ・alphaを検証してから出力へ原子的に移します。PNGバイナリをstdoutへ流しません。workerは一つのrender threadで要求を処理し、全件ジョブで再利用します。16リクエストごとにバニラのフレーム処理へ戻り、描画・present・GPUの遅延解放を進めます。

requested inventory passでバニラのfallback BakedModelまたはMissingItemModelが選択された場合はMISSING_MODEL、消費されたquad/materialまたはatlas取得のsprite名がminecraft:missingnoの場合はMISSING_TEXTUREを返します。直接取得・bindするTextureが保持済みmissing textureと同じ場合もMISSING_TEXTUREです。modernではバニラのTextureContents.createMissingで生成された内容を適用したtextureを追跡し、requested passでTextureManagerまたはGPU getterから取得した場合に同じ診断を返します。適用したtextureの状態はpass間で保持し、通常のrender executorによる正常な内容の適用で解除します。独自のreload APIは提供せず、非render threadからGPU適用だけを後送する外部呼び出しとの並行性は保証しません。観測のためにmissing textureを生成するgetterは呼びません。成功PNGを出力せず、entryをfailedとして記録します。 workerが返す診断codeと具体的なmessageをmanifestとAPI結果に保持します。空画像と描画例外はRENDER_FAILEDとなり、同じworkerで次のアイテムへ継続します。起動時や別の描画passでの利用だけでは失敗としません。追跡済みの欠損textureをrequested passで取得した場合は、ロードした時点にかかわらず失敗とします。破損モデルがmissing spriteを持つ代替形状へ解決された場合はMISSING_TEXTUREになり、元ファイルの破損種別とは一致しない場合があります。特殊rendererの全テクスチャやGPU cacheの完全監査は未完了です。画像の色だけでmissing判定は行いません。

cancelは現在coreのCancellationTokenと専用Dockerコンテナ終了で行います。応答中の描画を同じrender threadでキャンセルするJSON commandは未実装です。timeout、startup失敗、cancel後は所有するコンテナだけを停止します。shutdownで正常終了を待ち、失敗時は強制終了します。worker.logは診断用にcacheへ残し、一時icon PNGを削除します。起動するagentはジョブごとにsnapshotを取り、記録したSHA-256と実際に起動したbytesを一致させます。coreの通常終了・SIGTERMでは専用containerの終了を要求します。SIGKILL時の即時cleanupは保証しません。

## Manifest

[JSON Schema](../schemas/manifest-v1.schema.json)が現在の出力形式です。sorted entries、mode、exact version、client SHA-256、settingsとdigest、counts、completeを含みます。iconsはadapter、renderer、Java major、Linux amd64、context、catalogを含みます。settingsにはworker agent・mappings hashを含めます。rawのentryはsourceSha256・mcmeta path/hashを持ちます。資格情報・ユーザーの絶対パスをmanifestに書きません。

countsはそのmanifestに蓄積した成功/失敗の数です。選択IDは書き込み前にiconsではNOT_RENDERED、rawではNOT_EXTRACTEDとして予約するため、途中終了時には未処理IDもfailedに含めます。両モードで16件ごと・失敗時・終了時にcheckpointを保存します。rawの未確定mcmetaの所有権は、任意のreservedAuxiliaryフィールドへtexture ID→相対パスとして保存します。完成した付随ファイルのauxiliaryには実際のSHA-256だけを保存します。予約済みの未確定PNG/mcmetaは再開時に再生成し、所有権不明のOUTPUT_EXISTSは保持します。明示overwriteを受け付けた項目は新しい予約へ置き換え、直後のクラッシュでも再開できます。同条件で別の単品を追加した場合、以前の成功entryも残ります。completeは選択した処理が中断せず、manifest中に失敗がない状態です。全レジストリの網羅を意味するのはallを指定したジョブに限ります。別設定のoverwriteでは旧manifestを引き継ぎませんが、選択外の古いPNGまでは削除しません。

`.mcicon-<数字>.part` は書き込み途中のツール専用一時ファイル名です。通常の失敗ではfinallyで削除し、SIGKILL等で残った場合は次のエクスポートがFileLockを取得した後に回収します。対象は同じ版/modeの出力ツリー内にある、この名前に一致する通常ファイルだけです。シンボリックリンクとそのリンク先は削除対象にしません。

再開は元JAR、settings digest、renderer、保存PNG/sidecar hashを照合し、一致した成功だけ再利用します。iconsのsettingsにはversion metadata・mappings・agentのSHA-256とDocker image IDを含めます。Dockerのtagは起動前にimage IDへ解決し、そのIDでworkerを起動します。旧版mappingsは固定SHA-1のTinyを毎回検証・合成します。

metadataと設定のhashに使う整形JSONはUTF-8・LFでserializeし、同じ入力がホストの改行形式だけで別のhashにならないようにします。以前のWindows出力はhashが変わるため、新しい出力先へ再生成してください。以前のMac/LinuxのLF形式は維持します。

## fixed-v1とcanonical-v1の受入

fixed-v1はcount=1、既定ItemStack、overlayなし、world/playerなし、partialTick=0です。ゲーム内の暦日を2000-01-01 UTCにし、描画中の経過時刻を1000msに固定します。0msでは初期化を時刻で判定するバニラcacheに影響するため、正の定数を使います。worker自身のtimeout時計は固定しません。

Dockerfileはsoftware描画に加え、Mesa llvmpipeの`LP_NATIVE_VECTOR_WIDTH=128`と`GALLIUM_OVERRIDE_CPU_CAPS=sse4.1`を指定します。CPUによる最適化の差を抑えるための条件で、[Mesaの環境変数](https://docs.mesa3d.org/envvars.html)と[llvmpipeの指定](https://docs.mesa3d.org/drivers/llvmpipe.html)に従います。実行CPUまたはエミュレーションにSSE4.1が必要です。Docker image更新でsettings digestが変わるため、以前のiconsは新しい出力先へ再生成してください。任意のMesa/LLVM版・CPU・GPUでのRGBA一致を保証するものではありません。

1.20.6と1.21.4のlightmapは、通常ワールドのInventoryScreenで実測したfull-bright RGB 252/252/252へ描画前に初期化します。world/playerなしの起動を維持し、時刻・天候・状態異常による照明変動を持ち込みません。PNG保存後のRGB補正は行いません。manifestのcontext.lightmapFullBrightRgbに固定値を記録します。他版の照明は変更せず、各版の実表示との照合を継続します。以前の1.20.6と1.21.4はworldなしの初期白255を使っていたため、色が変わります。agent hashとsettings digestが変わるので、以前のiconsは新しい出力先へ再生成してください。

1.20.6はGUI 320×240のインベントリ位置(80,121)を固定基準にします。出力sizeに対して16 GUI単位のアイコンを拡大し、同じ投影・モデル位置のままviewportを移動してsize四方へ切り出します。512pxではviewport 10240×7680が必要です。検証したMesa環境の上限は16384×16384で、設定値と元viewportへの復帰を観測しました。バニラのGUI原点と他位置では浮動小数点演算・テクスチャ参照が変わるため、すべてのGUI位置との一致は保証しません。以前の原点基準とは画像・context・agent hashが変わるため、新しい出力先へ再生成してください。PNGへの色補正や差分許容の追加は行いません。

通常インベントリとの64px比較では、RGB各channelの誤差2/255を基準とします。ユーザーが画像で確認したoak_fenceの輪郭2画素だけは、同じID・出力PNG hash・参照RGBA hash・背景RGBA hash・差分座標が揃う場合に許容します。輪郭の上下左右1画素以内という見た目だけでは、背景と同じ色の不透明pixelや半透明を区別できないため、他画像へ自動的に一般化しません。厳密比較のrawPassedと誤差を残し、確認済み画像の条件を含むcomparisonPassedを別に記録します。productionの描画やPNGはこの判定で変更しません。

1.21.4の時計・コンパス・リカバリーコンパスは、entityなしのバニラNeedleDirectionHelperが返す向き0で選ばれます。時計はclock_00、コンパスとリカバリーコンパスはそれぞれcompass_16・recovery_compass_16です。実ワールドの時刻・方角を表す画像ではありません。

atlasのanimation更新を抑止し、初期フレームを保持します。時計・コンパス等の動的property乱数を各描画passでseed=0に戻します。モデルが明示的にseedを設定するquad用乱数はバニラのまま保持します。ゲームJAR内の対象呼び出しだけをagentで変換し、依存JARは変換しません。ASMはagent専用packageへrelocateし、ゲーム同梱ASMとの衝突を避けます。

deferred GUIでは各passのアトラス割当を初期化し、単品・全件で同じスロット位置を使用します。アトラスのGPU textureは再利用し、色・深度を消去します。GPU fence完了後にGUI frameと動的uniform storageを終了・resetします。

1.14.4の固定機能GUIでは、ワールド開始時に渡されるTextureManagerをBlockEntity dispatcherへ描画前に供給します。特殊アイテムのテクスチャを切り替え、直前のアイテムや起動画面への依存を防ぎます。

iconsのPNGは8bit RGBA・straight alphaで、sRGB chunk（Perceptual）を付けてRGBの解釈を明示します。[PNG仕様](https://www.w3.org/TR/png-3/#11sRGB)の色空間指定に従い、RGB値のgamma変換は行いません。rawは元JARのPNG bytesを保持します。

透過PNGは黒・白の不透明背景で同じアイテムを2回描画し、背景差からalphaとstraight RGBを復元します。GPU readbackの上下を反転し、alpha=0のRGBを0にします。byte RGB上の再合成はバニラのGUIブレンドとの一致を確認するもので、linear-lightの背景合成とは区別します。通常GUI fixtureでの再合成比較は[検証記録](validation.md)を参照してください。sRGB・半透明・glintを含む無改変のワールド内inventory基準画像との受入は未完了です。

canonical-v1への昇格には、固定環境の反復RGBAとsingle/all一致、意味的な画像検査、色空間の確認が必要です。現在はdeterministic=falseです。agent/contextの変更はsettings digestを変え、古いPNGを再利用しません。任意NBT/componentsや状態の直積は対象外です。
