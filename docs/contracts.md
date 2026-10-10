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

UTF-8、1行1JSON、stdin=request、stdout=protocolのみ、stderr=ゲームログ。coreは最初のreadyがprotocolVersion=1であることを要求します。catalog/render/shutdownには一意なrequestIdを付け、返答の不一致は致命エラーにします。requestは1MiB、responseは16MiBを上限とします。待受ポートは使いません。

```json
{"type":"ready","protocolVersion":1,"renderer":"llvmpipe / OpenGL"}
{"requestId":"1","command":"catalog"}
{"requestId":"1","type":"result","entries":[{"id":"minecraft:stone","kind":"block","status":"available","blocks":["minecraft:stone"]}]}
{"requestId":"2","command":"render","id":"minecraft:stone","size":64,"path":"/mcicon/cache/worker-.../icon-....png"}
{"requestId":"2","type":"result","result":{"path":"...","visiblePixels":2744}}
{"requestId":"3","command":"shutdown"}
{"requestId":"3","type":"result"}
```

エラーはtype=error、code、messageを返します。PNGは専用cache内の一時ファイルで渡し、coreでサイズ・alphaを検証してから出力へ原子的に移します。PNGバイナリをstdoutへ流しません。workerは一つのrender threadで要求を処理し、全件ジョブで再利用します。

cancelは現在coreのCancellationTokenと専用Dockerコンテナ終了で行います。応答中の描画を同じrender threadでキャンセルするJSON commandは未実装です。timeout、startup失敗、cancel後は所有するコンテナだけを停止します。shutdownで正常終了を待ち、失敗時は強制終了します。worker.logは診断用にcacheへ残し、一時icon PNGを削除します。

## Manifest

[JSON Schema](../schemas/manifest-v1.schema.json)が現在の出力形式です。sorted entries、mode、exact version、client SHA-256、settingsとdigest、counts、completeを含みます。iconsはadapter、renderer、Java major、Linux amd64、context、catalogを含みます。settingsにはworker agent・mappings hashを含めます。rawのentryはsourceSha256・mcmeta path/hashを持ちます。資格情報・ユーザーの絶対パスをmanifestに書きません。

countsはそのmanifestに蓄積した成功/失敗の数です。同条件で別の単品を追加した場合、以前の成功entryも残ります。completeは選択した処理が中断せず、manifest中に失敗がない状態です。全レジストリの網羅を意味するのはallを指定したジョブに限ります。別設定のoverwriteでは旧manifestを引き継ぎませんが、選択外の古いPNGまでは削除しません。

再開は元JAR、settings digest、renderer、保存PNG/sidecar hashを照合し、一致した成功だけ再利用します。iconsのsettingsにはversion metadata・mappings・agentのSHA-256とDocker image IDを含めます。Dockerのtagは起動前にimage IDへ解決し、そのIDでworkerを起動します。旧版mappingsは固定SHA-1のTinyを毎回検証・合成します。

## canonical-v1の目標と現状

受入後のcanonical-v1はtick=0、partialTick=0、seed=0、時刻=0、暦日=2000-01-01 UTC、world=null、player=nullのcontextを版別に実装する計画です。worldなしでも乱数を使うclock/compass等はモデルのpropertyを一定値にし、アニメーション・glintの時間も固定する必要があります。

現行preview-v1は既定ItemStackとoverlayなしだけを適用しています。時間・乱数・暦日・atlas animationを固定できたと誤認しないようdeterministic=falseです。canonical-v1へ移行するとsettings digestが変わり、古いPNGを再利用しません。任意NBT/componentsや状態の直積は対象外です。
