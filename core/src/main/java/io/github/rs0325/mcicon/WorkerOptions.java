package io.github.rs0325.mcicon;
import java.nio.file.Path;
import java.time.Duration;
/**
 * 描画workerのDocker環境と期限。javaHomeはimage内のLinux絶対ディレクトリで、nullでは版に適した同梱Javaを選ぶ。
 * ホストのJavaディレクトリはマウントしない。明示Javaは必要major以上であることを起動前に検査する。
 * containerMemoryMiBとheapMemoryMiBはゲームworkerのMiB上限で、ホストJVM・Docker VMのRAMを変更しない。
 * 従来の5/6引数constructorはcontainer 3072 / heap 2048 MiBを使う。
 * 両値は正、heapはcontainer未満とする。小さい予算での起動成功は保証せず、指定値は補正しない。
 * @param agentJar 起動時にsnapshotするworker agent JAR
 * @param dockerContext 利用するDocker context
 * @param dockerImage 起動前にIDを解決するDocker image
 * @param startupTimeout ready応答の待機期限
 * @param itemTimeout 個別要求の応答期限
 * @param javaHome image内Javaのディレクトリ、nullは版に応じた既定値
 * @param containerMemoryMiB ゲームcontainerのメモリ上限（MiB）
 * @param heapMemoryMiB ゲームJVMのheap上限（MiB）、container未満
 */
public record WorkerOptions(Path agentJar,String dockerContext,String dockerImage,Duration startupTimeout,Duration itemTimeout,String javaHome,int containerMemoryMiB,int heapMemoryMiB) {
    public WorkerOptions(Path agentJar,String dockerContext,String dockerImage,Duration startupTimeout,Duration itemTimeout) {
        this(agentJar,dockerContext,dockerImage,startupTimeout,itemTimeout,null);
    }
    public WorkerOptions(Path agentJar,String dockerContext,String dockerImage,Duration startupTimeout,Duration itemTimeout,String javaHome) {
        this(agentJar,dockerContext,dockerImage,startupTimeout,itemTimeout,javaHome,3072,2048);
    }
    public WorkerOptions {
        if(agentJar==null||startupTimeout==null||itemTimeout==null||startupTimeout.isNegative()||startupTimeout.isZero()||itemTimeout.isNegative()||itemTimeout.isZero())
            throw new IllegalArgumentException("Worker path and positive timeouts are required");
        if(containerMemoryMiB<=0||heapMemoryMiB<=0||heapMemoryMiB>=containerMemoryMiB)
            throw new IllegalArgumentException("メモリ上限は正のMiB整数で、heapはcontainer未満にしてください");
        if(javaHome!=null&&(!javaHome.startsWith("/")||javaHome.indexOf('\\')>=0||javaHome.indexOf('\0')>=0))
            throw new IllegalArgumentException("Java home must be an absolute Linux directory inside the Docker image");
    }
}
