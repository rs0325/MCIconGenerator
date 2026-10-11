package io.github.rs0325.mcicon.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportTemporaryCleanupTest {
    @TempDir Path directory;
    @Test void reclaimsOnlyRegularFilesInTheReservedNamespaceAndNeverFollowsLinks() throws Exception {
        Path nested=directory.resolve("assets/item");Files.createDirectories(nested);
        Files.writeString(nested.resolve(".mcicon-123.part"),"interrupted write");
        Path user=nested.resolve(".mcicon-user.part");Files.writeString(user,"user file");
        Path outside=directory.resolveSibling(directory.getFileName()+"-outside");Files.createDirectories(outside);
        try {
            Path external=outside.resolve(".mcicon-456.part");Files.writeString(external,"external file");
            Files.createSymbolicLink(nested.resolve("linked-directory"),outside);
            Files.createSymbolicLink(nested.resolve(".mcicon-789.part"),external);
            Files.createDirectory(nested.resolve(".mcicon-987.part"));
            FilesSupport.cleanupExportTemps(directory);
            assertFalse(Files.exists(nested.resolve(".mcicon-123.part")));
            assertEquals("user file",Files.readString(user));assertEquals("external file",Files.readString(external));
            assertTrue(Files.isSymbolicLink(nested.resolve(".mcicon-789.part")));
            assertTrue(Files.isDirectory(nested.resolve(".mcicon-987.part")));
        } finally {Files.deleteIfExists(outside.resolve(".mcicon-456.part"));Files.deleteIfExists(outside);}
    }
}
