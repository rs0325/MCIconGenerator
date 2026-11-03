# 初期化失敗からの復旧に必要な証拠

#98の部分成果。基準commit `3baafe5` の実装を確認した設計メモであり、自動復旧を有効化する仕様変更ではない。既存の公開API（`ManagedOptions`等）、bootstrap protocol 2、Minecraft worker/helper/descriptor protocol 1、bootstrap state schema 2とreceipt形式、Seed生成契約を維持する。この制約内では、候補固有故障と当該試行の回収完了をbootstrapが判定できず、復旧の状態遷移・不良候補記録・自動retryは実装しない。

## 復旧を許可する最小条件

旧activeへ戻して同じargvを継続するには、次の条件をすべて満たす必要がある。現行の自動判定allowlistは空のままとする。

1. 署名検証済み候補のmanifest hash・sequence・payload hash、旧active、trial invocation、CLIのPID/開始時刻と、coreのjob/owner/descriptorを同じ試行へ結合できる。REQUESTの参照一致だけではcoreのjobまでは結合できない。
2. READYより前の候補同梱物または候補実装に由来する故障で、元入力・設定・host能力・権限・通信・取消・期限切れ・共有資源競合に由来しないことを、発生元が型付きで確定する。例外名や診断文字列だけをallowlistにしない。検証段階の署名/hash不正も、実行した候補の初期化故障と混同しない。
3. 当該試行に属する全jobと生成intentが確定しており、worker/container・helper/keeper・transportの遅延生成を含む副作用が終端し、同じ所有証拠を照合したreleaseが完了している。lease生成前なら、資源生成がまだ一度も可能になっていない境界の肯定証拠が必要。単にleaseが見つからない場合はunknownとする。
4. list結果公開・Exporter呼出し前で、GO後の出力・副作用がないことを確定できる。READYは初期化完了の通知であり、回収完了の通知ではない。CAS後GO喪失は、この条件を満たすと推定しない。
5. 復旧先の旧activeの署名・archive・完全slot・host/protocol互換性を再検証する。旧activeが存在することと、同じjobを旧版で実行できることは別である。
6. 同じtrialの排他と期待stateのCASで、旧activeへの復旧と不良候補の確定を一度だけ保存できる。高水位sequenceを下げず、同sequence/同payloadの再取得・local pendingからの再試行をともに防ぐ。回収証拠やstateが変化した場合は何も確定しない。

これらが満たされても「切戻しの確定」と「旧activeによるjobの成功」は別の結果である。旧版も失敗した場合に復旧完了や無限retryへ変換しない。GCは別契約とし、失敗・不明receiptと旧slotを保持する。

## 既存の証拠と不足

| layer・根拠 | 現在確認できること | 復旧判定に足りないこと |
| --- | --- | --- |
| [bootstrap StartupControl](../src/main/java/io/github/rs0325/mcicon/bootstrap/StartupControl.java) の `validate` / `Session.ready` | nonce/invocation/slot/argv/PID/開始時刻を照合し、READYを受信する | protocol 2の通知はREADYだけで、失敗発生元・回収結果・core job参照を受信しない。EOFやtimeoutは原因を分類できない |
| [CLI StartupControl](../../cli/src/main/java/io/github/rs0325/mcicon/cli/StartupControl.java) と [Main](../../cli/src/main/java/io/github/rs0325/mcicon/cli/Main.java) の `failure` | 親切断を取消へ接続する。`McIconException.code()`をstderr、`exitCode()`を終了値へ写す | typedな発生元やcleanup結果を親へ返さない。stderrは人間向け診断であり証拠channelではない |
| [ManagedRuntime](../../core/src/main/java/io/github/rs0325/mcicon/internal/managed/ManagedRuntime.java) の `prepare` / `requireOk` / `release` | 初期化失敗時もreleaseを試み、cleanup例外をsuppressedへ残す | helperのstatus/diagnosticsは `MANAGED_SETUP_REQUIRED` または `IO_FAILED` に集約される。`MANAGED_RUNTIME_INVALID`も同梱物不正等の診断であり、試行全体の候補固有故障・回収済みを表す型ではない |
| [WorkerClient](../../core/src/main/java/io/github/rs0325/mcicon/internal/WorkerClient.java) の `await` / `terminate` / `close` | `WORKER_EXITED` / `WORKER_TIMEOUT` / `WORKER_CONTEXT_MISMATCH`等で失敗を表す。shutdownや専用containerのrmを試みる | timeout/exit/context不一致だけでは候補起因と環境起因を区別できない。rmの終了値を確認せず、IOExceptionを保持しないため、close正常returnも回収完了証拠ではない |
| [RuntimeHelper](../../core/src/main/java/io/github/rs0325/mcicon/internal/managed/RuntimeHelper.java) の `control` と [RuntimeLease](../../core/src/main/java/io/github/rs0325/mcicon/internal/managed/RuntimeLease.java) の `released` | releaseのok後、immutable identity・receipt・resources空を再確認してreleasedを記録する。失敗時はactiveを保持する | この証拠はcore jobに属する。bootstrap trialとの完全な結合と全job列挙がなく、直接helper終了だけではOS資源や未確定intentの終端にならない |
| [Bootstrap](../src/main/java/io/github/rs0325/mcicon/bootstrap/Bootstrap.java) の `awaitChild` / `command` / `activate` | command receiptのfinishedとexitCodeは識別済み直接子のwait完了を表す。READY後CASでactiveを候補へ進めてからGOする | finishedはhelper/VM等の回収証拠ではない。異常経路の直接子destroy後も回収完了としない。CAS後はtrialInvocationがnullとなり、保存情報だけではGO到達を復元できない |
| [schema 2](../../schemas/bootstrap-state-v2.schema.json) の state / commandReceipt | active/pending/previous、高水位とtrial予約、直接子の状態を保存する | 不良候補・故障分類・回収結果の欄がない。command receiptは厳密field検査され、任意fieldを追記できない |

