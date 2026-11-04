# renderer imageのlicense/source対応調査（1.1）

Refs #91、#93。親Issueはcloseしない。

## 対象と結果

PR #152を含むmainのcommit `c0a3bca`を基準に、
[B1の固定候補](renderer-reproducibility-1.1.md)
`sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc`
を既存tagから読み取った。対象はlinux/amd64のJDK入りimage。
再buildせず調査し、Dockerfile・公開API・descriptor・保存形式は変更していない。

B2の[元表](../qa/renderer-sources-2026-11-04.json)は変更せず保持する。
B2bはPR #153を含む `b147ebd` を基準に、同日付の追補として
[更新表](../qa/renderer-sources-2026-11-04-b2b.json)、
[隣接SHA-256](../qa/renderer-sources-2026-11-04-b2b.json.sha256)、
[補足metadata](../qa/renderer-sources-2026-11-04-b2b-metadata.json)を追加した。
後者に新規8版の全構成員URL/size/SHA、公開履歴、archiveのHEAD結果、Java 8の調査根拠がある。
`baselineTableSha256`で元表（Git正本と同じLFへ正規化）、`supplementalMetadataSha256`で補足metadataを固定する。
image・既存210件・B1一覧・索引hashは維持した。

| 確認内容 | 結果 |
| --- | --- |
| B1のbinary名・版とimageのdpkg情報 | 全220件一致 |
| Sources索引でsource名・版が一致 | 210 binary / 132 source package |
| Launchpadの.dscで追加対応 | 10 binary / 8 source版 |
| 合計 / 未一致 | 220 binary / 140 source版、未一致0件 |
| image内のpackage copyright | 220/220件存在、欠落一覧は空 |
| copyright pathにlinkを含むもの | 10件。解決先の本文を読取り・hash化 |
| `/usr/share/common-licenses` | 17文書を読取り・hash化 |
| Temurin | 8/17/21/25のrelease/source tagとsource archive情報を取得 |
| 大きなsource archive本体 | 未取得。size/SHAは索引またはrelease metadata由来 |
| 完全なsource提供・公開配布の承認 | 未達・未判断 |

固定snapshotは `https://snapshot.ubuntu.com/ubuntu/20261103T000000Z/`。
noble、noble-updates、noble-backports、noble-securityの
main/restricted/universe/multiverse、計16 Sources.gzを使用した。
圧縮bytesのsize/SHA-256と展開後bytesのSHA-256を記録した。
今回の索引取得ではRelease/InReleaseの署名照合を実施していない。
B1のapt署名検証を、このsource調査の署名検証として引き継がない。

## 表の読み方と実装

- `packages`: binary名・版・architecture、dpkgの生の`sourceField`、
  解決した`sourcePackage`/`sourceVersion`、`sourceId`、copyrightの
  実path・link解決先・size/SHA・license記載の手がかり。
- `sources`: `sourceId`が参照するsource単位。.dsc、主要tarball
  （origだけでなくdebian/native tarも含む）と`files`の全構成員
  （diffや署名があればそれも含む）のURL/size/SHA、
  `Standards-Version`、source format、収録索引。
- `sourceField`省略時はbinary名・版、source名だけの場合はbinary版を使う。
  `Source: name (version)`の明示版を優先し、binNMUの`+bN`を機械的に削らない。
  multiarchの`:amd64`はB1一覧との比較時だけ外す。
  同じsource名でも版が違えば一致扱いにしない。
- `debianCopyrightInSource`: source内の`debian/copyright`は未確認。
  image内のDebian形式copyrightの存在と、未取得archive内の存在を区別する。
  `markers`の`License:`等や`Standards-Version`は手がかりであり、
  package全体の単一ライセンスや許諾範囲を確定する値ではない。
- `java`/`commonLicenses`: image内文書のpath・解決先・size/SHAと記載。
  `javaReleases`: imageのreleaseと公式release/tag metadata、source assetの対応。
