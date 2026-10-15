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

## 描画の対応状態

`render`は公式metadataでexact版を解決してから、同梱対応表のsupportで入力準備前に判定します。verifiedはopt-in不要、experimentalは`allowExperimental=true`／`--allow-experimental`が必要です。opt-inなしのexperimentalは`UNVERIFIED_VERSION`（exit2）、discovered・unsupportedは指定にかかわらず`ADAPTER_UNAVAILABLE`（exit2）です。`latest`も公式最新の解決結果に同じ判定を適用し、旧版へ切り替えません。doctorのadapter説明には実際のsupportを表示します。

1.0候補のverified対象は2026-10-10固定の51版だけです。同じ最終候補で全件・独立反復・独立oracle等を受け入れてから公開します。追加の正式版はdiscoveredのままで、古いverified版へfallbackしません。extractの元bytes・保存schemaは維持します。

## ゲームworkerのメモリ上限

`WorkerOptions.containerMemoryMiB` と `heapMemoryMiB` は正のMiB整数で、heapはcontainer未満を要求します。不正なAPI指定は `IllegalArgumentException`、CLIの `--memory-mib` / `--heap-mib` は引数エラー（exit 2）です。既定3072/2048 MiBを従来の5/6引数constructorでも保持します。containerはDocker `--memory`、heapはゲームJVM `-Xmx` へそのまま渡し、ホストJVM・Docker VM・起動前のJava診断containerは変更しません。起動を保証する最小予算ではなく、低予算の失敗を理由に補正・retryしません。CPU・期限・protocol・agentの描画処理も変更しません。

メモリ予算は描画設定digestに含めず、manifest schema・raw出力・既存保存形式を変更しません。予算だけを変えた再開は従来の画像hash照合に従います。

## Worker JSON Lines

UTF-8、1行1JSON、stdin=request、stdout=protocolのみ、stderr=ゲームログ。coreは最初のreadyがprotocolVersion=1、contextProfile=canonical-v1、animationFrameZero=trueであることを要求します。profile/hookの不一致はWORKER_CONTEXT_MISMATCHです。catalog/render/shutdownには一意なrequestIdを付け、返答の不一致はその要求を失敗にします。要求と応答を混同して成功PNGへ採用しません。1行の上限はrequestが1,048,576、responseが16,777,216 Java文字（UTF-16 code unit）です。bytesの上限ではなく、readLine後に検査します。待受ポートは使いません。

requestは整形なしでserializeし、文字列内の改行はJSON escapeで保持します。WindowsのCRLFもJSONの途中へ混入させず、行末だけに使います。

```json
{"type":"ready","protocolVersion":1,"renderer":"llvmpipe / OpenGL","contextProfile":"canonical-v1","animationFrameZero":true}
{"requestId":"1","command":"catalog"}
{"requestId":"1","type":"result","entries":[{"id":"minecraft:stone","kind":"block","status":"available","blocks":["minecraft:stone"]}]}
{"requestId":"2","command":"render","id":"minecraft:stone","size":64,"path":"/mcicon/cache/worker-.../icon-....png"}
{"requestId":"2","type":"result","result":{"path":"...","visiblePixels":2744}}
{"requestId":"3","command":"shutdown"}
{"requestId":"3","type":"result"}
```

[worker-v1.schema.json](../schemas/worker-v1.schema.json)は正規peerのメッセージ形式を定義します。[形式・51版contextの検証記録](../qa/validation-2026-10-14-worker-contract.json)は正常10例・不正25例と、現行coreの合成peer試験12条件を保持します。`python3 scripts/test-worker-contract.py -v`で再試験できます（先にCLI installDist、JDK21以上、jsonschema 4.25.1が必要）。core/workerがこのSchemaをruntimeで全面検証するという意味ではありません。追加フィールドは許可し、必須フィールド・command・応答payloadの形を固定します。catalogのavailable項目はkindとblocksを持ち、no_item_formはアイテム形態を持たないblockです。block aliasはblocksの関係からcoreで解決します。renderのvisiblePixelsは復元PNGのalpha>0の画素数です。shutdownのresultにはpayloadを付けません。

