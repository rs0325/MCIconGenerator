# Windows nativeの事前build

`build-native.py`はMac上の検査済LLVM-MinGWだけを明示指定し、PEとbuild.json/licenseを新しい出力directoryへ作る。取得、展開、署名設定変更、Windowsでのcompileは行わない。既存出力へ上書きしない。実行前に利用者が次を満たす。

- [公式LLVM-MinGW 20261006](https://github.com/mstorsjo/llvm-mingw/releases/tag/20261006) の `llvm-mingw-20261006-ucrt-macos-universal.tar.xz` を固定する。124399776 bytes、SHA-256 `a7a19b11048e89372637d26279cdb7c430ed3cf8c1e224310421312bf986ebf6`。LLVM23.1.3、x86_64/UCRT。
- 安全に展開した同一archiveの世代内にwrapper/clang-23/LLD/readobj/内部dylibが解決すること、arm64で実行可能なこと、otoolでsystemまたは同世代以外の依存が無いことを確認する。検査後のtoolchainを変更しない。
- この配布は私用検査で実Mach-Oが未署名だった。公式HTTPS/API digest一致を根拠とする開発用toolの利用判断が必要。署名済みとは扱わず、Gatekeeper/xattr/sudo/再署名でOS拒否を回避しない。製品Authenticodeや再配布の受入とは分ける。
- 同梱LICENSE.TXTとMinGW COPYING群を保持する。生成PEの静的runtimeのnoticeもbuild.jsonと一緒に包装される。最終製品の再配布license受入を代用しない。

compiler flagsはnative_artifact.pyに固定する。`--no-default-config`でユーザー/自動cfgを読まず、固定archiveのmingw32-common.cfgにある `-rtlib=compiler-rt -unwindlib=libunwind -stdlib=libc++ -fuse-ld=lld` だけを明示する。`-static`と`-Werror`を維持し、CCACHE/ユーザーflags/DYLDなどは継承しない。HOMEは再目的化せず、PATHはsystemだけ、TMPは新出力内に限定する。compileは120秒、期限時は専用process groupをkillし10秒以内に回収する。

```text
python3.12 helpers/windows/scripts/build-native.py --toolchain /absolute/verified/llvm-mingw-20261006-ucrt-macos-universal --archive /absolute/llvm-mingw-20261006-ucrt-macos-universal.tar.xz --source /absolute/repository/helpers/windows/src/main/native/windows-host.cpp --output /absolute/new-native-generation
```

build.jsonは元source SHA、toolchain archiveとcompiler入力SHA、固定flags、PEのbytes/SHA/import、licenseのbytes/SHAを記録する。PEは32+ AMD64 console、importはKERNEL32/ADVAPI32/OLE32/UCRTに限定し、C++/unwind/pthread/GCC/MSVC runtime DLLや未知DLLを拒否する。packagerはbuild.json・PE・現在のsource・noticeを再照合する。build.jsonは由来の整合記録であり、署名された信頼根ではない。信頼済み配布descriptorの生成は別段階。

Windows包装にはJDK21と検証済payloadに加えて `-PwindowsNativeDir` が必須となる。sourceをWindowsコンパイラへ渡す経路や旧MSVC fallbackは残さない。包装はWindows上で別途実行が必要で、MacのfixtureやPE静的検査だけでは実Windows動作を受け入れない。

## 限定native QAの来歴

2026-10-19の対照試験では、次の候補を元sourceと同一bytesでcrossbuildし、原PEと共通の強化fixtureで実測した。

| 対象 | SHA-256 |
| --- | --- |
| windows-host.cpp | `185031af1d0a7e9f231f51dc7d9f01697f8d75df568e13e1062e2e0276a12828` |
| 候補PE（1475072 bytes） | `f179dbd89cf4f6f24ffaa75b2be2c6b80ec6a60ffa23f37dc26b83599d2e432f` |
| 原PE（1474048 bytes） | `1d6e676007375680aee11a405f528dfb7d99afea112174528dff4360641aa2d9` |
| 対照QA入力ZIP | `dcbc5d7d1d3ef1c937d8dac20f263017bf300ee9e5f2348ce2ea45bfc35bf3a8` |

両PEともargvの実exit0、子孫回収・親死亡・guard死亡・期限の実exit125を確認し、各17既知actorを回収した。後二条件はdone欠落のunknown記録を保持する。候補の自身handle解放はAPI責務を明確にする修正であり、今回原PEも成功したため前回argv失敗の原因確定や再現解消とは扱わない。実exit・例外位置・Job accountingを保存するQAを使い、原失敗結果は変更していない。

この限定結果はWindows app-image/JRE包装、実WSL、renderer、再起動、OS最小版、署名・再配布の受入を含まない。build.jsonのcrossbuild区分は変更せず、限定QAの来歴と製品全体の受入を分ける。


### 現在のsource/PE世代

引用訂正source `55478f2c6852e9bc792ef7285ef1d99114f552a7a9edf2479ecdc07f253416d1` / PE `c31aa33644ddfba5c4075afbe06a7aefafa51d75e167c94e9efefb478821c405` は履歴として保持する。現sourceは `932c366edd7d7a909e96fb482db7d7b093f6105ec58181d372d9de6f0ff58e87`、現PE（1476608 bytes）は `a14c23cdbed4de8663e81aa072e3b96ec731180f91897ec169cd88a957ed4896`。argv/APIを変えず、child終了後の最大2秒を自然drain最大500msと同endまでの強制回収へ配分する。例外回収で期限を再開始しない。

2026-10-20の同argv/flags0のfacts-only対照で旧PE guard125、新PE guard0を観測した。新PEはJobactive1→0、queries2を実測した。正確なdrain経過時間は未採取で、両caseを含むPSrun windowは475ms。4clientと両guard/controllerの回収・done・本人性を照合した。旧FAILを保持し、この結果をWSL import/attachedworker/全製品受入へ継承しない。boot GUIDの実読取は成功したが旧proofと今回値は同じで、実boot差は未受入。

build.jsonは同じsourcebytesからのcrossbuild/notice整合記録を保ち、facts限定QAは別の来歴として扱う。`test-native-completion.py`は実sourceの回収関数を抽出し、fake API/clockで15条件を検査する。これは実Windowsやkernel動作の代用ではない。

### A-current実測の結合

現source/PEを変更せずA-currentの5条件を実行し、独立raw監査PASSを得た。入力ZIP SHA-256は `7dc0fefe2ad9014c10ea98bd2d024dea31337c0884c8a10edd83d5dd9eb0bfc5`、結果freeze SHA-256は `ab2dad9a2181688e4d6744eb00323c7710a766dd721f0eb34c2904d598e9afdd`。これは署名された配布pinではなく、固定実測の来歴である。

| 条件 | 実guard exit | 終端record |
| --- | --- | --- |
| argv | 0 | 本人state/done一致 |
| 子孫回収 | 125 | 本人state/done一致 |
| 親死亡 | 125 | 本人state/done一致 |
| guard死亡 | 125 | done欠落・unknown保持 |
| 期限 | 125 | done欠落・unknown保持 |

17既知actorと4接続clientを有限回収し、前後facts8field/configのsameboot一致を確認した。未知actor不在・全資源clear・実boot差は未証明。WSLコマンドやguestは実行せず、WSL import/attachedworker/製品包装/51版は未受入のまま。現source/PEのcrossbuild記録と本限定QAを結合し、過去f179/1d6e世代の受入を継承したものではない。
