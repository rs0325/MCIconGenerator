# 1.1.0 release acceptance ledger (draft)

この台帳はIssue #99の受入項目を追跡する。証拠は記録された範囲に限定し、先行1.0.1の結果、方式feasibility、fixture試験を1.1.0製品受入へ読み替えない。公開・署名・再取得が必要な作業は実行せず、許可待ちとして区別する。

状態の意味:

- **達成**: 指定した1.1.0受入条件が記録済み証拠で満たされる。
- **未確認**: 現在の証拠では条件を判定できない、または1.1.0製品経路の試験がない。
- **要別ホスト**: 対象host/OS上の実機確認が必要。
- **要外部公開許可**: Centralや配布先への公開、署名、アカウント操作が必要。許可前は未実施。

| 項目 | 状態 | 根拠・確認できた範囲 | 残りの受入条件 |
|---|---|---|---|
| macOS arm64・Windows x86_64・Linux x86_64の1.1.0製品受入 | 未確認 / 要別ホスト | `docs/managed-runtime-1.1.md`は3 hostを初期実証対象とする。`qa/validation-2026-10-18-managed-helper-macos.json`、`qa/validation-2026-10-19-windows-managed-proof.json`、`qa/validation-2026-10-19-linux-rootless-proof.json`はそれぞれ限定条件の記録で、製品の全受入ではない。Windows記録はWindows 11 Pro / AMD64上でのWSL2 feasibility実測、Linux記録はMac上のarm64 Linux guestでRosettaを使ったrootless feasibility実証であり、どちらも製品受入ではない。 | 各OSの正式bootstrap/helper/runtimeで固定51版の受入を行い、実行・失敗・回収範囲を記録する。Windows/Linuxの限定実証を各OSの製品受入へ拡張しない。 |
| 固定51版の版集合・adapter境界 | 未確認 | `docs/compatibility.md`と`qa/validation-2026-10-16-release-101.json`に固定51版の1.0.1実績がある。1.1.0 managed runtime実描画の合格記録ではない。 | 1.1.0正式runtime/worker/descriptorをpinし、同じ固定51版を対象にする。 |
| 1.1.0固定51版の全件PNG・manifest監査 | 未確認 | 1.0.1では全件成果の記録がある。1.1.0については`qa/validation-2026-10-18-managed-runtime.json`等が「51版画像受入」を未完了としている。 | 全件生成後にPNG/hash/metadata/path/source/settings/renderer/runtime digestを監査する。 |
| 全件の独立反復・境界/代表比較 | 未確認 | 1.0.1の独立反復記録は1.1.0管理環境の再現性を証明しない。managed helper/runtime記録は全51版受入を未達としている。 | 別generationで同条件を反復し、境界版と代表比較を記録する。 |
| 既存1.0.1から1.1.0への更新・既存iconsの扱い | 未確認 | `docs/managed-runtime-1.1.md`、`docs/usage.md`。`IconExporter`はsource hash、renderer、settings digestの不一致を拒否する。旧rootの自動移行は契約されていない。 | 1.0.1利用者からの導入・保存領域分離を確認。digest不一致時の拒否と再生成案内を結合受入する。元raw・既存利用者出力を保持する。 |
| installer候補の生成・正式install/upgrade/uninstall | 未確認 / 要別ホスト / 要外部公開許可 | `bootstrap/docs/initial-bootstrap.md`および`bootstrap/docs/windows-packaging.md`。候補/fixture・設計文書は実installer受入ではない。Windows実winget、upgrade/uninstall、本番署名・配布は未完了。 | 正式署名済み配布bytes、実installerの通常user導入・更新・修復・PATH・uninstallとuser data保持を対象hostで確認する。 |
| Homebrew archive/Formula候補 | 未確認 | `bootstrap/docs/homebrew-distribution.md`の候補生成・fixture検証はbrew実install受入ではない。 | 明示URL/hashの正式artifact、実brew install/upgrade/uninstall、PATH・権限・競合・OS下限をmacOSで確認する。 |
| winget package候補・実install/update/uninstall | 未確認 / 要別ホスト / 要外部公開許可 | `bootstrap/docs/windows-packaging.md`は候補生成まで。実winget install、upgrade/uninstall、署名/公開は残件。 | 正式manifest/artifact/署名とWindows実hostで標準userを含む導入・更新・削除後の保存データを確認する。 |
| raw取得 | 未確認 | `docs/usage.md`はraw/extractが管理helper起動を必要としない契約を記載。1.1.0正式配布物での結合結果は未確認。 | 1.1.0正式CLIでraw取得時にmanaged runtime/helper準備を起動しないこと、bytes/metadataを確認する。 |
| offline既存cacheでの利用 | 未確認 | `docs/usage.md`にcache/offline契約がある。`docs/managed-runtime-1.1.md`は未準備時の実配布受入を残件としている。 | 固定artifact/cacheを用いたoffline実行、cache miss時の診断、許可外通信が始まらないことを各hostで確認する。 |
| 独立Gradle consumerでのCentral依存解決 | 未確認 / 要外部公開許可 | `docs/maven-central.md`はworker推移依存と独立consumer検証を規定。正式1.1.0 Central公開・公開後consumer再取得は本作業の根拠にない。 | Central公開後、新規独立Gradle cache/consumerからcore・worker依存を解決し、rawおよびmanaged描画の依存経路を確認する。 |
| Central artifact signature/PGP検証 | 要外部公開許可 | `docs/maven-central.md`はPGP署名を自動更新descriptor鍵と分離する。namespace/token・秘密鍵/公開状態の全条件は未確認と記録される。 | 公開許可と利用可能な署名環境を得た後にartifact/signature publishを行い、公開先からsignature/key verificationを再取得確認する。鍵を新規作成・設定しない。 |
| stable update署名・固定trust・正式配布URL | 要外部公開許可 | `bootstrap/docs/auto-update.md`は正式URL・本番trust未発行とし、`docs/managed-runtime-1.1.md`も本番pin/trust/runtime未発行としている。 | 外部公開の判断・必要なtrust入力が揃ってから正式manifest/payloadを構成・公開し、別の新規環境で署名検証・更新を確認する。 |
| 自動更新の設定・無効化・次回適用 | 未確認 | `bootstrap/docs/auto-update.md`に既定true、`mcicon --bootstrap-auto-update false`、offline、pending/次の適格managed起動の契約がある。実installer・正式署名配信は未受入。 | 正式package上で設定永続化、無効時通信抑止、実行中job世代維持、次回起動適用を確認する。 |
| アンインストール時のデータ保持 | 未確認 | `bootstrap/docs/initial-bootstrap.md`と`bootstrap/docs/homebrew-distribution.md`はnonpurgeでuser root/runtime/cache/PNG/API rootを保持する契約。実installerのuninstallは未受入。 | 各正式配布経路を実uninstallし、user dataとraw/output保持、program-owned file除去を検証する。 |
| Windows全体テストの文字コード/symlink失敗 | 一部再現・一部未再現 | 基準 `b481a04`でREADMEの`gradlew.bat --offline test :cli:installDist`を実行。`bootstrap:test`は118件中97件skip、`WindowsDataHomeChildTest` 3件が`MalformedInputException`（各`WindowsDataHomeChildTest.java:27`）で失敗し、後続タスクへ進まなかった。別実行の`:core:test`は159件中28件skip、3件失敗。`InstallationImportTest.cacheCannotWriteWithinOrContainTheInstallation`は期待した`IllegalArgumentException`なし、`WorkerArtifactTest.resolvesResourcesAfterFlatteningWithoutOriginalWorkerJar`はextension context closeの`IOException`、`RuntimeLeaseTest.receiptRegionIsOutsideActiveLockAndExpiredPreparationDoesNotBlockRelease`はWindowsのfile lock IOException。独立実行したsymlink拒否テスト2件は成功したため、この実行ではsymlink権限失敗を再現していない。core全体の別3失敗もsymlink権限原因とは断定できない。 | bootstrapの文字コード原因を追跡し、全体テストでskipされたsymlink権限条件の再現環境・条件を特定する。現Issueの文書変更に修正を混ぜず、必要なら別の最小修正に分ける。`:cli:installDist`は未実行。 |

## 先行証拠の境界

- `qa/validation-2026-10-16-release-101.json`: 1.0.1配布候補の3 host記録。1.1.0管理runtime受入ではない。
- `qa/validation-2026-10-18-managed-helper-macos.json`: Mac helperの限定実証。正式配布bundle/trust、実更新、実アンインストール、全51版への合格を示さない。
- `qa/validation-2026-10-19-windows-managed-proof.json`: Windowsの既準備WSL feasibility。CLI 1.0.1のprobeによる限定描画で、製品helper・51版・正式runtime受入ではない。
- `qa/validation-2026-10-19-linux-rootless-proof.json`: Mac上arm64 Linux guestでのrootless feasibility。物理Linux amd64 host/製品helper/全51版受入ではない。
- `docs/managed-runtime-1.1.md`と`docs/managed-helper-macos-1.1.md`: 契約・方式実証の未完了項目を確認する正本。個別QAの限定PASSをrelease gate達成へ読み替えない。

この台帳は作業時点の追跡用であり、release承認・公開許可を意味しない。
