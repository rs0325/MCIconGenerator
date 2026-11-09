package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLeaseTest {
    @TempDir Path root;
    @Test void 新規receipt作成前の取消しでもleaseを完成し次回の準備へ進める() throws Exception {
        createAfterStop("cancel");
    }
    @Test void 新規receipt作成前の期限超過でもleaseを完成し次回の準備へ進める() throws Exception {
        createAfterStop("timeout");
    }
    @Test void receipt取得前のIO失敗は他者のfileを保持する() throws Exception {
        createAfterStop("io");
    }
    private void createAfterStop(String mode) throws Exception {
        var descriptor=new ManagedFixture().descriptor();CancellationToken token=new CancellationToken();
        var store=new RuntimeStore(root,true,token,Duration.ofSeconds(2),(u,o,t)->{throw new AssertionError();});
        Path jobs=store.root().resolve("jobs"),created=null;
        var field=RuntimeLease.class.getDeclaredField("receipts");field.setAccessible(true);Object receipts=field.get(null);
        FutureTask<RuntimeLease> result=new FutureTask<>(()->RuntimeLease.create(store,descriptor,ManagedOptions.defaults(),java.time.Instant.now().plusSeconds(2)));
        Thread worker=Thread.ofPlatform().unstarted(result);
        try {
            synchronized(receipts) {
                worker.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(created==null&&System.nanoTime()<end&&!result.isDone()) {
                    if(Files.isDirectory(jobs))try(var paths=Files.list(jobs)){created=paths.findFirst().orElse(null);}
                    if(created==null)Thread.yield();
                }
                assertNotNull(created,"新規job directoryへ到達しません");
                var threads=java.lang.management.ManagementFactory.getThreadMXBean();boolean blocked=false;
                while(worker.isAlive()&&System.nanoTime()<end) {
                    var waiting=threads.getThreadInfo(worker.threadId());
                    if(waiting!=null&&waiting.getLockInfo()!=null&&waiting.getLockInfo().getIdentityHashCode()==System.identityHashCode(receipts)&&waiting.getLockOwnerId()==Thread.currentThread().threadId()){blocked=true;break;}
                    Thread.yield();
                }
                assertTrue(blocked,"receipt作成前の管理入口へ到達しません");
                try(var paths=Files.list(created)){assertEquals(0,paths.count());}
                switch(mode) {
                    case "cancel"->token.cancel();
                    case "timeout"->{
                        while(true) {
                            try{store.check();}catch(java.net.http.HttpTimeoutException expired){break;}
                            Thread.sleep(1);
                        }
                    }
                    case "io"->Files.writeString(created.resolve("lease.lock"),"foreign");
                    default->throw new AssertionError(mode);
                }
            }
            if(mode.equals("io")) {
                var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));
                assertInstanceOf(FileAlreadyExistsException.class,failure.getCause());
                assertEquals("foreign",Files.readString(created.resolve("lease.lock")));
                assertFalse(Files.exists(created.resolve("lease.json")));return;
            }
            try(var lease=assertDoesNotThrow(()->result.get(5,TimeUnit.SECONDS),"取消し・期限超過でreceiptが未完成になりました")) {
                assertEquals(created,lease.file().getParent());assertEquals(64,Files.size(created.resolve("lease.lock")));
                assertEquals("active",lease.read().path("state").asText());
                if(mode.equals("cancel"))assertEquals("CANCELLED",assertThrows(McIconException.class,store::check).code());
                else assertThrows(java.net.http.HttpTimeoutException.class,store::check);
                lease.released();
            }
            var next=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
            assertTrue(RuntimeLease.idle(next,descriptor));
            try(var held=next.lock("generations");var paths=Files.list(jobs)) {
                for(Path job:paths.toList())assertNull(RuntimeLease.tryRecovery(next,descriptor,job));
            }
            try(var lease=RuntimeLease.create(next,descriptor,ManagedOptions.defaults(),java.time.Instant.now().plusSeconds(5))) {lease.released();}
            assertTrue(RuntimeLease.idle(next,descriptor));
            try(var paths=Files.list(jobs)) {
                List<Path> complete=paths.toList();assertEquals(2,complete.size());
                for(Path job:complete){assertTrue(Files.isRegularFile(job.resolve("lease.json")));assertEquals(64,Files.size(job.resolve("lease.lock")));}
            }
        }finally {
            if(worker.isAlive()){worker.interrupt();worker.join(3000);}assertFalse(worker.isAlive());
            if(result.isDone())try{RuntimeLease lease=result.get();if(lease!=null)lease.close();}catch(java.util.concurrent.ExecutionException ignored){}
        }
    }
    RuntimeStore store() throws Exception {return new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(10),(u,o,t)->{throw new AssertionError();});}
    @Test void requirementsReachLeaseAndArePartOfImmutableReceipt() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();var deadline=java.time.Instant.parse("2026-10-18T00:15:00Z");
        var options=new ManagedOptions(root,Duration.ofSeconds(10),Duration.ofSeconds(2),Duration.ofSeconds(1),6144,4096);
        try(var lease=RuntimeLease.create(store,descriptor,options,deadline)) {
            ObjectNode value=lease.read();var requirements=(ObjectNode)value.get("requirements");
            assertEquals(6144,requirements.path("containerMemoryMiB").intValue());assertEquals(4096,requirements.path("heapMemoryMiB").intValue());
            assertEquals(4,requirements.path("cpus").intValue());assertEquals(deadline.toString(),requirements.path("preparationDeadline").asText());
            requirements.put("containerMemoryMiB",8192);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));
            assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,lease::read).code());
        }
    }
    @Test void receiptFixtureSortsNestedFieldsAndKeepsLongIntegersExact() throws Exception {
        var fixture=RuntimeDescriptor.read(Files.readAllBytes(Path.of("..","schemas","managed-helper-v1.examples.json"))).path("receipt");
        ObjectNode identity=(ObjectNode)fixture.get("identity");assertEquals(9007199254740993L,identity.path("pid").longValue());
        assertEquals(fixture.path("sha256").asText(),new String(RuntimeLease.receipt(identity),java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(fixture.path("sha256").asText(),io.github.rs0325.mcicon.internal.FilesSupport.hash(fixture.path("canonicalUtf8").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ObjectNode reversed=RuntimeDescriptor.JSON.createObjectNode();var names=new ArrayList<String>();identity.path("requirements").fieldNames().forEachRemaining(names::add);Collections.reverse(names);
        for(String name:names)reversed.set(name,identity.path("requirements").get(name));identity.set("requirements",reversed);
        assertEquals(fixture.path("sha256").asText(),new String(RuntimeLease.receipt(identity),java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Test void receiptRegionIsOutsideActiveLockAndExpiredPreparationDoesNotBlockRelease() throws Exception {
        var store=store();var descriptor=new ManagedFixture().descriptor();var options=ManagedOptions.defaults();
        var lease=RuntimeLease.create(store,descriptor,options,java.time.Instant.EPOCH);
        try(lease;var channel=java.nio.channels.FileChannel.open(lease.file().resolveSibling("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE)) {
            try(var receipt=channel.tryLock(0,64,false)) {
                assertNotNull(receipt);assertEquals(64,channel.size());assertFalse(RuntimeLease.idle(store,descriptor));
            }
            lease.released();
        }
        assertTrue(RuntimeLease.idle(store,descriptor));
    }
    @Test void anotherJobAndUnreleasedEvidencePreventReclaim() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();
        RuntimeLease first=ManagedFixture.lease(store,descriptor),second=ManagedFixture.lease(store,descriptor);
        first.released();first.close();assertFalse(RuntimeLease.idle(store,descriptor));
        second.close();assertTrue(Files.exists(second.file()));assertFalse(RuntimeLease.idle(store,descriptor));
    }
    @Test void helperMustClearResourcesAndPreserveAllOwnershipFields() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();
        try(var lease=ManagedFixture.lease(store,descriptor)) {
            ObjectNode value=lease.read();((ObjectNode)value.get("resources")).put("vmId","foreign");Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));
            assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,lease::released).code());
            value.put("ownerId",UUID.randomUUID().toString());Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));
            assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,lease::read).code());
            assertFalse(RuntimeLease.idle(store,descriptor));
        }
    }
    @Test void pidAndStartMatchAreInsufficientAndUnknownLeaseIsConservative() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=ManagedFixture.lease(store,descriptor);
        ObjectNode value=lease.read();assertEquals(ProcessHandle.current().pid(),value.path("pid").longValue());lease.close();
        assertFalse(RuntimeLease.idle(store,descriptor));Files.writeString(lease.file(),"{}");assertFalse(RuntimeLease.idle(store,descriptor));
    }
    @Test void releasedLeaseRequiresEveryIdentityFieldAndMatchingReceipt() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=ManagedFixture.lease(store,descriptor);
        lease.released();lease.close();ObjectNode original=(ObjectNode)RuntimeDescriptor.read(Files.readAllBytes(lease.file()));
        assertTrue(RuntimeLease.idle(store,descriptor));
        for(String field:List.of("schemaVersion","jobId","ownerId","hostId","pid","processStartedAt","createdAt","workerSha256","runtimeId","toolVersion","descriptorSha256","requirements","state","resources")) {
            ObjectNode missing=original.deepCopy();missing.remove(field);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(missing));assertFalse(RuntimeLease.idle(store,descriptor),field);
        }
        for(String field:List.of("jobId","ownerId","hostId")) {
            ObjectNode changed=original.deepCopy();changed.put(field,UUID.randomUUID().toString());Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor),field);
        }
        ObjectNode changed=original.deepCopy();changed.put("pid",ProcessHandle.current().pid()+1);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor));
        changed=original.deepCopy();changed.putNull("processStartedAt");Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor));
        changed=original.deepCopy();changed.put("descriptorSha256","b".repeat(64));Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor));
        for(String field:List.of("containerMemoryMiB","heapMemoryMiB","cpus","preparationDeadline")) {
            changed=original.deepCopy();((ObjectNode)changed.get("requirements")).remove(field);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor),field);
        }
        changed=original.deepCopy();((ObjectNode)changed.get("requirements")).put("heapMemoryMiB",3072);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(changed));assertFalse(RuntimeLease.idle(store,descriptor));
        Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(original));
        for(String receipt:List.of("{}","","z".repeat(64))) {Files.writeString(lease.file().resolveSibling("lease.lock"),receipt);assertFalse(RuntimeLease.idle(store,descriptor));}
    }
    @Test void externalProcessLockIsBusyUntilOwnerReleasesIt() throws Exception {checkExternalProcess(true);}
    @Test void silentExternalProcessHasFiniteCleanup() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(6),()->checkExternalProcess(false));
    }
    private void checkExternalProcess(boolean announce) throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=ManagedFixture.lease(store,descriptor);lease.released();lease.close();
        String classpath=Path.of(LeaseLockProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,LeaseLockProcess.class.getName(),lease.file().resolveSibling("lease.lock").toString(),Boolean.toString(announce)).redirectError(root.resolve("external-lock.stderr").toFile()).start();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        var output=process.inputReader();var input=process.outputWriter();
        try {
            var ready=executor.submit(output::readLine);
            if(announce) {
                assertEquals("HELD",ready.get(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse(RuntimeLease.idle(store,descriptor));
                try(var receipt=java.nio.channels.FileChannel.open(lease.file().resolveSibling("lease.lock"),StandardOpenOption.READ)) {
                    var bytes=java.nio.ByteBuffer.allocate(64);while(bytes.hasRemaining())assertTrue(receipt.read(bytes)>0);assertEquals(64,receipt.size());
                }
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
    @Test void recoveryKeepsBusyAndLiveOwnerUntouched() throws Exception {
        var store=store();var descriptor=new ManagedFixture().descriptor();
        try(var lease=ManagedFixture.lease(store,descriptor)) {
            byte[] before=Files.readAllBytes(lease.file());assertNull(RuntimeLease.tryRecovery(store,descriptor,lease.file().getParent()));assertArrayEquals(before,Files.readAllBytes(lease.file()));
            lease.close();assertNull(RuntimeLease.tryRecovery(store,descriptor,lease.file().getParent()));assertArrayEquals(before,Files.readAllBytes(lease.file()));
        }
    }
    @Test void deadOwnerRecoveryRetainsLockAndRequiresOriginalEvidence() throws Exception {
        var store=store();var fixture=new ManagedFixture();byte[] inventory="fixture inventory".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for(var artifact:fixture.json.withArray("artifacts"))((ObjectNode)artifact).put("inventorySha256",io.github.rs0325.mcicon.internal.FilesSupport.hash(inventory));
        var descriptor=fixture.descriptor();var lease=ManagedFixture.lease(store,descriptor);Path file=lease.file(),job=file.getParent();lease.close();
        ManagedFixture.owner(file,ManagedFixture.deadOwner(root));Files.write(job.resolve("descriptor.json"),fixture.bytes());Files.createDirectory(job.resolve("tmp"));Files.createDirectory(job.resolve("inventory"));
        for(var artifact:descriptor.artifacts())Files.write(job.resolve(artifact.inventoryPath()),inventory);
        byte[] original=Files.readAllBytes(job.resolve("descriptor.json"));Files.writeString(job.resolve("descriptor.json"),"{}");assertThrows(McIconException.class,()->RuntimeLease.tryRecovery(store,descriptor,job));Files.write(job.resolve("descriptor.json"),original);
        Path inventoryFile=job.resolve(descriptor.artifacts().get(0).inventoryPath());Files.writeString(inventoryFile,"corrupt");assertThrows(McIconException.class,()->RuntimeLease.tryRecovery(store,descriptor,job));Files.write(inventoryFile,inventory);
        try(var recovered=RuntimeLease.tryRecovery(store,descriptor,job)) {
            assertNotNull(recovered);assertEquals(file,recovered.file());assertNull(RuntimeLease.tryRecovery(store,descriptor,job));
            Files.writeString(file.resolveSibling("lease.lock"),"0".repeat(64),StandardOpenOption.WRITE);assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,recovered::released).code());
        }
        assertEquals("active",RuntimeDescriptor.read(Files.readAllBytes(file)).path("state").asText());
        assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,()->RuntimeLease.tryRecovery(store,descriptor,job)).code());
    }
    @Test void recoveryRejectsUnknownStartWithoutClearing() throws Exception {
        var store=store();var descriptor=new ManagedFixture().descriptor();var lease=ManagedFixture.lease(store,descriptor);Path file=lease.file();lease.close();
        ObjectNode unknown=(ObjectNode)RuntimeDescriptor.read(Files.readAllBytes(file));unknown.putNull("processStartedAt");Files.write(file,RuntimeDescriptor.JSON.writeValueAsBytes(unknown));byte[] before=Files.readAllBytes(file);
        assertEquals("MANAGED_OWNERSHIP_MISMATCH",assertThrows(McIconException.class,()->RuntimeLease.tryRecovery(store,descriptor,file.getParent())).code());assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void differentGenerationAndToolAreNotRecoveredAndPidReuseNeverKillsCurrentProcess() throws Exception {
        var store=store();var descriptor=new ManagedFixture().descriptor();var lease=ManagedFixture.lease(store,descriptor);Path file=lease.file();lease.close();
        var owner=new ManagedFixture.Owner(ProcessHandle.current().pid(),java.time.Instant.EPOCH);ManagedFixture.owner(file,owner);
        assertTrue(RuntimeLease.ownerDead(owner.pid(),owner.started()));assertTrue(ProcessHandle.current().isAlive());
        ObjectNode value=(ObjectNode)RuntimeDescriptor.read(Files.readAllBytes(file));value.put("descriptorSha256","b".repeat(64));Files.write(file,RuntimeDescriptor.JSON.writeValueAsBytes(value));ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));Files.write(file.resolveSibling("lease.lock"),RuntimeLease.receipt(identity));
        byte[] before=Files.readAllBytes(file);assertNull(RuntimeLease.tryRecovery(store,descriptor,file.getParent()));assertArrayEquals(before,Files.readAllBytes(file));
        value.put("descriptorSha256",descriptor.digest()).put("toolVersion","1.0.1");Files.write(file,RuntimeDescriptor.JSON.writeValueAsBytes(value));identity=value.deepCopy();identity.remove(List.of("state","resources"));Files.write(file.resolveSibling("lease.lock"),RuntimeLease.receipt(identity));
        before=Files.readAllBytes(file);assertNull(RuntimeLease.tryRecovery(store,descriptor,file.getParent()));assertArrayEquals(before,Files.readAllBytes(file));assertTrue(ProcessHandle.current().isAlive());
    }
    @Test void successfulReleaseIsIdleOnlyAfterFilelockEnds() throws Exception {
        var descriptor=new ManagedFixture().descriptor();var store=store();RuntimeLease lease=ManagedFixture.lease(store,descriptor);
        lease.released();assertFalse(RuntimeLease.idle(store,descriptor));lease.close();assertTrue(RuntimeLease.idle(store,descriptor));
    }
}
