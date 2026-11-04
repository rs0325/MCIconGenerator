# Mac候補資材の限定スモーク（D2 / D2b）

Refs #91、#94。**2026-11-04のD2bでは、隔離属性を除去せず構築した候補で限定スモークa〜eが成功した。** 正式配布・Minecraft・製品全体の受入ではなく、親Issueはcloseしない。基準は最新の `origin/main` `17ab285537f5b4e943868f7433bd422f8fcdc9f8`、作業ブランチは `feature/issue-94-mac-smoke-2`。

今回の実測値・失敗履歴・VM設定・前後比較は[D2b QA記録](../qa/mac-smoke-2026-11-04b.json)と[checksum](../qa/mac-smoke-2026-11-04b.json.sha256)に保持する。[前回D2記録](../qa/mac-smoke-2026-11-04.json)は変更しない。D2で包装完了前にlimactlを並行起動した手順は未受入のままで、今回の成功で過去の失敗を取り消さない。

## 環境と入力

macOS 15.8.1（24H32）/arm64、RAM 24 GiB、開始時空き144,091,652,096 bytes。host-probeでVZ対応、Rosetta `installed`、CPU 10を確認した。Rosettaの導入・同意操作は行っていない。

Macの新規 `/Users/ryuman/tmp-mcicon-d2b` だけを使い、HOME・TMP・Gradle/Swift cache・出力・`LIMA_HOME=/Users/ryuman/tmp-mcicon-d2b/l` を配下に隔離した。既存JDK21とApple toolchainを利用した。取得元・入力pinは[D1候補](mac-helper-candidate-1.1.md)と[source記録](../qa/mac-helper-sources-2026-11-04.json)のまま。Windowsに保全済みの公式入力・offline依存をscp転送し、Macでcurlやブラウザによる取得は行っていない。

Lima 2.2.1、Ubuntu noble arm64 release-20260926 qcow2、3debは既存のsize/SHA検証を通した。VMはVZ/aarch64、4 CPU、9 GiB、24 GiB。guestの実表示はUbuntu 24.04.5 LTS。guestagentも固定payloadを使用し、既定containerd取得・依存パッケージ解決は無効化した。Limaの「download」ログはローカル `inputs/base.img` の読み込みを指す。guest全体の無通信をパケット単位で確認した結果ではない。

## 隔離属性の切り分け

各工程のprocess終了を確認してから、次の工程を開始した。候補起動前に転送・展開・build/包装後の `xattr -lr` を記録した。属性の削除・追加・変更は行っていない。

| 観測境界 | 実行した操作 | quarantine / provenance | その他の属性 |
| --- | --- | --- | --- |
| 転送直後 | 保全済みtarとGit archiveをscp転送 | なし / なし | 全再帰出力が空 |
| 展開後 | Python 3.12 tarfile、filter=data | なし / なし | 全再帰出力が空 |
| build/包装後、候補起動前 | offline Gradle、payload準備、jlink/jpackage/native生成・署名 | なし / なし | cpu-probe上にcom.apple.cs.* 4属性 |
| 移動・ZIP再展開後 | 既存verify-packaging.py、Python zipfileと実行bit復元 | なし / なし | 検証出力treeの再帰属性が空 |
| renderer転送後 | E1固定saveを再包装してscp転送 | なし / なし | 当該tar.gzの属性が空 |

`cpu-probe` の属性は `CodeDirectory`、`CodeRequirements`、`CodeRequirements-1`、`CodeSignature`。包装工程後に初観測したコード署名属性であり、quarantineとは区別する。個々の署名呼出しごとの付与主体は単独検証していない。binary属性をUTF-8として読んだ記録処理が失敗したため、raw bytesとhexへ切り替えて再取得した。属性を操作して記録失敗を回避していない。

**今回のscp → Python展開 → ローカルbuild経路では、quarantine/provenanceの発生源は観測されなかった。前回の発生源は未特定である。** ユーザーが前回確認した「MCIconHelperは壊れているため開けません。ゴミ箱に入れる必要があります」「ダウンロード日は不明」のダイアログはキャンセル済み。当該appの属性実測がないため、その表示をquarantine起因と確定せず、前回limactlのsyspolicyd promptとの同一性も未確認とする。ブラウザ・Mac curl・Finder/Archive Utility・正式配布URLの経路は今回実行していない。

## a〜eの実測

| 項目 | 判定 | 実測と限界 |
| --- | --- | --- |
| a native起動 | PASS（限定） | 包装完了後のhelperがinvalid lease拒否まで到達。host-probe、process-guard、limactl --helpが逐次終了。正規leaseでのhelper管理VM一連操作は対象外 |
| b VZ/arm64 guest | PASS | 固定UbuntuでVZ起動、aarch64/nobleを確認。専用LIMA_HOMEのみ使用 |
| c offline deb/daemon | PASS | runc/containerd/docker.ioをguest system provisionでdpkg導入し、両daemonがactive |
| d amd64 renderer | PASS | E1固定SHAを照合してload。固定ID/linux/amd64、Rosetta binfmt、SSE4.1 CPUID、Java 8/17/21/25、Xvfb/GLX llvmpipe成功 |
| e 回収・前後比較 | PASS | 試験containerなし、VM通常停止・削除、専用path削除、所有processなし。観測した既存Lima/仮想化状態が一致 |

