# Windows guest rootfs 1.1候補の生成

Refs #91, #95。C1はWindows prepared rootfsの生成・再現測定・source対応を扱う。正式配布物、署名descriptor、WSL実機受入は未完了である。起点は `659b51bc1f8277aa83681b49266a20e2f68caac5`（取得時の最新origin/main）。既存のH-C18等の受入を今回のbytesへ継承しない。

## 契約チェックリスト

正本は[Windows helper](../helpers/windows/README.md)、[helper契約](managed-helper-1.1.md)、[構成](managed-runtime-composition-1.1.md)、[runtime契約](managed-runtime-1.1.md)。公開API・protocol・保存形式は変更しない。

| 必須条件 | 今回の確認 | 残る確認 |
| --- | --- | --- |
| Ubuntu Noble linux/amd64、公式base固定 | multi-platform digestと`--platform linux/amd64`で固定 | 正式配布条件 |
| `/usr/bin/python3`と標準library | Python 3.12、bridge構文・標準module import成功 | WSL実guestでの操作 |
| `/usr/lib/mcicon/guestbridge.py` exact bytes、ABI 1 | repository sourceのSHAと収録bytesが一致 | 新rootfsと製品helperの結合 |
| `/usr/bin/docker`、`dockerd`、`containerd`、`runc`と依存 | 各version起動、動的library解決、各binary SHA記録 | 専用daemon実起動・socket/data-root所有 |
| `/usr/lib/mcicon/cpu-probe`、amd64 SSE4.1 | 既存macOS helper内のLinux amd64 assemblyを再利用。libc不要、Docker amd64上でexit 0 | 同一の正式renderer image内での実行 |
| root権限、cgroup v2、cpu/memory/pids、cgroup.kill、network無効daemon | rootfsのファイル要件とguestbridgeの固定argvを確認 | WSL kernel、専用namespace、controller供給・回収の実証 |
| 通常起動でapt導入しない | build時に全package導入済み。起動用install処理は追加しない | 実WSLの通常起動・offline製品経路 |
| hostでrootfsを展開しない、XZメモリ64MiB以内 | Docker exportをhostでtarとして読み直すだけ。file展開はしない。64MiB制限付きdecodeとxz実測が成功 | WSL import受入 |
| bridge/engine/probe/archiveのpin、inventory/source対応 | 各runの`inventory.json`とQAへ記録 | 最終descriptor・payload・source/notice bundleの発行 |

kernelの機能・容量・所有はrootfsのbytesだけでは保証できない。WSL import・実guest起動・offline・cgroup/容量能力・Docker engine実起動は**未検証**。WSL実機受入はユーザー承認後の別単位であり、本手順から`wsl --import`等を実行しない。

## 入力固定と構成

[Dockerfile](../helpers/windows/rootfs/Dockerfile)はUbuntu公式`ubuntu:noble@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852`を使う。取得時のamd64 manifestは`sha256:78d4744ead1f5b19b04d0df558ab182a5300903900755e515b2ca4d10eb2044d`。aptは`https://snapshot.ubuntu.com/ubuntu/20261103T000000Z/`に固定し、Ubuntu archiveの既存Signed-Byを維持する。外部Docker apt repoと依存追加は不要だった。

minimal baseにはCA bundleがない。公式snapshotの`ca-certificates_20240203_all.deb`をSHA-256 `641de77d8f142cfd62a1a6f964ba67b20754d3337c480efb529d086075a06c9a`で固定して取得し、そのMozilla CAから一時bundleを作る。aptのTLS・InRelease署名検証を維持し、署名検証済み索引の同package SHAとも照合する。その後、lockの新版ca-certificatesを通常導入してbootstrap用fileを除去する。TLSやapt署名検証を無効化しない。

[packages.lock](../helpers/windows/rootfs/packages.lock)はbaseに対する追加・更新の全31件を固定する。base内の変更しないpackageはimage digestで固定する。DockerはUbuntu版`docker.io=29.1.3-0ubuntu3~24.04.2`、`containerd=2.2.1-0ubuntu1~24.04.4`、`runc=1.3.4-0ubuntu1~24.04.1`。Pythonは`python3=3.12.3-0ubuntu2.1`と実体`python3.12=3.12.3-1ubuntu0.17`。推奨packageは導入しない。

[probe-packages.lock](../helpers/windows/rootfs/probe-packages.lock)のbinutils 2.42-4ubuntu2.10と依存8件は組立stageだけに置く。既存`helpers/macos/src/main/native/cpu-probe.S`はLinux amd64用であり、`as --64`と`ld -m elf_x86_64 --build-id=none`で生成する。最終rootfsにはprobe実行物、未変更guestbridge、projectのMIT通知をCOPYする。Ubuntu packageのcopyright/common-licensesは保持する。

