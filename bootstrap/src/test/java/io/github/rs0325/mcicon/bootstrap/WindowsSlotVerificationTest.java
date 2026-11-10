package io.github.rs0325.mcicon.bootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 実readerを使い、再照合でもslotの変更を拒否することを確認する。 */
class WindowsSlotVerificationTest {
    @TempDir Path directory;
    private Fixture fixture() throws Exception {
        assumeTrue(Main.host().os().equals("windows"));
        assumeTrue(System.getenv("MCICON_WINDOWS_READER_STAGE")!=null);
        return new Fixture(directory.toRealPath(),true);
    }
    private Bootstrap prepare(Fixture f) throws Exception {
        Bootstrap b=f.bootstrap(directory.toRealPath().resolve("root"));long end=Long.MAX_VALUE;
        b.directory(b.root,end);for(String name:new String[]{"archives","staging","tools/1.1.0"})b.directory(b.root.resolve(name),end);
        b.copyArchive(f.seed(),end);return b;
    }
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private long stages(Bootstrap b,Action action) throws Exception {
        Path staging=b.root.resolve("staging");
        try(WatchService watcher=staging.getFileSystem().newWatchService()) {
            staging.register(watcher,StandardWatchEventKinds.ENTRY_CREATE);
            action.run();String marker="marker-"+UUID.randomUUID();Path sentinel=staging.resolve(marker);Files.createFile(sentinel);
            long count=0;boolean found=false;
            try {
                while(!found) {
                    WatchKey key=watcher.poll(10,TimeUnit.SECONDS);assertNotNull(key,"作成通知が到着しない");
                    for(WatchEvent<?> event:key.pollEvents()) {
                        assertNotEquals(StandardWatchEventKinds.OVERFLOW,event.kind());
                        String name=event.context().toString();if(name.startsWith("slot-"))count++;if(name.equals(marker))found=true;
                    }
                    assertTrue(key.reset());
                }
            } finally {Files.delete(sentinel);}
            return count;
        }
    }
    @Test void repeatedVerificationCreatesExactlyOneReferencePerBootstrap() throws Exception {
        Fixture f=fixture();Bootstrap b=prepare(f);Seed seed=f.seed();
        assertEquals(1,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
        assertEquals(1,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
        assertEquals(0,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
        assertEquals(0,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
        Bootstrap fresh=f.bootstrap(b.root);
        assertEquals(1,stages(b,()->fresh.verifySlot(seed,Long.MAX_VALUE)));
    }
    @Test void cachedReferenceRejectsCurrentContentStructureAndArchiveChanges() throws Exception {
        Fixture f=fixture();Bootstrap b=prepare(f);Seed seed=f.seed();Path slot=b.verifySlot(seed,Long.MAX_VALUE);b.verifySlot(seed,Long.MAX_VALUE);
        Path license=slot.resolve("LICENSE");byte[] original=Files.readAllBytes(license);
        Files.writeString(license,"changed");
        assertThrows(IOException.class,()->b.verifySlot(seed,Long.MAX_VALUE));assertEquals("changed",Files.readString(license));Files.write(license,original);
        for(boolean directory:new boolean[]{false,true}) {
            Path extra=slot.resolve("extra");if(directory)Files.createDirectory(extra);else Files.createFile(extra);
            assertThrows(IOException.class,()->b.verifySlot(seed,Long.MAX_VALUE));assertTrue(Files.exists(extra));Files.delete(extra);
        }
        Files.delete(license);assertThrows(IOException.class,()->b.verifySlot(seed,Long.MAX_VALUE));assertFalse(Files.exists(license));Files.write(license,original);
        Path archive=b.root.resolve("archives/"+seed.hash+".zip");byte[] zip=Files.readAllBytes(archive);Files.write(archive,new byte[]{1});
        assertThrows(IOException.class,()->b.verifySlot(seed,Long.MAX_VALUE));assertArrayEquals(new byte[]{1},Files.readAllBytes(archive));Files.write(archive,zip);
        assertEquals(0,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
    }
    @Test void cachedReferenceRejectsHardLinksAndDoesNotShareManifestValidation() throws Exception {
        Fixture f=fixture();Bootstrap b=prepare(f);Seed seed=f.seed();Path slot=b.verifySlot(seed,Long.MAX_VALUE);b.verifySlot(seed,Long.MAX_VALUE);
        Path license=slot.resolve("LICENSE"),held=directory.resolve("held-license");Files.move(license,held);Files.createLink(license,held);
        try {assertThrows(IOException.class,()->b.verifySlot(seed,Long.MAX_VALUE));assertTrue(Files.isSameFile(license,held));}
        finally {Files.delete(license);Files.move(held,license);}
        assertEquals(0,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)f.manifest.path("platforms").get(0).get("runtimeDescriptor")).put("sha256","0".repeat(64));f.writeManifest();
        Seed incompatible=f.seed();assertEquals(seed.hash,incompatible.hash);assertNotEquals(seed.manifestHash,incompatible.manifestHash);
        assertThrows(IOException.class,()->b.verifySlot(incompatible,Long.MAX_VALUE));
    }
    @Test void missingSlotCannotBeReturnedFromTheReference() throws Exception {
        Fixture f=fixture();Bootstrap b=prepare(f);Seed seed=f.seed();Path slot=b.verifySlot(seed,Long.MAX_VALUE);b.verifySlot(seed,Long.MAX_VALUE);
        Files.move(slot,directory.resolve("held-slot"));
        assertEquals(1,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
        assertTrue(Files.isRegularFile(slot.resolve("LICENSE")));
        assertEquals(0,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
    }
    @Test void readerRejectsTheExactDeadlineBeforeStartingItsProcess() throws Exception {
        Fixture f=fixture();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        WindowsSecurityReader reader=new WindowsSecurityReader(f.installed,()->{calls.incrementAndGet();return 100;});
        IOException failure=assertThrows(IOException.class,()->reader.read(java.util.List.of(directory.toRealPath()),100));
        assertTrue(failure.getMessage().contains("期限"));assertEquals(2,calls.get());
    }
    @Test void cachedReferenceCannotBypassExpiredBudgetOrInterruption() throws Exception {
        Fixture f=fixture();Bootstrap b=prepare(f);Seed seed=f.seed();b.verifySlot(seed,Long.MAX_VALUE);b.verifySlot(seed,Long.MAX_VALUE);
        assertThrows(IOException.class,()->b.verifySlot(seed,Long.MIN_VALUE));
        Thread.currentThread().interrupt();
        try {assertThrows(IOException.class,()->b.verifySlot(seed,Long.MAX_VALUE));assertTrue(Thread.currentThread().isInterrupted());}
        finally {Thread.interrupted();}
        assertEquals(0,stages(b,()->b.verifySlot(seed,Long.MAX_VALUE)));
    }
}
