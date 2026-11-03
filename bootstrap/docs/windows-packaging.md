# Windows x64 bootstrap の候補生成

`package-windows-bootstrap.py` は、明示した署名済み seed と現在の core/runtime resources を検証して、Java 同梱 app-image と ZIP 候補を新規生成する。秘密鍵の生成・署名・起動・installer・winget 操作は行わない。

## 入力と責務

| 入力 | 契約 |
|---|---|
| `--jdk` | Windows x64 Java 21 JDK。`release` と java/jlink/jpackage の通常fileが必要 |
| `--input` | 同版 bootstrap と依存 JAR だけを直下に持つ installDist/lib。既 reader は混ぜない |
| `--seed` / `--trust` | 署名済み stable.json・stable.json.sig・tool.zip と公開 DER。配布へコピーするseedはこの3fileだけ |
| `--resources` / `--core` | 同じ生成runtime resourcesと現在core JAR。payload core全bytesとの一致を既ManagedPackageVerifierが検査 |
| `--native` | 既build入口の固定reader/build.json/licenses。既stagerがsource/toolchain/PE/import/licenseを検査 |
| `--version` / `--owner` | 同版。ownerはwinget/installer/archive。package manager領域は自己更新しない |
| `--os` / `--arch` | windows / x86_64を明示。ARM64は対象外 |
| `--output` | 未使用の出力directory。入力内への出力・既存候補の上書きは拒否 |
| `--stable-manifest-url` | 任意。指定する場合は認証情報/fragmentを含まないHTTPS。未指定時はnull、URLを推測しない |

verifier後のJAR追加・欠落・差替えをコピー前後と成功境界で拒否する。staged入力は元JARと既stagerのPE/build/metadata/license一覧だけに限定し、元native入力・検証記録・コピー先・app入力を同bytesへ結ぶ。

生成環境にはPython 3.12以降とJDK 21を用意する。Pythonは生成開発環境だけの依存で、配布後の利用者には要求しない。

## 処理と出力

1. 既ManagedPackageVerifierでresources・署名seed・公開trust・現在core/agentの結合を検証する。失敗時はruntime生成へ進まない。
2. 既reader stagingでbootstrap入力へnative/source/build/license記録を結合する（現構成17file）。新規app入力へseed/public trust/metadata/project licenseを加える。
3. `jlink --add-modules java.se,jdk.crypto.ec,jdk.security.auth`、`jpackage --type app-image --win-console`を順に実行する。引数配列を使い、空白pathをshell文字列へ変換しない。
4. inputとimage内appの全bytes、launcher/config/runtimeの存在を確認し、`candidate.zip`を排他的に生成する。image前後inventory・ZIP全file bytes・入力の不変を確認してから`packaging.json`を保存する。

出力は`staging/`、`image/mcicon/`、`candidate.zip`、`packaging.json`。監査記録は検証元入力のpath/size/hash、staged input/app input/image inventory、ZIP size/hashと未受入範囲を持つ。reader/support/licenseを省略しない。失敗時の途中出力は保持し、同じ出力先へ再実行して上書きしない。監査記録が無い途中ZIPを完成候補として扱わない。

外部toolは各120秒のtimeout付きで呼ぶ。同期I/Oや子孫すべてのhard終端をこのscriptだけで保証しない。実機QAは固定Jobによる別の有限監督で全本人を回収し、timeout/unknownを保存する。

## 呼出し例

Windowsの確認済み生成環境で、各pathを実入力へ置き換える。

```powershell
& 'C:\tools\Python\python.exe' bootstrap/scripts/package-windows-bootstrap.py `
  --jdk 'C:\tools\jdk-21' --input 'C:\build with space\bootstrap\lib' `
  --seed 'C:\inputs\seed' --trust 'C:\inputs\public.der' `
  --resources 'C:\build\core\runtime-resources' --core 'C:\build\core\core-1.1.0.jar' `
  --native 'C:\inputs\reader' --version 1.1.0 --owner winget `
  --os windows --arch x86_64 --output 'C:\new candidate'
