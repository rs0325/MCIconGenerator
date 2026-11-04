# rendererの入力固定と再現ビルド測定（1.1）

Refs #91、#93。2026-11-04、基準 `origin/main` の `9ea39f0` で測定した。
正式rendererの採用・公開を決める記録ではない。Minecraftの取得・実行・描画は行っていない。

## 固定した入力

既存のTemurin 8/17/21/25の4つのdigest、Java配置、entrypointと環境変数を維持する。
追加依存を増やさず、apt、BuildKit、Python標準ライブラリで固定・測定する。

- apt取得元を `https://snapshot.ubuntu.com/ubuntu/20261103T000000Z/` に固定した。noble、noble-updates、noble-backports、noble-securityを使用する。既存のUbuntu archive keyringによる署名検証を維持し、updateの取得失敗をエラーにする。
- [packages.lock](../docker/packages.lock) に直接導入・推移依存を合わせた103パッケージのexact版を固定した。測定した全220パッケージは[一覧](../qa/renderer-repro-2026-11-04-packages.tsv)に残す。固定日を変える際は依存分も含めてlockと検証記録を更新する。
- `SOURCE_DATE_EPOCH=1793664000`（2026-11-03 00:00:00 UTC）、BuildKitの `rewrite-timestamp=true`、`--provenance=false` を使用する。Python生成物のhash seedも導入処理で固定する。apt/dpkgの時刻入りログと、inode等に依存するldconfig補助cacheを除去する。
- Dockerfileとlock、記録用TSVはLFに固定する。lockにCRが入るとaptの版指定へ混入するため、Windowsでの再checkoutにも属性を適用する。

Dockerfileの通常buildだけで全bytesの一致を保証しない。以下のexportオプションを付ける測定スクリプトを使用する。
snapshot・版の固定は将来のsecurity更新を自動追従しないため、入力更新時に再検証する。

## 環境と測定方法

Windows amd64 / Docker Desktop、Docker Client・Engine 29.9.2、buildx 0.38.1、
BuildKit v0.34.0、docker driver、`linux/amd64`。開始時のHドライブ空きは636,805,296,128 bytes。
Docker Desktopは停止していたため通常起動した。システム設定は変更していない。

変更前・変更後をそれぞれ別tag、`--no-cache` で2回buildした。
所要秒数はbuild開始からimageのload完了までで、save・hash・実行確認は含まない。
image bytesは `docker image inspect` の `Size`、archive bytesは `docker save` のtar実サイズである。
このEngineのimage IDとconfig digestは別の値になるため両方を記録する。
archiveはEngineが出力したままの形式であり、最終配布用tar.gzではない。

[測定スクリプト](../docker/measure-repro.py)は、buildログ、inspect、
tag付きsaveとID指定saveのSHA-256、dpkg-query一覧、exportした全pathの
type/mode/uid/gid/size/mtime/linkおよび通常ファイルSHA-256を保存する。
saveのtarはhash取得後に削除する。exportはストリームで読み、一時コンテナを削除する。
`--fixed` では既定entrypointを通したGLX・Javaの確認も行う。
各 `docker run` は `--rm --network=none` で実行する。

## 測定結果

| build/tag末尾 | build秒 | image bytes | save bytes |
| --- | ---: | ---: | ---: |
| baseline-1 | 66.500 | 2,056,609,175 | 598,801,920 |
| baseline-2 | 53.813 | 2,056,610,019 | 598,802,432 |
| fixed-1 | 106.969 | 2,056,219,398 | 598,764,544 |
| fixed-2 | 93.234 | 2,056,219,398 | 598,764,544 |

### 変更前

パッケージ一覧は一致し、この2回にapt版差はなかった。
image ID・config digest・tag付きおよびID指定saveのhashは不一致だった。

- baseline-1 image: `sha256:d60fce0a677a38d0ce2c4b2d1bd8bc4b3e6d69d6e28986466eae371eb80004d7`
- baseline-2 image: `sha256:f3e7aa076c95a12b474a711033c7d73c16ba14c525dcf9eebb379c242c3ce381`
- baseline-1 tag付きsave SHA-256: `145d98d6a5dbd367f3ac04f438bf527aa8b5a3caf01166597a9a32e4ea81fd03`
- baseline-2 tag付きsave SHA-256: `c66b34a52097f7732f1b9496202f6ca87be468ab49fd78f7ed632c126a6d2cdf`

