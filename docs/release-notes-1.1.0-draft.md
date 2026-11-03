# MCIconGenerator 1.1.0 — リリースノート下書き

> **未公開の下書きです。** 1.1.0の受入、artifactの公開、署名、tag/releaseは完了していません。この文面は完成した機能の告知として公開できません。公開前に[受入台帳](../qa/acceptance-1.1.0.md)の残件を解消し、実際の成果に合わせて更新してください。

## 予定している変更

- 描画workerを含むMaven Central配布と独立Gradle consumerからの依存解決。
- Java同梱CLIと管理runtime/helperによる、DockerやColimaの事前導入を要求しない描画環境の準備。
- 署名・hashで検証するstable更新。更新は実行中のjobを差し替えず、次の適格な起動で適用する。
- Homebrew、Windows installer/wingetなどの配布経路。

上記は1.1.0の目標・契約として文書化されている内容です。公開済みの機能として確定していません。対応OS、OS下限、installerの利用方法、更新URL、署名状態は正式受入後に記載します。

## 移行と既存データ

- 1.0.1の設定・cache・runtimeを1.1.0が自動移行するとは限りません。`MCICON_DATA_HOME`等で指定するrootは独立した保存領域です。
- worker/runtime digestまたは描画設定が異なるicons出力は再開できません。新しい出力先を使用するか、対象ファイルを確認して明示的にoverwriteしてください。
- 元rawと既存のユーザー出力は自動削除されません。
- 自動更新は既定有効の契約です。無効化は `mcicon --bootstrap-auto-update false`。通常のアンインストールはユーザー領域のcache、runtime、生成物を削除しません。

配布・移行手順の確定内容は[利用ガイド](usage.md)と[受入台帳](../qa/acceptance-1.1.0.md)を参照してください。

## 公開前に確定する項目

- 3 hostの固定51版・全件・独立反復・境界受入。
- installer、Homebrew、wingetの正式artifactを用いた導入・更新・アンインストール。
- raw/offline経路、独立Gradle consumer、Central再取得と署名検証。
- stable更新の本番trust/pin/URLと新規環境からの再取得確認。
- 対応OS/下限、未署名配布の制約、必要な利用者操作。