```

Gradleの`windowsAppImage`はinstallDist/core JARに依存し、同じ入口へ値を渡す。次を明示する。

```text
-PreleaseVersion=1.1.0
-PmanagedRuntimeInputDir=<core生成器の入力>
-PbootstrapSeedDir=<署名seed>
-PbootstrapTrustKey=<公開DER>
-PwindowsSecurityNativeDir=<固定reader>
-PwindowsBootstrapOwner=winget
-PwindowsBootstrapOutputDir=<未使用出力>
-PwindowsPackagingPython=<既存Windows Pythonの絶対path>
```

`managedRuntimeInputDir`から生成した現在coreとseedが一致する必要がある。私用PackageFixtureの単純descriptorを正式resourcesへ代用せず、verifierを迂回しない。

## 検証範囲と残件

局所testは固定crossbuild nativeの実stagingと、代役JDK toolによる引数/排他/不一致・license欠損改変/全bytes拒否を確認する。placeholder JAR/runtime/seedを使うため実JVM・署名seed検証・Windows jlink/jpackage成功を示さない。実機では別の具体許可で固定入力から生成し、新artifactのhelp/version/offline-cache/repair、独立OS lineageと3receipt、全本人回収と監査を受け入れる。

この生成物はinstallerが提供する実体・全reparse拒否・他標準userの書換え拒否prefixの代替ではない。reader自身のhashでその外部前提を循環証明しない。正式配置ACL、実winget install、標準非admin一般、upgrade/uninstall、本番署名/配布は別成果。Refs #97であり、#97全体の完了を意味しない。

## user向けMSI・winget候補の生成と静的監査

`package-windows-installer.py`は前段の`packaging.json`がある候補directoryを入力にし、未署名MSI、winget manifest候補、checksumと静的監査記録を新しいdirectoryに作る。前段のJava/Seed生成・更新manifest・自動更新処理は変更しない。

生成器はPython標準機能と、導入済みのWiX 3の`candle.exe`・`light.exe`・`dark.exe`を使用する。確認環境はWiX 3.14。既存のJava同梱app-imageを再利用し、WiXのMSI file/component・Environment・MajorUpgradeで配置・PATH・更新を表す。独自installer実行系やPATHを書き換えるcustom action、追加Python依存は導入しない。WiXは生成環境だけの依存で、製品payloadには同梱しない。WiXの導入・更新・ライセンス確認は生成環境側で行う。この選択はローカル候補用であり、正式配布toolchainの保守・署名方針は未確定。

| 項目 | 候補の契約 |
|---|---|
| インストール先 | `%LOCALAPPDATA%\Programs\MCIconGenerator`。per-user、x64、limited privileges。`ALLUSERS`指定をLaunchConditionで拒否。独自の任意prefix選択UIは持たない |
| PATH | MSI Environment tableでユーザーPATH末尾へ`[INSTALLFOLDER]`を追加し、uninstall時に当該要素を除去。machine PATHは変更しない。空白を含むPATH要素へ引用符は追加しない。既存shellへの反映は未検証で、利用時は新しいshellを使う |
| 更新の所有者 | payload/tool slot・runtime cacheの更新は既存bootstrapper。MSIに含むbootstrap本体・同梱Java・trust・reader・seedの置換はinstaller/winget。`bootstrap-package.json`のownerは前段の`installer`/`winget`を保持し、別のowner文字列は追加しない |
| upgrade | bootstrapの数値`major.minor.patch`をMSI版に使用（上限255.255.65535）。同一UpgradeCodeと版ごとのProductCodeによるMajorUpgrade。候補buildごとにProductCodeは新規となり、正式公開時は確定MSIを固定する。同版の実upgradeやdowngrade拒否は未検証 |
| uninstall | MSIが所有する配布fileとHKCU製品component key、空になった配布directoryだけを除去。cache・slot・`MCICON_DATA_HOME`・生成PNGへ向けた削除処理、再帰削除、custom actionは持たない。これらのデータはprogram prefix外に置く |
| 空白path | 全外部toolを引数配列で実行し、WiXのsourceはXMLエスケープする。空白を含む生成元・出力・監査pathで実buildを試験する。実install先での動作は未検証 |
| 権限拒否 | 生成先作成やtool実行の拒否・非0終了・120秒timeoutはエラーとして伝播し、完了記録を作らない。昇格・ACL変更・再試行fallbackはしない。install先/PATH/registryの実権限拒否は別の実機受入が必要 |

入力は`installer`または`winget`所有の同版Windows x64 app-imageに限定する。前段の完了status・image全file bytes・ZIP一覧/全bytes/checksum・package metadataを照合する。project licenseと第三者通知はリポジトリ正本、reader licenseは前段reader metadataの一覧/size/hashに結ぶ。同梱Javaの`runtime/legal/`も必須とする。署名seedの検証をこの段で代替せず、前段の正式verifierを通した入力を使用する。監査JSON/checksum自体の署名や、同時に改変された記録の真正性は保証しない。

```powershell
& 'C:\tools\Python\python.exe' bootstrap/scripts/package-windows-installer.py build `
  --candidate 'C:\new candidate' --version 1.1.0 `
  --wix 'C:\Program Files (x86)\WiX Toolset v3.14\bin' `
  --output 'C:\installer candidate'

& 'C:\tools\Python\python.exe' bootstrap/scripts/package-windows-installer.py audit `
  --candidate 'C:\installer candidate' `
  --wix 'C:\Program Files (x86)\WiX Toolset v3.14\bin' `
  --scratch 'C:\new static audit'
