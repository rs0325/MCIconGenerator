package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.McIconException;
import java.io.*;
import java.net.URL;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 同版workerのresourceを検証し、実行用snapshotの元になる専用cacheへ確定する。 */
public final class WorkerArtifact {
    private static final String RESOURCE="META-INF/mcicon/worker/";
    private WorkerArtifact() {}
    public record Resolved(Path agentJar,String sha256) {}

    /**
     * 同版の検証済みagentを専用cacheへ原子的に保存する。既存fileもhash検証し、破損は拒否する。
     * @param cacheRoot worker世代を保存するroot。利用時に絶対pathへ正規化する
     * @param token 読込・lock待機・保存に適用する取消状態
     * @return 検証済みagentの絶対pathとSHA-256
     * @throws IOException resourceの読込またはcacheへの書込に失敗した場合
     * @throws McIconException resource不足・重複・版/hash不一致、または取消の場合
     */
    public static Resolved resolve(Path cacheRoot,CancellationToken token) throws IOException {
        return resolve(cacheRoot,token,WorkerArtifact.class.getClassLoader());
    }
    static Resolved resolve(Path cacheRoot,CancellationToken token,ClassLoader loader) throws IOException {
        token.check();
        URL metadata=uniqueResource(loader,"version.properties");
        URL agent=uniqueResource(loader,"agent.jar");
        String expected="toolVersion="+ToolVersion.VERSION+"\nagentSha256="+WorkerArtifactInfo.SHA256+"\n";
        try(InputStream input=metadata.openStream()) {
            if(!Arrays.equals(expected.getBytes(StandardCharsets.UTF_8),input.readNBytes(1025)))
                throw invalid("workerのmetadataが同版coreの契約と一致しません");
        }
        byte[] bytes;
        try(InputStream input=agent.openStream()) {bytes=input.readNBytes(64*1024*1024+1);}
        token.check();
        if(bytes.length>64*1024*1024||!FilesSupport.hash(bytes).equals(WorkerArtifactInfo.SHA256))
            throw invalid("worker agentのSHA-256が同版coreと一致しません");
        Path root=cacheRoot.toAbsolutePath().normalize();
        Path target=FilesSupport.safeResolve(root,"workers/"+WorkerArtifactInfo.SHA256+"/agent.jar");
        Files.createDirectories(target.getParent());
        Path lockPath=FilesSupport.safeResolve(root,"workers/"+WorkerArtifactInfo.SHA256+"/agent.lock");
        try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock acquired=null;
            while(acquired==null) {
                token.check();
                try {acquired=channel.tryLock();}catch(OverlappingFileLockException pending){}
                if(acquired==null) {
                    try {Thread.sleep(10);}
                    catch(InterruptedException failure) {Thread.currentThread().interrupt();token.check();throw new FileLockInterruptionException();}
                }
            }
            try(FileLock ignored=acquired) {
                token.check();
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                    if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||!FilesSupport.hash(target,"SHA-256").equals(WorkerArtifactInfo.SHA256))
                        throw invalid("cacheのworker agentが破損しています");
                } else {
                    FilesSupport.atomicWrite(target,bytes);
                }
                WorkerClient.validateAgentSnapshot(target);
                token.check();
                return new Resolved(target,WorkerArtifactInfo.SHA256);
            }
        }
    }

    private static URL uniqueResource(ClassLoader loader,String name) throws IOException {
        List<URL> resources=Collections.list(loader.getResources(RESOURCE+name));
        if(resources.isEmpty())throw new McIconException("WORKER_NOT_FOUND","同版mcicon-workerのresourceがありません。runtime依存とJAR統合時のresource保持を確認してください",1);
        if(resources.size()!=1)throw invalid("worker resourceが重複しています: "+name);
        return resources.getFirst();
    }
    private static McIconException invalid(String message) {return new McIconException("WORKER_INVALID",message,1);}
}