- `unresolvedMetadata`: 未一致版の.dscを調べたURLとHTTP状態。
  `availableSourceVersions`は同じsource名で固定索引に収録された別版。
- `dscChecks`: Mesa、LLVM、base-filesの小さな.dscを実取得し、
  索引のsize/SHAとsource名・版の一致を確認した結果。PGP署名は未検証。
- `assetSearch`/`fileList`: ファイル名検索条件・結果と全一覧のhash。
  `evidenceSha256`等はbuild配下の入力証跡をpinする。

Python 3.12標準ライブラリのDeb822パーサ・gzip・urllib・hashlibを使用する。
既存のdpkg情報とUbuntu Sourcesを再利用し、独自のbinary名変換表や依存は追加しない。
同名同版が複数索引にある場合は内容一致を要求して参照索引を併記する。
これは最終artifactの全fileをcomponentへ帰属させる最終inventoryではない。

## B2bで解決した未一致10件

8版すべての.dscをLaunchpadの公開sourcefiles URLから取得し、
`Source`/`Version`の完全一致と、`Binary`への各binaryの帰属を確認した。
取得した.dsc自体のSHA-256/sizeと、`Checksums-Sha256`に載るtarball・署名等の
全構成員のURL/size/SHA-256を補足metadataへ記録した。
tarball・署名の20 URLはHEADでHTTP 200・Content-Length一致を確認した。
archive本体の取得・hash検証や.dscのPGP署名検証は未実施。

