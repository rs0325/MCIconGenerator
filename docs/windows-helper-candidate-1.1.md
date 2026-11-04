# Windows helper 1.1候補の再現build・資材結合（E1）

Refs #91、#95。基準は前担当が確認した`origin/main`、`4a7c5d0`（完全なcommitは[QA](../qa/windows-helper-repro-2026-11-04.json)を参照）。**E1の入力固定・再現build・最終候補包装・source照合は完了した。** Microsoft DLLの法務判断、公開資材と署名descriptorの採用、実機・全版受入は後続gateである。公開API、protocol、保存形式は変更していない。native包装検証にWindows版toolchain pinを追加した。過去のH-C18等の受入を今回へ継承しない。

## 入力固定

既存`installDist`、`jlink`、`jpackage`、[native検証](../helpers/windows/scripts/native_artifact.py)、B1の[測定器](../docker/measure-repro.py)を再利用する。追加libraryは導入していない。

| 構成要素 | 入力・固定状態 | 今回の確認と不足 |
| --- | --- | --- |
| native C++ PE / 静的runtime | [手順](../helpers/windows/scripts/native-build.md)のLLVM-MinGW 20261006 / LLVM 23.1.3。公式Windows x86_64/UCRT ZIP、190,836,485 bytes、SHA `317492c456aa27ee607a5919f1d2d38dcdc1112516a24d0bf4b00d078f52d17a` | 公式API/UI digestとPython/PowerShellの実hashが一致。展開9,053fileを原ZIPと照合。compiler/LLVM DLL・target library 1,003入力のSHAとlicense/sourceを記録。Mac手順を維持 |
| helper Windows/common | 上記commitのJava 21 source、releaseVersion 1.1.0、Gradle 9.8.1 | `--offline --no-daemon --rerun-tasks`で2回生成。依存10 JARの実size/SHAをQAに保存。wrapper配布archive自体のSHA再検証はしていない |
| 専用JRE / jpackage | Temurin 21.0.12.1+1、公式Windows ZIP 205,073,461 bytes、SHA `f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e` | 固定版の公式metadataとZIPのsize/SHAを照合。実JDKのrelease・java/jlink/jpackage・jmods・libの94fileがZIPと一致。source archive 115,126,841 bytes、SHA `573057d03584ae793fb7ec9a14c76d826d9187a53efeefd99da47403a5308234`も実取得・一致 |
| guest bridge | C1収録bytes SHA `508ea9fb5cb33babb98a74a71554419a9353f931c4f2bfccb16b6545f87e90ff`、ABI 1 | 現Windows checkoutと一致。今回guestは実行していない |
| guest-base | C1の[固定rootfs](windows-guest-rootfs-1.1.md) | archive原本を今回はコピー・再hashしていない。C1の既存実測pinを参照 |
| renderer | B1のimage ID `sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc` | 既存imageを再利用し、ID指定saveがB1のSHAと一致。新しい輸送archiveを2回生成 |
| Microsoft runtime同梱物 | JREのapi-ms-*.dll、ucrtbase.dll、msvcp140.dll、vcruntime140*.dll | Temurin ZIP由来の49 DLL。OS提供物から分離し、再配布資格・適用license・notice・source提供要否は法務判断が必要と記録。配布可能とは確定しない |
| OS補助 | 同梱DLL以外のWindows system DLL、WSL OS機能/kernel | 再配布しない。存在・版・動作の受入は今回行っていない |

Temurin binary/sourceに加え、公式LLVM-MinGW binary、LLVM/MinGW/source recipe、Apache 4件とXZのsourceを実取得した。取得元URL・asset・size・SHA・license所在は[固定入力記録](../qa/windows-helper-source-inputs-2026-11-04.json)に保存した。新しい版の自動解決や別ミラーへの切替はしていない。

Windowsの`core.autocrlf=true`により、native sourceのcheckout SHAは`a20f1b06d94802033699c48c4d7ac69d55e77a43bb4e83765b8b7fabdf252021`、Git blob SHAは`9484e9341ef048c63247432723dc19cc9908e2ed075a02711327255dece55ea0`となる。guest bridgeもGit blobはLF、C1実収録はCRLFのcheckout bytesである。source表は両hashを保存し、改行以外の変更がないことを照合する。C1のpinを壊す一括改行変更は加えない。別hostでの再生成ではcheckout bytesも入力として合わせる必要がある。

