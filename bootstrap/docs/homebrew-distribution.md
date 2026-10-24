# Homebrew向けJava同梱archive候補

#97のHomebrew向け配布候補をローカルで生成する。初版はApple Silicon・macOS 15.7.7以降を対象とするJava同梱app、prefixから相対位置で起動する`bin/mcicon`、file inventory、明示URL/hashから生成するFormulaを対象とする。tap公開先・本番trust・署名アカウントは入力から推測しない。

## 生成入力

既存JDK 21、bootstrap JARと依存JAR、署名済みseed、配布側が明示する公開trustを使う。seedとtrustの内容は[初期設置契約](initial-bootstrap.md)に従う。

```sh
./gradlew --offline -PreleaseVersion=1.1.0 \
  -PbootstrapSeedDir=/absolute/seed \
  -PbootstrapTrustKey=/absolute/trust.der \
  :bootstrap:homebrewArchive
```

任意の`-PbootstrapManifestUrl=https://...`は既存bootstrapの更新先設定へ渡す。未指定時は更新URLを追加しない。正式runtimeを配布する際は、`-PmanagedRuntimeInputDir=/absolute/runtime-resources`も指定して`:bootstrap:managedHomebrewArchive`を使い、既存validatorでcore resourcesとseedの同bytesを検証する。通常の`homebrewArchive`成功だけでは正式helperや描画の受入を意味しない。

生成先は`bootstrap/build/homebrew/`で、app-image、`mcicon-bootstrap-1.1.0-macos.tar.gz`、隣接する`.tar.json`を残す。JSONにはhost arch、package owner、archive bytes/SHA-256、各fileのpath/bytes/SHA-256/modeを記録する。既存候補は上書きしない。再生成時は保存先の成果を保全してから別のworktreeまたは明示したscript出力先を使う。

archiveと監査JSONが同じpathになる出力指定は拒否する。包装中に監査JSONの作成が競合した場合も既存記録を保持して失敗し、新規archiveを残す。archiveと監査JSONが揃い、実hashが一致した候補だけを配布対象にする。

既存app-imageからarchiveだけを作る場合は次を使う。appのownerは`homebrew`、hostは`macos/aarch64`であることが必要で、Javaとlauncherの実行属性、link・特殊fileを確認する。

```sh
python3 bootstrap/scripts/package-homebrew.py archive \
  --app-image /absolute/image/MCIconBootstrap.app \
  --output /absolute/candidates/mcicon-bootstrap.tar.gz
```

## 配布先とFormula候補

archiveと隣接JSONを保持し、利用者が明示したHTTPS URLとSHA-256からFormulaを生成する。scriptはネットワークへ接続せず、明示hashとarchive実bytesを照合する。URL先の内容一致や公開状態は別途確認が必要である。

```sh
python3 bootstrap/scripts/package-homebrew.py formula \
  --archive /absolute/candidates/mcicon-bootstrap.tar.gz \
  --url 'https://example.invalid/explicit-fixture/mcicon-bootstrap.tar.gz' \
  --sha256 '<明示したarchiveのSHA-256>' \
  --output /absolute/candidates/mcicon-bootstrap.rb
ruby -c /absolute/candidates/mcicon-bootstrap.rb
```

上のURLは未公開fixtureの説明用であり、正式配布URLではない。SHA-256不一致、非HTTPS、URL内認証情報・fragment、既存出力は拒否する。初版FormulaはApple SiliconとmacOS Sequoiaを宣言し、install時に15.7.7未満を追加で拒否する。IntelとLinuxは対象に含めない。macOS 15.0も通るSequoia宣言だけで15.7.7の下限を表現した扱いにはしない。Formulaの構成は[Homebrew Formula Cookbook](https://docs.brew.sh/Formula-Cookbook)の`libexec`配置と`bin.install`に従う。候補生成や`ruby -c`はbrew監査・tap審査の成功ではない。

## 配置、所有者、修復、uninstall

archiveの単一トップディレクトリには`bin/mcicon`と`libexec/MCIconBootstrap.app`がある。FormulaはappをCellar内の`libexec`へ、launcherを`bin`へ配置する。launcherはHomebrew prefixからのsymlinkを解決して同じprefix内のappをexecし、引数・stdio・exit codeを継承する。空白pathや空引数も保持する。JAVA_HOMEやhostのjavaコマンドに依存しない。

Homebrewはprefix/Cellar内のJava同梱bootstrapを更新する。bootstrapは署名seedのtool slotをユーザー領域へ設置し、以後のtool更新もユーザー領域へ書き込む。prefix/Cellarを自己更新しない。`brew`の表示版はbootstrapの版であり、`mcicon --version`はstderrのbootstrap版/ownerとstdoutの実行CLI版を分けて表示する。

`mcicon --bootstrap-repair`は既存の同一seed・missing-only修復契約を利用する。package自体の破損はHomebrewによる再設置の責務である。通常uninstallはHomebrew所有fileを除去し、ユーザー領域のtool slot、runtime cache、生成PNG、任意API rootを保持する。Formulaはuninstall時のuser data削除やpurgeを追加しない。同じ`mcicon`名を別の配布経路が所有している場合はHomebrew側の衝突を解決してから利用する。

## 検証と残る受入

`python3 bootstrap/scripts/test-package-homebrew.py`でarchiveの実inventory/hash/mode、空白prefixとsymlink chainからの引数・stdio・exit継承、owner/link/上書き拒否、Formulaの明示URL/hashとRuby構文、stub上で15.7.6拒否・15.7.7/15.8.1通過を確認する。このテスト内のJava/launcherは軽量fixtureであり、実Javaの受入ではない。

実Java同梱appは別途offlineで展開後のlauncherから起動して確認する。今回の生成・起動hostはmacOS 15.8.1、旧VM世代の15.7.7実測とは分けて扱う。既JDK生成appのMach-O deployment target 11.0とplistの最低版10.11はJDKの生成値であり、製品の対応範囲を拡大しない。署名後のappを書き換えない。JAVA_HOMEを外し、javaが存在しない専用PATHで起動できても、Java/Docker未導入の実ホスト受入の代用にしない。正式trust、Developer ID/notarization、実brew install/upgrade/uninstall、PATH反映・権限拒否・他配布経路との同時install、実helper/管理描画、正式package世代でのOS下限受入、winget・installerは未受入であり、#97全体の完成条件として残る。
