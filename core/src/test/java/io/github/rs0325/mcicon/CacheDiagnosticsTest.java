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
        Files.writeString(root.resolve("file"),"changed");
        assertEquals(java.util.List.of("file"),Diagnostics.cache(root,true,new CancellationToken()).failures());
        assertEquals("changed",Files.readString(root.resolve("file")));
        Files.delete(root.resolve("file"));
        assertEquals(java.util.List.of("file"),Diagnostics.cache(root,true,new CancellationToken()).failures());
    }
}
