# managed準備・file identityの失敗保持（Issue #207）

## 確定した境界

起点は1.1.2の`c9dbd16`。元IO_FAILEDの原因や、#204の履歴照合コストは本変更で解決済みとは扱わない。
根拠は#207と、G4の`round-7b21e4a/io-investigation/`に保全されたREPORT.md・STATIC-ANALYSIS.md・ISSUE-112-CLI.md。

`LeaseTrust`はshared sourceで、core・helper-common・bootstrapへ同じ実装が入る。
実行前のreader認証・descriptor/inventory pin・所有判定を維持し、認証後の実行処理だけを分離する。
bootstrap固有の`WindowsSecurityReader`は別executable・別protocolなので変更しない。
追加依存は不要。JDK21のProcess・virtual threadと既存Jacksonの厳密parserを使う。

## native伝送

成功stdoutは従来のschemaVersion/volumeSerial/fileIdの3項目、成功exitは0のまま。
失敗はexit 1、stdoutなし、stderrだけに次の固定schemaを出す。

```json
{"schemaVersion":1,"phase":"file-identity","kind":"WIN32","operation":"CreateFileW","win32Code":32}
```

kindはWIN32またはCONTRACT。operationはCreateFileW、FileAttributeTagInfo、FileIdInfo、FileStandardInfoのいずれか。
OS API失敗直後にGetLastErrorを保存する。API成功後のreparse/directory/link数拒否はCONTRACT、win32Codeはnull。
Javaは512 byte未満のstderrを厳密検証し、許可済み項目だけを例外の固定messageへ移す。不正stderrや本文・pathは転送しない。
旧callerはexit 1で拒否し続ける。旧nativeの汎用stderrもNONZERO_EXITとして保持できるが、数値を推測しない。
新nativeとJavaの組合せは、後段でarchive/inventory/descriptor/pinを同版に再凍結して受け入れる。

## Javaの診断・期限

内部IOExceptionに固定phase=FILE_IDENTITY、分類、読取りbyte数、判明したexit、回収確認を保持する。
起動例外・InterruptedExceptionはcauseに残し、中断フラグを復元する。
INVALID_RESPONSEは元例外のclass名と、取得できた正の行・列だけを保持する。Jacksonのmessageにはnative応答片が入るため、元例外やlocation objectをcause/suppressed/fieldへ残さない。
起動失敗は子process起動前、pipe読取り・回収失敗はJava/OS由来、timeoutは固定messageであり、native本文を例外messageへ組み立てない。不正stderrのparse例外は破棄する。
stdout/stderrを並行して有限読取りし、stdoutは検出byte込み4096 byteに達した時点でOUTPUT_LIMITとする。
成功応答として採用できるのは4096 byte未満。stderrも検出byte込み512 byteまで。
現行の終了待ち後の無期限pipe読取りは修正する。起動後2秒の単一期限のうち末尾100msを終了回収用に確保し、読取りと終了待ちはそれ以前に打ち切る。
file-identity分岐はprocessを生成しないため、起動時に取得したProcessのchildだけを回収する。PID snapshotによる子孫列挙・終了は行わない。
pipe threadの待機も同じ最終期限までに限定する。契約外のreaderが別processへpipeを渡しても、そのprocessは終了せず、期限内にTIMEOUTと回収未確認を返す。fixtureが作ったpipe保持processは試験側が所有を照合して回収する。
回収時RuntimeExceptionもCLEANUP_FAILEDとして一次失敗へsuppressedで保持し、本処理成功後なら主例外にする。byte数・取得できたexit・中断状態を保持し、回収未確認を成功にはしない。
これは診断追加に直接必要な動作修正であり、準備全体のdeadline延長ではない。

## managed段情報と回収

helper展開、旧job recovery、Windows reader認証、admissionを内部causeの固定stageで区別する。
helper preflight/prepareもhelper段とする。元IOExceptionをcauseに残し、McIconExceptionはcode/message/exitを維持して元例外をcauseへ保持する。
回収suppressedを外側にも保持し、doctorの前提不足判定を変えない。
肯定証拠によるManagedBusyFailure/AdmissionEvidence.Busyは制御フローのまま通し、延期契約を変えない。
公開API・永続診断・lease/receipt/protocol schemaは追加しない。既定CLIは従来の型分類だけを使い、内部messageを既定/JSON出力へ追加しない。

temp/stageとhelper/leaseの回収はtry-with-resources相当で一次例外を主にし、回収失敗をsuppressedへ残す。
本処理成功時の回収失敗は主例外になる。対象外の回収処理は一括変更しない。

## 検証と後段

認証済みreader fixtureの分類・数値・cause・中断・期限・pipe保持・pin拒否、native実OSの占有/不存在/契約拒否、managed各段、一次/回収失敗の組合せとCLI既定/debug/JSONを単体・protocol試験で確認する。
Windowsのprotocol試験には、`-PleaseIdentityReader`と`-PleaseIdentityReaderSha256`に現在sourceからbuildしたnativeを指定し、同directoryへ`helpers/windows/src/test/native/identity-fixture.cpp`を`identity-fixture.exe`としてbuildする。fixtureもbuild-native.pyと同じ固定compiler flagsを使う。未指定時はfixture試験を省略するため、実行件数と省略を区別する。
実OSの限定試験は`helpers/windows/scripts/test-native-identity.py --native <生成PE> --temporary <担当build内>`、API全失敗点の合成試験は`test-native-identity-api.py --cxx <compiler> --temporary <担当build内>`で実行する。
第1段ではVM/WSL/Docker/Lima・Mac接続・実E2E・公開を実行しない。
後段で変更core依存のworker/CLI/library/payload/seed/installerを再buildし、native/helperを再凍結する。
両OSの固定51版×2、API/managed、更新/resume/旧reader、bootstrap組合せ・配布経路を新しい入力pinで再受入する。元証跡・元非成功採点は変更しない。
