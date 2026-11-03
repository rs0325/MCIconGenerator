# 管理runtimeを使ったconsumer確認

`--managed-runtime`には、1.13の実行済みcache rootを指定する。`RuntimePreparer`が読むversion manifest、version metadata、client.jar、legacy mappingsのfeather/intermediary JAR、asset indexと`.ogg`以外のasset object、Linux amd64向けlibraries/native classifierを事前取得しておく。検証器は不足パスを報告し、管理APIを起動しない。

入力がそろった場合はconsumerで`doctorManaged`と`renderManaged`を実行する。実素材がこの環境にないため、管理runtimeを使った描画成功の正例は未検証である。実行状況はGradle出力の到達マーカーから記録され、コンパイルなど実行前の失敗は未実施として報告する。