`exit 1`は候補不具合・権限拒否・取得失敗・所有不一致などを区別しない。`exit 3`のworker errorも入力や描画に由来し得る。exit 0、suppressed例外なし、PID不在、receipt lock取得、resources空のいずれか一つから復旧を許可しない。

core内の同世代回収は再利用候補だが、対象はcurrent tool exact版/current descriptorの旧jobであり、bootstrapの世代切戻しとは責務が異なる。[既存回収契約](../../docs/managed-runtime-recovery-1.1.md)のOS終端gateと異版cleanupの制限を維持する。新しい汎用復旧frameworkや外部依存の追加では、この証拠欠落を解消できない。

## 状態遷移の境界

以下の「再開後に必要な条件」は未実装の受入条件であり、現在の成功を示さない。

| 条件 | 現在の挙動・維持する制約 | 再開後に必要な条件 |
| --- | --- | --- |
| 正常なPREPARE/READY | 期待stateのCAS後だけGOする | 復旧分岐を追加してもGO前の結果公開を許さない |
| READY前の候補固有故障 | pending/trialInvocationと旧activeを保持しunknownとする | 上記の故障・回収証拠を結合した場合だけ不良確定と旧版継続へ進む |
| 回収未了・不明receipt | 旧active保持を復旧完了と呼ばず、receipt/slotを保持する | release失敗、未知identity、欠落・置換receipt、遅延intentで復旧CASを拒否する |
| 二重起動 | state lockとtrialInvocationの予約で同じpendingの同時trialを抑える。他の起動のRUNはあり得る | 片方だけが復旧を確定し、他のjob/ownerや共有VMを停止しない |
| 親子の途中死・CAS後GO喪失 | unknownを保持する。CAS後なら候補activeの可能性もある | GO前後を推定せず、durableな試行結果と全資源の終端が揃うまで切戻し・GCしない |
| 旧manifest再提示 | `UpdateDownload.newer`が高水位以下を拒否する。これは既存pendingの不良分類ではない | 復旧後も高水位を維持し、同sequence/同payloadをlocal pendingからも再適用しない |
| 不正署名・破損・互換不一致 | 検証境界で拒否し、typedな初期化失敗へ読み替えない | 検証拒否と候補実行後の失敗を別々に試験する |
| 権限拒否・設定無効・offline・明示pin | 環境/設定の制約を候補不良としない。無効化等では暗黙trialを始めない | 復旧直前の設定変更や競合でも暗黙継続を許可せず、pinの指定版を勝手に変えない |

既存の [UpdateTrialTest](../src/test/java/io/github/rs0325/mcicon/bootstrap/UpdateTrialTest.java) は正常切替、非適格/無効化/pin、初期化失敗unknown、READY前state変更をfixtureで扱う。回収完了や不良候補の記録を検証する試験ではない。本成果では状態遷移を追加しないため、これらを自動復旧の成功試験へ変更しない。

## 追加証拠と再開判断

追加が必要な情報の責務は次の通り。名称・wire形式・保存形式の採用は未決定であり、既存fieldを流用して実装しない。

- core内部: 初期化phase、候補同梱物/実装に限定した故障分類、発生元、資源生成開始の有無、全job/owner/descriptor参照を持つ内部結果。worker/helperの一般診断をそのまま候補不良に昇格させない。公開`ManagedOptions`等の変更は必須ではない。
- coreとOS helper: 所有照合した全resource/intentの終端を集約する回収結果。既存helper protocol 1のreleaseとreleased leaseを再利用できる範囲を先に検証する。未知transportやdaemonの遅延生成を終端とできなければ不明のまま返す。helper protocolを変える必要性は、この調査だけでは確定しない。
- CLI→bootstrap境界: 試行identityに結合した故障と回収結果を、READY前失敗でも親が厳密検証して受け取る手段。現行protocol 2のREADY専用受信では表現できない。protocol拡張/版更新か、明示的に版管理する別の内部結果channelの選定が必要である。
- bootstrap永続層: 不良候補のsequence/payload/manifest、元trialと検証済み回収証拠、GO前後の確定状態を、競合・途中死後にも取り違えない記録。現行schema 2とcommand receiptに意味を隠して入れず、保存契約の拡張または別の版管理された記録契約を選定する。

推奨は、まずcore内部の故障分類と回収集約を確定し、既存lease/helperの証拠を再利用する範囲を限定してから、親への伝達・永続化を一つの契約として設計すること。stderr解析、予約値としてのexit code、preparation receiptの自由文字列result、previous/pendingの組合せへの暗黙符号化は、発生元・回収・途中死の証拠にならないため採用しない。

再開には、bootstrapの伝達・保存契約の拡張を許可するか、その場合どの版管理方針を採るかの判断が必要である。許可後は上表の正常・候補故障・回収未了・二重起動・途中死・再提示を状態遷移試験で検証し、OS側回収は対象hostの非対話検証と既存gateで別途受け入れる。本成果ではAPI/protocol/schemaの変更、実装・テスト追加、#97の包装変更、Seed変更を行わず、#98を完了扱いにしない。
