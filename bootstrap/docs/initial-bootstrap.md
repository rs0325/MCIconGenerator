# Java同梱bootstrapの初期設置

このmoduleは#97の初期設置を担当する。package所有のJavaでbootstrapを起動し、署名済みseedをuser領域のtool slotへ検証・設置して、そのslotのJavaからCLIを子プロセスとして実行する。package prefixやProgram Filesをtool updaterが上書きする仕組みは含まない。packageのJavaとbootstrapの更新はinstaller/package managerの責務である。

既存のruntime generationはhelper/engineの保存契約なので、tool slotへ流用しない。署名はJCA Ed25519、ZIPは既存と同版のCommons Compressを使う。runtime専用descriptor/archiveの型をtool用に偽装しない。

## 入力と配置

packageのapp入力はbootstrap JARと依存JAR、`bootstrap-package.json`、`trust-set.bin`、`seed/stable.json`、`seed/stable.json.sig`、`seed/tool.zip`である。trustは配布側が指定する運用鍵・回復鍵の公開鍵集合であり、自己採用や鍵生成のfallbackはない。正式URL・本番trust・製品署名配布はまだ確定していない。

Mac・Windowsの生成器は`bootstrap-package.json`に`bootstrapProtocol: 4`を出力する。package metadataの`schemaVersion: 1`は変更せず、command receipt schema 3・保存状態schema 4とは区別する。Windows installerは前段metadataとimage・ZIPの一致を確認して同じmetadataを引き継ぐ。

stable manifestはchannel/sequence/toolVersion/minimumBootstrapProtocolとplatform別tool-payload ZIPのsize/hash/unpackedBytes、inventory/runtime descriptorの参照を閉じる。manifest署名、対象host、ZIP実bytes、展開上限、entryの重複/外向きpath/link、inventoryとdescriptorのhash、必要なJava/CLI/core/worker/licenseを初期設置前に確認する。descriptorの全runtime契約と実helper受入は既coreの責務であり、この初期設置の成功では代用しない。

user rootはMacの`~/Library/Application Support/MCIconBootstrap`、Windowsの`%LOCALAPPDATA%/MCIconBootstrap`、Linuxの`$XDG_DATA_HOME/mcicon-bootstrap`（未指定は`~/.local/share/mcicon-bootstrap`）を候補とする。このpath候補は製品の対応OSを示さない。v1.1.0の正式対応はWindows 11 x64とmacOS 15.8.1 / Apple Silicon (arm64)、Linux x86_64とWSLは実験的・未保証、Windows arm64とLinux arm64は対象外である。Mac以外のpackage実受入は未実施である。

