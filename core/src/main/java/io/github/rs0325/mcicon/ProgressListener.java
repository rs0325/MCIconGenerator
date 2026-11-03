package io.github.rs0325.mcicon;

@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = event -> {};
    /** ジョブのスレッドで同期通知する。startup-readyは管理worker/catalog確定後、描画出力または一覧公開前。
     * 通知先の例外は準備失敗として伝播し、既資源のclose後にFutureへ返る。
     * @param event 段階と進捗。通知中に同じinstanceのFutureを待たない
     */
    void onProgress(ProgressEvent event);
    /** 項目単位の失敗で、結果へ記録されるcode・messageとは別に元の例外を通知する。cause・suppressedを含む診断用で、結果の契約には影響しない。
     * @param id 失敗した項目のID
     * @param cause 項目処理で捕捉した元の例外
     */
    default void onDiagnostic(String id, Throwable cause) {}
    record ProgressEvent(String phase, String id, int completed, int total) {}
}