coreは1要求を送ってその応答を待つ直列peerです。readyは最初の1回だけ、catalogの返答はentries、renderの返答はresult、shutdownの返答はtype=resultです。requestIdは文字列で、同じジョブの要求ごとにUUIDを生成します。idの意味はcommand依存なので、JSON Schemaだけで要求と応答の対応は検証できません。

| 条件 | 現在の処理と外部結果 |
|---|---|
| 最初のtypeがready以外／protocolVersionが1以外 | 接続を破棄し、起動FutureはIO_FAILED（exit 1）。別protocolへのfallbackなし |
| canonical-v1／animationFrameZeroの不一致 | 接続を破棄し、WORKER_CONTEXT_MISMATCH（exit 1） |
| 応答requestId不一致／result・error以外のtype | その要求がIOExceptionで失敗。catalog段階はIO_FAILED、render段階はfailed entryのRENDER_FAILED（exit 3） |
| workerのtype=error | code/messageを保持。catalog段階は例外、render段階はfailed entry。fail-fast指定がなければ次IDへ進む |
| ready／応答期限超過 | WORKER_TIMEOUT、専用containerの終了を要求。render途中は部分失敗としてmanifestへ保存 |
| 応答前にDocker CLIが終了 | WORKER_EXITED。render途中は成功済みPNGを保持して部分失敗へ |
| 不正JSON／上限超過／reader例外 | readerが処理を終了。core側はCLI終了またはtimeoutを検出。JSON Schema専用の診断codeは存在しない |
| stdin EOF | worker readerが内部shutdown要求を投入し、render threadで終了 |

不正commandはworkerのRENDER_FAILEDです。第三者の不正peerとの再同期や、欠落・過剰なpayloadの完全診断は公開APIの保証範囲に含めません。workerのschemaVersionという別フィールドは使わず、handshakeのprotocolVersionを用います。

エラーはtype=error、code、messageを返します。PNGは専用cache内の一時ファイルで渡し、coreでサイズ・alphaを検証してから出力へ原子的に移します。PNGバイナリをstdoutへ流しません。workerは一つのrender threadで要求を処理し、全件ジョブで再利用します。16リクエストごとにバニラのフレーム処理へ戻り、描画・present・GPUの遅延解放を進めます。

requested inventory passでバニラのfallback BakedModelまたはMissingItemModelが選択された場合はMISSING_MODEL、消費されたquad/materialまたはatlas取得のsprite名がminecraft:missingnoの場合はMISSING_TEXTUREを返します。直接取得・bindするTextureが保持済みmissing textureと同じ場合もMISSING_TEXTUREです。modernではバニラのTextureContents.createMissingで生成された内容を適用したtextureを追跡し、requested passでTextureManagerまたはGPU getterから取得した場合に同じ診断を返します。適用したtextureの状態はpass間で保持し、通常のrender executorによる正常な内容の適用で解除します。独自のreload APIは提供せず、非render threadからGPU適用だけを後送する外部呼び出しとの並行性は保証しません。観測のためにmissing textureを生成するgetterは呼びません。成功PNGを出力せず、entryをfailedとして記録します。 workerが返す診断codeと具体的なmessageをmanifestとAPI結果に保持します。空画像と描画例外はRENDER_FAILEDとなり、同じworkerで次のアイテムへ継続します。起動時や別の描画passでの利用だけでは失敗としません。追跡済みの欠損textureをrequested passで取得した場合は、ロードした時点にかかわらず失敗とします。破損モデルがmissing spriteを持つ代替形状へ解決された場合はMISSING_TEXTUREになり、元ファイルの破損種別とは一致しない場合があります。特殊rendererの全テクスチャやGPU cacheの完全監査は未完了です。画像の色だけでmissing判定は行いません。

