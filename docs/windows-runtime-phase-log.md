# Windows旧lease回復のphase診断

Windowsのcoreとhelperは、検証済みjobの `jobs/<job-id>/tmp/phase-<UUID>.jsonl` にphase診断を保存する。成功時も記録し、初回doctorと再試行の記録を分けて比較できる。実reboot後の初回TIMEOUTは未再現であり、この計測追加だけで原因確定・修復完了とは扱わない。

## 記録と結合

各行は診断schema 1のJSON。`actor`、`operation`、`phase` は固定ラベル、`jobId` は操作対象のjob、`actorPid / actorStartedEpochMillis` は現在のcontroller/helperである。旧jobの元ownerと現在のactorを混同しない。keeperとguest交換には、存在する場合 `attempt`、子の `processPid / processStartedEpochMillis` も付く。

`nanoTime`、`deadlineNanos`、`remainingNanos`、`budgetNanos` はナノ秒単位。期限なしはdeadline/remainingがnull。差分は同じactor内のmonotonic時刻で算出し、別JVMの起点が同一だと仮定しない。`epochMillis` とPID開始identity、job/attemptを併用して各actorを結合する。時刻はログ記録時点であり、guest内部の実開始・終了時刻そのものではない。

記録対象は次の境界である。

- core: 回復開始と呼出し元予算、keeper spawn・ACK、release本体期限と送信、応答待機・読取り、abort。
- Windows helper: payload検証、絶対期限への変換、journal/generation lock待機、keeper所有照合とnative開始・guest ACK、daemon回復、guest release、keeper EOF・completion読取り、lease回収確定。
- native交換: subcommand上限、spawn前後、guest送信時の予算、native待機・終端、応答読取り、失敗後の回収・terminal/completion読取り。

native交換の失敗診断には `operation`、`failedPhase`、pathを含まない `phaseLog` 名が付く。guest operationは既知の固定名に限定し、argv・request本文・root path・例外文言・利用者名を新しいphaseログへ書かない。

失敗診断の `mode / completionRequired` は実ABIを表す。plain `exec` はProcessのPID・開始時刻、intentの実行物・親identity、bootのUUID形式、state/doneの一致を検査してnative Job終端を判定し、生成されないcompletionを要求しない。`exec-bridge` とkeeperの終端には従来どおりcompletionが必要である。execのrecord欠落・identity不一致は `terminalUnknown`、bridgeのcompletion確認失敗は `completionUnknown` として残す。stateが正常でもdone欠落時は `terminalUnknown=true` と `terminal-unknown` イベントを記録し、`terminal-read-done` を記録しない。native Job終端だけではguestの解放完了を証明しない。

ログ保存失敗は固定stderr行 `MCICON_PHASE_LOG_UNAVAILABLE` で一度だけ通知し、そのloggerでの追加記録を停止する。元操作の成功・失敗、typed busy、cleanup証明をログの有無で変更しない。不完全な末尾行やログ欠落を補完して成功証明にしてはいけない。

## 期限の関係

controllerや新規jobが渡した壁時計の絶対期限は、変換時点の壁時計とmonotonic時刻を対にして変換する。helper初期化前のmonotonic開始時刻へ残時間を加えると、初期化済み時間を再び差し引いてしまう。相対上限の通常releaseや明示的な共通lock期限は変更しない。

native交換は既存の回収予約2秒とsubcommand上限から、1つのmonotonic実行期限を確定する。spawn後に新しいsubcommand予算を開始し直さず、guest要求の `timeoutSeconds` を送信時の同じ期限の残時間以内へclampする。元要求が持つ短い上限も維持する。転送・guest側での読取りや処理開始の遅れはphaseログで別途確認する必要があり、相対秒数だけでguest内部全処理の絶対期限遵守を証明するものではない。

通常releaseの各native呼出しは、同じ絶対期限からnative回収2秒と後続必須段ごとの250msを引いた残時間を上限にする。現在段にも250msの最小実行枠がなければ、nativeを開始せずnative-budgetで期限不足を記録する。250msは固定の打切り上限ではなく、未使用時間は後続へ渡す。keeper EOF後は所有確認・一覧・停止・再一覧・最終所有確認の5段を見込み、2秒＋5×250ms=3.25秒を予約する。初期factsは後続8段、lock内owner factsは7段、guest releaseは6段（EOFを含む）、停止後は4/3/2/1/0段を予約する。全体のclose10秒・core keeper上限9秒・helper本体約6秒とlock期限は延長しない。

段別予約は通常closeの変更であり、旧lease回復releaseは既存の予算・EOF後予約3秒を維持する。lock内でownerを検証したHostFactsのbootは、その直後のnative reapにも使い、重複factsだけを削減する。停止前後のfresh factsとowner照合は省かない。

native-requiredイベントのbudgetNanosは、回収2秒＋現在/後続必須段の最小枠を表す。references-start/doneで参照走査を他の前後処理と区別する。いずれも固定phase名と時刻だけを追加し、argv・利用者名・任意requestは記録しない。

固定した実行期限は、native待機の前後と応答読取り後の正常結果確定時に検査する。待機開始が遅れ、既にexit 0で終了している場合も、期限超過後の `waitFor(0)` 成功を元操作の成功にはしない。結果確定前の超過はTIMEOUTとして通常の失敗診断・cleanupへ渡す。

現在のguestでこの値を実際に使うのは主にcreate/image loadであり、daemon-recoverやrelease-recoverがこの値だけで停止するとは保証しない。元の回復TIMEOUTがこの修正で解消したとは扱わず、reboot traceで失敗subcommandと残予算を確認する。

`done / jobEmpty / nativeExit=0` がcleanup中に得られても、要求期限を超えた元TIMEOUTは失敗のまま返す。phaseログは診断専用で、native receipt/completion、guest所有・応答、lease/journal CASの代用にはならない。helper protocol 1、lease/receipt、native ABI、Bootstrap state、通常closeの上限は変更しない。