## このWindowsでの2回測定

Windows、Python 3.12.14、zlib 1.3.2、既存Temurin 21を使用した。実環境の文字列はQAが正本。出力・TMPを専用worktreeの`build/e1/`に保持する。Dockerはrendererのsaveだけに使用し、Minecraft・WSLコマンドは実行していない。

| Java/JRE中間候補 | 1回目 | 2回目 |
| --- | ---: | ---: |
| JAR build秒 | 11.484 | 14.000 |
| jlink秒 | 3.937 | 6.141 |
| jpackage秒 | 0.563 | 1.062 |
| app-image file数 / 合計bytes | 214 / 91,672,127 | 214 / 91,672,127 |
| 中間ZIP bytes | 35,423,487 | 35,423,487 |

全JAR・全214fileの内容差は0件。中間ZIPはpath順、時刻1980-01-01、deflate level3、`.exe`は0755・他fileは0644で包装し、各entryを元manifestへ再照合した。SHAは両方`ef2ab96f9c7c00fa486b164dca059db6cdd950bff52a8be09e9b997629ecacab`。linkは0件。jpackage launcherは453,632 bytes、AMD64、GUI subsystem 2、certificate directoryは0で、Authenticode署名がない。PE timestampは両方2089015567で一致した。これはテンプレート由来のfieldであり、今回のbuild時刻と解釈しない。

このJava/JRE中間ZIPは測定履歴として保持する。最終候補は下記のnative/C1 bridge/support結合済みZIPであり、中間ZIPをdescriptorに採用しない。jpackage launcherのsubsystem 2とnative Job入口のsubsystem 3を区別する。

## nativeと最終app-imageの2回測定

現checkout source SHA `a20f1b06d94802033699c48c4d7ac69d55e77a43bb4e83765b8b7fabdf252021`を、固定Windows toolchainで独立した2出力先へcompileした。既存flagsの`-Wl,--no-insert-timestamp`を維持し、再現性修正は不要だった。両runのPEは1,506,816 bytes、SHA `1112d86bd18633004759b4d327d9cd0e9e3772ecafb472b5cc731e50e39166d8`、AMD64 console subsystem 3、timestamp 0、certificate directory `[0,0]`で、埋込Authenticode署名はない。build.jsonも一致した。

importsは既存Python PE解析と公式toolchainの`llvm-objdump -p`を照合した。KERNEL32、ADVAPI32、OLE32と12件の`api-ms-win-crt-*.dll`で一致し、C++/unwind/pthread/GCC/MSVC runtime DLLはimportしない。全DLL名はQAに保存した。定義済みpthread symbolも0件。静的runtimeのnotice 4文書を保持した。過去Mac PEとはhost/archiveとsource checkout世代が異なり、同bytesや実機受入の継承を主張しない。

C1のQA inventoryにあるguestBase pinと実収録bridge SHAを使い、同一checkout bytesの`guestbridge.py`と`payload.json`を`build/e1/c1-payload/`へ構成した。rootfs archiveをhost ZIPへ重複収録せず、原archiveの再取得・再hashとは区別する。既存`package-helper.py`でrunごとのnative/build.json/licenseとJava JARを結合した。

| 最終候補 | 1回目 | 2回目 |
| --- | ---: | ---: |
| jlink・jpackage・ZIP照合秒 | 7.359 | 7.297 |
| file数 / 合計bytes | 230 / 93,464,196 | 230 / 93,464,196 |
| ZIP bytes | 36,029,106 | 36,029,106 |

全fileとZIP bytesの差分は0件。ZIP SHAは両方`efc2c38e735cf359ea751931ce7d2f746e8b082a758cd2f32ea885ad48257ab8`。path順・時刻1980-01-01・deflate level3・exe 0755/他0644で包装し、全entryのsize/SHAを元treeへ照合した。生成物は`build/e1/final-1/`と`final-2/`に保持する。これを公開済み・正式descriptor採用済みとは扱わない。

## rendererのimage→tar.gz

