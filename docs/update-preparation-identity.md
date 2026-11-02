# 更新準備receiptの物理識別子

Windowsの更新準備は、既存のWindows security readerで親子鎖・通常file・非reparse・owner/DACLを資格化した同じ観測から、`windows-file-id-v1:<volumeSerial>:<fileId>`を取得します。最大72文字で、既存の`receiptKey`文字列欄に保存します。Javaの`fileKey`が未提供の場合に、内容hash・mtime・sizeで代用する処理はありません。非Windowsでは元のregular・NOFOLLOW・canonical・本人owner条件を確認し、従来のJDK fileKeyを使います。

識別子は新規receipt作成後、子の排他lock取得後、期限切れ予約のlock前後で照合します。Windows reader呼出しは新規予約から子ACKまで既処理比最大2回、期限切れ回復から子ACKまで最大3回増えます。各呼出しの既5秒上限・cleanup2秒・入出力上限と、親のACK期限、receiptの期限を延長しません。子の最初のfile資格化からlock後照合までも共通の5秒枠です。失敗・期限超過・回収不明を成功keyへ変換しません。

readerの保持handleはJava FileChannelとは別です。保護parent・NOFOLLOW・排他lock・前後照合に依存する範囲を越え、Java channel自身のID、CAS、削除後のID非再利用を保証するものではありません。

JSON schemaとACK/GO protocolは維持しますが、Windows receiptKeyの意味は変わります。本機能の導入は公開release v1.0.1・v1.0.0・previewより後です。手動で開発版を導入した状態の不存在は保証しません。旧keyが不一致ならunknownで停止し、自動移行・書換え・削除・修復は行いません。実Windowsの更新準備・後続起動は、新private/dataを用いた別の固定実機確認が必要です。ローカル固定応答テストをWindows実機受入として扱いません。
