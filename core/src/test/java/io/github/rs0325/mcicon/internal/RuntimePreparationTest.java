package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePreparationTest {
    @TempDir Path root;
    @Test void oneFailedAssetInterruptsOtherPendingTransfers() throws Exception {
        failedAssetInterruptsPeers(true);
    }
    @Test void laterAssetFailureInterruptsTheEarlierBlockedTransfer() throws Exception {
        failedAssetInterruptsPeers(false);
    }
    private void failedAssetInterruptsPeers(boolean failureFirst) throws Exception {
        CountDownLatch peerStarted=new CountDownLatch(1),peerInterrupted=new CountDownLatch(1);
        var store=new AssetStore(root,false,new CancellationToken(),uri->{
            if(uri.toString().endsWith("a".repeat(40))) {
                if(!peerStarted.await(3,TimeUnit.SECONDS))throw new IOException("peer未起動");
                throw new IOException("失敗fixture");
            }
            return new InputStream(){public int read() throws IOException {
                peerStarted.countDown();try{new CountDownLatch(1).await();return -1;}
                catch(InterruptedException e){Thread.currentThread().interrupt();peerInterrupted.countDown();throw new IOException(e);}
            }};
        });
        var preparer=new RuntimePreparer(store,new VersionService(store),new CancellationToken());
        var objects=Json.MAPPER.createObjectNode();
        objects.putObject("first").put("hash",(failureFirst?"a":"b").repeat(40)).put("size",1);
        objects.putObject("second").put("hash",(failureFirst?"b":"a").repeat(40)).put("size",1);
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertThrows(ExecutionException.class,()->preparer.prepareAssets(objects)));
        assertTrue(peerInterrupted.await(1,TimeUnit.SECONDS));
        try(var walk=Files.walk(root)){assertFalse(walk.anyMatch(p->p.toString().endsWith(".part")));}
    }
    private Path archive(String name,int count,boolean directories) throws IOException {
        Path jar=root.resolve("native.jar");try(var zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            for(int i=0;i<count;i++){zip.putNextEntry(new ZipEntry(name+i+(directories?"/":"")));if(!directories)zip.write(new byte[]{1,2,3});zip.closeEntry();}
        }return jar;
    }
    private RuntimePreparer preparer(CancellationToken token) {
        var store=new AssetStore(root,true,token);return new RuntimePreparer(store,new VersionService(store),token);
    }
    @Test void nativeExtractionRejectsTraversalLinksAndExcessEntryCount() throws Exception {
        Path natives=root.resolve("natives");Files.createDirectories(natives);
        assertEquals("UNSAFE_PATH",assertThrows(McIconException.class,()->preparer(new CancellationToken()).extractNatives(archive("../outside",1,false),natives)).code());
        assertFalse(Files.exists(root.resolve("outside0")));
        Path external=root.resolve("external");Files.writeString(external,"保持");Files.createSymbolicLink(natives.resolve("native0"),external);
        assertEquals("UNSAFE_PATH",assertThrows(McIconException.class,()->preparer(new CancellationToken()).extractNatives(archive("native",1,false),natives)).code());
        assertEquals("保持",Files.readString(external));
        assertThrows(IOException.class,()->preparer(new CancellationToken()).extractNatives(archive("dir",4097,true),natives));
    }
    @Test void nativeExtractionChecksCancellationBeforeWriting() throws Exception {
        var token=new CancellationToken();token.cancel();Path jar=archive("native",1,false);
        assertEquals("CANCELLED",assertThrows(McIconException.class,()->preparer(token).extractNatives(jar,root.resolve("natives"))).code());
        assertFalse(Files.exists(root.resolve("natives/native0")));
    }
    @Test void expandedArchiveBudgetDoesNotTrustDeclaredUncompressedSizes() throws Exception {
        Path jar=root.resolve("misreported.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.setLevel(1);byte[] block=new byte[1024*1024];
            for(int entry=0;entry<5;entry++) {
                zip.putNextEntry(new ZipEntry("native"+entry));for(int mb=0;mb<60;mb++)zip.write(block);zip.closeEntry();
            }
        }
        byte[] bytes=Files.readAllBytes(jar);int central=0;
        for(int i=0;i<bytes.length-46;i++)if(bytes[i]==0x50&&bytes[i+1]==0x4b&&bytes[i+2]==1&&bytes[i+3]==2) {
            bytes[i+24]=1;bytes[i+25]=0;bytes[i+26]=0;bytes[i+27]=0;central++;
        }
        assertEquals(5,central);Files.write(jar,bytes);
        Path natives=root.resolve("natives");
        var failure=assertThrows(IOException.class,()->preparer(new CancellationToken()).extractNatives(jar,natives));
        assertEquals("Native archive exceeds total size limit",failure.getMessage());
        assertTrue(Files.exists(natives.resolve("native3")));assertFalse(Files.exists(natives.resolve("native4")));
    }
    @Test void selectedJavaMustReportSupportedMajorWithoutExposingItsDirectory() {
        var java=Diagnostics.parseJava("/custom/private/java/bin/java","openjdk version \"21.0.9\" 2025-10-21",21);
        assertEquals(21,java.major());assertEquals("21.0.9",java.version());assertFalse(java.settings().toString().contains("/custom/private"));
        assertEquals(8,Diagnostics.parseJava("/java","java version \"1.8.0_472\"",8).major());
        assertEquals("WORKER_JAVA_INCOMPATIBLE",assertThrows(McIconException.class,()->Diagnostics.parseJava("/java","openjdk version \"17.0.17\"",21)).code());
        assertEquals("WORKER_JAVA_UNAVAILABLE",assertThrows(McIconException.class,()->Diagnostics.parseJava("/private","unavailable /private",21)).code());
        assertThrows(IllegalArgumentException.class,()->new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(1),Duration.ofSeconds(1),"C:\\Java"));
    }
}
