package io.github.rs0325.mcicon.helper.macos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeRepairTest {
    @TempDir Path temporary;
    @Test void 自己整合した未対応世代へ操作権限を与えず元leaseを保持する() throws Exception {
        var fixture=new MacRuntimeTest.Fixture(temporary.toRealPath());var job=fixture.job(true);byte[] original=Files.readAllBytes(job.command().lease());
        assertThrows(IOException.class,()->RuntimeRepair.run(new String[]{"--repair","--runtime-root",fixture.root.toString(),"--job",job.command().lease().getParent().getFileName().toString()},fixture.executable));
        assertArrayEquals(original,Files.readAllBytes(job.command().lease()));assertFalse(Files.exists(job.command().lease().getParent().resolve("tmp/macos-operations")));assertFalse(Files.exists(fixture.cache().anchor()));
    }
}
