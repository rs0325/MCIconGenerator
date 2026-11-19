# 永続runtime参照v1の共通基盤

本形式は既存のjobs、lease、receipt、journal、bootstrap protocol4を変更しない追加契約である。既存admission・close・回復からの呼出し、参加必須化、保持/停止判断、終端予算の切替はまだ有効にしていない。能力ファイルの存在だけを生成許可にしてはならない。

## 信頼と能力

`ReferenceContract`は参照上限16、同時helper世代上限G=1、Windows配分Aを固定する。公開終端D=9000msは、照合前1300＋照合1600＋native回収2000＋EOF/停止後6段1700＋最終確定250＋応答150＋core abort2000。helper本体H=6850ms、照合後予約3950msである。これらは共通の宣言値であり、既存運用側のtimerを変更しない。

両helperのpackagerは`support/reference-contract.json`を同梱し、最終inventoryへbytesとSHA-256を含める。Windowsでは`app/support/`、Macでは`Contents/app/support/`に配置する。`ReferenceContract.authenticate`へ渡す相対pathはhelper archiveのrootを基準とする。

認証順は固定trustによるdescriptor署名→descriptorのinventory SHA→inventoryの能力file pin→実際に読んだ能力bytes・file identity→共通宣言との厳密一致。`LeaseTrust.bindReader`を済ませた同じtrustを使用する。runtime root内の鍵を選択しない。能力宣言自体に独立した署名を追加せず、既存の署名descriptor/inventory chainに含める。欠落・数値差・型差・未知field・重複field・末尾recordは拒否する。

## 集合・世代・sidecar

`logs/active-references-v1/state.json`は最大65536bytesの単一JSON object。envelopeは`payload`とcanonical payloadのSHA-256である`payloadSha256`だけを持つ。UTF-8、field順固定、entriesはjobId順、disk sealsはdescriptorSha256とgenerationOwner順で出力する。読取りはfield順やJSON whitespaceを許すが、型・field集合・UUID・hash・結合を厳格に検査し、重複fieldや末尾recordを拒否する。

payloadのfieldは`schemaVersion=1`、`kind=active-references`、`hostId`、`rootKey`、`bootSha256`、`epoch`、正の`revision`、`phase=ready|recovering`、`macDiskSeals`、nullableな`repair`、`entries`。entryはjobId、ownerId、descriptorSha256、attempt、phase、jobKey、receiptKey、receiptSha256、admissionBootSha256、immutable identity、generationに結合する。reservedでは実体key2つをnullとし、元ownerのPID/開始時刻を含む完全な予定identityと予定receiptを保存する。admittedでは実体keyを必須とする。実owner・receipt・署名・terminalの正当性は、形式の自己整合だけで代用しない。

generationは次の厳密な組である。

| field | 意味 |
| --- | --- |
| os、arch | descriptorのhost OS/arch |
| helperDirectory | `helpers/<os>-<arch>/<archiveSha256>` |
| helperExecutable | 同directory下のcanonical相対executable path |
| descriptorSha256 | 署名検証したdescriptor全bytesのSHA-256 |
| archiveSha256 | helper artifact archiveのSHA-256 |
| inventorySha256 | helper inventory全bytesのSHA-256 |

`ReferenceGeneration.of`は検証済み`LeaseSnapshot`から作り、実helper/JARの検査も維持する。file IDやVM/distro incarnationをこの組へ追加して置換を別世代として受理しない。保持先資格には別途同incarnationの実証が必要である。

`requireCapacity`は認証済み全entryと候補の和集合にG=1、新予約込みの参照上限16を適用する。reserved・未除外terminalも数える。保存形式のdecodeはGを超えた既存/回復対象の表現を拒否せず、新規予約の判定で拒否する。これにより上限導入時にも既存ownerのclose/回復を続けられる。未知peerを同世代と推定してはならない。

`reserve`は同じroot lockと期待stateに対して件数・世代を検査し、予約をCAS公開する。実署名・元実体を認証したgenerationだけを渡す責務は呼出し元にある。

job sidecarの保存先は`jobs/<jobId>/tmp/active-reference-v1.json`。fieldはschemaVersion、kind、hostId、epoch、attempt、jobId、ownerId、descriptorSha256、receiptSha256、admissionBootSha256、generation。`verifySidecar`は元集合と全fieldを照合する。

