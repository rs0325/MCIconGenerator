# 1.1.0正式runtime構成

この文書は1.1.0の構成方針を固定する。記述した未完了項目は、資材の発行・製品受入済みを意味しない。事実は既存実装・記録で確認された範囲、方針はこの仕様で採用した内容、推定は実測前の見通しとして区別する。

## 構成方針

Java 21自己完結native app-image helperを継続する。Windowsは専用WSL2 distributionとprepared rootfs、macOSはLima/VZのarm64 Ubuntu guestとOS提供Rosettaを使う。両hostは同一の固定linux/amd64 rendererを使い、描画用Java 8/17/21/25を維持する。host helper/CLI用Java 21と描画用Java群は別の役割である。

新VM実装、native renderer、helperのスクリプト全面置換は1.1.0へ入れない。正式Windowsアプリが内部で専用WSL2を使うことはWindows正式経路の一部であり、利用者がWSL内から製品を直接実行する構成の保証とは別である。OS署名なしのため警告ゼロを約束しない。実配布経路で警告と必要操作を実測し、案内とともに配布する。unsigned Macで初回許可または更新後起動が成立しない場合は正式gate未達とする。OS保護の一括無効化、quarantineの一括削除、実行policy緩和は製品に実装しない。

## host別の構成と状態

| 要素 | Windows 11 x86_64 | macOS arm64 | 状態 |
|---|---|---|---|
| helper | Java 21自己完結native app-image。専用WSL2を制御 | Java 21自己完結native app-image。Lima/VZを制御 | 既存実装・限定実績あり。最終配布bytesと製品受入は未完了 |
| guest | 専用WSL2 distribution、prepared Ubuntu amd64 rootfs | Lima/VZ arm64 Ubuntu guest | 各guest baseの正式資材、license/source closure、再現生成と最終pinが未完了 |
| renderer | 共通linux/amd64固定image | 同じ共通linux/amd64固定imageをRosetta経由で実行 | Dockerfileあり。正式採用bytes、再生成固定性、公開条件は未確定 |
| 描画Java | 8/17/21/25 | 8/17/21/25 | 既存Dockerfileと契約に基づき維持。縮小しない |
| OS提供機能 | WSL2/kernel | Virtualization.framework、Rosetta | 製品Releaseへ再配布しない。必要導入・同意・再起動の案内と実機受入が残る |

【事実】schema/parserはartifact数2～3とhost-helper/renderer-imageの参照を要求し、既存DockerfileはJava 8/17/21/25を含む。これは各hostの最終assetが発行済みという意味ではない。

## macOS下限

正式検証基準と最小下限をmacOS 15.8.1へ揃える。15.7.7保証は残さない。生成器・Formula・fixture・descriptorの下限値も15.8.1へ整合する。過去の15.7.7試験記録は履歴として保持し、新しい下限を証明する受入には読み替えない。

## Release asset命名

Release assetは直下へ配置し、descriptor内部path `descriptors/<os>-<arch>.json` は維持する。

- `mcicon-helper-1.1.0-<os>-<arch>.zip`
- `mcicon-guest-1.1.0-windows-x86_64.tar.xz`
- `mcicon-guest-1.1.0-macos-aarch64.qcow2`
- `mcicon-renderer-1.1.0-linux-amd64.tar.gz`（両host共通）
- `windows-x86_64.json` / `.json.sig`、`macos-aarch64.json` / `.json.sig`
- `mcicon-tool-1.1.0-<os>-<arch>.zip`
- `stable.json` / `.sig`、およびcomponent別source/notice bundle

1.1.0に別の更新indexを追加しない。version別資材はimmutableとし、同版の別hashへの差替えをしない。修正は次版で発行する。

## 作業分解と依存

以下は設計報告に基づく後続作業候補であり、既存Issue番号との対応は候補であって確定割当てではない。

| 単位 | 作業 | 既存Issue対応候補 | 主な完了条件 |
|---|---|---|---|
| A | 2host構成・下限・命名を共通前提として固定 | #91/#99、文書Issue | 本仕様で共通前提を確定 |
| B | 共通renderer正式候補 | #93関連 | image/Java/Mesa/LLVMのhash、再生成記録、source条件と最終archiveを確定 |
| C | Windows prepared rootfs候補 | #95依存 | bridge/engine/probe等のexact bytes、再現可能な生成とinventoryを確定 |
| D | Mac helper/guest正式候補と15.8.1数値整合 | #94関連 | 最終archive/hash/sourceを固定し、別PRで下限数値を整合 |
| E | Windows helperと未準備時setup | #95/#97（成果ごとに別PR候補） | 初回・未準備・異常・回復を含む製品導線と実機受入 |
| F | source/notice/inventory集約 | #93候補 | final bytesに対応する帰属、license/notice、source URL/hash、inventoryを確定 |
| G | descriptor・trust・resource結合 | #93/trust関連候補 | 全host共通trust、descriptor署名とcore resourceのbytes一致 |
| H | tool payload・bootstrap・stable候補 | #97/#98候補 | payload確定後のstable署名、導入/更新所有境界とseed/package整合 |
| I | Windows製品受入 | #95/#97/#98/#99関連候補 | 正式候補でsetup、更新、異常回収、offline、固定51版等を受入 |
| J | Mac製品受入 | #94/#97/#98/#99関連候補 | 15.8.1正式候補、警告/Rosetta/更新、cold-start、install/uninstall、固定51版を受入 |
| K | 公開候補監査と公開後再取得 | Central/Release関連候補 | 公開前gate後、承認された公開と空cache再取得・hash/署名確認 |

依存の概略は `A → B/C/D → E/F → G → core → payload → stable署名/seed → H → I/J → K`。B/C/Dの準備は独立に進められる。署名・共有manifest・共有Release asset集合は直列化する。I/Jはhostごとに並列受入できる。

## 未確定・未完了

- 最終helper/guest/renderer/tool bytes、公開URL、inventoryの最終asset basenameと本番値。
- Ubuntu/renderer等の全componentに対するsource・notice・再配布条件。
- Windowsの未準備WSL導入・再起動・既定設定への影響を含むsetup受入。
- 15.8.1下限の生成器・Formula・fixture・descriptor数値整合（別PR）。
- OS警告と必要操作の実配布経路での測定。unsigned Macの初回許可・更新後起動が不成立ならgate未達。
- 正式trust/署名、tool payloadとstable署名、installer、公開後の空cache consumer受入。

OS署名・notarization・Authenticodeは行わない方針である。警告が出ないという保証はない。実測して利用者向け手順を整えるまで正式配布完了とは扱わない。
