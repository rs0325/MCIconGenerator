package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheDiagnosticsTest {
    @TempDir Path root;
    @Test void verificationFindsTamperingAndMissingInputsWithoutDeletingThem() throws Exception {
        byte[] input="original".getBytes();
        Files.write(root.resolve("file"),input);Files.writeString(root.resolve("file.sha256"),FilesSupport.hash(input));
        assertEquals(1,Diagnostics.cache(root,true,new CancellationToken()).verified());
        Files.writeString(root.resolve("file.sha256"),"incorrect checksum");
        assertEquals(java.util.List.of("file"),Diagnostics.cache(root,true,new CancellationToken()).failures());
        Files.writeString(root.resolve("file.sha256"),FilesSupport.hash(input));
        Files.writeString(root.resolve("file"),"changed");
        assertEquals(java.util.List.of("file"),Diagnostics.cache(root,true,new CancellationToken()).failures());
        assertEquals("changed",Files.readString(root.resolve("file")));
        Files.delete(root.resolve("file"));
        assertEquals(java.util.List.of("file"),Diagnostics.cache(root,true,new CancellationToken()).failures());
    }
    @Test void verificationResolvesNestedCacheFilesAcrossPathSeparators() throws Exception {
        Path nested=Files.createDirectories(root.resolve("nested/cache"));
        byte[] input="nested original".getBytes();
        Files.write(nested.resolve("file"),input);
        Files.writeString(nested.resolve("file.sha256"),FilesSupport.hash(input));

        CacheReport report=Diagnostics.cache(root,true,new CancellationToken());
        assertEquals(2,report.files());
        assertEquals(1,report.verified());
        assertTrue(report.failures().isEmpty());
    }
    @Test void verificationDoesNotFollowSymlinkDirectories() throws Exception {
        Files.createSymbolicLink(root.resolve("linked"),Path.of(System.getProperty("java.home")));

        CacheReport report=Diagnostics.cache(root,true,new CancellationToken());
        assertEquals(0,report.files());
        assertEquals(0,report.verified());
        assertTrue(report.failures().isEmpty());
    }
}
