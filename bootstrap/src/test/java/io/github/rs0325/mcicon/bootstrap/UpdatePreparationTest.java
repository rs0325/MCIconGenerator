package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdatePreparationTest {
    @TempDir Path directory;
    private long deadline(){return System.nanoTime()+Duration.ofSeconds(5).toNanos();}
    private Bootstrap bootstrap() throws Exception {directory=directory.toRealPath();Fixture fixture=new Fixture(directory);Bootstrap b=fixture.bootstrap(directory.resolve("root"));assertEquals(0,b.launch(java.util.List.of()));return b;}
    private JsonNode pin(){ObjectNode pin=Seed.JSON.createObjectNode().put("java","a".repeat(64)).put("metadata","b".repeat(64));pin.putObject("jars").put("bootstrap.jar","c".repeat(64));return pin;}
    @Test void reservationPrecedesStartAndSecondOwnerCannotObtainPermit() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);JsonNode initial=b.state();String id=prep.reserve(initial,pin(),Instant.now(),deadline());assertNotNull(id);
        JsonNode record=UpdatePreparation.receipt(Seed.json(Seed.read(prep.path(id),65536)));assertEquals("reserved",record.path("state").asText());assertTrue(record.path("child").isNull());assertEquals(id,b.state().path("preparation").path("invocation").asText());
        assertNull(prep.reserve(initial,pin(),Instant.now(),deadline()));assertNull(prep.reserve(b.state(),pin(),Instant.now(),deadline()));
    }
    @Test void expiryWithoutReceiptLockNeverPermitsAnotherWorker() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String id=prep.reserve(b.state(),pin(),Instant.now().minusSeconds(360),deadline());JsonNode before=b.state();
        try(FileChannel channel=FileChannel.open(prep.path(id),StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock held=channel.lock()){assertNull(prep.reserve(before,pin(),Instant.now(),deadline()));assertEquals(before,b.state());}
    }
    @Test void expiredSameInodeAndStateCasFenceCreateOnlyOneFreshIntent() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String old=prep.reserve(b.state(),pin(),Instant.now().minusSeconds(360),deadline());JsonNode expired=b.state();byte[] evidence=Files.readAllBytes(prep.path(old));
        String fresh=prep.reserve(expired,pin(),Instant.now(),deadline());assertNotNull(fresh);assertNotEquals(old,fresh);assertArrayEquals(evidence,Files.readAllBytes(prep.path(old)));assertNull(prep.reserve(expired,pin(),Instant.now(),deadline()));assertEquals(fresh,b.state().path("preparation").path("invocation").asText());
    }
    @Test void missingOrPartialReceiptIsUnknownRatherThanFreshQualification() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String id=prep.reserve(b.state(),pin(),Instant.now().minusSeconds(360),deadline());JsonNode expected=b.state();Path path=prep.path(id);byte[] raw=Files.readAllBytes(path);
        Files.delete(path);assertThrows(IOException.class,()->prep.reserve(expected,pin(),Instant.now(),deadline()));assertEquals(expected,b.state());
        Files.writeString(path,"{partial",StandardOpenOption.CREATE_NEW);assertThrows(IOException.class,()->prep.reserve(expected,pin(),Instant.now(),deadline()));assertEquals(expected,b.state());assertNotEquals(0,raw.length);
    }
    @Test void changedStateCannotBeFencedFromStaleSnapshot() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);prep.reserve(b.state(),pin(),Instant.now().minusSeconds(360),deadline());JsonNode expected=b.state();ObjectNode changed=expected.deepCopy();changed.put("highestAcceptedSequence",2);b.atomic(b.root.resolve("active.json"),changed);
        assertNull(prep.reserve(expected,pin(),Instant.now(),deadline()));assertEquals(changed,b.state());
    }
    @Test void workerRequiresDurableOwnedIdentityAndConfigBeforePermission() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String id=prep.reserve(b.state(),pin(),Instant.now(),deadline());JsonNode self=Bootstrap.identity(ProcessHandle.current());assertThrows(IOException.class,()->prep.checkOwner(id,self,deadline()));
        ObjectNode state=(ObjectNode)b.state(),reservation=(ObjectNode)state.get("preparation");reservation.put("phase","owned");reservation.set("worker",self);b.atomic(b.root.resolve("active.json"),state);prep.checkOwner(id,self,deadline());
        ObjectNode other=self.deepCopy();other.put("startedAt",Instant.EPOCH.toString());assertThrows(IOException.class,()->prep.checkOwner(id,other,deadline()));prep.configure(false,deadline());assertFalse(prep.enabled());assertThrows(IOException.class,()->prep.checkOwner(id,self,deadline()));
    }
    @Test void disableAndPendingSuppressFuturePreparation() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);JsonNode expected=b.state();prep.configure(false,deadline());assertNull(prep.reserve(expected,pin(),Instant.now(),deadline()));prep.configure(true,deadline());
        ObjectNode state=(ObjectNode)b.state();state.set("pending",state.get("active"));b.atomic(b.root.resolve("active.json"),state);assertNull(prep.reserve(state,pin(),Instant.now(),deadline()));
    }
    @Test void anOldUuidCannotUseTheNewOwnersPermission() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String old=prep.reserve(b.state(),pin(),Instant.now().minusSeconds(360),deadline());String fresh=prep.reserve(b.state(),pin(),Instant.now(),deadline());ObjectNode state=(ObjectNode)b.state(),reservation=(ObjectNode)state.get("preparation");JsonNode self=Bootstrap.identity(ProcessHandle.current());reservation.put("phase","owned");reservation.set("worker",self);b.atomic(b.root.resolve("active.json"),state);
        prep.checkOwner(fresh,self,deadline());assertThrows(IOException.class,()->prep.checkOwner(old,self,deadline()));
    }
    @Test void ackReceiveHasOneAbsoluteDeadlineWithoutBlockingOnUnavailablePipe() throws Exception {
        java.io.InputStream silent=new java.io.InputStream(){public int available(){return 0;}public int read(){throw new AssertionError("available無しでreadしません");}};
        long started=System.nanoTime();assertThrows(IOException.class,()->UpdatePreparation.receive(silent,1,started+Duration.ofMillis(30).toNanos()));assertTrue(System.nanoTime()-started<Duration.ofSeconds(1).toNanos());
        java.io.ByteArrayInputStream bytes=new java.io.ByteArrayInputStream(new byte[]{1,2,3}){@Override public synchronized int available(){return Math.min(1,super.available());}};assertArrayEquals(new byte[]{1,2,3},UpdatePreparation.receive(bytes,3,deadline()));
    }
    @Test void processIdentityUsesNumericValuesInsteadOfJacksonNodeRepresentation() throws Exception {
        ObjectNode a=Seed.JSON.createObjectNode().put("pid",42L).put("startedAt",Instant.EPOCH.toString()),b=Seed.JSON.createObjectNode().put("pid",42).put("startedAt",Instant.EPOCH.toString());assertTrue(UpdatePreparation.sameProcess(a,b));b.put("pid",43);assertFalse(UpdatePreparation.sameProcess(a,b));
    }
    @Test void concurrentReservationsGiveOnlyOneFreshPermission() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);JsonNode expected=b.state();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {var a=pool.submit(()->{start.await();return prep.reserve(expected,pin(),Instant.now(),deadline());});var c=pool.submit(()->{start.await();return prep.reserve(expected,pin(),Instant.now(),deadline());});start.countDown();String one=a.get(5,java.util.concurrent.TimeUnit.SECONDS),two=c.get(5,java.util.concurrent.TimeUnit.SECONDS);assertTrue((one==null)!=(two==null));assertEquals(one==null?two:one,b.state().path("preparation").path("invocation").asText());}
        finally {pool.shutdownNow();assertTrue(pool.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void identicalBodyOnAnotherInodeCannotFenceTheOriginalLiveWorker() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String id=prep.reserve(b.state(),pin(),Instant.now().minusSeconds(360),deadline());JsonNode expected=b.state();Path path=prep.path(id);byte[] raw=Files.readAllBytes(path);Path kept=directory.resolve("held-old-receipt");
        try(FileChannel old=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock held=old.lock()) {
            Files.move(path,kept);Files.write(path,raw,StandardOpenOption.CREATE_NEW);assertArrayEquals(raw,Files.readAllBytes(path));assertThrows(IOException.class,()->prep.reserve(expected,pin(),Instant.now(),deadline()));assertEquals(expected,b.state());
        }
    }
    @Test void lockedChannelReadRetainsTheOsLockAgainstAnotherJvm() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String id=prep.reserve(b.state(),pin(),Instant.now().minusSeconds(360),deadline());Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("bootstrap.testClasspath"),ReceiptLockProcess.class.getName(),prep.path(id).toString()).redirectError(directory.resolve("lock.stderr").toFile()).start();
        try {assertArrayEquals(new byte[]{1},UpdatePreparation.receive(child.getInputStream(),1,deadline()));assertNull(prep.reserve(b.state(),pin(),Instant.now(),deadline()));child.getOutputStream().write(1);child.getOutputStream().close();assertTrue(child.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,child.exitValue());assertNotNull(prep.reserve(b.state(),pin(),Instant.now(),deadline()));}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));}child.getInputStream().close();child.getOutputStream().close();}
    }
    @Test void selfDeadlineAlreadyRunsWhilePreAckInputIsBlocked() throws Exception {
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("bootstrap.testClasspath"),PreparationDeadlineProcess.class.getName()).redirectError(directory.resolve("deadline.stderr").toFile()).start();
        try {assertArrayEquals(new byte[]{1},UpdatePreparation.receive(child.getInputStream(),1,deadline()));assertTrue(child.waitFor(7,java.util.concurrent.TimeUnit.SECONDS));assertEquals(124,child.exitValue());}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));}child.getInputStream().close();child.getOutputStream().close();}
    }
    @Test void rejectedPackageBeforeAckKeepsAReadableIdentityAndReleasesReceiptLock() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String id=prep.reserve(b.state(),pin(),Instant.now(),deadline());assertThrows(IOException.class,()->PreparationMain.run(b.installed,b.root,id));
        try(FileChannel channel=FileChannel.open(prep.path(id),StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock held=channel.tryLock()){assertNotNull(held);JsonNode record=UpdatePreparation.readReceipt(channel);assertEquals("finished",record.path("state").asText());assertEquals(1,record.path("exitCode").asInt());assertTrue(UpdatePreparation.sameProcess(Bootstrap.identity(ProcessHandle.current()),record.get("child")));}
    }
    @Test void finishedPreparationLogOutsideRunsDoesNotBlockMissingActiveRepair() throws Exception {
        Bootstrap b=bootstrap();UpdatePreparation prep=new UpdatePreparation(b);String id=prep.reserve(b.state(),pin(),Instant.now(),deadline());JsonNode self=Bootstrap.identity(ProcessHandle.current());
        try(FileChannel channel=FileChannel.open(prep.path(id),StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock held=channel.lock()){ObjectNode record=(ObjectNode)UpdatePreparation.readReceipt(channel);record.set("child",self);record.put("state","finished").put("exitCode",1).put("result","未適用");Bootstrap.write(channel,record);}
        ObjectNode state=(ObjectNode)b.state(),reservation=(ObjectNode)state.get("preparation");reservation.put("phase","owned");reservation.set("worker",self);b.atomic(b.root.resolve("active.json"),state);Path log=b.root.resolve("logs/"+id+".stderr");Files.writeString(log,"UPDATE_NOT_APPLIED: fixture failure\n",StandardOpenOption.CREATE_NEW);
        Path slot=b.root.resolve("tools/"+state.path("active").path("toolVersion").asText()+"/"+state.path("active").path("payloadSha256").asText());Files.move(slot,directory.resolve("held-slot-for-log-repair"));b.repair();assertTrue(Files.isDirectory(slot));assertEquals("UPDATE_NOT_APPLIED: fixture failure\n",Files.readString(log));
    }
}
