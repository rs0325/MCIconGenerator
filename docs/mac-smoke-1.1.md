# Mac候補資材の限定スモーク（D2）

Refs #91、#94。2026-11-04、`origin/main` `92a6996` から専用ブランチ `feature/issue-94-mac-smoke` を作成した。

**D2は未受入。OSのcode-evaluation promptを確認したため停止した。** 正式配布・本番署名の受入ではなく、親Issueはcloseしない。実測値と失敗履歴の正本は[QA記録](../qa/mac-smoke-2026-11-04.json)。

## 環境・固定入力

macOS 15.8.1（24H32）、arm64、RAM 24 GiB、開始時空き145,520,074,752 bytes。`kern.hv_support=1`。Rosettaの2つのホストpathは存在したが、VZ API availabilityとguest内変換は未確認。導入操作は行っていない。

Macの作業先は新規 `/Users/ryuman/tmp-mcicon-d2`。HOME、TMP、Gradle/Maven/Swift cacheと出力をその配下に隔離した。既存JDK21を選び、D1保全済み公式入力をWindowsから転送し、Lima 2.2.1、Ubuntu noble arm64、固定3debの実SHAがD1 pinに一致した。Macからのdownloadは行っていない。

D1と同じ `installDist/test --offline --no-daemon --rerun-tasks -PreleaseVersion=1.1.0` → `prepare-payload.py` → `package-helper.py --app-version 1.1.0` → `verify-packaging.py` を1回実行した。196file、206,610,229 bytes。今回ZIPは125,853,872 bytes、SHA `2466ed0ea8447e735d7384faf2c4c98abb996158742c60fc4b9a38cbd9c2dca4`。

D1最終ZIPとは一致しない。manifest比較では193fileが一致し、LICENSE本文・launcher・outer sealの3fileが異なる。原因の全面確定は未実施で、D1の最終bytesそのものを受入したとは扱わない。

rendererはE1保全済みの元ID指定saveを読み取り、既存 `docker/export-candidate.py` の `normalize` と同じgzip設定で再包装した。元save・正規化tar・tar.gzの全SHAがE1 pinと一致。593,385,993 bytes、SHA `dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4` をMac転送後にも照合した。今回 `docker save` 自体を再実行した結果ではない。

## 判定

| 項目 | 判定 | 実測・制約 |
| --- | --- | --- |
| a native起動 | 一部確認・停止 | 最終helperの移動後/ZIP再展開後launcherはinvalid lease拒否まで到達。host-probe/process-guardはcompileのみ。limactlでOS許可待ちを観測 |
| b VZ/arm64 guest | 未実行 | OS promptにより停止、VM/LIMA_HOME未作成 |
| c offline deb/daemon | 未実行 | 入力SHAとpayload検証まで |
| d amd64 renderer | 未実行 | 再包装・SHA照合・転送まで。load/GLX/llvmpipe/SSE4.1/Java4系統は未確認 |
| e 回収・前後比較 | PASS（VM未作成） | 専用path削除、既存Lima/仮想化状態一致、D2 pathを含む残存command 0 |

## OS許可待ちと手順上の問題

同梱 `limactl shell --help` は20秒timeoutとなった。17:38:55 JSTのOSログに `syspolicyd` の `Prompt shown (1, 0), waiting for response` と `CoreServicesUIAgent` の `present code-evaluation prompt` がある。観測時のlimactlはad-hoc署名で、`xattr -l` は空だった。quarantineの追加/除去やGatekeeper設定変更はしていない。

**このhelp起動をpackaging完了前に並行開始したのは実行手順の誤りである。** 生成途中treeと重なっているため、最終候補の初回起動拒否とは断定できない。一方、最終helper launcherのinvalid lease到達だけから、同梱Limaの許可不要・正式配布の初回起動成功も推論しない。

ログでpromptを確認した時点で追加候補起動を停止した。GUI操作・目視・許可応答・回避操作・再試行はしていない。OS管理promptが終了したかは未確認で、OSプロセスを停止していない。

既存 `native-acceptance.py` は `/private/tmp` の短cacheを使い、今回の隔離範囲に合わない。新しいVM runnerを実行する前に停止条件へ到達したため、使い捨てVM runnerの追加・実行は未実施。今回の準備・観測用スクリプトとログは `build/d2/runners/` と `build/d2/` に保全した。再開時はbuildの完了・最終bytes照合を必ず先に待ち、候補起動を直列化する。

## 検証・後始末

Mac helperテストは45件中44成功・1skip、失敗/error 0。skipは実host-probe保存stdout未指定による。packaging検証は成功し、署名/tree/移動/ZIP再展開/invalid lease拒否を確認した。renderer包装の既存回帰2テストは成功。空白差分とQA checksumの検証結果は `build/d2/validation.json` に保存する。これらを実VM成功とは扱わない。

開始・終了とも `~/.lima` 不存在、PATHのlimactl無し。既存領域を生成する可能性がある `limactl list` は実行せず、不存在を一覧相当として記録した。前後の仮想化process抽出結果は一致する。部分一致の `utm` はInputMethodKit/TextInputMenuAgentの2行にも一致しており、実VMプロセスではないことを区別した。

証跡とhelper ZIPをWindows `build/d2/evidence.tar` へ回収し、VM/LIMA_HOME未作成・所有process不在を確認して `/Users/ryuman/tmp-mcicon-d2` を削除した。最後に全command引数を含むD2 path参照も0件と確認した。既存VM/ツール、brew、システム設定、鍵・認証情報には操作していない。sudo・Minecraft・公開操作は行っていない。

## 未検証・残作業

D2のb〜d、aのhost-probe/process-guardと最終候補の逐次初回起動が残る。OS promptが発生したため現在の範囲で再試行せず、対話が不要な候補/許可経路と再実行方針をメインで確定する必要がある。GUI許可操作の依頼を今回の範囲に追加しない。

実Minecraft描画、全51版、OS警告の正式配布経路、実reboot、Homebrew実install、正式署名なし配布の更新後起動、guest全体の無通信も未検証。既存候補の過去成功を今回へ流用しない。commit・push・PR作成は禁止範囲のため未実施。公開用文面だけを `build/` に保全した。