export一覧には1,066 pathのmtime差があり、うち内容も異なる4ファイルは
`var/log/apt/history.log`、`var/log/apt/term.log`、`var/log/dpkg.log`、
`var/cache/ldconfig/aux-cache` だった。残る1,062 pathはmtimeだけが異なる。
image設定・layerのビルド時刻と、後述のコンテナ生成時刻も差の要因になる。

### 固定後

2回のimage ID、config digest、ID指定save、パッケージ一覧は一致した。

| 対象 | 2回共通の値 |
| --- | --- |
| image ID | `sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc` |
| config digest | `sha256:130ff7c7f504746fa447d312b72638c662b0a777bfb80fc4329cab5ac0974314` |
| ID指定save SHA-256 | `6e63f160f2e3d15af3b088485739b21a076cf8672a9b02dc5600299d6daa1fe1` |
| パッケージTSV SHA-256（4回共通） | `c9347178f5c1973a35af95b65aa01ddf2ed41e3f4e7ff850155e9fbbd8125ef2` |

別tagのままsaveしたarchiveは一致しない。

- fixed-1: `4200c7eaad4c5a082032ea1708020688094f6a79856a2f150103c7bbd69e6579`
- fixed-2: `8a6208d9b97311ec9c956ae514896adfc0dd9bfac973d39a6f91820834712a79`

archive内の全memberの内容hashとメタデータを比較し、差は
`index.json` と `manifest.json` の内容hashだけだった。tag参照の差であり、
layer/config blobsは一致する。ID指定saveでは `RepoTags=null` となり、
両archiveのSHA-256も一致した。

export一覧の全10,797 pathは内容・type/mode/uid/gid/size/linkが一致した。
ただしコンテナ作成時に生成・更新される次の10 pathのmtimeは一致しない。
生の一覧hashも一致しないため、記録では不一致のまま残す。

`.dockerenv`、`dev`、`dev/console`、`dev/pts`、`dev/shm`、
`etc`、`etc/hostname`、`etc/hosts`、`etc/mtab`、`etc/resolv.conf`。

### c498との比較

[既存記録](../qa/validation-2026-10-19-linux-rootless-proof.json)のimage ID
`sha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cf`、
archive SHA-256
`1dcf9b4363583ed6c9cf5847aaf5648aaaf80a084f978b075d1b4fe483ed2e70`
のどちらとも一致しない。config digestもこのimage ID記録とは一致しない。
旧archiveを取得・実行しておらず、生成形式・tag・圧縮条件を再測定していないため、
旧候補との差の原因は個別断定しない。旧候補も今回候補も正式採用済みとは扱わない。

## ゲームを使わない実行確認

fixed-1/2とも既定entrypointからXvfb :99が起動し、GLXが成立した。
`glxinfo -B` は `llvmpipe (LLVM 20.1.2, 128 bits)`、
Mesa `25.2.8-0ubuntu0.24.04.4`、Accelerated: noを返した。
LinuxのCPU flagsに `sse4_1` があり、
`GALLIUM_OVERRIDE_CPU_CAPS=sse4.1`、`LP_NATIVE_VECTOR_WIDTH=128`、
`LIBGL_ALWAYS_SOFTWARE=1`、`ALSOFT_DRIVERS=null` を確認した。
これはSSE4.1だけを備えた別CPUでの実行やMinecraftの画像一致を検証したものではない。

| 実行path | Temurin build | javaバイナリSHA-256 |
| --- | --- | --- |
| `/opt/java/java8/bin/java` | 1.8.0_504-b01 | `1a081a2c18367e5d966d716a6091d5fe2eec26b19e471980d0d8fd550174e21c` |
| `/opt/java/java17/bin/java` | 17.0.20.1+1 | `f5aed21d3a0b0f4b05d3a3f9fe71263916d5bc0d47b53aa52a3340b90f0b4805` |
| `/opt/java/java21/bin/java` | 21.0.12.1+1-LTS | `2a207f5e7d075afa01d97f8048389a64432a44c4a5af0f5e77d6e286ec5f401d` |
| `/opt/java/openjdk/bin/java`（既定） | 25.0.4.1+1-LTS | `7380ce48ed5013735d2c8414db54adb8f981e7933ff594bd36f3baccddaafba3` |

