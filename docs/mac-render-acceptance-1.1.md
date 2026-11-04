# Mac候補資材の実Minecraft描画受入（F2）

Refs #91 #94。2026-11-04、固定51版すべてで `minecraft:stone` の64×64描画を2回実行し、102/102回がexit 0で有効なPNGを生成した。全51版で2回のPNGが直接バイト比較で一致し、Java選択も合格した。Windowsの版別PNG SHA-256は51/51版で一致した。親Issueはcloseしない。

測定値は[QA JSON](../qa/mac-render-acceptance-2026-11-04.json)と[checksum](../qa/mac-render-acceptance-2026-11-04.json.sha256)に保持する。jar・Minecraft資産・PNG本体は含めない。基準commitは `c5a46b01a1c4dc4dba6be038f00dd13ae1dc0f33`、ブランチは `feature/issue-94-render-acceptance-mac`。commit・push・PR作成は実施していない。

## 環境と実行経路

macOS 15.8.1（24H32）/ arm64 / 24 GiB。既存JDK21とApple toolchainを使用した。Lima 2.2.1、VZ/aarch64、4 CPU、9 GiB RAM、48 GiB disk、固定Ubuntu 24.04.5 LTS（noble）guestを新規作成した。rendererはD2bと同じ固定linux/amd64 image、Rosetta binfmt、Mesa 25.2.8 / llvmpipe（LLVM 20.1.2、128 bits）。Java 8u504、17.0.20.1、21.0.12.1、25.0.4.1の診断と版ごとの選択を確認した。

Macの `/Users/ryuman/tmp-mcicon-f2` だけを使用した。タスク初稿の `/Users/ryuma/` は誤記として訂正・許可済み。HOME・TMP・Gradle/Swift cache・出力・LIMA_HOMEを専用領域配下へ分離した。既存 `~/.lima`、VM、Homebrew/Docker/Colima/UTM、システム・キーチェーン・ファイアウォール・ネットワーク設定は操作していない。

CLIをMacのJava21で `-Djava.awt.headless=true` とともに実行し、F2専用 `qa-bin/docker` が `limactl shell v -- sg docker -c` でguest Dockerへ渡した。repoは同じ絶対pathでread-only、run/cache/outputはread-writeのvirtiofs mountを使用した。描画containerは製品既存実装の `--rm --network none --platform linux/amd64`。このQA用transportでの描画成功は、正規leaseからの製品helper管理VM一連操作の成功を意味しない。

## 手順と再現条件

既存D2b構築手順、F1の公式資産選択とCLI描画方式、既存 `scripts/audit-export.py` のPNG検証を再利用した。製品API・保存形式・依存宣言は変更せず、Mac用runnerだけを追加した。[runner](../helpers/macos/scripts/render-acceptance.py)は承認された専用Mac領域でのみ動作し、環境構築・VM回収は行わない。

1. 開始時の既存Lima/仮想化候補process・Rosetta既知pathを読み取り記録する。
2. 保全済みD1/D2b資材とGit archiveを逐次転送し、サイズ・SHA-256をMacで照合する。転送完了後に属性を記録してからPython tarfileで展開する。
3. `--offline --no-daemon --rerun-tasks -PreleaseVersion=1.1.0` でhelper installDist/testとCLI installDistを完了し、既存prepare-payload/package-helperで包装する。
4. 構築後属性を観測し、native・包装検証・専用VZ起動・offline Docker load・renderer/Java診断を順に完了する。保護ブロックやOS promptが出た場合は次へ進めない。
5. Macで `/opt/homebrew/bin/python3.12 repo/helpers/macos/scripts/render-acceptance.py` を実行する。公式manifestを取得し、まず1.21.4を2回描画する。pilot合格後に残り50版を逐次2回描画する。既存result.jsonがある場合は上書きしない。
6. 各回のexit、有効RGBA PNG、64×64寸法、可視pixel、Java majorを検証する。各版で2回のPNGを直接バイト比較し、settingsの一致も確認する。Windows参照SHA-256と版別に比較する。
7. containerが空であることを確認してVMを通常stop/deleteし、専用LIMA_HOMEのlistが空であることを確認する。ログのみの証跡を回収・SHA-256照合後、専用領域を削除して既存状態を比較する。

工程runnerと証跡はWindows `build/f2/` に保全した。build・転送・展開・起動を並行実行していない。再実行には候補入力・オフライン依存・rendererを別途準備する必要があり、公開runner単体では環境構築しない。

## 取得元とmetadata

Minecraft取得はHTTPSのMojang公式manifest / piston-data / libraries / resources、および製品pinで指定されたOrnithe上流MavenのFeather/Calamusだけに限定した。redirectも同じ許可集合で検査し、許可外URLへ接続する前に停止する。公式SHA-1と宣言サイズを照合し、実測SHA-256を記録した。最上位manifestは上位SHA-1 pinがないため、HTTPS取得後の実測hashとして区別する。

