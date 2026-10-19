package io.github.rs0325.mcicon;

@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = event -> {};
    /** ジョブのスレッドで同期通知する。startup-readyは管理worker/catalog確定後、描画出力または一覧公開前。
     * 通知先の例外は準備失敗として伝播し、既資源のclose後にFutureへ返る。
     * @param event 段階と進捗。通知中に同じinstanceのFutureを待たない
     */
    void onProgress(ProgressEvent event);
    record ProgressEvent(String phase, String id, int completed, int total) {}
}
