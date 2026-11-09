package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Duration;
import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.McIconException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionLockTest {
    @TempDir Path temporary;
    WindowsAdmissionTest.Fixture fixture;
    RuntimeDescriptor descriptor;
    RuntimeLease current,peer;
    ObjectNode claim;

    @BeforeEach void setup() throws Exception {
        fixture=new WindowsAdmissionTest.Fixture(temporary);
        fixture.source.json.withObject("host").put("os","macos").put("arch","aarch64");
        fixture.source.json.put("minimumOsVersion","13");
        byte[] bytes=fixture.source.bytes();
        descriptor=RuntimeDescriptor.verify(bytes,fixture.source.sign(bytes),fixture.source.trust,
                new RuntimeDescriptor.Pin(FilesSupport.hash(bytes),"1.1.2",ManagedFixture.HASH,ManagedFixture.HASH,
                        new RuntimeDescriptor.Host("macos","aarch64"),Set.of(21)));
        fixture.publish(bytes);
        Path helper=fixture.store.root().resolve("helpers/macos-aarch64/"+"d".repeat(64)+"/bin/helper");
        Files.createDirectories(helper.getParent());Files.writeString(helper,"fixture");
        current=RuntimeLease.create(fixture.store,descriptor,fixture.options,Instant.now().plusSeconds(30));
        peer=RuntimeLease.create(fixture.store,descriptor,fixture.options,Instant.now().plusSeconds(30));
        fixture.writeJob(current,bytes);fixture.writeJob(peer,bytes);
        assertLocks("BUSY","BUSY");
        ObjectNode budget=RuntimeDescriptor.JSON.createObjectNode().put("domain","macos-vm").put("constraint","generation")
                .put("hostMiB",16384).put("requiredMiB",7168).put("availableMiB",4096);
        budget.putArray("generations").addObject().put("descriptorSha256",descriptor.digest()).put("reservedMiB",4096).put("requestedMiB",6144);
        var own=LeaseSnapshot.read(fixture.store.root(),current.file(),fixture.trust,current.receiptChannel());
        var other=LeaseSnapshot.read(fixture.store.root(),peer.file(),fixture.trust,peer.receiptChannel());
        claim=(ObjectNode)assertThrows(AdmissionEvidence.Busy.class,()->AdmissionEvidence.capacity(fixture.store.root(),own,List.of(other),budget,RuntimeLease.receipts(fixture.store))).job();
        assertLocks("BUSY","BUSY");
    }

    @AfterEach void close() throws Exception {
        try{if(current!=null)current.close();}finally{if(peer!=null)peer.close();}
    }

    private RuntimeHelper.Result busy() {
        return new RuntimeHelper.Result("failed",List.of(new RuntimeHelper.Diagnostic("MANAGED_BUSY","fixture",false,false)));
    }

    @Test void 正規容量延期の前後とidle回収probe後も両jobのactiveLockを別JVMへ渡さない() throws Exception {
        AdmissionEvidence.Busy failure=assertThrows(AdmissionEvidence.Busy.class,
                ()->ManagedRuntime.requireAdmission(busy(),fixture.store,current,descriptor,fixture.trust));
        assertEquals(claim,failure.job());assertLocks("BUSY","BUSY");
        assertFalse(RuntimeLease.idle(fixture.store,descriptor));
        assertNull(RuntimeLease.tryRecovery(fixture.store,descriptor,current.file().getParent()));
        assertLocks("BUSY","BUSY");
        current.released();current.close();assertLocks("FREE","BUSY");
        assertNull(RuntimeLease.receiptChannel(current.file()));
    }

    @Test void 同JVMのlive参照とproof照合も所有channelを閉じない() throws Exception {
        FileChannel channel=peer.receiptChannel();
        LeaseSnapshot snapshot=LeaseSnapshot.read(fixture.store.root(),peer.file(),fixture.trust,channel);
        channel.position(17);RuntimeMaintenanceVerifier.requireLive(snapshot,channel);
        ObjectNode proof=RuntimeMaintenanceVerifier.proof(snapshot,channel);
        assertEquals(snapshot.receiptSha256(),proof.path("receiptSha256").asText());
        assertEquals(17,channel.position());assertTrue(channel.isOpen());assertLocks("BUSY","BUSY");
    }

    @Test void 候補receipt改変をMANAGED_BUSYへ変換しない() throws Exception {rejectChangedReceipt(current);}
    @Test void 同JVMのpeerReceipt改変をMANAGED_BUSYへ変換しない() throws Exception {rejectChangedReceipt(peer);}

    private void rejectChangedReceipt(RuntimeLease lease) throws Exception {
        FileChannel channel=lease.receiptChannel();ByteBuffer saved=ByteBuffer.allocate(1);assertEquals(1,channel.read(saved,0));
        byte original=saved.array()[0];channel.write(ByteBuffer.wrap(new byte[]{(byte)(original=='a'?'b':'a')}),0);
        try {
            IOException failure=assertThrows(IOException.class,()->ManagedRuntime.requireAdmission(busy(),fixture.store,current,descriptor,fixture.trust));
            assertFalse(failure instanceof AdmissionEvidence.Busy);assertLocks("BUSY","BUSY");
        }finally{channel.write(ByteBuffer.wrap(new byte[]{original}),0);}
    }

    @Test void 同JVMの保有channel不明は別FDを開かずUNKNOWNとして拒否する() throws Exception {
        IOException failure=assertThrows(IOException.class,()->verify(path->null));
        assertFalse(failure instanceof AdmissionEvidence.Busy);assertLocks("BUSY","BUSY");
        failure=assertThrows(IOException.class,()->verify(path->path.equals(current.file())?current.receiptChannel():null));
        assertFalse(failure instanceof AdmissionEvidence.Busy);assertLocks("BUSY","BUSY");
    }

    @Test void peerClose競合では閉じたchannelを再openせずUNKNOWNとして拒否する() throws Exception {
        IOException failure=assertThrows(IOException.class,()->verify(path->{
            FileChannel channel=RuntimeLease.receiptChannel(path);
            if(path.equals(peer.file()))peer.close();
            return channel;
        }));
        assertFalse(failure instanceof AdmissionEvidence.Busy);assertLocks("BUSY","FREE");
        assertNull(RuntimeLease.receiptChannel(peer.file()));
    }

    @Test @EnabledOnOs(OS.MAC)
    void Macの容量証拠を照合している途中も別JVMのactiveLock取得を拒否する() throws Exception {
        Set<Path> observed=new HashSet<>();
        LeaseTrust trust=LeaseTrust.verified(fixture.source.trust,path->{
            if(path.getFileName().toString().equals("descriptor.json")) {
                try{assertLocks("BUSY","BUSY");observed.add(path.getParent());}
                catch(Exception failure){throw new IOException("別JVMでactive lockを確認できません",failure);}
            }
            return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey().toString();
        });
        assertThrows(AdmissionEvidence.Busy.class,()->ManagedRuntime.requireAdmission(busy(),fixture.store,current,descriptor,trust));
        assertEquals(Set.of(current.file().getParent(),peer.file().getParent()),observed);
        assertLocks("BUSY","BUSY");
    }

    private void verify(AdmissionEvidence.ReceiptChannels receipts) throws IOException {
        verify(fixture.trust,receipts);
    }
    private void verify(LeaseTrust trust,AdmissionEvidence.ReceiptChannels receipts) throws IOException {
        AdmissionEvidence.verifyJob(fixture.store.root(),claim,descriptor.digest(),ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElseThrow().toString(),trust,false,receipts);
    }

    @Test void null検索後の回復所有開始を読取り完了まで待たせ別JVMへactiveLockを渡さない() throws Exception {
        Process dead=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-version").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertTrue(dead.waitFor(5,TimeUnit.SECONDS));}finally{if(dead.isAlive()){dead.destroyForcibly();assertTrue(dead.waitFor(3,TimeUnit.SECONDS));}dead.getInputStream().close();dead.getOutputStream().close();dead.getErrorStream().close();}
        ObjectNode value=peer.read();peer.close();value.put("pid",dead.pid()).put("processStartedAt",Instant.EPOCH.toString());
        ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));
        Files.write(peer.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));Files.write(peer.file().resolveSibling("lease.lock"),RuntimeLease.receipt(identity));
        AtomicReference<RuntimeLease> recovered=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        CountDownLatch started=new CountDownLatch(1);AtomicBoolean observed=new AtomicBoolean(),blocked=new AtomicBoolean();
        Thread recovery=Thread.ofPlatform().unstarted(()->{
            started.countDown();try{recovered.set(RuntimeLease.tryRecovery(fixture.store,descriptor,peer.file().getParent()));}catch(Throwable failure){error.set(failure);}
        });
        var fileIdentity=LeaseTrust.class.getDeclaredMethod("identity",Path.class);fileIdentity.setAccessible(true);
        LeaseTrust trust=LeaseTrust.verified(fixture.source.trust,path->{
            if(path.equals(peer.file())&&observed.compareAndSet(false,true)) {
                assertNull(RuntimeLease.receiptChannel(peer.file()));recovery.start();
                try {
                    assertTrue(started.await(2,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    ReentrantLock gate=receiptGate(peer.file());
                    while(recovery.isAlive()&&System.nanoTime()<end) {
                        if(gate!=null&&gate.hasQueuedThread(recovery)){blocked.set(true);break;}
                        Thread.yield();
                    }
                }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException(failure);}
                catch(ReflectiveOperationException failure){throw new IOException(failure);}
            }
            try{return fileIdentity.invoke(fixture.trust,path).toString();}catch(ReflectiveOperationException failure){throw new IOException(failure);}
        });
        try {
            IOException failure=assertThrows(IOException.class,()->AdmissionEvidence.verifyJob(fixture.store.root(),claim,descriptor.digest(),ProcessHandle.current().pid(),
                    ProcessHandle.current().info().startInstant().orElseThrow().toString(),trust,false,RuntimeLease.receipts(fixture.store)));
            assertFalse(failure instanceof AdmissionEvidence.Busy);assertTrue(observed.get());
            recovery.join(5000);assertFalse(recovery.isAlive());assertNull(error.get());assertNotNull(recovered.get());
            assertLock(peer.file().resolveSibling("lease.lock"),"BUSY");assertTrue(blocked.get(),"null検索後の読取り中に回復所有が開始しました");
            assertThrows(IOException.class,()->verify(RuntimeLease.receipts(fixture.store)));assertLock(peer.file().resolveSibling("lease.lock"),"BUSY");
        }finally {
            recovery.join(5000);assertFalse(recovery.isAlive());if(recovered.get()!=null)recovered.get().close();
        }
        assertLock(peer.file().resolveSibling("lease.lock"),"FREE");
    }

    @Test void 未保有receiptの照合停止中も別jobと別rootのcloseと短期限prepareのlease作成が完了する() throws Exception {
        peer.released();peer.close();
        CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
        LeaseTrust trust=pausedTrust(peer.file(),entered,resume);
        var other=new WindowsAdmissionTest.Fixture(temporary.resolve("other-root"));
        RuntimeLease otherLease=RuntimeLease.create(other.store,other.candidate,other.options,Instant.now().plusSeconds(30));
        var pool=Executors.newFixedThreadPool(3);
        var verifying=pool.submit(()->{verify(trust,RuntimeLease.receipts(fixture.store));return null;});
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            pool.submit(()->{current.close();return null;}).get(1,TimeUnit.SECONDS);
            pool.submit(()->{otherLease.close();return null;}).get(1,TimeUnit.SECONDS);
            for(Path root:List.of(fixture.store.root(),other.store.root())) {
                pool.submit(()->{
                    RuntimeStore shortStore=new RuntimeStore(root,true,new CancellationToken(),Duration.ofMillis(300),(u,o,t)->{throw new AssertionError();});
                    try(RuntimeLease fresh=RuntimeLease.create(shortStore,descriptor,fixture.options,Instant.now().plusMillis(300))){fresh.released();}
                    return null;
                }).get(1,TimeUnit.SECONDS);
            }
            assertEquals(1,resume.getCount(),"照合を再開する前に全操作が完了する必要があります");
        }finally {
            resume.countDown();pool.shutdown();
            if(!pool.awaitTermination(5,TimeUnit.SECONDS)){pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            otherLease.close();
        }
        assertInstanceOf(IOException.class,assertThrows(ExecutionException.class,()->verifying.get(1,TimeUnit.SECONDS)).getCause());
        assertNull(receiptGate(peer.file()));
    }

    @Test void 同じreceiptの回復待機は準備期限と取消しと割込みで終了する() throws Exception {
        peer.released();peer.close();
        CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(3);
        var verifying=pool.submit(()->{verify(pausedTrust(peer.file(),entered,resume),RuntimeLease.receipts(fixture.store));return null;});
        AtomicReference<Throwable> interrupted=new AtomicReference<>();AtomicBoolean interruptFlag=new AtomicBoolean();
        CancellationToken token=new CancellationToken();
        RuntimeStore cancellable=new RuntimeStore(fixture.store.root(),true,token,Duration.ofSeconds(15),(u,o,t)->{throw new AssertionError();});
        Thread waiter=null;
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var timed=pool.submit(()->{
                RuntimeStore shortStore=new RuntimeStore(fixture.store.root(),true,new CancellationToken(),Duration.ofMillis(100),(u,o,t)->{throw new AssertionError();});
                return assertThrows(java.net.http.HttpTimeoutException.class,()->RuntimeLease.tryRecovery(shortStore,descriptor,peer.file().getParent()));
            });
            assertNotNull(timed.get(1,TimeUnit.SECONDS));
            AtomicReference<Thread> cancellingThread=new AtomicReference<>();
            var cancelled=pool.submit(()->{
                cancellingThread.set(Thread.currentThread());
                return assertThrows(McIconException.class,()->RuntimeLease.tryRecovery(cancellable,descriptor,peer.file().getParent()));
            });
            ReentrantLock gate=receiptGate(peer.file());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while((cancellingThread.get()==null||!gate.hasQueuedThread(cancellingThread.get()))&&System.nanoTime()<end)Thread.yield();
            assertNotNull(cancellingThread.get());assertTrue(gate.hasQueuedThread(cancellingThread.get()));
            token.cancel();assertEquals("CANCELLED",cancelled.get(1,TimeUnit.SECONDS).code());
            RuntimeStore interruptible=new RuntimeStore(fixture.store.root(),true,new CancellationToken(),Duration.ofSeconds(15),(u,o,t)->{throw new AssertionError();});
            waiter=Thread.ofPlatform().unstarted(()->{
                try{RuntimeLease.tryRecovery(interruptible,descriptor,peer.file().getParent());}
                catch(Throwable failure){interrupted.set(failure);interruptFlag.set(Thread.currentThread().isInterrupted());}
            });
            waiter.start();end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!gate.hasQueuedThread(waiter)&&waiter.isAlive()&&System.nanoTime()<end)Thread.yield();
            assertTrue(gate.hasQueuedThread(waiter));waiter.interrupt();waiter.join(1000);
            assertFalse(waiter.isAlive());assertInstanceOf(java.io.InterruptedIOException.class,interrupted.get());assertTrue(interruptFlag.get());
        }finally {
            resume.countDown();if(waiter!=null&&waiter.isAlive()){waiter.interrupt();waiter.join(2000);}
            pool.shutdown();if(!pool.awaitTermination(5,TimeUnit.SECONDS)){pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        }
        assertNull(verifying.get(1,TimeUnit.SECONDS));assertNull(receiptGate(peer.file()));
    }

    private LeaseTrust pausedTrust(Path file,CountDownLatch entered,CountDownLatch resume) throws Exception {
        AtomicBoolean paused=new AtomicBoolean();
        var identity=LeaseTrust.class.getDeclaredMethod("identity",Path.class);identity.setAccessible(true);
        return LeaseTrust.verified(fixture.source.trust,path->{
            if(path.equals(file)&&paused.compareAndSet(false,true)) {
                entered.countDown();
                try{if(!resume.await(15,TimeUnit.SECONDS))throw new IOException("照合の再開期限を超えました");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException(failure);}
            }
            try{return identity.invoke(fixture.trust,path).toString();}catch(ReflectiveOperationException failure){throw new IOException(failure);}
        });
    }
    private static ReentrantLock receiptGate(Path file) throws ReflectiveOperationException {
        var entries=RuntimeLease.class.getDeclaredField("receipts");entries.setAccessible(true);
        Object entry;var map=(Map<?,?>)entries.get(null);synchronized(map){entry=map.get(file);}
        if(entry==null)return null;
        var field=entry.getClass().getDeclaredField("gate");field.setAccessible(true);
        return (ReentrantLock)field.get(entry);
    }

    private void assertLocks(String own,String other) throws Exception {
        assertLock(current.file().resolveSibling("lease.lock"),own);
        assertLock(peer.file().resolveSibling("lease.lock"),other);
    }

    private static void assertLock(Path receipt,String expected) throws Exception {
        String classpath=Path.of(ReceiptProbeProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,
                ReceiptProbeProcess.class.getName(),receipt.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(child.waitFor(5,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
            assertEquals(expected,new String(child.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).trim());
        }finally {
            if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}
            child.getInputStream().close();child.getErrorStream().close();child.getOutputStream().close();
        }
    }
}