```

出力先の親directoryは事前に用意し、出力先と監査scratchは未使用pathを指定する。前段入力は変更しない。出力には`payload/`、`installer.wxs`、WiX中間物、`candidate.msi`、`extracted/`、`winget.yaml`、`SHA256SUMS`、`installer-audit.json`を残す。失敗時は途中出力を保全し、完了記録がなければ完成候補に扱わない。再実行で既存候補を上書きしない。

buildはWiX標準検証を有効のまま実行する。続いてWindows Installer APIでMSIをread-onlyに開き、版/識別子/user scope・PATH・file/component/directoryの配置・HKCU key・空directoryのuninstall・custom action不在を照合する。`dark.exe`で埋込cabinetを展開して全fileのsize/hashを入力と照合し、MSIのSHA-256をmanifest/checksumへ結ぶ。`audit`はこれらとWiX定義を再照合する。MSIを起動するAPIや`msiexec`は使用しない。WiXのICE91はuser directoryをmachine installで全userへコピーできないという警告で、合成fixtureではfileごとに発生した。警告・検証は無効化していない。

`winget.yaml`は追加YAML依存を避けたJSON表記のYAML 1.2 singleton候補。`InstallerType=msi`、`Scope=user`、`Architecture=x64`、`Commands=[mcicon]`、版、ローカルMSIのhash/ProductCodeを出力する。`InstallerUrl=PENDING_PUBLIC_HTTPS_URL`は意図的に配布URLとして無効で、提出可能manifestではない。正式版・PackageIdentifierの登録可否・HTTPS URLは未確定。コード署名後はMSI bytesが変わるため、InstallerSha256/ProductCodeを正式artifactから再取得する。installerType等の実winget schema/審査適合は未確認で、静的監査成功をwingetの受入と扱わない。

検証入口は次のとおり。合成imageのlauncher/Java/seedはダミーで、正式配布候補・起動成功の証拠ではない。

```powershell
& 'C:\tools\Python\python.exe' bootstrap/scripts/test-package-windows-installer.py `
  --wix 'C:\Program Files (x86)\WiX Toolset v3.14\bin'
```

任意の`--fixture-output <新規directory>`は同じ合成入力で実MSIを生成・再監査し、レビュー用に保持する（通常のunittestとは別実行）。本変更ではコード署名、実install/upgrade/uninstall、PATHの実反映、正式配置のACL/reparse資格・他userによる書換え拒否、標準非admin実機、実winget検証/外部審査/公開、正式署名seedの起動を実行しない。installerのprefix全体の安全性や#97全体の完成は依然として未受入。Issue本文と本候補の接続確認、正式入力による統合buildも残件とする。

## 任意のdata rootとH配置の受入範囲

対応済み候補は利用者プロセスの`MCICON_DATA_HOME`から、`bootstrap/runtime/cache`の各既定先とbootstrap子専用の`bootstrap/tmp`を導出する。packagerやinstallerへ値供給の仕組みを追加する変更ではない。program prefixとdata rootを分離し、通常uninstallはdataを保持する責務境界とする。詳細な優先順・失敗・移行なし・temp範囲は[利用ガイド](../../docs/usage.md#Windowsのデータ保存先)を参照。

H受入は本機能対応済みの固定bootstrap/seed/core/CLI/slot/更新候補の版・配布物識別子・hashを記録し、normal・pin・trial/更新経路ごとにbootstrap/runtime/cacheと出力の実利用先を確認する。既存署名検証/protocol 2だけでresolver対応済みとは判断しない。旧Cのtree/hash不変は無変更だけを示すため、未採用・未読取は各rootとconsumerの入出力先で別途確認する。OS全read監査は保証しない。

実機のHroot資格・owner/ACL/reparse拒否・cold準備/doctor/render・所有回収は別許可の実機検証が必要で、現時点では未確認。最初のbootstrap JVM、直接CLI/core、build時verifier、OS/外部runtimeが別途使うtempはH保証の対象外。正式H entry/installer・winget・本番H変更・資格修復・uninstaller実装の完了を意味しない。
