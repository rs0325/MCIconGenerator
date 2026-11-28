package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheStateTest {
    @TempDir Path root;
    private CacheState cache(){return new CacheState(root,"host","a".repeat(64),501,"00000000-0000-0000-0000-000000000001");}
    private ObjectNode owner(){return MacFiles.JSON.createObjectNode().put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("jobId",UUID.randomUUID().toString()).put("ownerId",UUID.randomUUID().toString());}
    @Test void 状態は一つのファイルに保存し解放後は持ち主だけを空にする() throws Exception {
        CacheState cache=cache();Files.createDirectory(root.resolve("macos"));ObjectNode state=cache.create(7168);CacheState.assign(state,owner());cache.save(state);
        assertEquals(MacFiles.JSON.readTree(MacFiles.JSON.writeValueAsBytes(state)),cache.read());assertEquals(6,state.size());assertTrue(CacheState.ownerLive(state));
        state.putNull("owner");cache.save(state);assertFalse(CacheState.ownerLive(cache.read()));
        try(var files=Files.list(root.resolve("macos"))){assertEquals(List.of(cache.file()),files.toList());}
        assertTrue(cache.anchor().toString().replace('\\','/').contains("/private/tmp/mcicon-501-"));cache.checkSocketLength();
    }
    @Test void 元processの死亡とPID再利用と再起動を分けて判定する() throws Exception {
        CacheState cache=cache();ObjectNode state=cache.create(3072),owner=owner();CacheState.assign(state,owner);assertTrue(CacheState.ownerLive(state));assertFalse(cache.previousBoot(state));
        ((ObjectNode)state.path("owner")).put("processStartedAt","2000-01-01T00:00:00Z");assertFalse(CacheState.ownerLive(state));
        ((ObjectNode)state.path("owner")).put("pid",2147483647);assertFalse(CacheState.ownerLive(state));
        state.put("bootSessionId","00000000-0000-0000-0000-000000000002");assertTrue(cache.previousBoot(state));cache.currentBoot(state);assertFalse(cache.previousBoot(state));
    }
    @Test void jobと持ち主が違えば同じPIDでも解放対象にしない() {
        ObjectNode state=cache().create(3072),owner=owner();CacheState.assign(state,owner);assertTrue(CacheState.ownedBy(state,owner));
        owner.put("ownerId",UUID.randomUUID().toString());assertFalse(CacheState.ownedBy(state,owner));
    }
    @Test void 壊れた状態は対象と削除手順を返し上書きしない() throws Exception {
        CacheState cache=cache();Files.createDirectory(root.resolve("macos"));Files.writeString(cache.file(),"{}");
        var failure=assertThrows(IOException.class,cache::read);assertTrue(failure.getMessage().contains(cache.file().toString()));assertTrue(failure.getMessage().contains(cache.anchor().toString()));assertTrue(failure.getMessage().contains("limactl delete v"));assertEquals("{}",Files.readString(cache.file()));
    }
    @Test void lifecycleLockは有限時間で使用中の対象を示す() throws Exception {
        CacheState cache=cache();try(var held=cache.lock(Duration.ofSeconds(1))) {
            var failure=assertThrows(IOException.class,()->cache.lock(Duration.ofMillis(20)));assertTrue(failure.getMessage().contains("macos-lifecycle.lock"));assertTrue(failure.getMessage().contains("再試行"));
        }
        try(var held=cache.lock(Duration.ofSeconds(1))){assertNotNull(held);}
    }
}
