package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** テスト鍵の整合する将来世代を、coreの現在pinへ混入させない。 */
class AdmissionTrustTest {
    @TempDir Path temporary;
    @Test void 証拠が自己整合し署名済みでも将来世代は回収前後とも採用しない() throws Exception {
        var f=new WindowsAdmissionTest.Fixture(temporary);f.source.json.put("toolVersion","1.1.3");((ObjectNode)f.source.json.path("worker")).put("toolVersion","1.1.3");
        byte[] bytes=f.source.bytes();var future=f.descriptor(bytes,"1.1.3");f.publish(bytes);
        try(var current=RuntimeLease.create(f.store,f.candidate,f.options,Instant.now().plusSeconds(30));var other=RuntimeLease.create(f.store,future,f.options,Instant.now().plusSeconds(30))) {
            f.writeJob(current,f.candidateBytes);f.writeJob(other,bytes);
            var own=LeaseSnapshot.read(f.store.root(),current.file(),f.trust);var reference=LeaseSnapshot.read(f.store.root(),other.file(),f.trust);
            ObjectNode budget=RuntimeDescriptor.JSON.createObjectNode().put("domain","macos-vm").put("constraint","host").put("hostMiB",7168).put("requiredMiB",8192).put("availableMiB",5120);
            var rows=budget.putArray("generations");rows.addObject().put("descriptorSha256",f.candidate.digest()).put("reservedMiB",4096).put("requestedMiB",3072);rows.addObject().put("descriptorSha256",future.digest()).put("reservedMiB",4096).put("requestedMiB",3072);
            assertThrows(AdmissionEvidence.Busy.class,()->AdmissionEvidence.capacity(f.store.root(),own,List.of(reference),budget));
            var claim=AdmissionEvidence.jobReference(f.store.root(),current.read().path("jobId").asText());long pid=ProcessHandle.current().pid();String started=ProcessHandle.current().info().startInstant().orElseThrow().toString();
            assertNotNull(AdmissionEvidence.verifyJob(f.store.root(),claim,f.candidate.digest(),pid,started,f.trust,false));
            var response=new RuntimeHelper.Result("failed",List.of(new RuntimeHelper.Diagnostic("MANAGED_BUSY","fixture",false,false)));
            IOException failure=assertThrows(IOException.class,()->ManagedRuntime.requireAdmission(response,f.store,current,f.candidate,f.trust));
            assertTrue(failure.getMessage().contains("既知の署名descriptor"));assertFalse(failure instanceof AdmissionEvidence.Busy);
            byte[] receipt=new byte[64];try(var channel=java.nio.channels.FileChannel.open(current.file().resolveSibling("lease.lock"),StandardOpenOption.READ)){var buffer=java.nio.ByteBuffer.wrap(receipt);while(buffer.hasRemaining())assertTrue(channel.read(buffer)>0);}current.released();current.close();
            failure=assertThrows(IOException.class,()->AdmissionEvidence.verifyJob(f.store.root(),claim,f.candidate.digest(),pid,started,f.trust,true));assertTrue(failure.getMessage().contains("既知の署名descriptor"));assertArrayEquals(receipt,Files.readAllBytes(current.file().resolveSibling("lease.lock")));
        }
    }
}
