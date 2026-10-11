package io.github.rs0325.mcicon.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerSnapshotTest {
    @org.junit.jupiter.api.Test void rejectsTruncatedAgentBeforeStartingDocker() throws Exception {
        Path invalid=directory.resolve("truncated.jar");Files.write(invalid,new byte[]{80,75,3,4});
        var error=assertThrows(io.github.rs0325.mcicon.McIconException.class,()->WorkerClient.validateAgentSnapshot(invalid));
        assertEquals("WORKER_INVALID",error.code());
    }
    @TempDir Path directory;
    @Test void copiedAgentAndDigestRemainStableWhenOriginalBuildChanges() throws Exception {
        Path source=directory.resolve("build-agent.jar"),snapshot=directory.resolve("snapshot.jar");
        byte[] original={1,2,3};Files.write(source,original);
        byte[] bytes=WorkerClient.readAgent(source);String digest=FilesSupport.hash(bytes);
        FilesSupport.atomicWrite(snapshot,bytes);
        Files.write(source,new byte[]{9,8,7,6});
        assertArrayEquals(original,Files.readAllBytes(snapshot));
        assertEquals(digest,FilesSupport.hash(snapshot,"SHA-256"));
        assertNotEquals(digest,FilesSupport.hash(source,"SHA-256"));
    }
}
