package io.github.rs0325.mcicon.internal;

import com.sun.net.httpserver.*;
import io.github.rs0325.mcicon.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AssetTransferTest {
    @TempDir static Path certificates;
    @TempDir Path root;
    private static SSLContext tls;
    private HttpsServer server;
    private ExecutorService handlers;
    private HttpClient client;
    private URI uri;
    @BeforeAll static void certificate() throws Exception {
        Path file=certificates.resolve("test.p12");
        Process keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","fixture",
            "-keystore",file.toString(),"-storetype","PKCS12","-storepass","fixture-password","-keypass","fixture-password",
            "-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1")
            .redirectErrorStream(true).redirectOutput(certificates.resolve("keytool.log").toFile()).start();
        assertTrue(keytool.waitFor(20,TimeUnit.SECONDS));assertEquals(0,keytool.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(file)){keys.load(input,"fixture-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture-password".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
    }
    private void serve(HttpHandler handler) throws IOException {
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        handlers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(handlers);server.createContext("/asset",handler);server.start();
        client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).build();
        uri=URI.create("https://localhost:"+server.getAddress().getPort()+"/asset");
    }
    @AfterEach void close() {
        if(client!=null)client.shutdownNow();if(server!=null)server.stop(0);if(handlers!=null)handlers.shutdownNow();
    }
    @Test void stalledResponseBodyTimesOutAndThreeAttemptsLeaveNoPartialFile() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        serve(exchange->{
            attempts.incrementAndGet();try(exchange){exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();
                try{Thread.sleep(10000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
            catch(IOException disconnected){}
        });
        var token=new CancellationToken();var store=new AssetStore(root,false,token,AssetStore.httpsTransport(client,token,Duration.ofMillis(800)));
        assertTimeoutPreemptively(Duration.ofSeconds(6),()->assertThrows(HttpTimeoutException.class,()->store.obtain("file",uri,null,2,false)));
        assertEquals(3,attempts.get());assertFalse(Files.exists(root.resolve("file")));
        try(var walk=Files.walk(root)){assertFalse(walk.anyMatch(p->p.toString().endsWith(".part")));}
    }
    @Test void cancellationClosesBlockedBodyWithoutWaitingForTransferDeadline() throws Exception {
        CountDownLatch sent=new CountDownLatch(1);
        serve(exchange->{try(exchange){exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();sent.countDown();
                try{Thread.sleep(10000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
            catch(IOException disconnected){}
        });
        var token=new CancellationToken();var store=new AssetStore(root,false,token,AssetStore.httpsTransport(client,token,Duration.ofSeconds(20)));
        try(var job=Executors.newSingleThreadExecutor()) {
            Future<Path> future=job.submit(()->store.obtain("file",uri,null,2,false));assertTrue(sent.await(3,TimeUnit.SECONDS));token.cancel();
            var failure=assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));
            assertInstanceOf(McIconException.class,failure.getCause());assertEquals("CANCELLED",((McIconException)failure.getCause()).code());
        }
        assertFalse(Files.exists(root.resolve("file")));
        try(var walk=Files.walk(root)){assertFalse(walk.anyMatch(p->p.toString().endsWith(".part")));}
    }
    @Test void continuousBodyProgressDoesNotExtendTheOverallDeadline() throws Exception {
        serve(exchange->{try(exchange){exchange.sendResponseHeaders(200,100);
            for(int i=0;i<100;i++){exchange.getResponseBody().write(i);exchange.getResponseBody().flush();try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}}
            catch(IOException disconnected){}
        });
        var token=new CancellationToken();var transport=AssetStore.httpsTransport(client,token,Duration.ofMillis(800));
        assertTimeoutPreemptively(Duration.ofSeconds(3),()-> {
            try(InputStream input=transport.open(uri)){assertThrows(HttpTimeoutException.class,()->input.readAllBytes());}
        });
    }
    @Test void cancellationAlsoInterruptsWaitingForResponseHeaders() throws Exception {
        CountDownLatch received=new CountDownLatch(1);
        serve(exchange->{received.countDown();try(exchange){try{Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
        var token=new CancellationToken();var transport=AssetStore.httpsTransport(client,token,Duration.ofSeconds(20));
        try(var job=Executors.newSingleThreadExecutor()) {
            Future<?> future=job.submit(()->transport.open(uri));assertTrue(received.await(3,TimeUnit.SECONDS));token.cancel();
            var failure=assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));
            assertInstanceOf(McIconException.class,failure.getCause());assertEquals("CANCELLED",((McIconException)failure.getCause()).code());
        }
    }
}
