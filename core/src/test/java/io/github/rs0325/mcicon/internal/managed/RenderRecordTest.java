package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
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
        RuntimeLease done=lease(store),held=lease(store);
        done.released();done.close();
        held.released();
        RuntimeLease.discardReleased(store);
        assertFalse(Files.exists(done.file().getParent()));
        assertTrue(Files.exists(held.file().getParent()),"保持中のjobは消さない");
        held.close();
        RuntimeLease.discardReleased(store);
        assertFalse(Files.exists(held.file().getParent()));
    }
    @Test void 回収したjobの記録はその起動で消える() throws Exception {recoverRecord(true);}
    @Test void 回収に失敗したjobの記録は消さない() throws Exception {recoverRecord(false);}
    private void recoverRecord(boolean success) throws Exception {
        for(String os:List.of("windows","macos")) {
            Path directory=Files.createDirectory(root.resolve(os)).toRealPath();var fixture=new ManagedFixture();
            var host=new RuntimeDescriptor.Host(os,os.equals("windows")?"x86_64":"aarch64");
            ((ObjectNode)fixture.json.path("host")).put("os",host.os()).put("arch",host.arch());
            byte[] inventory="fixture inventory".getBytes(StandardCharsets.UTF_8);
            for(var artifact:fixture.json.withArray("artifacts"))((ObjectNode)artifact).put("inventorySha256",FilesSupport.hash(inventory));
            byte[] bytes=fixture.bytes();var descriptor=RuntimeDescriptor.verify(bytes,fixture.sign(bytes),fixture.trust,
                    new RuntimeDescriptor.Pin(FilesSupport.hash(bytes),"1.1.0",ManagedFixture.HASH,ManagedFixture.HASH,host,Set.of(21)));
            var store=new RuntimeStore(directory,true,new CancellationToken(),Duration.ofSeconds(10),(u,o,t)->{throw new AssertionError();});
            var lease=ManagedFixture.lease(store,descriptor);Path file=lease.file(),job=file.getParent();lease.close();
            ManagedFixture.owner(file,new ManagedFixture.Owner(ProcessHandle.current().pid(),Instant.EPOCH));
            Files.write(job.resolve("descriptor.json"),bytes);Files.createDirectory(job.resolve("tmp"));Files.createDirectory(job.resolve("inventory"));
            for(var artifact:descriptor.artifacts())Files.write(job.resolve(artifact.inventoryPath()),inventory);
            boolean windows=System.getProperty("os.name").startsWith("Windows");Path helper=directory.resolve(windows?"helper.cmd":"helper");
            String response="{\"protocolVersion\":1,\"status\":\""+(success?"ok":"failed")+"\",\"diagnostics\":[]}";
            Files.writeString(helper,windows?"@echo off\r\necho "+response+"\r\nexit /b "+(success?0:1)+"\r\n":"#!/bin/sh\nprintf '%s' '"+response+"'\nexit "+(success?0:1)+"\n");
            if(!windows)assertTrue(helper.toFile().setExecutable(true,true));
            var recovery=ManagedRuntime.class.getDeclaredMethod("recover",RuntimeStore.class,RuntimeDescriptor.class,Path.class,CancellationToken.class);recovery.setAccessible(true);
            byte[] before=Files.readAllBytes(file);
            try(var render=store.render()) {
                if(success) {
                    recovery.invoke(null,store,descriptor,helper,new CancellationToken());
                    assertFalse(Files.exists(job));try(var jobs=Files.list(directory.resolve("jobs"))){assertEquals(0,jobs.count());}
                }else {
                    var failure=assertThrows(InvocationTargetException.class,()->recovery.invoke(null,store,descriptor,helper,new CancellationToken()));
                    assertEquals("IO_FAILED",assertInstanceOf(McIconException.class,failure.getCause()).code());
                    assertArrayEquals(before,Files.readAllBytes(file));
                }
            }
        }
    }
}