[export-candidate.py](../docker/export-candidate.py)はB1固定imageだけを受け付け、`docker save <image ID>`を使う。外側tarをpath順、mtime 1793664000、uid/gid 0、owner名空に固定し、gzipのmtime 0・filename空・level6で圧縮する。layer/config/index/manifest本文、file size・type・modeは維持する。元saveと正規化後の全member内容をB1の測定関数で比較する。Python/zlibの版が異なる環境でのgzip bytes一致は未確認である。

```text
python docker/test-export-candidate.py
python docker/export-candidate.py --output build/e1/repeat-renderer-1
python docker/export-candidate.py --output build/e1/repeat-renderer-2
```

| 測定 | bytes | SHA-256（両run共通） |
| --- | ---: | --- |
| 元ID指定save | 598,764,544 | `6e63f160f2e3d15af3b088485739b21a076cf8672a9b02dc5600299d6daa1fe1` |
| 正規化tar | 598,773,760 | `b82f097689841b8272b493a2f3d4250f72861801aa0af3ccfef749698fff7785` |
| tar.gz | 593,385,993 | `dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4` |

save・正規化・圧縮・一覧採取は37.515秒 / 40.062秒。19memberの内容を保持し、RepoTags=null、config digestはB1と一致した。gzipをdecodeしたtar SHAと、全`blobs/sha256/`本文のdigestも実照合した。新tar.gzの`docker load`やguestでのCPU probe実行は未検証である。

## inventoryとsource

[source対応表](../qa/windows-helper-sources-2026-11-04.json)は[専用入口](../helpers/windows/scripts/map-candidate-sources.py)から既存`docker/map-sources.py`の形式・digest処理、B2のTemurin source記録、D1のJackson source pinを再利用して生成する。通信はしない。

```text
python -X utf8 helpers/windows/scripts/map-candidate-sources.py --measurement build/e1/final-measurement.json --jdk "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" --inputs build/e1/inputs --source-inputs qa/windows-helper-source-inputs-2026-11-04.json --native-build build/e1/native-1/build.json --output build/e1/repeated-sources.json
```

14構成要素中13件が対応、残る1件はMicrosoft runtimeの法務判断である。Temurin 1、Jackson 3、project Windows/common/bridge 3に加え、Commons Codec/Compress/IO/LangとXZの5件、native/runtimeの1件を対応させた。Apache sourceは公式SHA-512とも一致し、XZは公式HTTPSから取得した実SHA-256を固定した（OpenPGP署名の検証とは区別する）。LLVM 23.1.3 sourceは公式release digestと一致し、LLVM-MinGWの同版recipe、MinGW commit、compiler-rt/libc++/libc++abi/libunwindの各licenseとsource archiveを記録した。toolchain自身をsourceから再buildした証明ではない。