cancelはcoreのCancellationTokenと専用Dockerコンテナ終了で行う正式仕様です。JSON cancel commandは提供せず、実行中の描画threadが要求を受信できることを取消の前提にしません。timeout、startup失敗、cancel後は所有するコンテナだけを停止します。shutdownで正常終了を待ち、失敗時は強制終了します。worker.logは診断用にcacheへ残し、一時icon PNGを削除します。起動するagentはジョブごとにsnapshotを取り、記録したSHA-256と実際に起動したbytesを一致させます。coreの通常終了・SIGTERMでは専用containerの終了を要求します。SIGKILL時の即時cleanupは保証しません。

## Manifest

[JSON Schema](../schemas/manifest-v1.schema.json)が現在の出力形式です。sorted entries、mode、exact version、client SHA-256、settingsとdigest、counts、completeを含みます。iconsはadapter、renderer、Java major、Linux amd64、context、catalogを含みます。settingsにはworker agent・mappings hashを含めます。rawのentryはsourceSha256・mcmeta path/hashを持ちます。資格情報・ユーザーの絶対パスをmanifestに書きません。

countsはそのmanifestに蓄積した成功/失敗の数です。選択IDは書き込み前にiconsではNOT_RENDERED、rawではNOT_EXTRACTEDとして予約するため、途中終了時には未処理IDもfailedに含めます。両モードで16件ごと・失敗時・終了時にcheckpointを保存します。rawの未確定mcmetaの所有権は、任意のreservedAuxiliaryフィールドへtexture ID→相対パスとして保存します。完成した付随ファイルのauxiliaryには実際のSHA-256だけを保存します。予約済みの未確定PNG/mcmetaは再開時に再生成し、所有権不明のOUTPUT_EXISTSは保持します。明示overwriteを受け付けた項目は新しい予約へ置き換え、直後のクラッシュでも再開できます。同条件で別の単品を追加した場合、以前の成功entryも残ります。completeは選択した処理が中断せず、manifest中に失敗がない状態です。全レジストリの網羅を意味するのはallを指定したジョブに限ります。別設定のoverwriteでは旧manifestを引き継ぎませんが、選択外の古いPNGまでは削除しません。

`.mcicon-<数字>.part` は書き込み途中のツール専用一時ファイル名です。通常の失敗ではfinallyで削除し、SIGKILL等で残った場合は次のエクスポートがFileLockを取得した後に回収します。対象は同じ版/modeの出力ツリー内にある、この名前に一致する通常ファイルだけです。シンボリックリンクとそのリンク先は削除対象にしません。

再開は元JAR、settings digest、renderer、保存PNG/sidecar hashを照合し、一致した成功だけ再利用します。iconsのsettingsにはversion metadata・mappings・agentのSHA-256とDocker image IDを含めます。Dockerのtagは起動前にimage IDへ解決し、そのIDでworkerを起動します。起動前にimage内Javaを検査し、実major・version・実行パスのSHA-256をsettingsへ含めます。Javaの絶対パスはmanifestへ保存しません。Javaの選択またはversionが変わると旧iconsを再利用しないため、従来のsettingsで生成したiconsは別の出力先へ再生成してください。rawの条件は変わりません。旧版mappingsは固定SHA-1のTinyを毎回検証・合成します。

metadataと設定のhashに使う整形JSONはUTF-8・LFでserializeし、同じ入力がホストの改行形式だけで別のhashにならないようにします。以前のWindows出力はhashが変わるため、新しい出力先へ再生成してください。以前のMac/LinuxのLF形式は維持します。

## canonical-v1の受入

canonical-v1はcount=1、既定ItemStack、overlayなし、world/playerなし、partialTick=0です。ゲーム内の暦日を2000-01-01 UTCにし、描画中の経過時刻を1000msに固定します。0msでは初期化を時刻で判定するバニラcacheに影響するため、正の定数を使います。描画外ではバニラの時計供給元の値を維持し、GLFW初期化からの経過時刻をホストの単調時計へ置き換えません。worker自身のtimeout時計は固定しません。