各 `java -version` は成功した。Javaは4系統ともJDKであり、JREだけの配布ではない。
展開一覧の通常ファイルは7,916件。`/workspace` 配下は空、
JDK外のjar、Minecraft・launcher等の資産path、認証情報pathは見つからなかった。
PNGは `usr/share/pixmaps/` のDebian/Ubuntuロゴ3件だけだった。
COPY入力も確認し、作業端末のゲーム資産・鍵・認証情報をimageへコピーしていない。
この確認のためにホストの鍵・認証情報を探索していない。

## 証跡と再測定

全測定値、入力digest、Java出力、差の分類は
[QA記録](../qa/renderer-repro-2026-11-04.json)にある。
生ログ・inspect・全ファイル一覧・比較JSONは担当worktreeの
`build/renderer-repro/` に保持する。全一覧のhashとサイズはQA記録に残す。
導入済み依存を増やさず、Python 3.11以上の標準ライブラリを使う。

このWindows環境では次の記録用Pythonとloggerを使う。
以下は既存の結果を上書きしない新しい測定名の例である。
既存結果・imageに衝突する名前を再利用しない。

```powershell
$reproPython = 'C:/Users/ryuma/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$reproLogger = 'C:/Users/ryuma/.codex/scripts/run_logged.py'
$reproRoot = 'H:/MCIconGenerator/worktrees/milestone-renderer-b1/MCIconGenerator'
& $reproPython $reproLogger -- $reproPython "$reproRoot/docker/measure-repro.py" repeat-fixed-1 --fixed
& $reproPython $reproLogger -- $reproPython "$reproRoot/docker/measure-repro.py" repeat-fixed-2 --fixed
& $reproPython $reproLogger -- $reproPython "$reproRoot/docker/measure-repro.py" --compare repeat-fixed-1 repeat-fixed-2
```

変更前の再測定には、基準commitのDockerfileを担当worktreeのbuild配下へ保存し、
`--dockerfile` で指定する。作業中のDockerfileを差し替える必要はない。
保存済みの `build/renderer-repro/baseline.Dockerfile` は
`git show 9ea39f0:docker/Dockerfile` の出力をPythonの `write_bytes` で保存したもの。

```powershell
& $reproPython $reproLogger -- $reproPython -c "from pathlib import Path; import subprocess; p=Path('$reproRoot/build/renderer-repro/baseline.Dockerfile'); p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(subprocess.check_output(['git','-C','$reproRoot','show','9ea39f0:docker/Dockerfile']))"
& $reproPython $reproLogger -- $reproPython "$reproRoot/docker/measure-repro.py" repeat-baseline-1 --dockerfile "$reproRoot/build/renderer-repro/baseline.Dockerfile"
& $reproPython $reproLogger -- $reproPython "$reproRoot/docker/measure-repro.py" repeat-baseline-2 --dockerfile "$reproRoot/build/renderer-repro/baseline.Dockerfile"
& $reproPython $reproLogger -- $reproPython "$reproRoot/docker/measure-repro.py" --compare repeat-baseline-1 repeat-baseline-2
```

## 残る確認と後始末

同じ端末・同じEngine/BuildKitでimageとID指定saveの再現を確認した。
別host、別BuildKit、別snapshotへの更新、最終配布用tar.gzの固定性は未検証。
Minecraft描画・canonical-v1の受入、全版の画像一致、license/source提供と
正式artifact採用は後続作業であり、本記録から合格扱いにしない。
アプリ本体・公開API・保存データ形式は変更していない。

saveの一時tarと測定コンテナは削除した。ログと全一覧はbuild配下へ保持し、
固定候補 `mcicon-renderer-repro:fixed-1` は後続の確認用に保持する。
変更前2tag・fixed-2の別名・一時snapshot検証imageは削除した。
作成時刻・description・IDで特定した本タスクの非共有cache29件だけを削除し、対象外25件の保全を確認した。共有cacheのpruneはしていない。
本タスクで起動したDocker Desktopは停止し、Desktop/backendプロセスが残っていないことを確認した。
commit・push・PR作成・Issue closeは行っていない。
