# Windows bootstrap の security reader

`windows-security-reader.exe` は、seed 展開前の bootstrap が directory chainとterminal通常fileの所有者・raw DACL・型・identity を観測するための別 PE である。親の安全判定、新規 node の所有者設定、CLI 起動は後続 #97 の責務で、この reader は作成・ACL変更・削除・子起動・WSL操作を行わない。

## 入力と観測

stdin に一つの UTF-8 JSON を送って EOF にする。reader IPCはschemaVersion 3で、field順は `schemaVersion`、`terminalType`、`paths` に固定し、文字列は UTF-8、引用符・backslash・slash だけ JSON escape を受け付ける。Java caller の Jackson 出力と一致する専用形式で、汎用 JSON parser は追加しない。入力64KiB、最大64 node。`read(List<Path>)`はlocal drive rootから既存directoryだけを指定する。`readFile(List<Path>)`は親chainを含め、最後の1nodeだけを通常fileとして指定する（最小2node）。file本文やEA/xattrは読まない。

```json
{"schemaVersion":3,"terminalType":"directory","paths":["C:\\","C:\\Users","C:\\Users\\example"]}
```

最初の drive root をアンカーとして開き、次の component は保持した parent を `NtCreateFile` の `RootDirectory` に指定する。`FILE_OPEN`、`FILE_DIRECTORY_FILE`、`FILE_OPEN_REPARSE_POINT`、child の `OBJ_DONT_REPARSE` を使い、read/write共有・delete非共有とする。絶対 path の再走査や同一 volume だけで親への帰属を推測しない。

通常file terminalは `FILE_NON_DIRECTORY_FILE`、`READ_CONTROL|FILE_READ_ATTRIBUTES|SYNCHRONIZE` で開く。directoryの `FILE_TRAVERSE` と同値のfile `FILE_EXECUTE`、本文の `FILE_READ_DATA`、EAの `FILE_READ_EA` は要求しない。同handleのGetFileTypeがdiskであり、FileStandardInfo.Directory=false、属性が非directory/reparseなしであることも確認する。途中のfile、型不一致、API不明はunknownとなる。

行の`nodeType`はdirectory/regular-file、`linkCount`はdirectoryではnull、通常fileでは1である。FileStandardInfoのNumberOfLinksが1以外、DeletePending=true、採取中に観測された差は元値付きunknownとする。複数hardlinkの観測を採用しない限定契約であり、解放後のalias不存在や不変は保証しない。file size/本文/EA/SACLをresponseへ増やさない。

owner/DACL は `GetSecurityInfo`、型・reparse tag・volume/file ID は同じ保持 handle の `GetFileInformationByHandleEx` から得る。response の行順、parent index、relative name、parent ID を保持 handle と結合する。reparse、型不一致、API失敗、観測された identity/属性差は unknown とする。reparse/type拒否では実属性と tag も返す。

DACL present/null/空を区別し、ALLOW/DENY の SID と raw uint32 mask を保持する。fileとdirectoryで同値bitの意味が異なるため、reader自身は権限の安全判定やgeneric mask展開を行わない。後続#97がnodeTypeに従い、fileのWRITE_DATA/APPEND_DATAとdirectoryのADD_FILE/ADD_SUBDIRECTORY、親DELETE_CHILDを区別する。generic mask を NIO permissions へ変換しない。その他 ACE は header/type/flags と `unknown:true` を残し、黙って捨てない。ACL/ACE/出力の上限超過も unknown で、truncated な安全観測へ変換しない。

SD と属性は別 API による可変な観測である。全 chain の原子的 snapshot、途中変更の全検出、解放後の不変は保証しない。Java 側が後続の親安全 gate を通して初めて新規作成へ進める。reader IPC1/2との混在は採用せず、JAR/PE/packageを同世代で固定する。未リリースのIPC移行/fallbackは追加せず、state/receipt/manifestとbuild/package metadataのschemaは変えない。同 SID・昇格管理者・SYSTEM は信頼範囲とし、既 Admin 所有 root の本人採用・ACL救済は行わない。

## 有限 caller と package 信頼

`WindowsSecurityReader` の入力 `installed` は、installer/package配置側で実体・非reparse・他標準userによる書換え拒否を検証済みの prefix であることを前提とする。caller自身が確認するのは絶対path化、Javaがsymlinkとして認識する経路の拒否、通常file、exe/build記録/licenseのhashであり、Windows junction等を含む全reparseやcanonical実体一致を自身で証明しない。後続 #97 integration がこの前提を満たす配置検証を接続する責務を持つ。readerによる自己検証を信頼の起点にして循環させない。ユーザーが書換えたinventoryの自己採用やreaderのhashだけではprefix安全性の証明にならない。

単一要求の writer と stdout/stderr drainer を並行に開始し、Process 起動時から write/wait/drain 全てを同じ5秒期限で監督する。stdinは送信後EOFにする。Windows private stateからの呼出しは、作業期限を親の元deadlineと5秒の短い方に制限し、各観測前後・変更前後・最終成功時にも同期限を確認する。失敗回収だけは作業期限から固定2秒を認め、操作ごとに親期限を延長しない。同期NIOやProcess.startのkernel内停止をこの前後確認だけで強制中断できるとはしない。stdout1MiB/stderr64KiB、途中終了・送信失敗・上限・期限は成功にしない。回収は保持した直接 ProcessHandle だけを TERM/KILL し、起動時期限＋固定2秒まで待つ。writer の同じ stream を監督 thread が先に同期 close して待ち続ける構造は使わない。全 I/O task の終端も確認し、回収不能は元 cause に unknown を併記する。native は5秒の自己予算を持つが、kernel 内停止を自己期限で中断できる保証はない。

