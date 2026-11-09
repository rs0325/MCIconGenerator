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
        claim=(ObjectNode)assertThrows(AdmissionEvidence.Busy.class,()->AdmissionEvidence.capacity(fixture.store.root(),own,List.of(other),budget,RuntimeLease::receiptChannel)).job();
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
        AdmissionEvidence.verifyJob(fixture.store.root(),claim,descriptor.digest(),ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElseThrow().toString(),fixture.trust,false,receipts);
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
