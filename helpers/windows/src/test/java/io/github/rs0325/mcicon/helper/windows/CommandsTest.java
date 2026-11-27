package io.github.rs0325.mcicon.helper.windows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs({OS.MAC,OS.LINUX})
class CommandsTest {
    @TempDir Path temporary;
    Commands commands() throws Exception {
        Path root=temporary.toRealPath(),guard=root.resolve("fixture guard");String javaExecutable=Path.of(System.getProperty("java.home"),"bin/java").toString();String classpath;
        // Gradle test workerのclasspathは実クラス位置から構成する。
        List<String> paths=new ArrayList<>();for(Class<?> type:List.of(NativeFixture.class,WindowsFiles.class,com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class))paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());classpath=String.join(java.io.File.pathSeparator,paths);
        Files.writeString(guard,"#!/bin/sh\nexec '"+javaExecutable.replace("'","'\\''")+"' -cp '"+classpath.replace("'","'\\''")+"' "+NativeFixture.class.getName()+" \"$@\"\n");assertTrue(guard.toFile().setExecutable(true,true));return new Commands(guard,root,Map.of(),Long.MAX_VALUE);
    }
    @Test void shell展開せずUnicodeと空白を固定argvとして渡す() throws Exception {
        Commands commands=commands();List<String> args=List.of("/fixture-command","日本語 space","$(echo forbidden)","quote\"slash\\");Commands.Result result=commands.capture(args,Duration.ofSeconds(3));assertEquals(0,result.exit());assertEquals(WindowsFiles.JSON.valueToTree(args),WindowsFiles.JSON.readTree(result.stdout()));commands.reap(OwnershipTest.BOOT);
    }
    @Test void 同bootの終了済nativeならdone欠落でも回収できる() throws Exception {
        Commands commands=commands();commands.capture(List.of("/fixture-command"),Duration.ofSeconds(3));Path done;try(var files=Files.list(temporary)){done=files.filter(path->path.toString().endsWith(".done.json")).findFirst().orElseThrow();}Files.delete(done);
        assertDoesNotThrow(()->commands.reap(OwnershipTest.BOOT));try(var files=Files.list(temporary)){assertTrue(files.anyMatch(path->path.toString().endsWith(".state.json")));}
    }
    @Test void 終了しないclientのstop待機を二秒で打ち切る() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),System.nanoTime()+Duration.ofMillis(40).toNanos());
        Process stubborn=new Process(){
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}public int waitFor(){return 1;}public int exitValue(){throw new IllegalThreadStateException();}public void destroy(){}public Process destroyForcibly(){return this;}public boolean isAlive(){return true;}
            public boolean waitFor(long value,java.util.concurrent.TimeUnit unit) throws InterruptedException {long nanos=unit.toNanos(value);assertTrue(nanos<=Duration.ofSeconds(2).toNanos());if(nanos>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);return false;}
        };
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThrows(IOException.class,()->commands.stop(stubborn)));
    }
    @Test void cleanup後は繰返し呼出しでも総期限を延長しない() throws Exception {
        Commands commands=commands();commands.cleanup();Duration first=commands.left(Duration.ofSeconds(30));Thread.sleep(20);commands.cleanup();assertTrue(commands.left(Duration.ofSeconds(30)).compareTo(first)<0);
    }
    @Test void 非zero空出力は元文言とcauseなしを保持する() {
        IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(125,new byte[0],new byte[0]),"専用guestbridge load"));
        assertTrue(failure.getMessage().startsWith("専用guestbridge loadが失敗しました（exit 125）"));assertNull(failure.getCause());
    }
    @Test void 非zero診断はstderrを優先しnative原行を保つ() {
        String error="fixture stderr\nnativeJob phase=child-signalled childExit=0 active=1 queries=1\nnativeJob phase=terminated childExit=125 active=0 queries=2\n";
        byte[] bytes=error.getBytes(StandardCharsets.UTF_8);IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(125,"unused stdout".getBytes(StandardCharsets.UTF_8),bytes),"load"));
        assertTrue(failure.getMessage().startsWith("loadが失敗しました（exit 125）\nstderr ("));assertTrue(failure.getMessage().contains(error));assertFalse(failure.getMessage().contains("unused stdout"));assertArrayEquals(error.getBytes(StandardCharsets.UTF_8),bytes);
    }
    @Test void stderrが空の失敗はstdoutを診断へ残す() {
        IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(1,"fixture stdout".getBytes(StandardCharsets.UTF_8),new byte[0]),"fixture"));
        assertTrue(failure.getMessage().contains("stdout (14 bytes):\nfixture stdout"));
    }
    @Test void 大きい失敗出力でも末尾native行と固定上限を保つ() {
        String tail="\nnativeJob phase=drain-empty childExit=125 active=0 queries=1\n";byte[] bytes=("not retained prefix"+"x".repeat(10000)+tail).getBytes(StandardCharsets.UTF_8);
        IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(125,new byte[0],bytes),"load"));
        assertTrue(failure.getMessage().contains("stderr (末尾8192 / "+bytes.length+" bytes)"));assertTrue(failure.getMessage().contains(tail));assertFalse(failure.getMessage().contains("not retained prefix"));assertTrue(failure.getMessage().length()<9000);
    }
    @Test void UTF8境界の切断もbyte上限内の診断として残す() {
        byte[] bytes=("日本語".repeat(4000)+"末尾").getBytes(StandardCharsets.UTF_8);IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(1,new byte[0],bytes),"fixture"));
        assertTrue(failure.getMessage().contains(new String(bytes,bytes.length-8192,8192,StandardCharsets.UTF_8)));assertTrue(failure.getMessage().length()<9000);
    }
    @Test void 成功は出力を参照せず従来どおり返す() {
        assertDoesNotThrow(()->Commands.zero(new Commands.Result(0,null,null),"fixture"));
    }
    @Test void 自然終端が先に成立すればstopはsignalしない() throws Exception {
        Commands commands=commands();java.util.concurrent.atomic.AtomicInteger waits=new java.util.concurrent.atomic.AtomicInteger();
        Process natural=new Process(){public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public boolean isAlive(){return false;}public void destroy(){fail("自然終端にsignal不要");}public boolean waitFor(long value,java.util.concurrent.TimeUnit unit){waits.incrementAndGet();assertTrue(value>0);assertTrue(unit.toNanos(value)<=Duration.ofMillis(1750).toNanos());return true;}};
        commands.stop(natural);assertEquals(1,waits.get());
    }

}