Windowsでは任意の`MCICON_DATA_HOME`を指定すると、その配下`bootstrap`をuser rootにする。通常drive付き絶対pathだけを受理し、不正指定は`BOOTSTRAP_INVALID`/終了2となり旧既定先へfallbackしない。旧領域の探索・移行は行わない。CLI/trialと更新準備子には正規化envとroot配下`tmp`を`java.io.tmpdir`・`TMP`・`TEMP`へ渡す。tmpにも既存owner/ACL/親gateを適用する。最初のbootstrap JVMのtempは対象外。[利用方法と全保証範囲](../../docs/usage.md#Windowsのデータ保存先)を参照。

署名/protocol 2適合だけでは旧CLI/coreを含むseed・pin・更新候補を排除しない。H受入は本機能対応済み固定bootstrap/seed/core/CLI/選択slot/trial・更新候補だけに限定し、版・hashと各経路の実利用先を確認する。新guardやprotocol/schema変更、旧版互換実装は追加しない。

| path | 責務 |
| --- | --- |
| `active.json` | active参照と受入sequence。pending/previousとtrial/準備owner参照を含む（更新契約は下記文書） |
| `state.lock` | 検証・初期設置・起動pin作成・repairの排他 |
| `tools/<toolVersion>/<payloadSha256>/` | 内容・実行属性を固定したtool slot |
| `archives/<payloadSha256>.zip` | 同一署名seedの修復用原本 |
| `manifests/<manifestSha256>/` | 受入済みmanifestと署名 |
| `runs/<invocationId>.json` | 起動receipt。同じfileのlockを起動中保持 |
| `logs/<invocationId>.stderr` | 更新準備processの診断。receiptとは分離 |
| `staging/` | 検証中の生成物。失敗・unknownは残す |

初回はstateだけでなく既slot/archive/manifest/receipt/stagingも空であることが必要で、state消失を初回へ再分類しない。stateは同一filesystemのatomic moveで公開し、非atomicなfallbackは使わない。

## 起動、修復、保全

receiptを`launching`として永続化してから子を起動し、PID/start時刻を観測して`running`へ進める。bootstrapはslot参照のlockをCLI終了まで保持する。開始時に観測した子identityを再利用し、実際にwait/reapできた場合だけ`finished`とexit codeを記録する。短命で開始識別を取得できなかった子も直接Processをwait/reapして元exitを返すが、receiptは`launching`のunknownとして保持する。これはCLIの直接子の終端であり、helperやOS資源の回収証拠ではない。親死、部分書込み、子の未確認状態はunknownとして保持し、PIDだけで既存processを停止しない。

準備待機は5分。CLIの実行時間は元CLIの引数・予算に従い、準備期限で通常jobを打ち切らない。割込み時には所有する直接子へTERM、有限待機後KILLを行い、回収失敗は元failureのsuppressed例外として保持する。

`--bootstrap-repair`は単独指定する。同じ署名seedのslotが欠落し、全receiptがfinishedでlockも取得できる場合のみ再設置する。壊れた既存slotや不明receiptを上書きしない。package自体の破損はinstaller/package managerによる再設置の対象である。

通常引数・stdio・CLI exit codeは子へ渡す。`--version`ではbootstrap版/ownerをstderrへ、元CLI版をstdoutへ出す。bootstrap不正は`BOOTSTRAP_INVALID`/exit 1、repairとの引数競合はexit 2となる。

nonpurge uninstallはpackage所有fileだけを除去し、user root、runtime cache、PNG、任意API rootを除去しない契約である。purge/GC/rollbackは行わない。更新の準備と次回適用は[更新契約](auto-update.md)を参照する。実installerのuninstall/PATH/同時installは後段の受入対象である。

## ローカル検証と未受入

`./gradlew --offline :bootstrap:test :bootstrap:build`で、署名/ZIPの拒否、同時初回、cachelost、同一seed repair、親死・割込み、保持receiptを直接fixtureで確認する。fixtureは実行時だけの一時Ed25519 KeyPairをJVMメモリ内で使用する。秘密鍵は保存・表示・外部送信せず、公開鍵と署名済みQA入力だけを保存する。本番trustには採用しない。

Mac app-image候補は既存JDK 21と明示seed/public trustで作る。

```sh
./gradlew --offline -PreleaseVersion=1.1.0 \
  -PbootstrapSeedDir=/absolute/seed \
  -PbootstrapTrustKey=/absolute/trust-set.bin :bootstrap:appImage
```

既存出力を上書きせず、jlink runtimeとapp-imageを生成し、通常codesign verifyとfile inventoryを残す。packagerは署名鍵を生成せず、明示された公開trustと署名済みseedだけを受け取る。`build/install/mcicon`の通常Java launcherをJava無しhost向け配布物とは扱わない。

Homebrew向けarchiveとFormula候補の生成は[配布候補の手順](homebrew-distribution.md)を参照する。正式配布seed/trust、無Java実host、Developer ID/notarization、installer/Homebrew/wingetの実受入、OS下限、実helper/描画、#98の更新/rollback/GCは未受入である。#98はこの初期slot/pin契約を前提とし、health切替の現在範囲は[更新契約](auto-update.md)を参照する。

#98の復旧実装はpackage/実CLIのbootstrap制御にprotocol 4、stateにschema 4、command receiptにschema 3を要求する。旧開発用rootは保全して新しいQA世代を再生成し、自動移行しない。worker/helper/descriptor protocol 1は維持し、署名SeedのminimumBootstrapProtocolは4とする。生成器のmetadataはprotocol 4へ合わせ、対応CLIを含む実packageの生成・受入は配布統合側に残る。[起動control](startup-control.md)と[復旧契約](initialization-recovery.md)を参照。