Dockerfileはsoftware描画に加え、Mesa llvmpipeの`LP_NATIVE_VECTOR_WIDTH=128`と`GALLIUM_OVERRIDE_CPU_CAPS=sse4.1`を指定します。CPUによる最適化の差を抑えるための条件で、[Mesaの環境変数](https://docs.mesa3d.org/envvars.html)と[llvmpipeの指定](https://docs.mesa3d.org/drivers/llvmpipe.html)に従います。実行CPUまたはエミュレーションにSSE4.1が必要です。Docker image更新でsettings digestが変わるため、以前のiconsは新しい出力先へ再生成してください。任意のMesa/LLVM版・CPU・GPUでのRGBA一致を保証するものではありません。

gui-fixedの1.15〜1.16.5の9版、gui-classicの1.17〜1.19.4の10版、gui-1.21.4の1.20〜1.21.4の12版、gui-1.21.5の1.21.5、gui-deferredの1.21.6〜1.21.11の6版のlightmapは、通常ワールドのInventoryScreenで実測したfull-bright RGB 252/252/252へ描画前に初期化します。world/playerなしの起動を維持し、時刻・天候・状態異常による照明変動を持ち込みません。PNG保存後のRGB補正は行いません。manifestのcontext.lightmapFullBrightRgbに固定値を記録します。他版の照明は変更せず、各版の実表示との照合を継続します。照明反映前のこれら38版はworldなしの初期白255を使っていたため、色が変わります。agent hashとsettings digestが変わるので、以前のiconsは新しい出力先へ再生成してください。

legacy-1.13の3版（1.13・1.13.1・1.13.2）とlegacy-1.14の4版（1.14・1.14.1・1.14.2・1.14.3）は、通常flatワールドのインベントリ位置(80,121)、GUI scale4、64pxで静的7種類を照合し、最大channel誤差0でした。対象スロットのモデル描画入口ではlightmap texture unitが無効で、観測したCPU/GPUのfull-bright texelはともにRGBA(252,252,252,255)です。このtexelをexportへ適用する変更は行いません。lightmap値がmanifestにないことからGUIの使用値を白255と推定せず、描画入口の状態と画像差分を分けて記録します。全モデルの内部描画状態・全サイズ・他GPUのWorld一致へ一般化しません。

1.21.5ではLightTextureのGpuTextureを描画前にRGBA(252,252,252,255)へ初期化します。通常flatワールドのインベントリ位置(80,121)と、既存の描画原点基準で生成した静的7種類・64pxのPNGは誤差0でした。この版のGUI投影・原点位置は維持し、guiReferenceは追加しません。CPU lightmap画像は存在せず、QAはGPU texelとSampler2の指定textureを観測します。全draw内部状態・全モデル・動的状態・全サイズ・他GPUの一致へ一般化しません。

1.20〜1.21.3の11版（1.20.6を含む）はGUI 320×240のインベントリ位置(80,121)を固定基準にします。出力sizeに対して16 GUI単位のアイコンを拡大し、同じ投影・モデル位置のままviewportを移動してsize四方へ切り出します。512pxではviewport 10240×7680が必要です。1.20.6では、検証したMesa環境の上限16384×16384と、viewport設定・元viewportへの復帰を観測済みです。今回の10版は16/512pxのPNG監査・終了検証で、GL状態の観測とは区別します。バニラのGUI原点と他位置では浮動小数点演算・テクスチャ参照が変わるため、すべてのGUI位置との一致は保証しません。以前の原点基準とは画像・context・agent hashが変わるため、新しい出力先へ再生成してください。PNGへの色補正や差分許容の追加は行いません。1.21.4は既存の原点基準を維持します。

通常インベントリとの64px比較では、RGB各channelの誤差2/255を基準とします。ユーザーが画像で確認したoak_fenceの輪郭2画素だけは、同じID・出力PNG hash・参照RGBA hash・背景RGBA hash・差分座標が揃う場合に許容します。輪郭の上下左右1画素以内という見た目だけでは、背景と同じ色の不透明pixelや半透明を区別できないため、他画像へ自動的に一般化しません。厳密比較のrawPassedと誤差を残し、確認済み画像の条件を含むcomparisonPassedを別に記録します。productionの描画やPNGはこの判定で変更しません。

1.21.4の時計・コンパス・リカバリーコンパスは、entityなしのバニラNeedleDirectionHelperが返す向き0で選ばれます。時計はclock_00、コンパスとリカバリーコンパスはそれぞれcompass_16・recovery_compass_16です。実ワールドの時刻・方角を表す画像ではありません。

atlasのanimation更新を抑止し、初期フレームを保持します。時計・コンパス等の動的property乱数を各描画passでseed=0に戻します。モデルが明示的にseedを設定するquad用乱数はバニラのまま保持します。ゲームJAR内の対象呼び出しだけをagentで変換し、依存JARは変換しません。ASMはagent専用packageへrelocateし、ゲーム同梱ASMとの衝突を避けます。

gui-deferredの1.21.6〜1.21.11ではLightTextureのGpuTextureViewが指すtextureを描画前にRGBA(252,252,252,255)へ初期化します。26.1・26.1.1・26.1.2は照明を変更しません。通常WorldのGUIは独立した1×1のUiLightmapを使用し、CPU/GPUで白255を観測しました。各9版の静的7種類・64pxは実インベントリ位置(80,121)との比較で誤差0でした。productionの既存原点基準とatlas初期化を維持し、guiReferenceは追加しません。CPU画像が存在しない1.21系はGPU値だけを記録します。全モデル・動的状態・全サイズ・他GPUへの保証は拡張しません。

deferred GUIでは各passのアトラス割当を初期化し、単品・全件で同じスロット位置を使用します。アトラスのGPU textureは再利用し、色・深度を消去します。GPU fence完了後にGUI frameと動的uniform storageを終了・resetします。

1.14.4の固定機能GUIでは、ワールド開始時に渡されるTextureManagerをBlockEntity dispatcherへ描画前に供給します。特殊アイテムのテクスチャを切り替え、直前のアイテムや起動画面への依存を防ぎます。

iconsのPNGは8bit RGBA・straight alphaで、sRGB chunk（Perceptual）を付けてRGBの解釈を明示します。[PNG仕様](https://www.w3.org/TR/png-3/#11sRGB)の色空間指定に従い、RGB値のgamma変換は行いません。rawは元JARのPNG bytesを保持します。

透過PNGは黒・白の不透明背景で同じアイテムを2回描画し、背景差からalphaとstraight RGBを復元します。GPU readbackの上下を反転し、alpha=0のRGBを0にします。byte RGB上の再合成はバニラのGUIブレンドとの一致を確認するもので、sRGB EOTFで線形化して合成するlinear-lightとは区別します。Issue #32の2版known-RGBA fixtureでは通常GUIとの差は最大2/255、linear-lightとの差は最大73/255でした。低alphaではRGBA8量子化後の元straight RGBを一意に復元できず、透明source RGBの保存や全色・全renderer・他GPUの無誤差復元を保証しません。色の条件・誤差分解・PNG chunkは[色精度の記録](color-precision.md)を参照してください。通常GUI fixtureでの再合成比較は[検証記録](validation.md)を参照してください。独立World基準との受入は、記録した静的fixture、無計装半透明の8比較、先頭frame・1000ms時計固定などの計装・制御を含む動的参照の条件に限ります。glintを含む完全無改変Worldの全ID・全状態・任意環境の一致を保証するものではありません。

Issue #31では1.20.6のstone・player_head・chest・glass・grass_blockを64pxで照合し、agent無しclientのF2画像が通常World QAと生成PNGに誤差0で一致しました。glassはbinary alphaの素材です。この静的5種類の結果を、半透明・glint・他版・他状態の受入へ広げません。条件は[検証記録](validation.md#issue-31の無計装client基準2026-10-14)を参照してください。

### canonical-v1の固定値と版別境界

canonical-v1は以下の固定描画状態の契約です。先行fixed-v1から描画の実値・renderer処理を変えず、core要求・worker ready・manifestのprofile名を整合します。adapterVersionはadapter名/1.0です。受入対象は固定51版の同じ1.0候補と、記録したDocker image・Java・renderer・確認済みホスト条件に限ります。

| 項目 | 全51版の固定値／境界 |
|---|---|
| ItemStack | 当該版バニラの既定stack、count=1、未損傷、利用者追加NBT/componentsなし。バニラ既定componentは削除しない |
| world／player／overlay | absent／absent／false。外部スキン取得なし |
| animation／partialTick | atlasの初期frame 0／0.0。atlas以外の動的propertyは下記の時刻・乱数・entityなしのバニラ判定に従う |
| 時刻／暦日 | 描画pass内1000ms・1,000,000,000ns、epoch 946684800000ms（2000-01-01 UTC）。timeoutは実時計 |
| 乱数 | 各黒・白passでseed=0。モデル自身のquad seedはバニラのまま |
| PNG | 8bit RGBA、straight alpha、sRGB Perceptual。色値のgamma変換なし、透明画素RGB=0。byte RGB合成とlinear-light合成の保証は分離 |

| adapterと対象版 | 照明／GUI投影の固定条件 |
|---|---|
| legacy-1.13：1.13〜1.13.2 | バニラ起動時の照明・原点投影を維持。GUI描画入口のlightmap無効を観測 |
| legacy-1.14：1.14〜1.14.3 | 同上 |
| gui-fixed：1.14.4、1.15〜1.16.5 | 1.14.4は起動時照明を維持、残り9版はRGB252。原点投影 |
| gui-classic：1.17〜1.19.4 | RGB252、原点投影 |
| gui-1.21.4：1.20〜1.21.4 | RGB252。1.20〜1.21.3はGUI320×240・位置(80,121)、1.21.4は原点投影 |
| gui-1.21.5：1.21.5 | RGB252、原点投影 |
| gui-deferred：1.21.6〜1.21.11、26.1〜26.1.2 | 1.21系はRGB252。26.1系は白255のUiLightmapを維持。原点投影、atlas割当reset-per-pass |
| gui-26.2：26.2 | 白255のUiLightmapを維持、原点投影、atlas割当reset-per-pass |
| gui-26.3：26.3 | 同上 |

色付けは当該版の既定stackとworld/playerなしのGUI規則を使用し、特定biome・効果・方角・実ワールド時刻を捏造しません。時計・コンパスの選択frameが全版で同一という契約はなく、前述の1.21.4の観測値を他版へ流用しません。版のexact集合は[adapters/releases.json](../adapters/releases.json)、選択はRuntimePreparer.profileForを正本とします。

deterministic=trueはcanonical固定状態を使い、同じ入力・adapter・settings・記録環境でdecoded RGBAを再現する契約を表します。任意のimage・agent・JDKを自動認証した表示ではありません。受入imageはsha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cfのLinux amd64/Mesa25.2.8・llvmpipe LLVM20.1.2（128bit/SSE4.1）です。同じDockerfileから再buildした別imageも同一受入とは扱いません。実際のagent・image・Java・rendererは既存manifestへ記録します。固定環境の反復RGBAとsingle/all一致、独立画像・色検査で受入を確認します。context/agent変更でsettingsDigestが変わるため旧fixed-v1 iconsの再開はOUTPUT_MISMATCHとなり、新出力先または明示overwriteが必要です。rawの元bytes・パス・schemaVersion=1は維持し、自動移行は行いません。任意NBT/componentsや状態の直積は対象外です。

## Docker通信切断時の終了

Dockerのattach接続が失われ、`docker run`のCLIが終了した場合は、応答待ちを`WORKER_EXITED`で解除します。closeはCLIが既に終了していても、ジョブが所有する名前のcontainerだけに削除を要求し、agent snapshotと一時iconを回収します。描画中の個別失敗は未完了のExportResultとmanifestに残り、fail-fastでは成功済み出力と未処理のパス予約を保持します。同じ入力・worker・image・renderer・設定を使う新しいinstanceで再開できます。

削除要求には選択したDocker接続先への新規接続が必要です。daemon停止や接続先全体の断絶ではcontainerの即時解放を保証しません。診断用worker.logは保持します。専用Unix代理でattachの両方向だけを切断する受入は、daemon本体の停止・再起動やネットワーク全体の断絶とは区別します。
