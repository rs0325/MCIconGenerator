package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** 公式hashで入力を検証し、HTTPSの期限内取得と既存インストールの読み取りからcacheへ確定する。 */
public final class AssetStore {
    @FunctionalInterface public interface Transport { InputStream open(URI uri) throws IOException, InterruptedException; }
    private final Path root;
    private final boolean offline;
    private final Transport transport;
    private final CancellationToken cancellation;
    private final Path minecraft;
    public AssetStore(Path root,boolean offline,CancellationToken cancellation) {
        this(root,offline,cancellation,httpsTransport(cancellation),null);
    }
    public AssetStore(Path root,boolean offline,CancellationToken cancellation,Path minecraft) {
        this(root,offline,cancellation,httpsTransport(cancellation),minecraft);
    }
    public AssetStore(Path root,boolean offline,CancellationToken cancellation,Transport transport) {
        this(root,offline,cancellation,transport,null);
    }
    public AssetStore(Path root,boolean offline,CancellationToken cancellation,Transport transport,Path minecraft) {
        this.root=root.toAbsolutePath().normalize();this.offline=offline;this.cancellation=cancellation;this.transport=transport;
        this.minecraft=minecraft==null?null:minecraft.toAbsolutePath().normalize();
        if(this.minecraft!=null&&!Files.isDirectory(this.minecraft,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Minecraft installation must be a directory");
        if(this.minecraft!=null) {
            try {
                Path input=this.minecraft.toRealPath(),cache=this.root.toFile().getCanonicalFile().toPath();
                if(input.startsWith(cache)||cache.startsWith(input))throw new IllegalArgumentException("Cache and Minecraft installation directories must not overlap");
            }catch(IOException failure){throw new IllegalArgumentException("Input directories could not be resolved");}
        }
    }
    private static Transport httpsTransport(CancellationToken cancellation) {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
        return httpsTransport(client,cancellation,Duration.ofMinutes(2));
    }
    static Transport httpsTransport(HttpClient client,CancellationToken cancellation,Duration timeout) {
        return uri -> {
            if(!"https".equals(uri.getScheme()))throw new IOException("Only HTTPS asset URLs are supported");
            URI current=uri;long deadline=System.nanoTime()+timeout.toNanos();
            for(int redirects=0;redirects<=5;redirects++) {
                cancellation.check();long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new HttpTimeoutException("Asset transfer timed out");
                HttpRequest request=HttpRequest.newBuilder(current).timeout(Duration.ofNanos(remaining)).header("User-Agent","MCIconGenerator/0.1").GET().build();
                var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
                HttpResponse<InputStream> response;
                try {
                    while(true) {
                        cancellation.check();
                        if(System.nanoTime()>=deadline)throw new HttpTimeoutException("Asset transfer timed out");
                        try {response=future.get(100,TimeUnit.MILLISECONDS);break;}
                        catch(TimeoutException pending){}
                        catch(ExecutionException error) {
                            if(error.getCause() instanceof IOException failure)throw failure;
                            throw new IOException("Asset request failed",error.getCause());
                        }
                    }
                } catch(IOException|InterruptedException|RuntimeException failure) {
                    future.thenAccept(received->{try {received.body().close();}catch(IOException ignored){}});
                    future.cancel(true);throw failure;
                }
                if(response.statusCode()==200)return new DeadlineInputStream(response.body(),cancellation,deadline);
                response.body().close();
                if(response.statusCode()>=300&&response.statusCode()<400) {
                    URI next=current.resolve(response.headers().firstValue("Location").orElseThrow(()->new IOException("Missing redirect location")));
                    if(!"https".equals(next.getScheme()))throw new IOException("Non-HTTPS redirect refused");
                    current=next;
                } else throw new IOException("Asset server returned HTTP "+response.statusCode());
            }
            throw new IOException("Too many asset redirects");
        };
    }
    private static final class DeadlineInputStream extends FilterInputStream {
        private final CancellationToken cancellation;
        private final Thread owner=Thread.currentThread();
        private final AtomicBoolean closed=new AtomicBoolean();
        private final Thread monitor;
        private final long deadline;
        private volatile boolean timedOut;
        DeadlineInputStream(InputStream input,CancellationToken cancellation,long deadline) {
            super(input);this.cancellation=cancellation;this.deadline=deadline;
            monitor=Thread.ofVirtual().name("mcicon-http-deadline").start(()-> {
                while(!closed.get()) {
                    if(cancellation.isCancelled()||owner.isInterrupted()||System.nanoTime()>=deadline) {
                        timedOut=System.nanoTime()>=deadline;
                        try {in.close();}catch(IOException ignored){}
                        return;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                }
            });
        }
        private void check() throws IOException {
            cancellation.check();if(timedOut||System.nanoTime()>=deadline)throw new HttpTimeoutException("Asset transfer timed out");
        }
        @Override public int read() throws IOException {
            check();try {int result=in.read();check();return result;}catch(IOException failure){check();throw failure;}
        }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            check();try {int result=in.read(bytes,offset,length);check();return result;}catch(IOException failure){check();throw failure;}
        }
        @Override public void close() throws IOException {closed.set(true);monitor.interrupt();in.close();}
    }
    public Path obtain(String relative,URI uri,String sha1,long expectedSize,boolean refresh) throws IOException {
        cancellation.check();
        Path target=FilesSupport.safeResolve(root,relative);
        Files.createDirectories(target.getParent());
        Path lockPath=FilesSupport.safeResolve(root,relative+".lock");
        synchronized((root+"/"+relative).intern()) {
            try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock ignored=lock(channel)) {
                Path checksum=FilesSupport.safeResolve(root,relative+".sha256");
                if(Files.exists(target)&&valid(target,sha1,expectedSize)&&(!refresh||offline)) {
                    String actual=FilesSupport.hash(target,"SHA-256");
                    boolean verified=Files.isRegularFile(checksum,LinkOption.NOFOLLOW_LINKS)&&Files.readString(checksum).trim().equals(actual);
                    if(sha1!=null||verified) {
                        if(!verified)FilesSupport.atomicWrite(checksum,actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        return target;
                    }
                }
                if(importExisting(relative,target,checksum,sha1,expectedSize))return target;
                if(offline)throw new McIconException("OFFLINE_CACHE_MISS","Verified cached input is unavailable: "+relative,1);
                IOException failure=null;
                for(int attempt=0;attempt<3;attempt++) {
                    cancellation.check();
                    Path temp=Files.createTempFile(target.getParent(),".download-",".part");
                    try(InputStream input=transport.open(uri);OutputStream output=Files.newOutputStream(temp)) {
                        long limit=expectedSize>=0?expectedSize:32L*1024*1024;
                        if(!copy(input,output,limit))throw new IOException("Input exceeds declared download limit");
                        output.close();
                        if(!valid(temp,sha1,expectedSize))throw new IOException("Downloaded input failed hash/size validation");
                        try {Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                        catch(AtomicMoveNotSupportedException e) {Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
                        FilesSupport.atomicWrite(checksum,FilesSupport.hash(target,"SHA-256").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        return target;
                    } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("Download interrupted",e);}
                    catch(IOException e) {failure=e;}
                    finally {Files.deleteIfExists(temp);}
                }
                throw failure;
            }
        }
    }
    private boolean copy(InputStream input,OutputStream output,long limit) throws IOException {
        byte[] buffer=new byte[65536];long total=0;int count;
        while((count=input.read(buffer))>=0) {
            cancellation.check();total+=count;if(total>limit)return false;output.write(buffer,0,count);
        }
        cancellation.check();return true;
    }
    private boolean importExisting(String relative,Path target,Path checksum,String sha1,long size) throws IOException {
        if(minecraft==null||sha1==null)return false;
        for(String part:relative.split("/"))if(part.equals("..")||part.equals("."))
            throw new McIconException("UNSAFE_PATH","Existing input must remain within its official subtree",2);
        String source=null;
        if(relative.startsWith("libraries/")||relative.startsWith("assets/indexes/")||relative.startsWith("assets/objects/"))source=relative;
        else {
            String[] parts=relative.split("/");
            if(parts.length==3&&parts[0].equals("versions")) {
                if(parts[2].equals("client.jar"))source="versions/"+parts[1]+"/"+parts[1]+".jar";
                if(parts[2].equals("version.json"))source="versions/"+parts[1]+"/"+parts[1]+".json";
            }
        }
        if(source==null)return false;
        Path existing=FilesSupport.safeResolve(minecraft,source);
        if(!Files.isRegularFile(existing,LinkOption.NOFOLLOW_LINKS))return false;
        Path temporary=Files.createTempFile(target.getParent(),".download-",".part");
        try {
            try(InputStream input=Files.newInputStream(existing);OutputStream output=Files.newOutputStream(temporary)) {
                if(!copy(input,output,size>=0?size:32L*1024*1024))return false;
            } catch(IOException failure) {throw new IOException("Existing Minecraft input could not be staged: "+failure.getClass().getSimpleName());}
            if(!valid(temporary,sha1,size))return false;
            try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            FilesSupport.atomicWrite(checksum,FilesSupport.hash(target,"SHA-256").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return true;
        } finally {Files.deleteIfExists(temporary);}
    }
    private FileLock lock(FileChannel channel) throws IOException {
        // OSのプロセス単位の待機循環検出を避け、取得スレッド同士の並列性を保つ。
        while(true) {
            cancellation.check();
            FileLock lock=channel.tryLock();
            if(lock!=null)return lock;
            try {Thread.sleep(10);}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw new FileLockInterruptionException();}
        }
    }
    private boolean valid(Path file,String hash,long size) throws IOException {
        return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&(size<0||Files.size(file)==size)
                &&(hash==null||FilesSupport.hash(file,"SHA-1").equals(hash));
    }
    public Path root(){return root;}
}