aptの自動更新設定・cron・timer・有効化linkは削除・無効化しない。存在、mode、link、SHAはQAの`automatic_apt_files`に記録する。存在することとWSL上での自動実行は別であり、後者は未確認。systemdやWSL設定・boot scriptの追加はしない。helperは既存のnetwork無効argvで専用daemonを管理する。

## 生成と測定

WindowsのPython 3.11以降（B1と同じ標準libraryのみ）、Linux amd64 buildができるDocker Desktop/Buildxを使う。配布時のhost依存ではない。今回のPythonは3.12.14。同bytesの再現比較ではPythonとlzma実装も入力として揃える。QAのcompressionToolchainに実行ファイル・lzma拡張の観測SHAを保存し、版を変えた再生成の圧縮bytes一致は未確認とする。コマンドは規約の`run_logged.py --`を通して実行する。出力名は未使用のものを指定し、既存記録を上書きしない。

```text
python helpers/windows/rootfs/test-build.py
python docker/test-map-sources.py
python helpers/windows/rootfs/build.py fixed1
python helpers/windows/rootfs/build.py fixed2
python helpers/windows/rootfs/build.py --compare fixed1 fixed2 --output qa/windows-rootfs-repro-2026-11-04.json
python helpers/windows/rootfs/sources.py fixed1 --output qa/windows-rootfs-sources-2026-11-04.json
git diff --check
```

`build/windows-rootfs/<name>/`にbuild log、image情報、版付き`dpkg-query`一覧、元`docker-export.tar`とmember一覧、正規化tar、`rootfs.tar.xz`、全file一覧、inventory、smoke結果を保持する。source採取の2回目以降はSources索引が揃っていれば`sources.py --offline`を使える。これはmetadata取得を省く指定であり、製品offline受入を意味しない。

B1と同じ`SOURCE_DATE_EPOCH=1793664000`、`--no-cache`、`--provenance=false`、`rewrite-timestamp=true`で独立buildする。`PYTHONHASHSEED=0`をapt導入時に固定し、apt/dpkg/alternatives logとldconfig補助cacheを除去する。再生成できるbuild記録をrootfsへ持ち込まない。

`docker create`した停止containerをexportし、tar memberをpath順に並べ、mtimeを固定し、uname/gnameを空にする。数値uid/gid、mode、hardlink/symlink、device node、その他PAX属性は保持する。全ownerをrootへ変えてpackageの所有属性を壊さない。`.dockerenv`は除外し、Dockerが注入する`etc/hostname`・`etc/hosts`・`etc/resolv.conf`は明示した固定内容へ置き換える。元exportと正規化後の差を両方保存し、輸送用設定・時刻等のmetadata・内容/構造へ分類する。

XZはPython標準lzmaのpreset 6、単一stream、CRC64、8MiB辞書。元tarへのSHA一致を64MiB制限付きdecoderで確認し、さらに同じ固定imageの`xz --robot --list -vv`でdecoderメモリを測る。smokeは`--network=none --read-only`でPython・各binaryのversion・library解決・CPU probeだけを実行する。daemonを起動しない。

## 再現測定

[QA記録](../qa/windows-rootfs-repro-2026-11-04.json)の最終fixed1/fixed2で、入力hash・image ID・正規化tar・tar.xz・全file一覧・package一覧が一致した。通常file等6,275 memberの正規化後差分は0件。

| 測定 | fixed1 | fixed2 |
| --- | ---: | ---: |
| build時間（秒） | 73.734 | 77.672 |
| export・正規化・圧縮・検査（秒） | 170.719 | 161.922 |
| image累積size（bytes） | 557,471,361 | 557,471,361 |
| 非圧縮tar（bytes） | 403,507,200 | 403,507,200 |
| tar.xz（bytes） | 98,146,824 | 98,146,824 |
| 通常file論理size合計（bytes） | 398,953,130 | 398,953,130 |
| XZ decoderメモリ（bytes） | 8,454,200 | 8,454,200 |

- image ID: `sha256:bfffeb2e814314ab496753cc7723749860d90253025822977d5ab4757f13e8d9`
- tar SHA-256: `c3b5269dd0bb26a551167bc36554fc90df5f9fc62504b981c9528259e1599bad`
- tar.xz SHA-256: `f68070d5b702a00916ecd1108de1c3e8f4b644ebeb603ccf7f6b7b881ffb15c5`

元のDocker exportはSHAが異なる。10 memberにmtime差があり、輸送用4件（`.dockerenv`、hostname/hosts/resolv.conf）とその他metadata6件（dev、dev/console、dev/pts、dev/shm、etc、etc/mtab）に分類した。内容/構造差は0件で、元exportの差を隠していない。正規化は配布候補tarに適用する。

