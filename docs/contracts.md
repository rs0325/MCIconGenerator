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

requested inventory passでバニラのfallback BakedModelまたはMissingItemModelが選択された場合はMISSING_MODEL、消費されたquad/materialのsprite名がminecraft:missingnoの場合はMISSING_TEXTUREを返します。成功PNGを出力せず、entryをfailedとして記録します。起動時や別の描画passで使われたfallbackはこの判定に含めません。対象は追跡したモデル・quadの経路で、特殊rendererが直接bindする全テクスチャやGPU cacheの完全監査は未完了です。画像の色だけでmissing判定は行いません。

cancelは現在coreのCancellationTokenと専用Dockerコンテナ終了で行います。応答中の描画を同じrender threadでキャンセルするJSON commandは未実装です。timeout、startup失敗、cancel後は所有するコンテナだけを停止します。shutdownで正常終了を待ち、失敗時は強制終了します。worker.logは診断用にcacheへ残し、一時icon PNGを削除します。起動するagentはジョブごとにsnapshotを取り、記録したSHA-256と実際に起動したbytesを一致させます。coreの通常終了・SIGTERMでは専用containerの終了を要求します。SIGKILL時の即時cleanupは保証しません。

## Manifest

[JSON Schema](../schemas/manifest-v1.schema.json)が現在の出力形式です。sorted entries、mode、exact version、client SHA-256、settingsとdigest、counts、completeを含みます。iconsはadapter、renderer、Java major、Linux amd64、context、catalogを含みます。settingsにはworker agent・mappings hashを含めます。rawのentryはsourceSha256・mcmeta path/hashを持ちます。資格情報・ユーザーの絶対パスをmanifestに書きません。

countsはそのmanifestに蓄積した成功/失敗の数です。選択IDは書き込み前にiconsではNOT_RENDERED、rawではNOT_EXTRACTEDとして予約するため、途中終了時には未処理IDもfailedに含めます。両モードで16件ごと・失敗時・終了時にcheckpointを保存します。rawの未確定mcmetaの所有権は、任意のreservedAuxiliaryフィールドへtexture ID→相対パスとして保存します。完成した付随ファイルのauxiliaryには実際のSHA-256だけを保存します。予約済みの未確定PNG/mcmetaは再開時に再生成し、所有権不明のOUTPUT_EXISTSは保持します。明示overwriteを受け付けた項目は新しい予約へ置き換え、直後のクラッシュでも再開できます。同条件で別の単品を追加した場合、以前の成功entryも残ります。completeは選択した処理が中断せず、manifest中に失敗がない状態です。全レジストリの網羅を意味するのはallを指定したジョブに限ります。別設定のoverwriteでは旧manifestを引き継ぎませんが、選択外の古いPNGまでは削除しません。

`.mcicon-<数字>.part` は書き込み途中のツール専用一時ファイル名です。通常の失敗ではfinallyで削除し、SIGKILL等で残った場合は次のエクスポートがFileLockを取得した後に回収します。対象は同じ版/modeの出力ツリー内にある、この名前に一致する通常ファイルだけです。シンボリックリンクとそのリンク先は削除対象にしません。

再開は元JAR、settings digest、renderer、保存PNG/sidecar hashを照合し、一致した成功だけ再利用します。iconsのsettingsにはversion metadata・mappings・agentのSHA-256とDocker image IDを含めます。Dockerのtagは起動前にimage IDへ解決し、そのIDでworkerを起動します。旧版mappingsは固定SHA-1のTinyを毎回検証・合成します。

## fixed-v1とcanonical-v1の受入

fixed-v1はcount=1、既定ItemStack、overlayなし、world/playerなし、partialTick=0です。ゲーム内の暦日を2000-01-01 UTCにし、描画中の経過時刻を1000msに固定します。0msでは初期化を時刻で判定するバニラcacheに影響するため、正の定数を使います。worker自身のtimeout時計は固定しません。

atlasのanimation更新を抑止し、初期フレームを保持します。時計・コンパス等の動的property乱数を各描画passでseed=0に戻します。モデルが明示的にseedを設定するquad用乱数はバニラのまま保持します。ゲームJAR内の対象呼び出しだけをagentで変換し、依存JARは変換しません。ASMはagent専用packageへrelocateし、ゲーム同梱ASMとの衝突を避けます。

deferred GUIでは各passのアトラス割当を初期化し、単品・全件で同じスロット位置を使用します。アトラスのGPU textureは再利用し、色・深度を消去します。GPU fence完了後にGUI frameと動的uniform storageを終了・resetします。

透過PNGは黒・白の不透明背景で同じアイテムを2回描画し、背景差からalphaとstraight RGBを復元します。GPU readbackの上下を反転し、alpha=0のRGBを0にします。3版の通常GUI fixture・2背景での再合成比較は[検証記録](validation.md)を参照してください。sRGB・半透明・glintを含む無改変のワールド内inventory基準画像との受入は未完了です。

canonical-v1への昇格には、固定環境の反復RGBAとsingle/all一致、意味的な画像検査、色空間の確認が必要です。現在はdeterministic=falseです。agent/contextの変更はsettings digestを変え、古いPNGを再利用しません。任意NBT/componentsや状態の直積は対象外です。
