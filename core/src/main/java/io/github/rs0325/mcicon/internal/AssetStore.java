package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;

/** Content validation and atomic cache installation, with bounded network reads. */
public final class AssetStore {
    @FunctionalInterface public interface Transport { InputStream open(URI uri) throws IOException, InterruptedException; }
    private final Path root;
    private final boolean offline;
    private final Transport transport;
    private final CancellationToken cancellation;
    public AssetStore(Path root,boolean offline,CancellationToken cancellation) {
        this(root,offline,cancellation,httpsTransport());
    }
    public AssetStore(Path root,boolean offline,CancellationToken cancellation,Transport transport) {
        this.root=root.toAbsolutePath().normalize();this.offline=offline;this.cancellation=cancellation;this.transport=transport;
    }
    private static Transport httpsTransport() {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
        return uri -> {
            if(!"https".equals(uri.getScheme()))throw new IOException("Only HTTPS asset URLs are supported");
            URI current=uri;
            for(int redirects=0;redirects<=5;redirects++) {
                HttpRequest request=HttpRequest.newBuilder(current).timeout(Duration.ofMinutes(2)).header("User-Agent","MCIconGenerator/0.1").GET().build();
                HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
                if(response.statusCode()==200)return response.body();
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
    public Path obtain(String relative,URI uri,String sha1,long expectedSize,boolean refresh) throws IOException {
        cancellation.check();
        Path target=FilesSupport.safeResolve(root,relative);
        Files.createDirectories(target.getParent());
        Path lockPath=FilesSupport.safeResolve(root,relative+".lock");
        synchronized((root+"/"+relative).intern()) {
            try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock ignored=channel.lock()) {
                Path checksum=FilesSupport.safeResolve(root,relative+".sha256");
                if(Files.exists(target)&&valid(target,sha1,expectedSize)&&(!refresh||offline)) {
                    String actual=FilesSupport.hash(target,"SHA-256");
                    boolean verified=Files.isRegularFile(checksum,LinkOption.NOFOLLOW_LINKS)&&Files.readString(checksum).trim().equals(actual);
                    if(sha1!=null||verified) {
                        if(!verified)FilesSupport.atomicWrite(checksum,actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        return target;
                    }
                }
                if(offline)throw new McIconException("OFFLINE_CACHE_MISS","Verified cached input is unavailable: "+relative,1);
                IOException failure=null;
                for(int attempt=0;attempt<3;attempt++) {
                    cancellation.check();
                    Path temp=Files.createTempFile(target.getParent(),".download-",".part");
                    try(InputStream input=transport.open(uri);OutputStream output=Files.newOutputStream(temp)) {
                        byte[] buffer=new byte[65536];long total=0;int count;
                        long limit=expectedSize>=0?expectedSize:32L*1024*1024;
                        while((count=input.read(buffer))>=0) {
                            cancellation.check();total+=count;
                            if(total>limit)throw new IOException("Input exceeds declared download limit");
                            output.write(buffer,0,count);
                        }
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
    private boolean valid(Path file,String hash,long size) throws IOException {
        return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&(size<0||Files.size(file)==size)
                &&(hash==null||FilesSupport.hash(file,"SHA-1").equals(hash));
    }
    public Path root(){return root;}
}
