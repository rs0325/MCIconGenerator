package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeStoreTest {
    @TempDir Path root;
    static final byte[] BYTES="verified-fixture-artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static RuntimeDescriptor.Artifact artifact(byte[] bytes) {return new RuntimeDescriptor.Artifact("fixture","host-helper",URI.create("https://fixture.invalid/helper"),bytes.length,FilesSupport.hash(bytes),100,"zip","inventory.json",ManagedFixture.HASH,List.of("LICENSE"),List.of(),List.of());}
    RuntimeStore store(boolean offline,RuntimeStore.Transport transport) throws IOException {return new RuntimeStore(root,offline,new CancellationToken(),Duration.ofSeconds(10),transport);}
    @Test void partialTransferResumesAndOfflineVerifiesAgain() throws Exception {
        var artifact=artifact(BYTES);AtomicInteger calls=new AtomicInteger();
        RuntimeStore store=store(false,(uri,offset,remaining)-> {
            if(calls.getAndIncrement()==0)return new RuntimeStore.Response(200,0,new ByteArrayInputStream(Arrays.copyOf(BYTES,5)));
            assertEquals(5,offset);return new RuntimeStore.Response(206,offset,new ByteArrayInputStream(Arrays.copyOfRange(BYTES,5,BYTES.length)));
        });
        assertThrows(EOFException.class,()->store.obtain(artifact));Path file=store.obtain(artifact);assertArrayEquals(BYTES,Files.readAllBytes(file));
        RuntimeStore offline=store(true,(u,o,t)->{throw new AssertionError("offlineで取得してはいけません");});
        assertEquals(file,offline.obtain(artifact));Files.writeString(file,"tampered");
        assertEquals("OFFLINE_CACHE_MISS",assertThrows(McIconException.class,()->offline.obtain(artifact)).code());
    }
    @Test void ignoredRangeRestartsWithoutAppending() throws Exception {
        var artifact=artifact(BYTES);Path dir=root.resolve("downloads/"+artifact.sha256());Files.createDirectories(dir);Files.write(dir.resolve("artifact.part"),new byte[]{1,2,3});
        assertArrayEquals(BYTES,Files.readAllBytes(store(false,(u,o,t)->new RuntimeStore.Response(200,0,new ByteArrayInputStream(BYTES))).obtain(artifact)));
    }
    @Test void rejectsHashSizeAndRangeMismatch() throws Exception {
        var artifact=artifact(BYTES);
        RuntimeDescriptorTest.assertInvalid(()->store(false,(u,o,t)->new RuntimeStore.Response(200,0,new ByteArrayInputStream(new byte[BYTES.length]))).obtain(artifact));
        RuntimeDescriptorTest.assertInvalid(()->store(false,(u,o,t)->new RuntimeStore.Response(200,0,new ByteArrayInputStream(new byte[BYTES.length+1]))).obtain(artifact));
        Path dir=root.resolve("downloads/"+artifact.sha256());Files.write(dir.resolve("artifact.part"),new byte[]{1,2,3});
        assertThrows(IOException.class,()->store(false,(u,o,t)->new RuntimeStore.Response(206,1,new ByteArrayInputStream(BYTES))).obtain(artifact));
    }
    @Test void concurrentInitializersPublishOnce() throws Exception {
        AtomicInteger calls=new AtomicInteger();RuntimeStore store=store(false,(u,o,t)->{calls.incrementAndGet();Thread.sleep(100);return new RuntimeStore.Response(200,0,new ByteArrayInputStream(BYTES));});
        try(var executor=Executors.newFixedThreadPool(2)) {
            Future<Path> first=executor.submit(()->store.obtain(artifact(BYTES))),second=executor.submit(()->store.obtain(artifact(BYTES)));
            assertEquals(first.get(),second.get());assertEquals(1,calls.get());
        }
    }
    @Test void insufficientSpaceFailsBeforeDownload() throws Exception {
        var a=artifact(BYTES);var huge=new RuntimeDescriptor.Artifact(a.id(),a.role(),a.url(),Long.MAX_VALUE,a.sha256(),100,a.format(),a.inventoryPath(),a.inventorySha256(),a.licenseFiles(),a.noticeFiles(),a.sourceArchives());
        IOException failure=assertThrows(IOException.class,()->store(false,(u,o,t)->{throw new AssertionError();}).obtain(huge));assertTrue(failure.getMessage().contains("空き容量"));
    }
    @Test void generationCopyAndHashCheckCancellationInsideRead() throws Exception {
        for(boolean hash:List.of(false,true)) {
            CancellationToken token=new CancellationToken();RuntimeStore store=new RuntimeStore(root,false,token,Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
            InputStream input=new ByteArrayInputStream(BYTES) {
                @Override public synchronized int read(byte[] b,int off,int len) {int count=super.read(b,off,len);token.cancel();return count;}
            };
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            assertEquals("CANCELLED",assertThrows(McIconException.class,()->{if(hash)store.hash(input);else store.copy(input,output,BYTES.length);}).code());assertEquals(0,output.size());
        }
    }
    @Test void generationCopyAndHashCheckDeadlineInsideRead() throws Exception {
        for(boolean hash:List.of(false,true)) {
            RuntimeStore store=new RuntimeStore(root,false,new CancellationToken(),Duration.ofMillis(100),(u,o,t)->{throw new AssertionError();});
            InputStream input=new ByteArrayInputStream(BYTES) {
                @Override public synchronized int read(byte[] b,int off,int len) {
                    try {Thread.sleep(120);}catch(InterruptedException failure){throw new AssertionError(failure);}
                    return super.read(b,off,len);
                }
            };
            ByteArrayOutputStream output=new ByteArrayOutputStream();assertThrows(java.net.http.HttpTimeoutException.class,()->{if(hash)store.hash(input);else store.copy(input,output,BYTES.length);});assertEquals(0,output.size());
        }
    }
    @Test void expiredPreparationAndCancellationDoNotDownload() throws Exception {
        RuntimeStore expired=new RuntimeStore(root,false,new CancellationToken(),Duration.ofNanos(1),(u,o,t)->{throw new AssertionError();});assertThrows(java.net.http.HttpTimeoutException.class,()->expired.obtain(artifact(BYTES)));
        CancellationToken token=new CancellationToken();token.cancel();RuntimeStore cancelled=new RuntimeStore(root,false,token,Duration.ofSeconds(1),(u,o,t)->{throw new AssertionError();});assertEquals("CANCELLED",assertThrows(McIconException.class,()->cancelled.obtain(artifact(BYTES))).code());
    }
}
