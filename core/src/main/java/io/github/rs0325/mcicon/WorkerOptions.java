package io.github.rs0325.mcicon;
import java.nio.file.Path;
import java.time.Duration;
/**
 * 描画workerのDocker環境と期限。javaHomeはimage内のLinux絶対ディレクトリで、nullでは版に適した同梱Javaを選ぶ。
 * ホストのJavaディレクトリはマウントしない。明示Javaは必要major以上であることを起動前に検査する。
 */
public record WorkerOptions(Path agentJar,String dockerContext,String dockerImage,Duration startupTimeout,Duration itemTimeout,String javaHome) {
    public WorkerOptions(Path agentJar,String dockerContext,String dockerImage,Duration startupTimeout,Duration itemTimeout) {
        this(agentJar,dockerContext,dockerImage,startupTimeout,itemTimeout,null);
    }
    public WorkerOptions {
        if(agentJar==null||startupTimeout==null||itemTimeout==null||startupTimeout.isNegative()||startupTimeout.isZero()||itemTimeout.isNegative()||itemTimeout.isZero())
            throw new IllegalArgumentException("Worker path and positive timeouts are required");
        if(javaHome!=null&&(!javaHome.startsWith("/")||javaHome.indexOf('\\')>=0||javaHome.indexOf('\0')>=0))
            throw new IllegalArgumentException("Java home must be an absolute Linux directory inside the Docker image");
    }
}
