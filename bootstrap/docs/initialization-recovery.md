# 初期化失敗からの自動復旧

#98の実装範囲。bootstrap制御をprotocol 4、active stateをschema 4、command receiptをschema 3とする。公開API（ManagedOptions等）とMinecraft worker/helper/descriptor protocol 1は変更しない。外部依存を追加せず、既存の署名slot検証、receipt lock、file identity、state lockと期待stateのCASを再利用する。

## 候補固有故障と回収境界

自動復旧のallowlistは、core内部のBundledPinsFailureで確定した同梱pins.jsonの欠落・重複・JSON/構造不正だけとする。公開例外のcodeはMANAGED_RUNTIME_INVALIDを維持し、CLIは内部原因型を識別する。code、例外名の文字列、stderr、exit値だけから判定しない。

この型を作れるのはManagedRuntimeのpins読取・検証境界だけで、RuntimeStore作成・既存job回収・lease/helper/worker/管理runtime用transport生成より前である。CLIのlist/renderは単一の管理jobを開始するため、この故障なら全job集合が空であり、資源生成intentも一度も可能になっていない。この肯定証拠をcleanup=NOT_STARTED、jobs=[]と表す。leaseが見つからないことを証拠にはしない。pinsの実読取IO（権限拒否等）はこの型に変換しない。hostの未対応、入力・設定・通信・取消・期限・競合もallowlistに含めない。

helper/worker起動後の失敗・release成功・resources空は試行全体の回収完了を証明しないため、現時点ではUNKNOWNである。既存protocol 1の回収を拡張せず、jobを伴う通知も受け入れない。OS helperの実資源回収、未知transport、遅延intentを復旧対象へ広げるには、別途その終端証拠の設計・受入が必要である。

## Protocol 4

REQUEST/RUN/SWITCH/PREPARE/READY/GOは既存の意味と有限期限を維持し、各messageのprotocolを4にする。protocol 2/3の相手は厳密検証で拒否する。READY後・GO前に結果公開やExporterを呼び出さない。

PREPAREに限り、READYの代わりに次のFAILEDを送れる。CLIはコマンド終了処理を経て、制御socketを閉じる前に送信する。親切断・READY以降・期限切れでは復旧通知を生成しない。

| field | 内容 |
| --- | --- |
| protocol/type | 4 / FAILED |
| nonce/invocation/slot/pid/startedAt | その接続のREQUESTと完全一致する試行・候補・CLI process |
| failure | BUNDLED_PINS_INVALID または UNKNOWN |
| cleanup | NOT_STARTED または UNKNOWN |
| jobs | 空配列だけを受け入れる |

親は全fieldを厳密検査し、REQUESTのargv・parser判断・予算と候補slotも照合する。FAILEDをdurable receiptへ保存し、直接子を有限期限でwait/reapする。EOF・timeout・未知field・置換identityはUNKNOWNのまま保持し、READYや回収完了へ読み替えない。直接子の終了だけでは復旧しない。

## State schema 4と復旧CAS

[state schema 4・receipt schema 3](../../schemas/bootstrap-state-v4.schema.json)を正本とする。preparation receiptとconfigはschema 1のまま。旧state schema 2/3の定義は履歴として保持する。信頼集合指紋の結合は[信頼集合](trust-set.md)に従う。

- command receiptにstartup（request/run/switch/prepare/ready/go/failed）、initialization（FAILEDの全証拠またはnull）、receiptKey、argsSha256を追加する。argv hashはJSON化した元argv bytesのSHA-256。invocationId、候補slot、親子PID/開始時刻と合わせて試行へ結合する。
- READY受信、FAILED受信、GO送信前をreceiptへ保存する。goは送信許可の記録であり到達証明ではない。CAS後GO喪失を復旧対象にしない。
- stateにbadCandidatesを追加する。各記録は候補slot（manifest/payload/descriptor hash）、sequence、previousActive、invocation、CLI process、receiptKey、argsSha256、FAILED証拠を持つ。
- 記録上限は32件。上限後の失敗はunknownを保持して停止し、古い不良候補を捨てて再適用を許可しない。GCは行わない。

