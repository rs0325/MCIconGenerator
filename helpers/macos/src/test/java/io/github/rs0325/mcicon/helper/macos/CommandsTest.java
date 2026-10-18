package io.github.rs0325.mcicon.helper.macos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {
    @TempDir Path temporary;
    @Test void 遅いprobeも回収余裕を予約して総期限で打ち切る() throws Exception {
        Path guard=temporary.resolve("guard");Files.writeString(guard,"#!/bin/sh\nexec /bin/sleep 2\n");guard.toFile().setExecutable(true,true);
        Commands commands=new Commands(guard,Map.of(),temporary);long started=System.nanoTime();commands.cleanupDeadline(started+Duration.ofMillis(3150).toNanos());
        assertThrows(java.io.IOException.class,()->HostFacts.read(Path.of("/fixture/probe"),commands,MacFiles.JSON.createObjectNode(),"00000000-0000-0000-0000-000000000001"));
        assertTrue(System.nanoTime()-started<Duration.ofSeconds(2).toNanos());
    }
    @Test void 回収枠が残らなければclientを開始しない() throws Exception {
        Path marker=temporary.resolve("started"),guard=temporary.resolve("guard");Files.writeString(guard,"#!/bin/sh\n/usr/bin/touch '"+marker+"'\n");guard.toFile().setExecutable(true,true);
        Commands commands=new Commands(guard,Map.of(),temporary);commands.cleanupDeadline(System.nanoTime()+Duration.ofMillis(50).toNanos());assertThrows(java.io.IOException.class,()->commands.capture(List.of("/fixture/client"),Duration.ofSeconds(5)));assertFalse(Files.exists(marker));
    }
    @Test void client期限と回収が両方失敗しても元例外を保持する() throws Exception {
        Process client=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){return false;}
            public int exitValue(){throw new IllegalThreadStateException();}
            public void destroy(){}
            public boolean isAlive(){return true;}
        };
        Commands commands=new Commands(Path.of("/fixture/guard"),Map.of(),temporary){@Override Process launch(ProcessBuilder builder){return client;}};
        commands.cleanupDeadline(System.nanoTime()+Duration.ofSeconds(4).toNanos());
        var failure=assertThrows(java.io.IOException.class,()->commands.capture(List.of("/fixture/client"),Duration.ofSeconds(5)));
        assertEquals("client操作の期限を超えました",failure.getMessage());assertEquals(1,failure.getSuppressed().length);assertEquals("client guardの有限回収を確認できません",failure.getSuppressed()[0].getMessage());
    }
}
