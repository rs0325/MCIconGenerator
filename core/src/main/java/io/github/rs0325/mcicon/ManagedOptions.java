package io.github.rs0325.mcicon;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * 固定版に同梱された管理描画環境の配置先・期限・ゲームworkerのメモリ予算。
 * runtimeRootへ検証済みartifactと所有記録を保存し、必要なら専用環境を起動する。
 * OS機能の導入・昇格やtool版の自動更新は行わない。
 * root・期限はnull不可、期限と両予算は正、heapはcontainer未満。不正な値はIllegalArgumentExceptionとする。
 * @param runtimeRoot 管理環境専用root。未作成を許可し、利用時に絶対pathへ正規化する
 * @param preparationTimeout download・検証・helper準備を含む全体の期限
 * @param startupTimeout ゲームworkerのready応答を待つ期限
 * @param itemTimeout 個別要求の期限
 * @param containerMemoryMiB ゲームcontainerのメモリ上限（MiB）
 * @param heapMemoryMiB ゲームJVMのheap上限（MiB）、container未満
 */
public record ManagedOptions(Path runtimeRoot,Duration preparationTimeout,Duration startupTimeout,Duration itemTimeout,int containerMemoryMiB,int heapMemoryMiB) {
    public ManagedOptions {
        if(runtimeRoot==null||preparationTimeout==null||startupTimeout==null||itemTimeout==null||preparationTimeout.isNegative()||preparationTimeout.isZero()||startupTimeout.isNegative()||startupTimeout.isZero()||itemTimeout.isNegative()||itemTimeout.isZero())throw new IllegalArgumentException("管理rootと正の期限を指定してください");
        if(containerMemoryMiB<=0||heapMemoryMiB<=0||heapMemoryMiB>=containerMemoryMiB)throw new IllegalArgumentException("メモリ上限は正のMiB整数で、heapはcontainer未満にしてください");
    }
    /** OS標準の専用root、準備15分・起動180秒・要求30秒、container 3072/heap 2048 MiB。 */
    public static ManagedOptions defaults() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);Path home=Path.of(System.getProperty("user.home"));Path root;
        if(os.contains("mac"))root=home.resolve("Library/Application Support/MCIconGenerator");
        else if(os.contains("win")){String local=System.getenv("LOCALAPPDATA");root=local==null?home.resolve("AppData/Local/MCIconGenerator"):Path.of(local).resolve("MCIconGenerator");}
        else {String data=System.getenv("XDG_DATA_HOME");root=data==null?home.resolve(".local/share/mcicon"):Path.of(data).resolve("mcicon");}
        return new ManagedOptions(root,Duration.ofMinutes(15),Duration.ofSeconds(180),Duration.ofSeconds(30),3072,2048);
    }
}