Temurin sourceは今回実SHA検証、Jackson 3本はD1の既存証拠参照である。Microsoft公式の[Visual C++再配布条件](https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files?view=msvc-170)と[UCRT配布制約](https://learn.microsoft.com/en-us/cpp/windows/universal-crt-deployment?view=msvc-170)を根拠として、適用license・再配布資格は法務判断に残す。source対応を公開source bundle発行や法務受入と同一視しない。

`build/e1/candidate-inventory.json`は最終treeの全230fileを14componentへ一意に帰属させ、102のlicense/notice文書のsize/SHAを保存した。同じinventoryをQAの`finalInventory`にも収録した。Microsoft DLL 49件はTemurin本体から分け、実version resourceは`build/e1/microsoft-runtime.json`に保持した。全fileのmanifest・ZIP内容を照合し、Apache 4件のNOTICEとXZのCOPYINGが同梱文書に保持されることも確認した。正式配布inventoryの採用、Microsoft条件、全source/notice bundle本文・manifest・公開参照が残るため、`finalBytesConfirmed=false`を維持する。

## windows/x86_64の結合点

入力契約は[resource生成](managed-runtime-resources-1.1.md)と[helper](managed-helper-1.1.md)に従う。package source表・rootfsのpayload pin断片は、署名descriptorが参照する正式inventoryの代用ではない。

| artifact | archive bytes / SHA | unpackedBytesとinventory |
| --- | --- | --- |
| host-helper ZIP | 36,029,106 / `efc2c38e735cf359ea751931ce7d2f746e8b082a758cd2f32ea885ad48257ab8` | file合計93,464,196 bytes。entry `MCIconHelper/MCIconHelper.exe`、exe 0755。全230fileの候補inventoryはQA `finalInventory`。正式採用前 |
| guest-base tar.xz | 98,146,824 / `f68070d5b702a00916ecd1108de1c3e8f4b644ebeb603ccf7f6b7b881ffb15c5` | C1の非圧縮tar 403,507,200 bytes以上を収める。通常file合計398,953,130とは異なる。C1 QA内inventoryとsource表を参照し、正式inventoryは未発行 |
| renderer-image tar.gz | 593,385,993 / `dc0a1acc5b8421f04271ffe4b79403fda7967a7a86422488650533a86aaf93d4` | 非圧縮tar 598,773,760 bytes以上を収める。image累積size 2,056,219,398やVHD予算と同一視しない。B2 source表を参照し、新archiveの正式inventoryは未発行 |

不足は、全3artifactの正式HTTPS URL、source/notice bundle本文・manifest・bytes/SHA・公開URL、inventoryの正式採用、toolVersion/worker agent SHA/adapter集合、正式trust/descriptor/64byte署名、Microsoft法務判断、OS範囲・警告・candidate採用である。bytesが2回一致したことだけで`finalBytesConfirmed=true`にしない。仮URLや未採用候補で本番resourcesを生成していない。

## 検証・反証

- renderer包装の回帰2テスト成功。順序/owner/時刻だけが違うtarの一致、blob本文・mode保持、重複pathとlinkの拒否を確認した。
- helper-windowsはmain比較とも49件中28成功・21skip・失敗0。実native/guest fixturesのskipを実機受入として数えない。
- helper-commonはmain比較とも33件中32成功・1失敗。DockerRequestTestの絶対path検査で同じ失敗。
- coreはmain比較とも166件中135成功・28skip・3失敗。InstallationImportTestの期待例外、RuntimeLeaseTestのmandatory byte-range lock、WorkerArtifactTestの後始末で同じ失敗。今回の変更による新しい失敗ではない。
- test-daemon成功、既存source対応テスト成功。今回native artifact/packagerはUTF-8で5件、build回収は8件成功した。buildテストはPOSIX APIをmockしてWindows上でMac経路を検査し、Windowsのtimeout/回収失敗も検査した。前回のcp932/Windows POSIX API不在の失敗はQAの履歴に保持する。test-guestbridgeはfcntl不在でmainも同じ失敗。guestのLinuxテストやmock回収を実機成功へ置き換えない。
- 最終source表のoffline再生成一致、不正source SHA拒否、Python構文、追跡外追加fileを含む空白差分、QA SHAを検証した。helper-windowsは今回再実行してmainの既存比較と一致。helper-common/coreの同一失敗の比較結果は前回の証拠を再利用し、再実行していない。
- main比較は`git archive HEAD`を担当worktreeの`build/e1/baseline-main/`へ展開して実行した。primary・他worktreeのファイルを変更していない。

## 未検証・残作業

Windows版toolchain固定、現sourceのnative 2回compile、PE/import/notice検査、C1 bridgeを含む最終app-image 2回包装、ZIP/inventory/source照合は完了した。Microsoft法務判断、公開資材・descriptorの正式採用、以下の実機受入は後続である。

**WSL import、実guest起動、現Job回収の実機、未準備host setup、OS警告/SmartScreen、全51版は未検証で、承認後または後続gateである。** WSL有効化、distro操作、Minecraft、鍵・認証探索、commit/push/PR/releaseは行っていない。

前担当はrenderer測定で起動したDocker Desktopを停止済みで、今回E1bではDockerを起動していない。image・cache・containerも作成していない。自分のcompile・包装・検証processは終了し、常駐processは残していない。B1の既存imageを保持し、取得archive・展開toolchain・生成候補・失敗ログは`build/e1/`へ保全した。commit/push/PR作成は行わず、準備文面を`build/commit-message.txt`、`build/pr-title.txt`、`build/pr-body.md`へUTF-8で保存した。