復旧は同じcommand receiptのlockを保持したままstate lockを取得し、次を再確認して一度だけ確定する。

1. failure=BUNDLED_PINS_INVALID、cleanup=NOT_STARTED、jobs=[]で、同じslot・invocation・PID/開始時刻に属し、READY/GOへ進んでいない。
2. 直接子の終端、同じlock保持channelから読んだfinished receiptの完全一致、予約時と同じ物理receiptのfile identityが揃う。消失・置換・改変は拒否する。
3. 期待state全体、active/pending/trialInvocationが変化せず、autoUpdateが有効で、不良payloadの再試行でない。
4. 旧activeの署名manifest、archive、完全slot、host/protocol適合を再検証する。
5. 同じatomic replaceで不良候補を追加し、pending/trialInvocationを消す。activeは旧世代を保持し、高水位sequenceを下げない。

その後、設定と旧activeを再確認して同じargvを一度だけRUNする。旧版によるjob成功と切戻し確定は別であり、旧版の終了値をそのまま返す。失敗を成功へ変換せず、無限retryもしない。

再提示された高水位以下のsequenceは従来通り拒否し、badCandidatesと同じpayloadも新たなsequenceを含め取得時に拒否する。local pendingへ同じ不良payloadが残っていてもtrial予約・適用を行わない。明示pinは指定版の実行契約を維持し、暗黙切戻ししない。

## 途中死と再開条件

FAILEDを受け取れない場合、回収不明、receipt欠落/置換、state/settingsの競合、親子の途中死ではpending/trialとreceipt/slotを保持し、復旧完了とは扱わない。同じtrialを別起動が奪わず、他job/owner/VMを停止しない。自動resumeやGCは実装しない。

復旧CAS前に親が死んだ試行は、次回起動で終了済みPIDやreceipt lockだけから再開しない。完全な全資源終端証拠・所有照合と再開CASを別途実装するまではunknown保持である。CAS後は不良候補記録とpending消費が原子的に残るため、同じ候補へ戻らない。旧版jobをまだ開始していない可能性は成功扱いにしない。

## 未リリース形式と包装への依存

schema 2/3 state、schema 1 command receipt、protocol 2/3制御相手・package metadataは未対応として拒否し、移行・互換fallbackを追加しない。旧rootは調査に必要なreceipt/slotを保全し、別の新しいQA root/packageを再生成する。旧rootを消失stateとして再baselineしない。

bootstrap-package.jsonのbootstrapProtocolは4が必要であり、Mac・Windows生成器の出力も4へ合わせる。package metadataのschemaVersion=1は維持し、署名SeedのminimumBootstrapProtocolは4を必要とする。これは旧CLI payloadをprotocol 4へ変換するものではなく、実CLIがprotocol 2なら起動時に拒否する。installerは前段metadataを引き継ぎ、配布統合側でprotocol 4対応CLIを含む実packageを生成・受入する必要がある。

## 検証

UpdateTrialTestは正常切替、候補固有故障からの旧版RUN、不良payload再取得/local再試行拒否、回収UNKNOWN、二重起動、設定競合、receipt置換、READY前state競合、子の途中死、disable/offline/pin、schema 2拒否を扱う。StartupControlTestは失敗通知の全identity・protocol 2・未対応jobを拒否し、CLI側では実coreの型付き原因と権限拒否の通知を区別する。ManagedRuntimeTestはpins欠落/不正が資源生成前であることと、read権限拒否を昇格しないことを検証する。署名不正・破損拒否は既存SeedTestで別途維持する。

これらのfixtureを実OSのhelper回収・実描画の受入とは区別する。#98全体のcloseを意味しない。