## build と同梱

初版は local NTFS / Windows x64。ARM64、任意 Windows 版、実 OS API、署名/SmartScreen の受入を crossbuild で代用しない。PowerShell/.NET/FFM/JNA を製品依存に加えない。reader単体はJava moduleを使用しない。Windows bootstrapのNTSystem利用には後述の `jdk.security.auth` 同梱が必要である。

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

Windows単体受入では、固定JDK/Python/guardianと新専用rootを明示し、H:祖先は読取り、ACL/reparse/rename故障は本人新fixture内だけに限定する。directory変動を原子的snapshot成功へ付け替えず、本人 actor の期限・回収と unknown を保存する。新PEで既read(List)のdirectory正例（全行directory/linkCount=nullとparent/ID/SD対応）も受け入れ、fileだけの成功でdirectory契約維持としない。本文読取を許可しないmetadata専用fixture、hardlink count2拒否、未知ACE/raw mask、file型混同と期限回収も確認する。実接続は別許可。製品 CLI/seed/key/repair/winget install をこの単体受入へ混ぜない。

directory treeは各directoryをpolicy検証してから直下のDirectoryStreamを開く。Windows junction先の未探索は新PEと実Windowsで別途受け入れる。POSIX展開は既契約のslot root0700と子directory既定modeを保持する。

関連: Refs #124, #122, #97。

一次資料: [NtCreateFile](https://learn.microsoft.com/en-us/windows/win32/api/winternl/nf-winternl-ntcreatefile)、[OBJECT_ATTRIBUTES](https://learn.microsoft.com/en-us/windows/win32/api/ntdef/ns-ntdef-_object_attributes)、[GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo)、[GetFileInformationByHandleEx](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getfileinformationbyhandleex)、[DACL作成](https://learn.microsoft.com/en-us/windows/win32/secauthz/dacl-for-a-new-object)。

通常fileの一次資料: [FILE_STANDARD_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_standard_info)、[GetFileType](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfiletype)。

## Windows private stateへの適用

installer側で実体・非reparse・他標準userからの書換え拒否を確認したprefixを外部前提とする。package内hashだけでその前提を証明しない。Windows runtime生成では `jlink --add-modules java.se,jdk.crypto.ec,jdk.security.auth` が必要。NTSystemはWindowsでのみ初期化し、token userと新ownerを設定するprincipalの対応を使用前の実owner SIDで確認する。Macの既producerへWindows prefix保証を追加したとは扱わない。

IPC3は同じprocess token handleからtokenUserSidとtokenDefaultOwnerSidを有界readで返す。全新node作成前にdefaultOwnerが本人/Admin/SYSTEMであることを確認する。作成後にだけ拒否して初期ownerの暗黙WRITE_DACを放置しない。impersonationや別資格情報による埋込み実行には対応しない。

既存ancestorのdirectory owner判定だけは、本人/Admin/SYSTEMに加えてTrustedInstallerの完全SID `S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464` を許容する。任意service SID・別要素のSID・file ownerへは広げない。ACE/defaultOwner/CREATOR_OWNERのtrusted主体は従来の本人/Admin/SYSTEMだけであり、TrustedInstaller ACEの危険権限をowner例外で救済しない。

WindowsPrivateFilesは基本ALLOW/DENYだけを扱い、generic maskをfile用定数で展開し、未知ACE/flags/maskを拒否する。DENYで危険ALLOWを相殺しない。他主体read-onlyは許容する。managed nodeは本人owner一致、他主体の内容・metadata・削除・ACL/owner変更を拒否する。installedのJARは外部trusted prefixの読取であり、Admin/SYSTEM所有を本人state所有へ変更しない。

既ancestorの他主体0x2/0x4は、次の保護chain childを同handleで実観測でき、child削除/rename/SD-owner変更およびparent DELETE_CHILDを拒否した場合だけ非空witnessとして許容する。newchild未存在のterminal parentは0x2/0x4の双方を拒否し、作成予定childをwitnessにしない。WRITE_EA/WRITE_ATTRIBUTES/DELETE/WRITE_DAC/WRITE_OWNERは祖先でも拒否。保証するのはchain identity/routing/SDとmanaged nodeであり、ancestor全ADSや全timestampの不変ではない。

新nodeは継承を別判定してからCREATE_NEW/単一createDirectoryで作り、初期本人ACL→setOwner→実owner/type/ACL再観測後に使用する。OI/CIを新file/dirで区別し、IOは現nodeへの権限ではない。NPで再伝播が止まっても直子へ有効な危険権限を救済しない。CREATOR_OWNERは観測defaultOwnerへ分類し、作成後はOSの実SID/ACEを再検証する。既AdminGroup stateの採用/ACL救済、Hの不適合親の修正は行わない。

本成果はRefs #97の限定先行実装。installer配置/実winget/安全prefix構成と、実Windowsの非空witness・owner context対照は別受入である。#97全体の完了を意味しない。

存在確認が不明なstate/config/slotをmissingや既定値へfallbackしない。確認済み不在だけを新規作成へ通し、存在または不明は既nodeの所有・型・ACL検証へ通す。atomic置換では、この検証を一時file生成前に行う。

追加NTFS測定ではCOMMONのみの空/非空がfalse/5、COMMON+0x4の非空がfalse/145で、保持handleのGrantedAccessはそれぞれ0x100080/0x100084だった。旧empty4 true/0と合わせ、0x4も保護child条件へ限定する保守的policyを採る。本人owner/BA enabledが残る対照であり、標準nonadmin一般や0x4の必要十分条件を証明しない。旧only4 false/5期待のFAILは不変保全し、新policyのQAで成功へ書き換えない。
