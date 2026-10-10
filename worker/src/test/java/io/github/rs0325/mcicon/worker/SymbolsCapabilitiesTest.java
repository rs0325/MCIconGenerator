package io.github.rs0325.mcicon.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolsCapabilitiesTest {
    @TempDir Path directory;
    @Test void distinguishesEqualArityModelAndPoseOverloads() throws Exception {
        Path mapping=directory.resolve("mapping.txt");
        Files.write(mapping,("example.Renderer -> a:\n"+
            "    void render(example.Stack,int,int,example.Model) -> b\n"+
            "    void render(example.Pose,example.Stack,int,int) -> c\n").getBytes(StandardCharsets.UTF_8));
        Symbols symbols=new Symbols(mapping);
        assertTrue(symbols.hasMethod("example.Renderer","render",4));
        assertTrue(symbols.hasMethod("example.Renderer","render","example.Stack","int","int","example.Model"));
        assertTrue(symbols.hasMethod("example.Renderer","render","example.Pose","example.Stack","int","int"));
        assertFalse(symbols.hasMethod("example.Renderer","render","example.Pose","example.Stack","int","float"));
    }
}
