# Windows runtimeの回収と期限

Issue #218でWindowsのphase logと失敗時の診断JSONを撤去した。coreとWindows helperは`phase-*.jsonl`や`*.diagnostic.json`を作らない。失敗は操作名・対象fileまたはdistro・exit・stderr末尾と対処をメッセージで返す。

releaseと旧jobの回復は、coreが定めた30秒の絶対期限をkeeper開始・guest清算・EOF・専用distro停止・所有照合・保存まで共有する。後続段の予約や段数による時間配分は行わない。guest要求の`timeoutSeconds`は送信時の残時間へ制限し、native待機の前後と応答確定時に期限を確認する。失敗後の直接process回収だけは最大2秒で打ち切る。

nativeのJobには`KILL_ON_JOB_CLOSE`が設定されている。hostはPIDと開始時刻でnative本人の終了を確認できれば、doneがなくてもそのJobを回収済みとする。生存中なら残期限内で待つか、対象と対処を示して止める。nativeが出力する証拠file同士の照合は行わない。guestの清算はguest応答と従来のowner・keeper・OperationJournalで別に確認する。

native本体・guest image・guest protocol・helper操作名は変更しない。詳細は[Windows helper](../helpers/windows/README.md)を参照。