| source名・imageのsource版（Launchpad公開記録） | binary | Nobleのpocket / pool削除日 |
| --- | --- | --- |
| [audit `1:3.1.2-2.1build1.1`](https://launchpad.net/ubuntu/+source/audit/1:3.1.2-2.1build1.1) | libaudit-common、libaudit1 | updates、proposed / 2026-09-25 |
| [ca-certificates `20260601~24.04.1`](https://launchpad.net/ubuntu/+source/ca-certificates/20260601~24.04.1) | ca-certificates | updates、security / 2026-10-22 |
| [freetype `2.13.2+dfsg-1ubuntu0.1`](https://launchpad.net/ubuntu/+source/freetype/2.13.2+dfsg-1ubuntu0.1) | libfreetype6 | updates、security / 2026-10-07 |
| [libpng1.6 `1.6.43-5ubuntu0.6`](https://launchpad.net/ubuntu/+source/libpng1.6/1.6.43-5ubuntu0.6) | libpng16-16t64 | updates、security / 2026-10-09 |
| [systemd `255.4-1ubuntu8.17`](https://launchpad.net/ubuntu/+source/systemd/255.4-1ubuntu8.17) | libsystemd0、libudev1 | updates、security / 2026-10-22 |
| [tzdata `2026c-0ubuntu0.24.04.1`](https://launchpad.net/ubuntu/+source/tzdata/2026c-0ubuntu0.24.04.1) | tzdata | updates、security、proposed / 2026-10-20 |
| [xz-utils `5.6.1+really5.4.5-1ubuntu0.3`](https://launchpad.net/ubuntu/+source/xz-utils/5.6.1+really5.4.5-1ubuntu0.3) | liblzma5 | updates、security / 2026-10-22 |
| [zlib `1:1.3.dfsg-3.1ubuntu2.2`](https://launchpad.net/ubuntu/+source/zlib/1:1.3.dfsg-3.1ubuntu2.2) | zlib1g | updates、security / 2026-10-24 |

各公開記録の `+publishinghistory` は、固定snapshot以前の新版への置換と
`Removed from disk` を示す。補足metadataの`publicationHistory`にURL・本文hashと
該当行を保持した。audit以外はsecurityにも履歴があり、security poolへ変えるだけでは
旧版を回収できるとは限らない。追加で確認したsystemdのsecurity pool .dscも404だった。
B2での固定snapshot/archiveの404と今回の成功を`retrievalAttempts`に併記した。
旧版を固定索引中の新版へ置き換えず、Launchpadで同名同版を補足した。

DockerfileはFROMにある全packageをsnapshot版へ置き換える処理ではない。
今回の履歴で欠落理由は確定したが、各binaryがどのFROM layerで導入されたかは未検証。
image ID・入力・公開API・descriptor・productionの保存形式は変更していない。

## copyrightとlink例外

copyright欠落一覧は **なし**。
本文は`build/renderer-sources/image-documents/usr/share/doc/<binary>/copyright`へ
linkを辿って通常fileとして抽出した。共通licenseも同じtreeへ抽出した。
全packageについて本文のsize/SHAが表にある。
本文の存在だけで再配布条件が充足したとは判定しない。

| linkを含むbinaryの文書path | 解決先の`/usr/share/doc/`配下 |
| --- | --- |
| binutils-x86-64-linux-gnu、libbinutils、libctf0、libgprofng0 | binutils-common/copyright |
| libgcc-s1、libstdc++6 | gcc-14-base/copyright |
| libncursesw6 | libtinfo6/copyright |
| libpython3.12-stdlib | libpython3.12-minimal/copyright |
| openssl | libssl3t64/copyright |
| p11-kit-modules | libp11-kit0/copyright |

Javaのlegal文書にもlinkがある。最終artifactのlicense/noticeとして参照する際は、
[既存inventory生成器](../helpers/macos/scripts/build-inventory.py)と同様に、
展開後の相対pathから通常fileの本文・size/SHAを検証できる配置へ確定する。
今回の抽出treeは証跡であり、正式artifactへの組込みは行っていない。

## Temurin

`/opt/java/*/release`の版とsource情報を読み、Adoptium公式GitHubの
release metadataとsource repoのtag参照を照合した。
`api.adoptium.net`の版検索はHTTP 403だったため公式GitHub APIを使った。
archive本体・署名・build再現性は検証していない。

| home | imageの版 / release tag | source repo / source tag |
| --- | --- | --- |
| `/opt/java/java8` | `1.8.0_504-b01` / `jdk8u504-b01` | adoptium/jdk8u / `jdk8u504-b01_adopt` |
| `/opt/java/java17` | `17.0.20.1+1` / `jdk-17.0.20.1+1` | adoptium/jdk17u / `jdk-17.0.20.1+1_adopt` |
| `/opt/java/java21` | `21.0.12.1+1-LTS` / `jdk-21.0.12.1+1` | adoptium/jdk21u / `jdk-21.0.12.1+1_adopt` |
| `/opt/java/openjdk` | `25.0.4.1+1-LTS` / `jdk-25.0.4.1+1` | adoptium/jdk25u / `jdk-25.0.4.1+1_adopt` |

source tagのcommitは4系統ともimageの`SOURCE`のcommit prefixに一致する。
Java 8のtag `jdk8u504-b01_adopt` はcommit
`1358e1b273e29392787e6feb32732304befaf1da`。B2bで公式tag参照を再照合した。
全系統の`BUILD_SOURCE`はadoptium/temurin-buildの
`e6ba7dec3d07654074559310376a3ae89da5f4ac`。

Java 8の `.:git:1358e1b273e2+` の末尾は、該当tagの
[make/SourceRevision.gmk](https://github.com/adoptium/jdk8u/blob/jdk8u504-b01_adopt/make/SourceRevision.gmk)
が `git status --porcelain` の出力が空でないときに付けるマーカー。
未追跡ファイルも対象なので、追跡sourceへのpatch適用だけを意味しない。

同じBUILD_SOURCEの
[prepareWorkspace.sh](https://github.com/adoptium/temurin-build/blob/e6ba7dec3d07654074559310376a3ae89da5f4ac/sbin/prepareWorkspace.sh)
はclean後、JDK8専用の`createSourceTagFile`でsource rootへ `.hgtip` を生成する。
内容は`OpenJDK: <git rev-parse --short HEAD>`。公式tagにこのfileはなく（contents APIは404）、
tagの`.gitignore`にも除外指定がない。この未追跡fileだけでも `+` の条件が成立する。
これは公式ビルド処理からの判断であり、当時の唯一の差分だったという証明ではない。

対象linux/x64のrelease JSONは`PATCHES=""`、`CLEAN_GIT_REPO="true"`、
`CREATE_SOURCE_ARCHIVE="true"`を示す。binary/source双方のrelease JSONは
同じsource commit・tag・BUILD_SOURCEを指す。
[sbin/build.sh](https://github.com/adoptium/temurin-build/blob/e6ba7dec3d07654074559310376a3ae89da5f4ac/sbin/build.sh)
は前処理後のtreeから.gitを一時退避してsource archiveを作り、その後configure/makeを実行する。
[common.sh](https://github.com/adoptium/temurin-build/blob/e6ba7dec3d07654074559310376a3ae89da5f4ac/sbin/common/common.sh)
のtar処理に.hgtipの除外はないため、コード上は生成fileもarchiveへ含まれる。
tagから自動生成されるGitHubのsource archiveとは区別する。

対応する公式assetは `OpenJDK8U-jdk-sources_8u504b01.tar.gz`、94,314,011 bytes、
SHA-256 `86cd14f299616dddca13268cc2fa794eb4d28fc732dedaad8c5b8a3078e5d3c9`。
release asset digest、source JSON、`.sha256.txt`の一致を確認した。
根拠URLと取得metadataのsize/SHAは補足表の`localChangesInvestigation`に記録した。

**依然未確定**: 当時の全`git status`/`git diff`、他の差分が無いこと、
全変更のsource asset実bytesへの包含。release JSONとSBOMにはその全差分がない。
`+`だけから未知のpatchがあるとは断定せず、外部patch設定が空というだけで
完全な対応sourceの証明にもしない。archive本体・署名・再buildの確認は後続。

各homeの`NOTICE`に`GPL-2.0 WITH Classpath-exception-2.0`の記載がある。
Java 8の`LICENSE`等、17/21/25の`legal/java.base/LICENSE`、
`ADDITIONAL_LICENSE_INFO`、各moduleのlegal文書を実読取りし、
GPLv2とClasspath Exceptionの本文記載を確認した。第三者文書も一覧に保持した。
全同梱componentに一律の許諾を推定しない。

各majorの公式`temurin<major>-binaries` releaseにある
`OpenJDK*U-jdk-sources_*.tar.gz`のURL/size/SHAを`javaReleases[].sourceArchive`へ記録。
4 archiveの合計は439,291,112 bytes。
値はGitHub release assetの`digest`由来で、download後の実hashではない。
メタデータの取得URL・取得bytesのSHAも記録した。

## Minecraft資産の確認

固定imageを`--network none --read-only --entrypoint python3`で読み取り、
ゲーム・Java本体・既定entrypointは実行していない。
`/proc`、`/sys`、`/dev`の仮想mountを除く9,321 file entryを列挙した。
`minecraft`、`mojang`、`lwjgl`、`.minecraft`、client/server/launcher jar、
assets/indexes・assets/objectsに一致するpathは0件。
JDK外のjarも0件、`/workspace`は空だった。
全一覧は`build/renderer-sources/image-files.txt`に保持し、表にhashを記録した。
ファイル名・配置に基づく確認であり、全bytesの内容分類や
最終配布archiveの検査ではない。ゲーム資産は取得していない。

## 再生成

1. `docker/inspect-sources.py`: Dockerの固定imageを通信なしで読む。
2. `docker/map-sources.py`: 新規索引の通信は上記snapshotの16 Sources索引だけを取得。
   `--offline`ならcacheのみ使用し、binary名・版のB1一覧との一致を必須にする。
3. `docker/fetch-source-metadata.py`: 未一致版の小さな.dscを固定snapshotと公式archiveで確認。
   代表3版の.dscも検証し、Adoptium公式release/tag metadataを取得する。
   大きなsource archiveは取得しない。

B2bの追加探索先は[設定JSON](../qa/renderer-source-locations-2026-11-04-b2b.json)に
`source名@版: [.dsc候補URL, ...]`として渡す。既存のDeb822パーサ・urllib・SHA処理を再利用した。
`fetch-source-metadata.py --source-locations`は候補を順に調べ、版不一致を拒否し、
404等を記録する。archive本体は取得しない。`--offline`時は.dsc cacheだけを読む。
追加分の`directory`はpool directoryを持たないためnull、`indexes`は空配列。

既存のimage抽出証拠・索引cacheを別worktreeから複製せず、
`map-sources.py --baseline`で元表の証拠hashと既存sourceを保持して補足する。
rendererのJSON記録は`.gitattributes`でLFへ固定する。元表は既存checkoutのCRLFも読取り時にLFへ正規化する。
既存`--evidence`経路の動作は維持する。B2b表は次の通信なしのコマンドで再生成できる。

~~~powershell
$sourcePython = 'C:/Users/ryuma/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$sourceLogger = 'C:/Users/ryuma/.codex/scripts/run_logged.py'
& $sourcePython $sourceLogger -- $sourcePython docker/map-sources.py --baseline qa/renderer-sources-2026-11-04.json --metadata qa/renderer-sources-2026-11-04-b2b-metadata.json --offline --output build/renderer-sources-2026-11-04-b2b.json
& $sourcePython $sourceLogger -- $sourcePython docker/fetch-source-metadata.py --table qa/renderer-sources-2026-11-04.json --source-locations qa/renderer-source-locations-2026-11-04-b2b.json --cache build/b2b/dsc-cache --offline --output build/b2b/dsc-only.json
~~~

2本目は採取済み.dsc cacheが必要で、公開履歴・Java追加調査を含める前の.dsc補足だけを出力する。
初回採取は`--offline`を外す。確定した全調査結果は上記の補足metadataが正本。

B2 worktreeルートでのWindows用コマンドは次のとおり。
Docker Desktopの通常起動以外に設定変更は不要。
保持済みimageを使う。今回の手順はregistryからimageを取得しない。

~~~powershell
$sourcePython = 'C:/Users/ryuma/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$sourceLogger = 'C:/Users/ryuma/.codex/scripts/run_logged.py'
& $sourcePython $sourceLogger -- docker desktop start
& $sourcePython $sourceLogger -- $sourcePython docker/inspect-sources.py --output build/renderer-sources
& $sourcePython $sourceLogger -- $sourcePython docker/map-sources.py --evidence build/renderer-sources/image-evidence.json --packages qa/renderer-repro-2026-11-04-packages.tsv --cache build/renderer-sources/indexes --output build/renderer-sources/index-table.json
& $sourcePython $sourceLogger -- $sourcePython docker/fetch-source-metadata.py --table build/renderer-sources/index-table.json --cache build/renderer-sources/metadata --output build/renderer-sources/supplemental.json
& $sourcePython $sourceLogger -- $sourcePython docker/map-sources.py --evidence build/renderer-sources/image-evidence.json --packages qa/renderer-repro-2026-11-04-packages.tsv --cache build/renderer-sources/indexes --offline --metadata build/renderer-sources/supplemental.json --output qa/renderer-sources-2026-11-04.json
& $sourcePython $sourceLogger -- $sourcePython docker/test-map-sources.py
& $sourcePython $sourceLogger -- git diff --check
& $sourcePython $sourceLogger -- docker desktop stop
~~~

map-sourcesは未一致があっても表と隣接SHAを書き、
**終了code 1**で全件対応が未達であることを示す。
B2元表は10件未一致でその状態だった。B2b更新表は220件対応で終了code 0。

fixtureは通信を禁止し、継続行、epoch、binary/source名差、
binNMUのsource明示版、native archive、未一致、誤帰属、
署名付き.dsc抽出、offline再生成、版不一致拒否を確認する。
B2の実行結果は単体テスト8件成功、git diff --check成功。
採取済み証跡からのoffline再生成で表のbytes一致を確認し、
image内の抽出文書についてsize/SHAを照合した。
アプリ本体を変えていないためGradle build・描画試験は対象外。
全source取得・bundle作成・署名検証・法的受入は未実施。

## 配布案と未確認・要法務判断

案は採用runtime版のGitHub Releaseにsource bundleとmanifestを
renderer archiveとともに固定assetとして公開すること。この作業では公開しない。

1. Ubuntuの旧8 source版は正規の取得先・size/SHAへ固定済み。
   Java 8はtag・公式source assetとマーカーの生成条件を確定したが、
   全変更差分・source archive実bytesとの対応確認は残る。
2. Ubuntuの`files`全構成員（.dsc、orig/debian/native tar、diff、署名等）を保持し、
   必要なbuild script・patch・license/noticeを含める。
   Temurinもsourceと必要なbuild情報・差分を版ごとに確定する。
3. bundle内の相対path・size/SHAとcomponent対応をmanifestへ記録し、
   最終bundle自体のsize/SHAを算出する。上流tarのSHAをbundleのSHAに流用しない。
4. 固定Release asset URLの確認後、最終inventoryのsourceArchivesを生成して
   descriptorへ同じURL/SHAを反映する。licenseFilesは1件以上の実文書path、
   noticeFilesも実体を検証したpathとする。productionへ仮URLを入れない。

残る確認・判断は次のとおり。

- Ubuntu/Debian各packageの個別条件、copyright/notice保持、
  対応source・build scriptの十分性、提供期間・方法。
  上流URLの一覧だけで条件充足とは判断しない。
- Mesa、LLVM、font、暗号系library、JDKの第三者component等の
  実配布bytesに適用される複数license・例外・notice。
- 未改変Ubuntu原本・個別package・改変renderer imageの区別、
  Canonical/Ubuntu等の名称・ロゴ・商標を含む配布条件。
  B1で見つかったロゴも、今回の調査だけで配布許可済みにしない。
- 索引/.dsc/Temurin releaseの署名・provenance、archiveの実bytes、
  Java 8変更部分、最終artifactの全fileのcomponent帰属。
- Ubuntu source名・版の未一致は0件。inventory/descriptorと実bundleの整合、公開後の取得確認は未実施。

#91/#93のlicense/source release gateは未完了。
この事実調査を再配布条件の確定や正式runtime採用として扱わない。

## 後始末と公開準備

調査コンテナは`--rm`で回収。再buildしなかったため新規image・build cacheはない。
後続用の既存`mcicon-renderer-repro:fixed-1`と他担当のcacheは保持した。
本作業で起動したDocker Desktopは停止した。証跡はB2のbuild配下に保持する。
UTF-8のcommit-message.txt、pr-title.txt、pr-body.mdをbuild配下に準備した。
commit・push・PR作成・Issue closeは行っていない。

## B2bの検証と後始末

単体テスト12件成功。追加分では設定URLからのoffline取得、全構成員のURL生成、
失敗URLの記録、版不一致・重複・誤帰属の拒否、既存証拠と210件の保持、
220件の通信なし再生成・追補表のbytes一致、CRLFの元表からも同じ結果になることを確認した。
採取済み8 .dscのsource名/版・size/SHAを再照合し、20構成員のHEADは全件200・size一致。
`git diff --check`成功。Gradle build・描画試験はアプリ本体を変えないため対象外。
Java 8の全変更差分・全source archiveの実hash・署名検証・法的受入は未完了。

Docker・コンテナ・image/cacheは今回起動・作成していない。バックグラウンド処理なし。
採取証拠は担当worktreeの`build/b2b/`へ保持した。
`build/commit-message.txt`、`build/pr-title.txt`、`build/pr-body.md`をUTF-8で準備した。
commit・push・PR作成・Issue closeは行っていない。Refs #91、#93。
