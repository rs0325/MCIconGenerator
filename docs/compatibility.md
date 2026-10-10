# 対応表

2026-10-10の公式manifestから固定した正式51版。全版のexact adapterを実装しています。rawは元JARのPNG抽出です。iconsはすべてexperimentalで、画質・再現性の受入を完了したverified版は0です。実行待ちと検査成功を区別します。新しい版はdiscoveredから個別に追加します。

| Version | Adapter | Java | 64px全件生成・PNG監査 |
| --- | --- | --- | --- |
| 26.3 | gui-26.3 | metadataから解決 | 未実行 |
| 26.2 | gui-deferred | metadataから解決 | 未実行 |
| 26.1.2 | gui-deferred | metadataから解決 | 未実行 |
| 26.1.1 | gui-deferred | metadataから解決 | 未実行 |
| 26.1 | gui-deferred | metadataから解決 | 未実行 |
| 1.21.11 | gui-deferred | metadataから解決 | 未実行 |
| 1.21.10 | gui-deferred | metadataから解決 | 未実行 |
| 1.21.9 | gui-deferred | metadataから解決 | 未実行 |
| 1.21.8 | gui-deferred | metadataから解決 | 未実行 |
| 1.21.7 | gui-deferred | metadataから解決 | 未実行 |
| 1.21.6 | gui-deferred | metadataから解決 | 未実行 |
| 1.21.5 | gui-1.21.5 | metadataから解決 | 未実行 |
| 1.21.4 | gui-1.21.4 | metadataから解決 | 未実行 |
| 1.21.3 | gui-1.21.4 | metadataから解決 | 未実行 |
| 1.21.2 | gui-1.21.4 | metadataから解決 | 未実行 |
| 1.21.1 | gui-1.21.4 | 21 | 1,332 成功 / 0 失敗（passed） |
| 1.21 | gui-1.21.4 | 21 | 1,332 成功 / 0 失敗（passed） |
| 1.20.6 | gui-1.21.4 | 21 | 1,329 成功 / 0 失敗（passed） |
| 1.20.5 | gui-1.21.4 | 21 | 1,329 成功 / 0 失敗（passed） |
| 1.20.4 | gui-1.21.4 | 17 | 1,311 成功 / 0 失敗（passed） |
| 1.20.3 | gui-1.21.4 | 17 | 1,311 成功 / 0 失敗（passed） |
| 1.20.2 | gui-1.21.4 | 17 | 1,254 成功 / 0 失敗（passed） |
| 1.20.1 | gui-1.21.4 | 17 | 1,254 成功 / 0 失敗（passed） |
| 1.20 | gui-1.21.4 | 17 | 1,254 成功 / 0 失敗（passed） |
| 1.19.4 | gui-classic | metadataから解決 | 未実行 |
| 1.19.3 | gui-classic | metadataから解決 | 未実行 |
| 1.19.2 | gui-classic | metadataから解決 | 未実行 |
| 1.19.1 | gui-classic | metadataから解決 | 未実行 |
| 1.19 | gui-classic | metadataから解決 | 未実行 |
| 1.18.2 | gui-classic | metadataから解決 | 未実行 |
| 1.18.1 | gui-classic | metadataから解決 | 未実行 |
| 1.18 | gui-classic | metadataから解決 | 未実行 |
| 1.17.1 | gui-classic | metadataから解決 | 未実行 |
| 1.17 | gui-classic | metadataから解決 | 未実行 |
| 1.16.5 | gui-fixed | metadataから解決 | 未実行 |
| 1.16.4 | gui-fixed | metadataから解決 | 未実行 |
| 1.16.3 | gui-fixed | metadataから解決 | 未実行 |
| 1.16.2 | gui-fixed | metadataから解決 | 未実行 |
| 1.16.1 | gui-fixed | metadataから解決 | 未実行 |
| 1.16 | gui-fixed | metadataから解決 | 未実行 |
| 1.15.2 | gui-fixed | metadataから解決 | 未実行 |
| 1.15.1 | gui-fixed | metadataから解決 | 未実行 |
| 1.15 | gui-fixed | 8 | 883 成功 / 0 失敗（passed） |
| 1.14.4 | gui-fixed | 8 | 876 成功 / 0 失敗（passed） |
| 1.14.3 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） |
| 1.14.2 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） |
| 1.14.1 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） |
| 1.14 | legacy-1.14 | 8 | 876 成功 / 0 失敗（passed） |
| 1.13.2 | legacy-1.13 | 8 | 789 成功 / 0 失敗（passed） |
| 1.13.1 | legacy-1.13 | 8 | 789 成功 / 0 失敗（passed） |
| 1.13 | legacy-1.13 | 8 | 784 成功 / 0 失敗（passed） |

全件成功数はairを除く登録アイテム数です。no_item_formのブロックは別に記録します。実測値と入力hashは[数値検証記録](../qa/validation-2026-10-10.json)に保存します。

ホストmacOS Apple Silicon＋Colima/Rosetta上のLinux amd64 containerを検証しています。Windows/Linux host、Docker Desktop、host native、Linux ARM64 imageは未検証です。GitHub Actionsは使用しません。