固定対象は51版のID集合である。現行製品VersionServiceと同様に取得時manifestのmetadataを使い、releases.json内のmetadata pinとの差異をQAの `metadataSelection.driftVersions` に記録した。metadataの差異を固定ID集合の変更とは扱っていない。音声oggは取得対象外で、他の必要資産・Linux用library/nativeを準備した。資産は認証・アカウントなしで取得し、作業終了後に専用領域ごと削除した。

## 全51版の結果

| 版 | 描画 | Java major / 2回のPNG | Windows SHA-256 |
| --- | --- | --- | --- |
| 26.3 | PASS (2/2) | 25 / 一致 | 一致 |
| 26.2 | PASS (2/2) | 25 / 一致 | 一致 |
| 26.1.2 | PASS (2/2) | 25 / 一致 | 一致 |
| 26.1.1 | PASS (2/2) | 25 / 一致 | 一致 |
| 26.1 | PASS (2/2) | 25 / 一致 | 一致 |
| 1.21.11 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.10 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.9 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.8 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.7 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.6 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.5 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.4 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.3 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.2 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21.1 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.21 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.20.6 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.20.5 | PASS (2/2) | 21 / 一致 | 一致 |
| 1.20.4 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.20.3 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.20.2 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.20.1 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.20 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.19.4 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.19.3 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.19.2 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.19.1 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.19 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.18.2 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.18.1 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.18 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.17.1 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.17 | PASS (2/2) | 17 / 一致 | 一致 |
| 1.16.5 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.16.4 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.16.3 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.16.2 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.16.1 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.16 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.15.2 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.15.1 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.15 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.14.4 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.14.3 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.14.2 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.14.1 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.14 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.13.2 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.13.1 | PASS (2/2) | 8 / 一致 | 一致 |
| 1.13 | PASS (2/2) | 8 / 一致 | 一致 |

Windows参照は前担当がF1担当worktreeの未コミット成果から読み取り固定したもの。比較元JSON SHA-256は `e8020a73a2751b381a90fd163c066283ac2b55c6a1cfd1e37647eb80268b7ff7`。他担当の変更は取り込んでいない。51/51版でSHA-256が一致した事実を記録する。他OS・別hardware全般の一致を保証する結果ではない。

## 隔離属性と途中失敗

転送直後とPython展開後の `xattr -lr` は空。包装後はcpu-probeの `com.apple.cs.CodeDirectory`、`CodeRequirements`、`CodeRequirements-1`、`CodeSignature` の4属性だけを観測した。移動・ZIP再展開後の検証出力tree、renderer tar.gz、取得・描画後のrun領域の属性は空だった。quarantine/provenanceはこの経路では観測せず、属性の削除・追加・変更は行っていない。native/包装/VM起動時とVM実行期間のOS logに保護promptは観測しなかった。

初回CLIビルドはGson 2.13.2不足、2回目は推移依存error_prone_annotations 2.41.0不足で失敗した。既存Windows Gradle cacheから両artifactと親POMを補い、追加tar SHA-256を照合してoffline buildを成功させた。許可外の取得元は使用していない。

guestの最初の直接 `docker info` はSSHセッションの補助group未反映でpermission deniedとなった。D2bと同じ `sg docker` 手順で診断・描画は成功し、socketやhost権限を変更していない。実描画の失敗はなかった。

## 検証・後始末・未検証

helperテストは45件中44成功・1skip、failure/error 0。skipは実probe保存stdoutをテストpropertyへ渡していない条件であり、実host-probeは別途成功した。既存包装検証6記録、native限定確認、rendererのSSE4.1・Java 4系列・llvmpipe診断、取得元/整合性/pilot停止のunit test 4件、QA checksum、`git diff --check` が成功した。

全描画後のDocker containerは空。専用VMの通常stop/deleteが成功し、専用LIMA_HOMEのlistは空だった。証跡 `build/f2/evidence.tar`（1,966,080 bytes、SHA-256 `b52d9a896feeda5164e93b358e7ab413497e2bef1caf6bd1d19bab5a4ae18916`）を回収・照合し、専用領域とMinecraft資産を削除した。証跡はログ・測定JSON・設定・テストXMLのみで、jar・資産・PNG・Lima内部や鍵を含めない。

開始・終了とも既存 `~/.lima` 不存在、PATHのlimactlなし。仮想化候補processのPID/実行file名とRosetta既知pathの存在が一致し、専用pathを含む残存processはなかった。InputMethodKit/TextInputMenuAgentの部分一致2件はVMではない。ディスク空き容量は変動し、同一性判定には含めない。

未検証は実host reboot、OS警告の正式配布経路、未準備host setup、正規leaseからの製品helper管理VM一連操作、Intel Mac・他macOS版。正式配布経路の隔離属性・警告や前回ダイアログの原因は未確定のままで、このローカル構築経路の成功から推定しない。