apt-daily/apt-daily-upgradeのservice・timer各2件、timers.target.wantsの有効化link2件、cronのapt-compatが存在する。apt.conf.d内8 fileの属性/hashもQAの`aptConfiguration`へ保存した。通常WSL起動での実行有無は未検証である。

展開後サイズはtar内の通常fileの論理bytes合計であり、WSL VHDの実使用量・空き容量の受入ではない。`guest-base`の`unpackedBytes`は少なくとも非圧縮tarのbytesを収める必要がある。圧縮原本・一時tar・import後diskの予算、descriptorの実上限は後段で確定する。`inventory.json`はpayload用pinの候補断片であり、署名済み製品inventory・descriptorを発行したものではない。

初回の版解決はCA bundle不足で失敗した。失敗logは`build/windows-rootfs/resolve/build.log`へ保持し、上記bootstrap方式で解決した。MIT通知追加前の`run1`は予備測定として別保存し、最終2回の比較へ混ぜていない。版解決用Dockerfile・base/runtime/probe package一覧も`build/windows-rootfs/resolve/`へ保持する。

## Source対応と残る配布gate

[B2採取](../docker/inspect-sources.py)と[B2対応付け](../docker/map-sources.py)を[専用入口](../helpers/windows/rootfs/sources.py)から再利用する。変更はexpected image ID指定、Java/workspaceがないimageの扱い、Refs指定とinventory不一致文言の一般化だけで、既定のrenderer pin・対応方法は維持する。

[対応表](../qa/windows-rootfs-sources-2026-11-04.json)は全123 packageを86 sourceへ完全一致で結び、未一致0件、copyright欠落0件。各binaryのSource field、copyright path/hash、各sourceの版・.dsc・source archive URL/bytes/SHA-256、索引のSHAを保持する。rootfs archive pinと、guestbridge/probeの元sourceおよびMIT通知のhashも同じ表へ結合する。

Sources索引の独立署名検証、source archive本体の取得・bytes検証、source/notice bundleの公開、Ubuntu改変版の商標・再配布条件、正式trust/descriptorとの結合は未完了である。aptの署名検証成功をsource archive検証へ読み替えない。今回は固定snapshot内で全件一致し、Launchpad補足や未許可の外部取得は不要だった。

## 後始末

自分が作成した`mcicon-windows-rootfs-c1:{resolve,resolve-runtime,resolve-probe,run1,fixed1,fixed2}`の6タグと、今回新規取得したUbuntu digest参照を削除した。対象imageのcontainer残存0件を確認した。共有builderのcacheは開始時の所有を分離できないためpruneせず保全した。

起動したDocker Desktopは通常停止し、Desktop/backend process不在とengine pipe消失で確認した。停止後の`docker desktop status`は非zeroとなったため、そのコマンドだけを停止確認の根拠にしていない。`build/windows-rootfs/`のarchive・全file一覧・package一覧・source文書・失敗log・環境記録を保持する。image削除後の再採取には、未使用の測定名で再buildが必要になる。

commit・push・PR・release、WSL import・distro操作、Windows機能変更、Minecraft実行は行っていない。

## 1.1.1 段階C（2026-11-06）

起点c7b507aのguestbridgeを収録し、上記Ubuntu digest・snapshot・両lockと
SOURCE_DATE_EPOCH=1793664000を維持して --no-cache で独立2run生成した。
Python3.12.14の同じlzma条件で圧縮し、XZ64MiB制限付きdecode・tar照合も成功した。

- rootfs tar.xz: **98,147,132 bytes**、SHA-256 **b9be53fe3bae9c468ae72e8ad0e24bf698090a93c7248b18b4fbe52b95d9c54c**
- 非圧縮tar: **403,507,200 bytes**、SHA-256 **f42ba303958a7259621bf70670a83440fcae3df4afd46c932907afb4cf73e8e6**
- 2runの圧縮bytes・正規化tar・file一覧・package一覧は一致。正規化後差分0件。
- bridge実収録bytesのSHA-256: **754447a88923b11799ba3510bfc924d0071729958ba7971cb1d23fbc6977e179**。helper payloadとguestBase pinも一致。
- docker/dockerd/containerd/runc・cpu-probe pinはC1から不変。実tar.xz内本文と全pinを照合した。

詳細は[段階C QA](../qa/windows-helper-candidate-1.1.1-2026-11-06.json)。
raw Docker exportのmetadata差は別記録し、配布tarの一致と区別する。
Docker Desktop停止・ユーザーWSL39件の登録/稼働状態の一致を確認済み。
Docker内部distroの起動時再登録によるGUID差はQAに明記した。名前・保存先・版・停止状態は同じ。
WSL import・製品daemon/cgroup・offline受入は今回実施せず、後続工程へ渡す。