## 排他・CAS・観測

排他順はjob journal→rootの`locks/generations.lock`→platform/generation→短いlease CAS。`RuntimeLock`は同JVMの単一registryで取得前gateを共有し、待機者が別FDをopen/closeする前に取消し・割込み・元絶対期限を確認する。JVMの専用propertyにregistry所有の識別子を置き、別classloaderでの二重利用をFD生成前に拒否する。暗黙再入、所有thread外のtoken利用・解放、元lockの置換も拒否する。既存の運用lock入口の統一は運用接続で行い、core/helper間でtokenを移譲しない。

Windowsで認証済みidentity readerを渡す場合、`path -> trust.fileIdentity(path, scratch, end)`のように元deadlineへ束縛する。snapshot/receiptの検査には既存の取得済みreceipt channelを渡し、同じreceiptを別FDで開かない。

集合と`JobReferenceEvidence`の公開は、期待snapshotのidentityと全元bytesを比較し、同volumeの一時fileへ書込・force→atomic rename→force→readbackを行う。revisionは連続し、未終端entryのあるepochを変更しない。boot変更はrecoveringに限定する。captureを終了してからCASし、新しい実体identityでreadbackする。CAS失敗で旧snapshotを新しい値へ置換しない。

`captureIdentities`は同thread・同root tokenの保持区間だけ有効。nativeはread-only `file-identities-v1`を1batch最大256path・入力4MiB・応答65536bytesで実行する。超過scopeは同thread・同絶対期限で順次batchにし、batch間の同実体も拒否する。directory kind、link/hardlink、重複path/identity、欠落・部分応答を検査し、対象外pathへのscalar fallbackを行わない。

同phase内のhelper pinだけを集約し、元snapshotは最初の値を保持する。効果確定直前の`revalidate`はfresh identity batch→全元bytes/receiptと登録済み全helper pinのhash→fresh identity batchを行う。`helperFile`で認証した非JARのsupport executableも対象となり、同じpath/pinの重複hashは集約する。同identityのbytes改変も拒否する。

新基盤のreader/helper/JAR hashは元endを渡し、各読取りの前後で期限と割込みを確認する。検証した初期sizeと超過検出の1byteまでに読み取りを制限し、途中の短縮・追記を拒否する。captureなしのhelper hashと既存scalar `FileIdentityReader`の動作・診断は維持する。scopeをcloseした後には観測を再利用しない。process回収・一時file回収も同じendへ含め、無期限thread/joinを追加しない。

root lockの取得・解放で元失敗がある場合、release/closeの失敗は元例外のsuppressedへ追加する。通常closeでは最初の解放失敗を保持し、後続の失敗をsuppressedへ追加する。いずれもchannel closeとgate/registry解放を必ず試みる。集合・job追加証拠の一時file cleanupも、元のCAS/公開失敗をtry-with-resourcesで保持する。

## 保持補助証拠

`jobs/<jobId>/tmp/runtime-retention-v1.json`は最大65536bytesのstrict単一record。集合と同じ`payload`/`payloadSha256` envelope、固定順のcanonical出力を使う。`RetentionEvidence`で形式と元集合への結合を検証し、`JobReferenceEvidence`でCAS/readbackする。既存journal等のfieldは追加しない。

共通payloadはschemaVersion、kind、reason、hostId、rootKey、bootSha256、epoch、jobId、attempt、ownerId、descriptorSha256、generation、generationOwner、registrationKey、registrationSha256、incarnationSha256、leaseSha256、receiptKey、receiptSha256、terminalJournalSha256、keeperProofSha256、holder、stopped。

| reason | 必須の結合 |
| --- | --- |
| same-incarnation-holder | holderのjobId/attempt/ownerId/receiptKey/receiptSha256/leaseSha256/journalSha256。自己job/attemptは禁止。stoppedはnull |
| stopped | stoppedのproofKey/proofSha256。holderはnull |

形式の検証はholderの生存・実使用開始・同incarnation、停止proofの真正性、guest/nativeの不在を証明しない。呼出し元は元jobとterminal journal/keeper proofのhash、登録実体、同incarnationの実holder資格または所有VM/distroの停止を検証する。補助証拠だけで停止権限やreleased遷移を認めない。

この追加形式は未リリース。以前の試作形式への移行・互換読取りや既存jobsの削除・renameは行わない。
