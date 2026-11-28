package io.github.rs0325.mcicon.helper.macos;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemoryTest {
    @Test void VM容量は単独の要求と搭載メモリから二GiBを引いた容量で比較する() throws Exception {
        assertEquals(3072,MacRuntime.desiredMemory(1024));assertEquals(7168,MacRuntime.desiredMemory(3072));
        assertEquals(22528,MacRuntime.memoryCapacityMiB(24L*1024*1024*1024));
        assertDoesNotThrow(()->MacRuntime.requireMemory(7168,MacRuntime.memoryCapacityMiB(24L*1024*1024*1024)));
        assertDoesNotThrow(()->MacRuntime.requireMemory(7168,MacRuntime.memoryCapacityMiB(9L*1024*1024*1024)));
        var failure=assertThrows(IOException.class,()->MacRuntime.requireMemory(7168,MacRuntime.memoryCapacityMiB(8L*1024*1024*1024)));
        assertTrue(failure.getMessage().contains("要求 7168 MiB"));assertTrue(failure.getMessage().contains("容量 6144 MiB"));assertTrue(failure.getMessage().contains("要求メモリを減らして再生成"));
        assertThrows(IOException.class,()->MacRuntime.requireMemory(22529,MacRuntime.memoryCapacityMiB(24L*1024*1024*1024)));
    }
    @Test void container一覧は完全IDを受け取り空の一覧を許す() throws Exception {
        assertEquals(List.of(),DockerTransport.ids(new byte[0]));assertEquals(List.of("a".repeat(64)),DockerTransport.ids(("a".repeat(64)+"\n").getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IOException.class,()->DockerTransport.ids("short-id".getBytes(StandardCharsets.US_ASCII)));
    }
}
