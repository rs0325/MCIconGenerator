package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLeaseTest {
    @TempDir Path root;
    RuntimeStore store() throws Exception {return new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(10),(u,o,t)->{throw new AssertionError();});}
    @Test void anotherJobAndUnreleasedEvidencePreventReclaim() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();
        RuntimeLease first=RuntimeLease.create(store,descriptor),second=RuntimeLease.create(store,descriptor);
        first.released();first.close();assertFalse(RuntimeLease.idle(store,descriptor));
        second.close();assertTrue(Files.exists(second.file()));assertFalse(RuntimeLease.idle(store,descriptor));
    }
    @Test void helperMustClearResourcesAndPreserveAllOwnershipFields() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();
        try(var lease=RuntimeLease.create(store,descriptor)) {
            ObjectNode value=lease.read();((ObjectNode)value.get("resources")).put("vmId","foreign");Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));
            assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,lease::released).code());
            value.put("ownerId",UUID.randomUUID().toString());Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));
            assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,lease::read).code());
            assertFalse(RuntimeLease.idle(store,descriptor));
        }
    }
    @Test void pidAndStartMatchAreInsufficientAndUnknownLeaseIsConservative() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=RuntimeLease.create(store,descriptor);
        ObjectNode value=lease.read();assertEquals(ProcessHandle.current().pid(),value.path("pid").longValue());lease.close();
        assertFalse(RuntimeLease.idle(store,descriptor));Files.writeString(lease.file(),"{}");assertFalse(RuntimeLease.idle(store,descriptor));
    }
    @Test void releasedLeaseRequiresEveryIdentityFieldAndMatchingReceipt() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=RuntimeLease.create(store,descriptor);
        lease.released();lease.close();ObjectNode original=(ObjectNode)RuntimeDescriptor.read(Files.readAllBytes(lease.file()));
        assertTrue(RuntimeLease.idle(store,descriptor));
        for(String field:List.of("schemaVersion","jobId","ownerId","hostId","pid","processStartedAt","createdAt","workerSha256","runtimeId","toolVersion","descriptorSha256","state","resources")) {
            ObjectNode missing=original.deepCopy();missing.remove(field);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(missing));assertFalse(RuntimeLease.idle(store,descriptor),field);
        }
        for(String field:List.of("jobId","ownerId","hostId")) {
            ObjectNode changed=original.deepCopy();changed.put(field,UUID.randomUUID().toString());Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor),field);
        }
        ObjectNode changed=original.deepCopy();changed.put("pid",ProcessHandle.current().pid()+1);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor));
        changed=original.deepCopy();changed.putNull("processStartedAt");Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor));
        changed=original.deepCopy();changed.put("descriptorSha256","b".repeat(64));Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor));
        Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(original));
        for(String receipt:List.of("{}","","z".repeat(64))) {Files.writeString(lease.file().resolveSibling("lease.lock"),receipt);assertFalse(RuntimeLease.idle(store,descriptor));}
    }
    @Test void externalProcessLockIsBusyUntilOwnerReleasesIt() throws Exception {checkExternalProcess(true);}
    @Test void silentExternalProcessHasFiniteCleanup() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(6),()->checkExternalProcess(false));
    }
    private void checkExternalProcess(boolean announce) throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=RuntimeLease.create(store,descriptor);lease.released();lease.close();
        String classpath=Path.of(LeaseLockProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,LeaseLockProcess.class.getName(),lease.file().resolveSibling("lease.lock").toString(),Boolean.toString(announce)).redirectError(root.resolve("external-lock.stderr").toFile()).start();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        var output=process.inputReader();var input=process.outputWriter();
        try {
            var ready=executor.submit(output::readLine);
            if(announce) {
                assertEquals("HELD",ready.get(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse(RuntimeLease.idle(store,descriptor));
                input.write("release\n");input.flush();assertTrue(process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
            }else {
                assertThrows(java.util.concurrent.TimeoutException.class,()->ready.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertTrue(process.isAlive());
            }
        }finally {
            boolean terminated=false;
            try {
                if(process.isAlive())process.destroyForcibly();terminated=process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);assertTrue(terminated);
            }finally {
                try {if(terminated){output.close();input.close();}}
                finally {executor.shutdownNow();assertTrue(executor.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
            }
        }
        assertTrue(RuntimeLease.idle(store,descriptor));
    }
    @Test void successfulReleaseIsIdleOnlyAfterFilelockEnds() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=RuntimeLease.create(store,descriptor);
        lease.released();assertFalse(RuntimeLease.idle(store,descriptor));lease.close();assertTrue(RuntimeLease.idle(store,descriptor));
    }
}
