package io.github.rs0325.mcicon.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UpdateDownloadTest {
    @TempDir Path directory;
    static final class Client extends HttpClient {
        final java.util.function.Function<HttpRequest,CompletableFuture<HttpResponse<InputStream>>> reply;
        final AtomicInteger calls=new AtomicInteger();
        Client(java.util.function.Function<HttpRequest,CompletableFuture<HttpResponse<InputStream>>> reply){this.reply=reply;}
        @SuppressWarnings("unchecked") @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){calls.incrementAndGet();return (CompletableFuture<HttpResponse<T>>)(CompletableFuture<?>)reply.apply(request);}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){return sendAsync(request,handler);}
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new AssertionError("同期HTTPは使いません");}
        @Override public Optional<CookieHandler> cookieHandler(){return Optional.empty();}
        @Override public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(1));}
        @Override public Redirect followRedirects(){return Redirect.NEVER;}
        @Override public Optional<ProxySelector> proxy(){return Optional.empty();}
        @Override public SSLContext sslContext(){throw new UnsupportedOperationException();}
        @Override public SSLParameters sslParameters(){return new SSLParameters();}
        @Override public Optional<Authenticator> authenticator(){return Optional.empty();}
        @Override public Version version(){return Version.HTTP_1_1;}
        @Override public Optional<Executor> executor(){return Optional.empty();}
    }
    record Reply(HttpRequest request,int statusCode,InputStream body,Map<String,List<String>> fields) implements HttpResponse<InputStream> {
        @Override public Optional<HttpResponse<InputStream>> previousResponse(){return Optional.empty();}
        @Override public HttpHeaders headers(){return HttpHeaders.of(fields,(a,b)->true);}
        @Override public Optional<SSLSession> sslSession(){return Optional.empty();}
        @Override public URI uri(){return request.uri();}
        @Override public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }
    private long deadline(){return System.nanoTime()+Duration.ofSeconds(3).toNanos();}
    @Test void exactBytesAreWrittenWithoutNetworkAndOversizeRemainsPartial() throws Exception {
        byte[] bytes={1,2,3};Client client=new Client(r->CompletableFuture.completedFuture(new Reply(r,200,new ByteArrayInputStream(bytes),Map.of())));Path target=directory.resolve("exact");UpdateDownload.get(client,URI.create("https://fixture.invalid/file"),target,3,deadline());assertArrayEquals(bytes,Files.readAllBytes(target));assertEquals(1,client.calls.get());
        Path partial=directory.resolve("partial");assertThrows(IOException.class,()->UpdateDownload.get(client,URI.create("https://fixture.invalid/file"),partial,2,deadline()));assertTrue(Files.exists(partial));assertEquals(0,Files.size(partial));
    }
    @Test void insecureInitialAndRedirectTargetsAreRejectedBeforeTheirRequest() throws Exception {
        Client client=new Client(r->CompletableFuture.completedFuture(new Reply(r,302,InputStream.nullInputStream(),Map.of("location",List.of("http://fixture.invalid/insecure")))));assertThrows(IOException.class,()->UpdateDownload.get(client,URI.create("http://fixture.invalid/file"),directory.resolve("one"),3,deadline()));assertEquals(0,client.calls.get());assertThrows(IOException.class,()->UpdateDownload.get(client,URI.create("https://fixture.invalid/file"),directory.resolve("two"),3,deadline()));assertEquals(1,client.calls.get());
    }
    @Test void continuousProgressCannotExtendTheTransferDeadline() throws Exception {
        InputStream slow=new InputStream(){int count;public int read(){try{Thread.sleep(15);}catch(InterruptedException failure){Thread.currentThread().interrupt();}return count++<100?1:-1;}@Override public int read(byte[] b,int offset,int length){int value=read();if(value<0)return -1;b[offset]=(byte)value;return 1;}};
        Client client=new Client(r->CompletableFuture.completedFuture(new Reply(r,200,slow,Map.of())));long start=System.nanoTime();assertThrows(IOException.class,()->UpdateDownload.get(client,URI.create("https://fixture.invalid/file"),directory.resolve("slow"),100,start+Duration.ofMillis(60).toNanos()));assertTrue(System.nanoTime()-start<Duration.ofSeconds(1).toNanos());
    }
    @Test void stalledReadIsClosedByTheOriginalAbsoluteDeadline() throws Exception {
        CountDownLatch closed=new CountDownLatch(1);InputStream blocked=new InputStream(){public int read() throws IOException {try{closed.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();}throw new IOException("closed");}@Override public void close(){closed.countDown();}};
        Client client=new Client(r->CompletableFuture.completedFuture(new Reply(r,200,blocked,Map.of())));long start=System.nanoTime();assertThrows(IOException.class,()->UpdateDownload.get(client,URI.create("https://fixture.invalid/file"),directory.resolve("blocked"),100,start+Duration.ofMillis(60).toNanos()));assertEquals(0,closed.getCount());assertTrue(System.nanoTime()-start<Duration.ofSeconds(1).toNanos());
    }
    @Test void missingHeadersTimeOutAndTheLateBodyIsClosed() throws Exception {
        CompletableFuture<HttpResponse<InputStream>> response=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};Client client=new Client(r->response);CountDownLatch closed=new CountDownLatch(1);InputStream body=new ByteArrayInputStream(new byte[]{1}){@Override public void close(){closed.countDown();}};
        assertThrows(IOException.class,()->UpdateDownload.get(client,URI.create("https://fixture.invalid/file"),directory.resolve("headers"),1,System.nanoTime()+Duration.ofMillis(40).toNanos()));HttpRequest request=HttpRequest.newBuilder(URI.create("https://fixture.invalid/file")).build();response.complete(new Reply(request,200,body,Map.of()));assertTrue(closed.await(1,TimeUnit.SECONDS));assertFalse(Files.exists(directory.resolve("headers")));
    }
    @Test void signedOldSequenceAndSameVersionCannotAdvanceHighWater() throws Exception {
        Fixture f=new Fixture(directory.toRealPath());var state=Seed.JSON.createObjectNode().put("highestAcceptedSequence",1);state.set("active",f.seed().reference());assertThrows(IOException.class,()->UpdateDownload.newer(f.seed(),state));f.manifest.put("sequence",2);f.writeManifest();assertThrows(IOException.class,()->UpdateDownload.newer(f.seed(),state));f.manifest.put("toolVersion","1.1.1");f.writeManifest();UpdateDownload.newer(f.seed(),state);assertEquals(1,state.path("highestAcceptedSequence").asInt());
        f.manifest.put("toolVersion","1.0.9").put("sequence",3);f.writeManifest();assertThrows(IOException.class,()->UpdateDownload.newer(f.seed(),state));
    }
}