nativeの各起動後、VM実行期間、包装検証後にOSログを確認し、`Prompt shown` / `present code-evaluation prompt` / `waiting for response` はなかった。候補のsignal終了・timeoutもなかった。GUI目視は行っていないため、これはOSログとprocess結果に基づく判定である。quarantine削除、spctl変更、Gatekeeper無効化、GUI操作・許可応答は行っていない。

固定debの実版はrunc `1.3.4-0ubuntu1~24.04.1`、containerd `2.2.1-0ubuntu1~24.04.3`、docker.io `29.1.3-0ubuntu3~24.04.2`。初期VMの一般ユーザーではDocker socketへのアクセスが拒否された。provision編集後の再起動ではgroup設定が反映されず、guestの/tmpに置いたrendererも消えたため、そのVMを通常停止・削除した。新しい専用VMでは初回system provisionでguestユーザーをdocker groupへ追加し、rendererを再転送した。

再生成後も既存Limaセッションの補助groupは古いままだった。`getent group docker` と `id ryuman` で所属を確認し、`sg docker -c <command>` で適用して全診断が成功した。Docker socketは `660 root:docker` のままで、hostのsudoや権限設定を変更していない。このVM用の補助処理はbuild配下だけに置き、製品実装は変更していない。

rendererはE1保全済みID指定docker-saveを、既存 `docker/export-candidate.py` のnormalizeとgzip設定で再包装した。docker save自体の再実行ではない。元save・正規化tar・tar.gzのSHAがE1 pinと一致し、Mac転送後にも照合した。

- tar.gz: 593,385,993 bytes、SHA `dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4`
- image ID: `sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc`、linux/amd64
- Rosetta binfmt: enabled、interpreter `/mnt/lima-rosetta/rosetta`、flags OCF
- Java: 8u504、17.0.20.1、21.0.12.1、25.0.4.1の各 `-version` がexit 0
- GLX: Mesa 25.2.8、llvmpipe（LLVM 20.1.2、128 bits）、OpenGL 4.5
- container: `--rm --pull=never --network none --platform linux/amd64`。CPUIDは同梱cpu-probeをread-only mount。GLX終了時はXvfbを回収し、最終 `docker ps -aq` は空

## 検証と再実行条件

D1と同じ `installDist/test --offline --no-daemon --rerun-tasks -PreleaseVersion=1.1.0` → `prepare-payload.py` → `package-helper.py --app-version 1.1.0` を完了させてからaを実行した。既存 `verify-packaging.py` は内部でhelperを起動するため、起動前の属性観測とは分けて単独実行した。

helperテストは45件中44成功・1skip、失敗/error 0。skipは実probe保存stdoutをテストpropertyへ指定していない条件。実host-probe自体はaで別途成功した。包装検証は6記録が成功し、全tree・owner実行bit・署名・改変拒否・移動後/ZIP再展開後のinvalid lease拒否を確認した。renderer包装の既存回帰2テスト、QA checksum照合、`git diff --check` も成功した。

今回helperは196file、206,610,229 bytes。ZIPは125,853,872 bytes、SHA `2466ed0ea8447e735d7384faf2c4c98abb996158742c60fc4b9a38cbd9c2dca4` で前回D2 ZIPと一致する。D1最終ZIPとは異なり、D1候補bytesそのものの受入ではない。D1との差異3file（LICENSE本文・launcher・outer seal）の原因の全面確定は前回同様に未実施。

逐次runner・stageログ・VM設定はWindows `build/d2b/`、回収証跡は `build/d2b/evidence.tar` に保全した。証跡tarは126,218,240 bytes、SHA `2073bbb1c10782548600854b175d396a4b943dc9760853d3c61597380e37e366`。再実行時はQAのconfigurationYamlを参照し、新規専用領域でbuild・転送・展開・起動の終了を順に待つ。OSダイアログやブロックを観測した場合は追加候補起動を止め、保護機能を回避しない。

## 後始末と未検証

VMは通常stop/delete成功、専用LIMA_HOMEのlistは空。証跡をWindowsへ回収しSHA一致を確認してから `/Users/ryuman/tmp-mcicon-d2b` を削除した。削除前後とも当該pathを含むprocessは0、削除後のpathも不存在だった。鍵・認証やLima内部データは証跡に含めていない。

開始・終了とも `~/.lima` 不存在、PATHのlimactlなし。仮想化候補processのPID/実行file名とRosetta既知pathの存在も一致する。utm部分一致のInputMethodKit/TextInputMenuAgent 2件はVMではない。既存領域を作る可能性があるlimactl listは既存HOMEでは実行していない。空き容量は終了時143,797,424,128 bytesで変動しており、disk量まで同一とは扱わない。Homebrew/Docker/Colima/UTMの既存状態やシステム・キーチェーン・ファイアウォール・ネットワーク設定は操作していない。

未検証は実Minecraft描画、全51版、実host reboot、正式配布経路の隔離属性・初回警告、Developer ID署名/notarization、Intel Mac/他OS版、正規leaseからのhelper管理VM一連操作、guest全体の無通信、D1のlicense/source閉包・正式inventory。**隔離属性がないローカル構築物の成功から、署名なしの正式ダウンロード配布で警告なく起動できるとは判断しない。** 前回ダイアログの原因確定と正式配布経路の受入は残る。

commit・push・PR・release・Issue closeは実施していない。公開用文面だけを `build/commit-message.txt`、`build/pr-title.txt`、`build/pr-body.md` に準備した。
