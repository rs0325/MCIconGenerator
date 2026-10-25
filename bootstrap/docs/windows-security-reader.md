# Windows bootstrap の security reader

`windows-security-reader.exe` は、seed 展開前の bootstrap が directory の所有者・raw DACL・型・identity を観測するための別 PE である。親の安全判定、新規 node の所有者設定、CLI 起動は後続 #97 の責務で、この reader は作成・ACL変更・削除・子起動・WSL操作を行わない。

## 入力と観測

stdin に一つの UTF-8 JSON を送って EOF にする。field 順は `schemaVersion`、`paths` に固定し、文字列は UTF-8、引用符・backslash・slash だけ JSON escape を受け付ける。Java caller の Jackson 出力と一致する専用形式で、汎用 JSON parser は追加しない。入力64KiB、最大64 directory。local drive rootから、一段ずつ直下の既存 directory を指定する。

```json
{"schemaVersion":1,"paths":["C:\\","C:\\Users","C:\\Users\\example"]}
```

最初の drive root をアンカーとして開き、次の component は保持した parent を `NtCreateFile` の `RootDirectory` に指定する。`FILE_OPEN`、`FILE_DIRECTORY_FILE`、`FILE_OPEN_REPARSE_POINT`、child の `OBJ_DONT_REPARSE` を使い、read/write共有・delete非共有とする。絶対 path の再走査や同一 volume だけで親への帰属を推測しない。

owner/DACL は `GetSecurityInfo`、型・reparse tag・volume/file ID は同じ保持 handle の `GetFileInformationByHandleEx` から得る。response の行順、parent index、relative name、parent ID を保持 handle と結合する。reparse、型不一致、API失敗、観測された identity/属性差は unknown とする。reparse/type拒否では実属性と tag も返す。

DACL present/null/空を区別し、ALLOW/DENY の SID と raw uint32 mask を保持する。generic mask を NIO permissions へ変換しない。その他 ACE は header/type/flags と `unknown:true` を残し、黙って捨てない。ACL/ACE/出力の上限超過も unknown で、truncated な安全観測へ変換しない。

SD と属性は別 API による可変な観測である。全 chain の原子的 snapshot、途中変更の全検出、解放後の不変は保証しない。Java 側が後続の親安全 gate を通して初めて新規作成へ進める。同 SID・昇格管理者・SYSTEM は信頼範囲とし、既 Admin 所有 root の本人採用・ACL救済は行わない。

## 有限 caller と package 信頼

`WindowsSecurityReader` の入力 `installed` は、installer/package配置側で実体・非reparse・他標準userによる書換え拒否を検証済みの prefix であることを前提とする。caller自身が確認するのは絶対path化、Javaがsymlinkとして認識する経路の拒否、通常file、exe/build記録/licenseのhashであり、Windows junction等を含む全reparseやcanonical実体一致を自身で証明しない。後続 #97 integration がこの前提を満たす配置検証を接続する責務を持つ。readerによる自己検証を信頼の起点にして循環させない。ユーザーが書換えたinventoryの自己採用やreaderのhashだけではprefix安全性の証明にならない。

単一要求の writer と stdout/stderr drainer を並行に開始し、Process 起動時から write/wait/drain 全てを同じ5秒期限で監督する。stdinは送信後EOFにする。stdout1MiB/stderr64KiB、途中終了・送信失敗・上限・期限は成功にしない。回収は保持した直接 ProcessHandle だけを TERM/KILL し、起動時期限＋固定2秒まで待つ。writer の同じ stream を監督 thread が先に同期 close して待ち続ける構造は使わない。全 I/O task の終端も確認し、回収不能は元 cause に unknown を併記する。native は5秒の自己予算を持つが、kernel 内停止を自己期限で中断できる保証はない。

## build と同梱

初版は local NTFS / Windows x64。ARM64、任意 Windows 版、実 OS API、署名/SmartScreen の受入を crossbuild で代用しない。PowerShell/.NET/FFM/JNA を製品依存に加えない。Java同梱 runtime の module 追加は不要である。

検査済 [LLVM-MinGW 20261006](https://github.com/mstorsjo/llvm-mingw/releases/tag/20261006)、124399776 bytes、SHA-256 `a7a19b11048e89372637d26279cdb7c430ed3cf8c1e224310421312bf986ebf6` だけを明示する。既6 tool の pin と arm64 Mach-O/system・同世代 dylib closure、ntdll header/import library を別途確認する。compiler探索・自動取得・host導入・OS拒否回避を build script は行わない。

```text
python3.12 bootstrap/scripts/build-windows-security-reader.py --toolchain /absolute/verified/toolchain --archive /absolute/fixed-archive.tar.xz --output /absolute/new-native
python3.12 bootstrap/scripts/stage-windows-security-reader.py --input /absolute/bootstrap-input --native /absolute/new-native --output /absolute/new-bootstrap-input
```

`stageWindowsSecurityReader` Gradle task も同じ staging を呼ぶ。`windowsSecurityNativeDir`、`windowsBootstrapInputDir`、`windowsBootstrapOutputDir` を明示する。staging は新規入力を生成するだけで jpackage や署名を行わない。既存 reader/build/license は上書きしない。Windows app-image を生成する次段は、staged input の全 file を保持し、最終 app-image inventory と結合する必要がある。

build は C++17/static、system/UCRTとntdllの許可 import に限定する。reader CPP と専用 protocol header の双方、toolchain、flags、PE bytes/hash/import、license の pin を記録する。既 helper の有限 build 監督を再利用し、Job/exec 製品 ABI は変更しない。native build 記録は署名された信頼根ではない。

source は repo MIT。LLVM license/例外、MinGW COPYING群、project LICENSE/THIRD_PARTY_NOTICES を package に同梱する。静的 compiler-rt/libc++/libunwind と MinGW runtime、動的 system/UCRT import を区別する。正式 Authenticode 署名後は最終 bytes/hash を改めて固定し、本番鍵/署名は別許可で扱う。

## 検証の区分

Mac の protocol fixture と、実 native source を fake WinAPI へ接続する fixture は、relative parent・同 handle・raw mask/未知 ACE・API失敗・reparse・全 close を確認する。Java の実 pipe 代役は EOF、書込先停止、過大出力、途中 exit、package/schema 不正を検証する。実 PE による packaging test は source/build/license/import/hash と排他出力を確認する。いずれも実 Windows の kernel API 成功ではない。

Windows単体受入では、固定JDK/Python/guardianと新専用rootを明示し、H:祖先は読取り、ACL/reparse/rename故障は本人新fixture内だけに限定する。directory変動を原子的snapshot成功へ付け替えず、本人 actor の期限・回収と unknown を保存する。実接続は別許可。製品 CLI/seed/key/repair/winget install をこの単体受入へ混ぜない。

関連: Refs #122, #97。

一次資料: [NtCreateFile](https://learn.microsoft.com/en-us/windows/win32/api/winternl/nf-winternl-ntcreatefile)、[OBJECT_ATTRIBUTES](https://learn.microsoft.com/en-us/windows/win32/api/ntdef/ns-ntdef-_object_attributes)、[GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo)、[GetFileInformationByHandleEx](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getfileinformationbyhandleex)、[DACL作成](https://learn.microsoft.com/en-us/windows/win32/secauthz/dacl-for-a-new-object)。
