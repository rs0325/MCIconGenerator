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

    @Test void releaseのphaseは共通期限とintentの完了印へ結合する() throws Exception {
        Path directory=temporary.resolve(UUID.randomUUID().toString());Files.createDirectory(directory);
        var operation=new OperationJournal.Operation(directory,"a".repeat(64));
        Process client=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){return true;}
            public int exitValue(){return 0;}
            public void destroy(){}
            public boolean isAlive(){return false;}
        };
        Commands commands=new Commands(temporary.resolve("guard"),Map.of(),temporary) {
            @Override Process launch(ProcessBuilder builder) throws java.io.IOException {
                Files.writeString(operation.completion(),operation.sha256()+"\n");return client;
            }
        };
        long deadline=System.nanoTime()+Duration.ofSeconds(9).toNanos();commands.cleanupDeadline(deadline);
        assertEquals(0,commands.recover(List.of(temporary.resolve("client").toString()),Duration.ofSeconds(9),operation).exit());
        List<com.fasterxml.jackson.databind.JsonNode> events=new ArrayList<>();
        try(var files=Files.newDirectoryStream(temporary,"phase-*.jsonl")) {
            for(Path file:files)for(String line:Files.readAllLines(file))events.add(MacFiles.JSON.readTree(line));
        }
        assertEquals(List.of("client-start","client-started","client-ended","completion-present"),events.stream().map(e->e.path("phase").asText()).toList());
        for(var event:events) {
            assertEquals(deadline,event.path("deadlineNanos").asLong());
            assertEquals(directory.getFileName().toString(),event.path("attempt").asText());
            assertFalse(event.toString().contains(temporary.toString()));
        }
        assertTrue(events.getFirst().path("budgetNanos").asLong()>0);
        assertTrue(events.getFirst().path("budgetNanos").asLong()<=Duration.ofSeconds(9).toNanos()-Commands.GUARD_RESERVE_NANOS);
    }

    @Test void stopの実効残量は二秒のguard回収と後段照合を残して六秒clientへ渡す() throws Exception {
        long[] granted={0};
        Process client=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){granted[0]=unit.toNanos(timeout);return granted[0]>=Duration.ofSeconds(6).toNanos();}
            public int exitValue(){return 0;}
            public void destroy(){}
            public boolean isAlive(){return false;}
        };
        Commands commands=new Commands(temporary.resolve("guard"),Map.of(),temporary){@Override Process launch(ProcessBuilder builder){return client;}};
        long deadline=System.nanoTime()+Duration.ofMillis(8500).toNanos();commands.cleanupDeadline(deadline);
        Duration stopWait=Duration.ofNanos(deadline-System.nanoTime()-Commands.GUARD_RESERVE_NANOS-Duration.ofMillis(150).toNanos());
        assertEquals(0,commands.capture(List.of(temporary.resolve("client").toString()),stopWait).exit());
        assertTrue(granted[0]>=Duration.ofSeconds(6).toNanos());
        assertTrue(granted[0]<=Duration.ofMillis(6100).toNanos());
        assertTrue(deadline-System.nanoTime()>Commands.GUARD_RESERVE_NANOS);
    }
    @Test void stop終端計算後の準備が遅れても停止後照合の百五十msを残す() throws Exception {
        Path directory=temporary.resolve(UUID.randomUUID().toString());Files.createDirectory(directory);
        var operation=new OperationJournal.Operation(directory,"a".repeat(64));
        long[] observedEnd={0},launchAt={0};
        Process client=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){
                observedEnd[0]=launchAt[0]+unit.toNanos(timeout);return true;
            }
            public int exitValue(){return 0;}
            public void destroy(){}
            public boolean isAlive(){return false;}
        };
        Commands commands=new Commands(temporary.resolve("guard"),Map.of(),temporary){@Override Process launch(ProcessBuilder builder){launchAt[0]=System.nanoTime();return client;}};
        long cleanupEnd=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        commands.cleanupDeadline(cleanupEnd);
        long stopEnd=cleanupEnd-Commands.GUARD_RESERVE_NANOS-Duration.ofMillis(150).toNanos();
        List<String> arguments=new AbstractList<>() {
            boolean delayed;
            public int size(){return 1;}
            public String get(int index){
                if(!delayed) {
                    delayed=true;
                    try{Thread.sleep(200);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
                }
                return temporary.resolve("client").toString();
            }
        };
        long preparationStarted=System.nanoTime();
        assertEquals(0,commands.recoverUntil(arguments,stopEnd,operation).exit());
        assertTrue(System.nanoTime()-preparationStarted>=Duration.ofMillis(150).toNanos());
        assertTrue(observedEnd[0]>0);
        // 起動時刻を基準に、clientへ渡した待機枠の上限を確認する。
        assertTrue(observedEnd[0]<=stopEnd);
        assertTrue(cleanupEnd-observedEnd[0]>=Commands.GUARD_RESERVE_NANOS+Duration.ofMillis(150).toNanos());
    }

    @Test void client起動に費やした時間を待機期限へ足し戻さない() throws Exception {
        long[] granted={0};
        Process client=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){granted[0]=unit.toNanos(timeout);return true;}
            public int exitValue(){return 0;}
            public void destroy(){}
            public boolean isAlive(){return false;}
        };
        Commands commands=new Commands(temporary.resolve("guard"),Map.of(),temporary) {
            @Override Process launch(ProcessBuilder builder) throws java.io.IOException {
                try{Thread.sleep(150);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
                return client;
            }
        };
        assertEquals(0,commands.capture(List.of(temporary.resolve("client").toString()),Duration.ofMillis(300)).exit());
        assertTrue(granted[0]>0&&granted[0]<=Duration.ofMillis(180).toNanos());
    }

    @Test void 起動中に期限を超えたclientは終了済みでも成功へ読み替えない() throws Exception {
        boolean[] waited={false};
        Process client=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){waited[0]=true;return true;}
            public int exitValue(){return 0;}
            public void destroy(){}
            public boolean isAlive(){return false;}
        };
        Commands commands=new Commands(temporary.resolve("guard"),Map.of(),temporary) {
            @Override Process launch(ProcessBuilder builder) throws java.io.IOException {
                try{Thread.sleep(150);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
                return client;
            }
        };
        var failure=assertThrows(java.io.IOException.class,()->commands.capture(List.of(temporary.resolve("client").toString()),Duration.ofMillis(50)));
        assertEquals("client操作の期限を超えました",failure.getMessage());assertFalse(waited[0]);
    }
}
