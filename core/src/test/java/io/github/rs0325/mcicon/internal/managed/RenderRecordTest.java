package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RenderRecordTest {
    @TempDir Path root;
    private RuntimeStore store(Duration timeout) throws Exception {
        return new RuntimeStore(root,true,new CancellationToken(),timeout,(u,o,t)->{throw new AssertionError();});
    }
    private RuntimeLease lease(RuntimeStore store) throws Exception {
        return RuntimeLease.create(store,new ManagedFixture().descriptor(),ManagedOptions.defaults(),Instant.now().plusSeconds(10));
    }
    @Test void 描画中は次の描画が待ち期限切れでPIDを示して止まる() throws Exception {
        try(var held=store(Duration.ofSeconds(10)).render()) {
            var waiting=Executors.newSingleThreadExecutor().submit(()->store(Duration.ofMillis(200)).render());
            var failure=assertInstanceOf(McIconException.class,assertThrows(ExecutionException.class,waiting::get).getCause());
            assertEquals("MANAGED_BUSY",failure.code());
            assertTrue(failure.getMessage().contains("PID "+ProcessHandle.current().pid()),failure.getMessage());
        }
        try(var next=store(Duration.ofSeconds(2)).render()){assertNotNull(next);}
    }
    @Test void 解放済みjobはclose後に記録を消し未解放は残す() throws Exception {
        var store=store(Duration.ofSeconds(10));
        RuntimeLease released=lease(store),active=lease(store);
        released.released();released.close();released.discard();active.close();active.discard();
        assertFalse(Files.exists(released.file().getParent()));
        assertTrue(Files.exists(active.file().getParent()));
    }
    @Test void 起動時は解放済みで誰も保持しない記録だけを消す() throws Exception {
        var store=store(Duration.ofSeconds(10));
        RuntimeLease done=lease(store),rebooted=lease(store),held=lease(store);
        done.released();done.close();
        rebooted.released();rebooted.close();
        Path tmp=rebooted.file().resolveSibling("tmp");Files.createDirectories(tmp);Files.writeString(tmp.resolve("macos-ended-by-reboot.json"),"{}");
        held.released();
        RuntimeLease.discardReleased(store);
        assertFalse(Files.exists(done.file().getParent()));
        assertTrue(Files.exists(rebooted.file().getParent()),"旧diskの回収前は残す");
        assertTrue(Files.exists(held.file().getParent()),"保持中のjobは消さない");
        Files.writeString(tmp.resolve("macos-reboot-cleanup.json"),"{}");held.close();
        RuntimeLease.discardReleased(store);
        assertFalse(Files.exists(rebooted.file().getParent()));
        assertFalse(Files.exists(held.file().getParent()));
    }
}
