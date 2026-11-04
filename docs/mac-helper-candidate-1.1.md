# Mac helper/guest候補 1.1.0（D1）

Refs #91 / #94。2026-11-04の`origin/main` `f209a927143e0e0d25874de3ee9815da5ff87352`を基準に、同一Mac・同一toolchainで2回offline buildした。全196fileのbytes/modeが一致し、ZIP時刻を固定した候補も完全一致した。guestは取得済みqcow2を無改変で使う。guest OSそのものをsourceから再buildした結果ではない。

正式配布は未完了である。正式inventoryを2回発行したとは扱わない。公開URLとsource/notice bundleが未確定のため、既存生成器は未採用候補を2回とも拒否した。9component/196fileの帰属と106文書本文のsize/SHAを候補監査として確認したが、全license/source閉包の受入ではない。

## 入力と固定状況

全9取得入力はHEAD 200、実bytesのsize/SHA一致を確認した。URL・SHA・転送量・秒数は[再現測定](../qa/mac-helper-repro-2026-11-04.json)に保持する。既存入力にリンク切れ・pin不一致はなかった。

| 入力 | 取得元 | SHA-256 | bytes |
|---|---|---|---:|
| Lima Darwin-arm64 2.2.1 | [公式入力](https://github.com/lima-vm/lima/releases/download/v2.2.1/lima-2.2.1-Darwin-arm64.tar.gz) | `9e9eacce88f37e185c346bad73aa6136f738d8cdf8c3bb23cd42b071824bc66e` | 38,328,082 |
| Lima source v2.2.1 | [公式入力](https://github.com/lima-vm/lima/archive/refs/tags/v2.2.1.tar.gz) | `d551efb52115ba006c1052d5c929e4d3afac363c78c9cfca6975af1f85c1426a` | 7,887,831 |
| Ubuntu noble arm64 release-20260926 qcow2 | [公式入力](https://cloud-images.ubuntu.com/releases/noble/release-20260926/ubuntu-24.04-server-cloudimg-arm64.img) | `1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55` | 620,224,512 |
| 同release manifest | [公式入力](https://cloud-images.ubuntu.com/releases/noble/release-20260926/ubuntu-24.04-server-cloudimg-arm64.manifest) | `be8373ae3b3be49905c1e9984196196cc9148c6437ec96a735e90428ee8b8ac6` | 21,147 |
| runc_1.3.4-0ubuntu1~24.04.1_arm64.deb | [公式入力](https://snapshot.ubuntu.com/ubuntu/20261017T000000Z/pool/main/r/runc-app/runc_1.3.4-0ubuntu1~24.04.1_arm64.deb) | `725a27bbdd5f58d7059ed25d88f14f2d45f6a1ce089585eddc715e04d4a3f79f` | 8,578,418 |
| containerd_2.2.1-0ubuntu1~24.04.3_arm64.deb | [公式入力](https://snapshot.ubuntu.com/ubuntu/20261017T000000Z/pool/main/c/containerd-app/containerd_2.2.1-0ubuntu1~24.04.3_arm64.deb) | `e12495f100f025750ca91c07bab3a58c674afb1576fe55516608a8daa4d067bd` | 25,160,084 |
| docker.io_29.1.3-0ubuntu3~24.04.2_arm64.deb | [公式入力](https://snapshot.ubuntu.com/ubuntu/20261017T000000Z/pool/universe/d/docker.io-app/docker.io_29.1.3-0ubuntu3~24.04.2_arm64.deb) | `fb0b4f8f4738d3d610b511f50808abd49376ee715a81364e7dbd8a2c60c580d6` | 32,026,672 |
| jpackage/jlink用Temurin21.0.13+9 | [公式入力](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz) | `bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a` | 199,754,598 |
| Temurin21.0.13+9 source | [公式入力](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk-sources_21.0.13_9.tar.gz) | `b5232c9980d8a0f0459676c084c41c6e2c771ed9e785890b03c6a438406ad1ca` | 115,231,532 |

- Lima binary/sourceと3debは`prepare-payload.py`が版・size・SHAを固定している。copyright 3本文も固定size/SHAで検証した。3debの合計は65,765,174 bytes。
- guest/manifestは既存文書と`native-acceptance.py`にrelease日・size/SHAがあり、公式URLの明記が不足していた。上表の公式release URLを実取得して既存pinと結合した。SHA256SUMS署名の再検証は今回行っていない。
- helper用JDKは従来、既存Java21の選択だけでURL・版・SHAが未固定だった。今回の`java_home -v 21`はTemurin21.0.13+9で、公式binary archiveの全459通常fileと完全一致した。公式release metadataからbinary/sourceを取得検証し、以後の再生成入力を上表へ記録した。jlinkの専用JREは別の配布JREを取得せず、このJDKから生成する。
- Java依存はJackson annotations2.22/core2.22.3/databind2.22.3。版は既存Gradleが固定し、同梱JARの実SHAはfile manifestに記録した。3source JARも公式Maven Centralから取得検証した。
- `host-probe.swift`、`process-guard.c`、Linux amd64の`cpu-probe.S`は上記commitのGit blobへ結合した。Windows checkoutのCRLFをsource変更と混同せず、source SHAはMac cloneで使うGit blobのbytesを記録する。
- Gradle9.8.1は既存wrapperのSHA固定を使った。Apple clang17.0.0、Swift6.2.3、SDK26.2は実測環境の記録であり、SDK取得URL・全bytesまで固定していない。異なるhost/toolchain間の再現性は未検証。

## 生成と再現測定

MacはmacOS15.8.1（24H32）/arm64、Python3.12.16、Temurin21.0.13+9。clone・HOME・TMP・Gradle/Maven cache・Swift module cache・出力を`/Users/ryuman/tmp-mcicon-d1`へ隔離した。Macからの資材downloadは行わず、Windowsで検証した公式入力と必要なGradle/Jackson/JUnit/ASMだけを転送した。Minecraftは取得・起動していない。

既存`installDist` → `prepare-payload.py` → `package-helper.py --app-version 1.1.0` → `verify-packaging.py`を2回実行した。2回ともGradleは`--offline --no-daemon --rerun-tasks -PreleaseVersion=1.1.0`でJARとテストを再実行し、packagerの出力directoryを分けた。通信しないローカルMaven参照のinit scriptを使い、Windows固有のcache参照を回避した。brew・システム設定は変更していない。

| 測定 | 1回目 | 2回目 |
|---|---:|---:|
| build/test秒 | 11.772 | 11.822 |
| payload準備秒 | 0.286 | 0.288 |
| jlink/jpackage/native生成秒 | 29.54 | 28.969 |
| 既存packaging検証秒 | 4.698 | 4.679 |
| file数 / 展開後file合計bytes | 196 / 206,610,208 | 196 / 206,610,208 |
| ZIP bytes | 125,853,859 | 125,853,859 |

元ZIPは全entryの内容・modeが一致し、更新時刻260entryだけが異なった。元SHAは`fc4973b19809177bf4fea9bb0c22aab4777ab5ab26b2900acbf1db2c8758863c` / `08f134929900eb2c0bfc5d6c65eaeaefbec5e21b87f76d35a4f727c8f63de37a`。jpackage生成file・Mach-O・ad-hoc sealの差は0件だった。

既存`verify-packaging.py`のZIP時刻を1980-01-01へ固定した。file mode・directoryのstored・fileのdeflate level3は保持する。最終ZIPは両候補とも125,853,859 bytes、SHA-256 **`48ddab0fa68844a269e4ac0982cbffbfa2d67806207efdeb187c0900d05efbde`**。同梱file bytesや署名後treeを変更しない包装修正である。

準備payloadは各13file・113,885,438 bytes。入力取得量の合計は1,047,212,876 bytes。各download時間と、payload/ZIPの展開を含む検証時間はQAに分けて記録した。Windowsでzstandard0.25.0をbuild内へ一時導入した。補足測定では固定3debのdecompressとtar metadata走査が合計0.469秒、通常file相当264,927,784 bytesだった。これは全fileのdisk展開・offline dpkg時間ではない。Gradle/cache転送量は928,501,760 bytesの初回tarに加え、ASM・親POM・deb文書の補足転送がある。これを製品downloadサイズへ加算しない。

## nativeとarchive

Mach-O deployment targetはlauncher11.0、Swift probe13.0、C guard15.0。JRE/Limaを含む全Mach-Oのload command・署名情報はQAにある。これらのcompile targetを製品の最低版と読み替えない。plist表示版は1.1.0。

公式limactlの元bytesとnetwork client/server・virtualization entitlementsを復元・完全一致確認し、inner strict / outer deep strictの検証が成功した。outer appはad-hoc sealでありDeveloper ID/notarizationは使っていない。JRE legalのsymlinkは既存packagerで本文へ実体化され、最終treeにsymlinkはない。

移動後・ZIP再展開後の全file bytes/owner実行bit、deep strict署名、TMP/TEMP/TMPDIRだけの入口からinvalid lease拒否までを確認した。さらに現行coreの実`RuntimeArchive`でも最終ZIPの196file・206,610,208 bytesを展開して同じ境界を確認した。VMは起動していない。

RosettaとApple frameworkは再配布しない。archiveにそれらのpayloadは含まれず、Swift probeは`/System/Library/Frameworks/`や`/usr/lib/swift/`のOS提供ライブラリを動的参照する。Rosetta未同梱の確認から導入済み・実行成功を推論しない。

## inventory・license/source

[source対応表](../qa/mac-helper-sources-2026-11-04.json)は`docker/map-sources.py`と`docker/fetch-source-metadata.py`を再利用するMac用scriptで生成した。guest manifest663件＋offline deb3件の**666件すべて一致、未一致0件、source436版**。初回snapshot索引だけでは35binaryが欠けたため、公式pool/Launchpadの.dsc本文でBinary名・版を完全一致照合した。kernelのlinux/linux-signed/linux-metaも含む。候補の推測名を照合済みへ昇格させていない。

全sourceの.dsc/archive URL・size・SHAを記録する。索引署名・.dsc署名の再検証、およびUbuntu全source archive本体の取得は未実施で、表のfalse値を維持した。Lima source archive、Temurin binary/source、Jackson source3本は実取得・SHA確認済み。Temurinのsource tag/commitは実JDK releaseのSOURCEへ対応する。

```text
python helpers/macos/scripts/map-candidate-sources.py \
  --inputs build/d1/inputs --deb-documents build/d1/deb-documents \
  --environment build/d1/results/environment.json \
  --cache build/d1/source-indexes --offline \
  --output build/d1/sources-repeated.json
```

QAの候補帰属監査は9componentで全196fileを覆い、106文書本文のsize/SHAを実ZIPと照合した。正式inventoryの公開bundle参照は未解決である。`verify-inventory.py`は合成入力45条件が2回成功し、合成inventoryも同一bytesだった。これは実候補の全license完了を意味しない。候補の`finalBytesConfirmed=false`は2回とも期待どおり拒否され、仮URLや仮source manifestで正式発行していない。

残る閉包は、LimaのGo依存/標準ライブラリ、3deb copyrightが参照するfull licenseを含むbundle、guest内copyright・kernelを含む全file文書監査、公開source/notice bundleの本文とmanifest/pinである。source版対応666/666を再配布条件の全面受入と混同しない。

## 検証と後始末

- Macでhelper build/testを2回実行：各45件中44成功・1skip、失敗/error0。skipは実probe facts指定なしの条件。WindowsでのMac用testは37失敗・1skipであり、POSIX権限/プロセス差を確認してMacで再実行した。
- 既存packaging検証、最終ZIPの2候補一致、inventory合成45条件×2、実core RuntimeArchiveを確認した。
- source表のoffline再生成・同一bytes、再利用する既存source対応12テスト、Python構文、`git diff --check`が成功した。
- Mac一時領域は必要なarchive/測定/ログをWindows `build/d1/`へ保全後に削除し、不存在と作業用プロセス残存0を確認した。Dockerは未使用。自作成image/cacheもない。

## 未検証と次のgate

**Lima/VZでの実VM起動、Rosettaの導入・同意・変換実行、OS下限15.8.1での正式候補受入、未署名配布物の初回許可、管理描画は未検証。承認後に実行する別単位である。** 同じ15.8.1上での非VM packaging成功を製品受入へ拡張しない。実更新/uninstall/cold-start、正式trust/resource/package接続も既存gateを維持する。

正式候補採用には、Apple toolchainの再生成条件、上記license/source閉包と正式公開先、最終inventoryを確定する必要がある。#91/#94はRefsのまま維持し、commit・push・PR・release・Issue closeは行っていない。
