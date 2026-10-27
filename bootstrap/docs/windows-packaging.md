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
