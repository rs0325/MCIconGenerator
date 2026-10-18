package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 署名で固定されたartifactを取得し、検証前の部分取得を完成世代から隔離する。 */
public final class RuntimeStore {
    record Response(int status,long offset,InputStream body) implements AutoCloseable {public void close() throws IOException {body.close();}}
    @FunctionalInterface interface Transport {Response open(URI uri,long offset,Duration remaining) throws IOException,InterruptedException;}
    private final Path root;
    private final boolean offline;
    private final CancellationToken token;
    private final Transport transport;
    private final long deadline;
    public RuntimeStore(Path root,boolean offline,CancellationToken token,Duration timeout) throws IOException {
        this(root,offline,token,timeout,https(token));
    }
    RuntimeStore(Path root,boolean offline,CancellationToken token,Duration timeout,Transport transport) throws IOException {
        Path absolute=root.toAbsolutePath().normalize();this.offline=offline;this.token=token;this.transport=transport;
        deadline=System.nanoTime()+timeout.toNanos();
        if(Files.isSymbolicLink(absolute))throw RuntimeDescriptor.invalid("管理rootにlinkがあります");
        Files.createDirectories(absolute);this.root=absolute.toRealPath();
    }
    public Path root() {return root;}
    public Path generation(RuntimeDescriptor descriptor) throws IOException {return path("runtimes/"+descriptor.runtimeId()+"/"+descriptor.digest());}
    /** 完成ファイルも毎回size/hashを再検証する。offlineでは部分取得を完成品として使用しない。 */
    public Path obtain(RuntimeDescriptor.Artifact artifact) throws IOException {
        Path directory=path("downloads/"+artifact.sha256());Files.createDirectories(directory);
        try(Locked ignored=lock("artifact-"+artifact.sha256())) {
            Path complete=path("downloads/"+artifact.sha256()+"/artifact");
            if(valid(complete,artifact))return complete;
            if(offline)throw new McIconException("OFFLINE_CACHE_MISS","検証済み管理artifactがありません: "+artifact.id(),1);
            Path partial=path("downloads/"+artifact.sha256()+"/artifact.part");
            long offset=Files.isRegularFile(partial,LinkOption.NOFOLLOW_LINKS)?Files.size(partial):0;
            if(offset>artifact.bytes()){Files.delete(partial);offset=0;}
            if(offset==artifact.bytes()) {
                if(valid(partial,artifact)){publish(partial,complete);return complete;}
                Files.delete(partial);offset=0;
            }
            requireSpace(directory,artifact.bytes()-offset);
            check();
            try(Response response=transport.open(artifact.url(),offset,remaining())) {
                boolean append=offset>0&&response.status()==206&&response.offset()==offset;
                if(response.status()!=200&&!append)throw new IOException("管理artifactの取得応答が不正です: HTTP "+response.status());
                if(!append){offset=0;requireSpace(directory,artifact.bytes());}
                try(OutputStream output=Files.newOutputStream(partial,StandardOpenOption.CREATE,StandardOpenOption.WRITE,append?StandardOpenOption.APPEND:StandardOpenOption.TRUNCATE_EXISTING)) {
                    long total=offset;byte[] buffer=new byte[65536];int count;
                    try(InputStream input=bounded(response.body())) {
                        while((count=input.read(buffer))!=-1) {check();if(count>artifact.bytes()-total)throw RuntimeDescriptor.invalid("artifactが宣言sizeを超えています");output.write(buffer,0,count);total+=count;}
                    }
                    if(total!=artifact.bytes())throw new EOFException("管理artifactの取得が途中で終了しました");
                }
            }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("管理artifactの取得が中断されました",failure);}
            check();
            if(!valid(partial,artifact)){Files.delete(partial);throw RuntimeDescriptor.invalid("管理artifactのhashが一致しません");}
            publish(partial,complete);return complete;
        }
    }
    private InputStream bounded(InputStream input) {
        Thread owner=Thread.currentThread();
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        Thread monitor=Thread.ofVirtual().name("mcicon-runtime-transfer").start(()-> {
            while(!closed.get()) {
                if(token.isCancelled()||owner.isInterrupted()||System.nanoTime()>=deadline) {try {input.close();}catch(IOException ignored){}return;}
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
            }
        });
        return new FilterInputStream(input) {
            @Override public int read(byte[] b,int offset,int length) throws IOException {check();try {int count=super.read(b,offset,length);check();return count;}catch(IOException failure){check();throw failure;}}
            @Override public void close() throws IOException {closed.set(true);monitor.interrupt();super.close();}
        };
    }
    static Transport https(CancellationToken token) {
        HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build();
        return (uri,offset,timeout)-> {
            URI current=uri;long end=System.nanoTime()+timeout.toNanos();
            for(int redirects=0;redirects<=5;redirects++) {
                RuntimeDescriptor.https(current.toString());long remaining=end-System.nanoTime();if(remaining<=0)throw new HttpTimeoutException("管理artifactの取得期限を超えました");
                HttpRequest.Builder request=HttpRequest.newBuilder(current).timeout(Duration.ofNanos(remaining)).GET();
                if(offset>0)request.header("Range","bytes="+offset+"-");
                var future=client.sendAsync(request.build(),HttpResponse.BodyHandlers.ofInputStream());
                HttpResponse<InputStream> response;
                try {
                    while(true) {
                        token.check();long left=end-System.nanoTime();if(left<=0)throw new TimeoutException();
                        try {response=future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);break;}
                        catch(TimeoutException pending){if(System.nanoTime()>=end)throw pending;}
                    }
                }
                catch(TimeoutException failure){future.thenAccept(r->{try {r.body().close();}catch(IOException ignored){}});future.cancel(true);throw new HttpTimeoutException("管理artifactの取得期限を超えました");}
                catch(ExecutionException failure){throw new IOException("管理artifactを取得できません",failure.getCause());}
                catch(InterruptedException|RuntimeException failure){future.thenAccept(r->{try {r.body().close();}catch(IOException ignored){}});future.cancel(true);throw failure;}
                if(response.statusCode()>=300&&response.statusCode()<400) {
                    response.body().close();current=current.resolve(response.headers().firstValue("Location").orElseThrow(()->new IOException("転送先がありません")));continue;
                }
                long start=0;
                if(response.statusCode()==206) {
                    String range=response.headers().firstValue("Content-Range").orElse("");
                    var match=java.util.regex.Pattern.compile("bytes ([0-9]+)-[0-9]+/[0-9]+").matcher(range);
                    if(!match.matches()){response.body().close();throw new IOException("再開応答の範囲が不正です");}
                    try {start=Long.parseLong(match.group(1));}catch(NumberFormatException failure){response.body().close();throw new IOException("再開応答の範囲が不正です",failure);}
                }
                return new Response(response.statusCode(),start,response.body());
            }
            throw new IOException("管理artifactの転送回数を超えました");
        };
    }
    public Locked lock(String name) throws IOException {
        Path file=path("locks/"+RuntimeDescriptor.relative(name)+".lock");Files.createDirectories(file.getParent());
        FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            while(true) {
                check();FileLock lock=null;
                try {lock=channel.tryLock();}catch(OverlappingFileLockException busy){}
                if(lock!=null)return new Locked(channel,lock);
                try {Thread.sleep(25);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new FileLockInterruptionException();}
            }
        }catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
    public record Locked(FileChannel channel,FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {try {lock.release();}finally{channel.close();}}
    }
    Path path(String relative) throws IOException {RuntimeDescriptor.relative(relative);return FilesSupport.safeResolve(root,relative);}
    void check() throws IOException {token.check();if(System.nanoTime()>=deadline)throw new HttpTimeoutException("管理環境の準備期限を超えました");}
    Duration remaining() throws IOException {check();return Duration.ofNanos(deadline-System.nanoTime());}
    static void requireSpace(Path directory,long bytes) throws IOException {if(Files.getFileStore(directory).getUsableSpace()<bytes)throw new IOException("管理環境の準備に必要な空き容量がありません");}
    boolean valid(Path path,RuntimeDescriptor.Artifact artifact) throws IOException {
        check();return Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)==artifact.bytes()&&hash(path).equals(artifact.sha256());
    }
    String hash(Path path) throws IOException {try(InputStream input=Files.newInputStream(path)){return hash(input);}}
    String hash(InputStream input) throws IOException {
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;
            check();while((count=input.read(buffer))!=-1){check();digest.update(buffer,0,count);}
            check();return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    void copy(Path source,Path target,long bytes) throws IOException {
        try(InputStream input=Files.newInputStream(source);OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){copy(input,output,bytes);}
    }
    void copy(InputStream input,OutputStream output,long bytes) throws IOException {
        long total=0;byte[] buffer=new byte[65536];int count;
        check();while((count=input.read(buffer))!=-1){check();if(count>bytes-total)throw RuntimeDescriptor.invalid("artifactのコピーが宣言sizeを超えています");output.write(buffer,0,count);total+=count;}
        check();if(total!=bytes)throw RuntimeDescriptor.invalid("artifactのコピーsizeが一致しません");
    }
    static void publish(Path source,Path destination) throws IOException {Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
}
