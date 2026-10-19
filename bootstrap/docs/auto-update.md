# 次の適格起動で適用する更新準備

package所有Java/固定bootstrap entryから、5分の有限準備JVMを一つの作業ownerとして起動する。CLI終了後にdownloadを待たず、結果はuser rootの既receiptへ保存する。bootstrap自身のJava・trust・installer所有prefixをpayload updaterが更新することはない。正式URL・本番trustは未発行である。

## 利用条件

実CLI parserが返したonlineのmanaged list/renderだけが暗黙準備・trialの対象となる。raw/help/version/doctor/extract/cache、明示Docker、offlineでは更新準備用process・更新取得通信を開始しない。`config.json`の`autoUpdate`は既定trueで、次で将来の準備を無効化できる。

```sh
mcicon --bootstrap-auto-update false
```

この指定は単独で使い、render/helper準備を行わない。進行中の別準備をPIDだけで停止せず、publish前の設定再検査でも無効化を確認する。offlineは当該起動の取得抑止であり、別起動が既に許可したnetworkを停止する意味ではない。

明示tool pinはbootstrap先頭の専用引数で、Minecraft版のCLI `--version`とは別である。

```sh
mcicon --bootstrap-pin 1.1.0@<payloadの64桁SHA256> --help
```

署名済みmanifest/archiveと完全一致する既local slotだけを選択し、取得・pending消費・暗黙昇格・旧版fallbackをしない。指定版が無い/壊れている場合はその指定で診断失敗する。

配布packageの`stableManifestUrl`は明示したHTTPS manifestとその`.sig`の取得先を指定する。未構成なら旧CLIを継続し未適用をstderrへ示す。packagerへ`--stable-manifest-url`、Gradle app-imageへ`-PbootstrapManifestUrl`で渡せるが、既定の製品URLを仮定しない。

## 有限background所有

元REQUESTの残時間に、ACK候補5秒・失敗回収3+2秒・RUN等1秒を予約できるときだけ起動する。ACKは開始前の費用で、job終了後の待機は0。cold JVMの5秒受入はOSごとの確認事項であり、nativeなprocess起動callをJavaで必ず中断できるとは保証しない。時間不足はspawnせず旧CLIのRUNを優先する。

stateへ予約UUIDと期限を先に保存し、既runs receiptを固定inodeで排他保持する。ACKのPID/start・固定entry/Java/JAR hashを親が照合し、durable所有移譲の後GOする。GO前はnetworkを始めない。短いACK pipeは閉じ、背景の診断は`logs/<UUID>.stderr`へ分離しCLIのstdioを保持しない。`runs/`はJSON receiptだけを走査し、未知fileは無差別にskipしない。期限で自作業を取消し、5秒尾部後の自己終端を監督する。取得不能/破損/途中死はpartialを保持する。

unknownの新準備には、期限超過、予約時と同じreceipt inodeのlock取得、旧UUIDのstate CAS fenceを全て必要とする。lock保持channel自身からreceiptを読み、別channelのopen/closeでOS lockを解放しない。旧workerをresume/killせず、作業許可が最大一つになるようUUIDを照合する。物理JVMが常に最大一つという保証ではない。

fileKeyはJDK/OS固有表現をstateとreceiptへ保存する。取得不可・不一致はunknownのため更新を保留する。Macの別JVM lock・同bytes別inode拒否を直接確認したが、Windows/Linuxの跨JVM表現を受入済みとはしない。

## pendingと次回適用

署名、host/protocol、実size/SHA、inventory、ZIPのpath/link/重複/展開上限と完全slotを検証してから、pendingと高水位sequenceを同じatomic state replaceで保存する。同sequence別内容・旧sequence・同版別payloadの暗黙採用を拒否する。実行中のslot/receiptを変更しない。この起動で取得した候補は同じ起動では適用しない。

起動開始時に存在するpendingだけを次の適格managed list/renderで試行する。旧parserへSWITCHし正常reapしてから、別receiptの候補をPREPAREする。候補自身のslot/argv/実parser判断を照合し、catalog後・Exporter/結果公開前のREADYで待つ。期待active/pending/trialInvocationのCAS後だけGOする。準備失敗、receipt消失、途中死、CAS後GO喪失はunknownとして証拠を残す。

PREPAREの外側transaction期限は実preparation/startup/item予算の和で、READY/GOは残期限と30秒の短い方とする。core内部のcold処理がその和で必ず完了する保証ではなく、公開ManagedOptionsの期限意味も変更しない。候補固有故障/実資源回収のtyped証拠が無いため、同jobの旧版fallback・自動retry/rollbackは行わない。旧active保持だけを復旧完了とは呼ばない。GCも実装せず旧slot/不明receiptを保持する。

## 保存形式と検証範囲

[bootstrap state schema 2](../../schemas/bootstrap-state-v2.schema.json)と既receiptのkindを使用する。未release1.1の旧開発root/packageは新しいQA世代へ再生成し、移行/fallbackしない。bootstrap制御はprotocol2のまま、Minecraft worker/helper/descriptor protocol1も不変である。

直接fixtureでは所有予約・別JVMのOS lock・inode置換・自己期限、有限HTTP代役、署名取得からpending保存、disable競合、適格trial/非適格据置・明示pin・初期化失敗unknownを確認する。Mac app-imageではTLS鍵不要の127.0.0.1 handshake保留により親終了後worker継続と失敗終端を確認する。HttpClient代役の取得成功は実TLS配信成功ではない。

正式配布trust/URL、実installer/Homebrew/winget、Java未導入実host、Windows/Linux backgroundとfileKey、管理helperの実描画・51版、typed回収による復旧完成は未受入である。#97/#98をcloseする成果ではない。
