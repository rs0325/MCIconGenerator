package io.github.rs0325.mcicon.helper.macos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VmMemoryTest {
    private long memory(String yaml) throws IOException {return VmBudget.memoryMiB(yaml.getBytes(StandardCharsets.UTF_8));}
    @Test void 実体設定のmemoryを記録と同じMiBで解釈する() throws Exception {
        assertEquals(9216,memory("arch: aarch64\nmemory: 9GiB\n"));assertEquals(9216,memory("memory: \"9GiB\"\n"));assertEquals(7168,memory("memory: '7168MiB' # generated\n"));
        assertEquals(9216,memory("memory: 9663676416\n"));assertEquals(7680,memory("memory: 7.5GiB\n"));
    }
    @Test void 生成入力だけ小さく整合させても実体容量との差を隠せない() throws Exception {
        assertEquals(3072,memory("memory: 3072MiB\n"));assertEquals(9216,memory("memory: 9GiB\n"));assertNotEquals(memory("memory: 3072MiB\n"),memory("memory: 9GiB\n"));
        for(String yaml:new String[]{"arch: aarch64","memory: 1GiB\nmemory: 2GiB","memory: *alias","memory: -1GiB","memory: 1KiB","memory: 999999999999999999999TiB"})assertThrows(IOException.class,()->memory(yaml));
    }
}
