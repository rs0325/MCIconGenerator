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
    @Test void 元残予算に回収枠が無ければ初回captureもspawnしない() throws Exception {
        Commands commands=commands();long original=System.nanoTime()+Duration.ofMillis(100).toNanos();commands.end(original);
        assertEquals(original,commands.end());assertThrows(IOException.class,()->commands.capture(List.of("/fixture-facts"),Duration.ofSeconds(3)));assertEquals(original,commands.end());
        try(var files=Files.list(temporary)){assertFalse(files.anyMatch(path->path.toString().endsWith(".intent.json")));}
    }
    @Test void shell展開せずUnicodeと空白を固定argvとして渡す() throws Exception {
        Commands commands=commands();List<String> args=List.of("/fixture-command","日本語 space","$(echo forbidden)","quote\"slash\\");Commands.Result result=commands.capture(args,Duration.ofSeconds(3));assertEquals(0,result.exit());assertEquals(WindowsFiles.JSON.valueToTree(args),WindowsFiles.JSON.readTree(result.stdout()));commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode());
    }
    @Test void timeoutを有限回収し未終端recordを保持する() throws Exception {
        Commands commands=commands();assertTimeoutPreemptively(Duration.ofSeconds(4),()->assertThrows(IOException.class,()->commands.capture(List.of("/fixture-sleep"),Duration.ofMillis(600))));
        try(var files=Files.list(temporary)){assertTrue(files.anyMatch(path->path.toString().endsWith(".intent.json")));}assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));
    }
    @Test void state不在はmissingを終了証明にしない() throws Exception {
        Commands commands=commands();Path intent=temporary.resolve("native-unknown.intent.json");Files.writeString(intent,"{\"native\":\""+temporary.toRealPath().resolve("fixture guard")+"\"}");assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));
    }
    @Test void 同bootの終了済guardでもdone欠落はunknownで元recordを保つ() throws Exception {
        Commands commands=commands();commands.capture(List.of("/fixture-command"),Duration.ofSeconds(3));Path done;try(var files=Files.list(temporary)){done=files.filter(path->path.toString().endsWith(".done.json")).findFirst().orElseThrow();}Files.delete(done);
        assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));try(var files=Files.list(temporary)){assertTrue(files.anyMatch(path->path.toString().endsWith(".state.json")));}
    }
    @Test void 終了しないclientのstop待機を絶対残予算で打ち切る() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),System.nanoTime()+Duration.ofMillis(40).toNanos());
        Process stubborn=new Process(){
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}public int waitFor(){return 1;}public int exitValue(){throw new IllegalThreadStateException();}public void destroy(){}public Process destroyForcibly(){return this;}public boolean isAlive(){return true;}
            public boolean waitFor(long value,java.util.concurrent.TimeUnit unit) throws InterruptedException {long nanos=unit.toNanos(value);assertTrue(nanos<=Duration.ofMillis(40).toNanos());if(nanos>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);return false;}
        };
        assertTimeoutPreemptively(Duration.ofMillis(250),()->assertThrows(IOException.class,()->commands.stop(stubborn)));
    }
    @Test void cleanup後は繰返し呼出しでも総期限を延長しない() throws Exception {
        Commands commands=commands();commands.cleanup();Duration first=commands.left(Duration.ofSeconds(30));Thread.sleep(20);commands.cleanup();assertTrue(commands.left(Duration.ofSeconds(30)).compareTo(first)<0);
    }
    @Test void 非zero空出力は元文言とcauseなしを保持する() {
        IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(125,new byte[0],new byte[0]),"専用guestbridge load"));
        assertEquals("専用guestbridge loadが失敗しました（exit 125）",failure.getMessage());assertNull(failure.getCause());
    }
    @Test void 非zero診断はstderrを優先しnative原行を保つ() {
        String error="fixture stderr\nnativeJob phase=child-signalled childExit=0 active=1 queries=1\nnativeJob phase=terminated childExit=125 active=0 queries=2\n";
        byte[] bytes=error.getBytes(StandardCharsets.UTF_8);IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(125,"unused stdout".getBytes(StandardCharsets.UTF_8),bytes),"load"));
        assertTrue(failure.getMessage().startsWith("loadが失敗しました（exit 125）\nstderr ("));assertTrue(failure.getMessage().endsWith(error));assertFalse(failure.getMessage().contains("unused stdout"));assertArrayEquals(error.getBytes(StandardCharsets.UTF_8),bytes);
    }
    @Test void stderrが空の失敗はstdoutを診断へ残す() {
        IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(1,"fixture stdout".getBytes(StandardCharsets.UTF_8),new byte[0]),"fixture"));
        assertTrue(failure.getMessage().contains("stdout (14 bytes):\nfixture stdout"));
    }
    @Test void 大きい失敗出力でも末尾native行と固定上限を保つ() {
        String tail="\nnativeJob phase=drain-empty childExit=125 active=0 queries=1\n";byte[] bytes=("not retained prefix"+"x".repeat(10000)+tail).getBytes(StandardCharsets.UTF_8);
        IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(125,new byte[0],bytes),"load"));
        assertTrue(failure.getMessage().contains("stderr (末尾8192 / "+bytes.length+" bytes)"));assertTrue(failure.getMessage().endsWith(tail));assertFalse(failure.getMessage().contains("not retained prefix"));assertTrue(failure.getMessage().length()<9000);
    }
    @Test void UTF8境界の切断もbyte上限内の診断として残す() {
        byte[] bytes=("日本語".repeat(4000)+"末尾").getBytes(StandardCharsets.UTF_8);IOException failure=assertThrows(IOException.class,()->Commands.zero(new Commands.Result(1,new byte[0],bytes),"fixture"));
        assertTrue(failure.getMessage().endsWith(new String(bytes,bytes.length-8192,8192,StandardCharsets.UTF_8)));assertTrue(failure.getMessage().length()<9000);
    }
    @Test void 成功は出力を参照せず従来どおり返す() {
        assertDoesNotThrow(()->Commands.zero(new Commands.Result(0,null,null),"fixture"));
    }
    @Test void 自然終端が先に成立すればstopはsignalしない() throws Exception {
        Commands commands=commands();java.util.concurrent.atomic.AtomicInteger waits=new java.util.concurrent.atomic.AtomicInteger();
        Process natural=new Process(){public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public boolean isAlive(){return false;}public void destroy(){fail("自然終端にsignal不要");}public boolean waitFor(long value,java.util.concurrent.TimeUnit unit){waits.incrementAndGet();assertTrue(value>0);assertTrue(unit.toNanos(value)<=Duration.ofMillis(1750).toNanos());return true;}};
        commands.stop(natural);assertEquals(1,waits.get());
    }
    @Test void Job前の肯定拒否だけをreapしdoneを捏造しない() throws Exception {
        Commands commands=commands();Path prefix=rejected(commands);commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode());assertFalse(Files.exists(Path.of(prefix+".done.json")));assertFalse(commands.terminal(prefix));
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)WindowsFiles.json(Path.of(prefix+".rejected.json"));
        for(String field:List.of("jobCreated","childCreated")){value.put(field,true);Files.write(Path.of(prefix+".rejected.json"),WindowsFiles.JSON.writeValueAsBytes(value));assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));value.put(field,false);}
        value.put("plannedGuardExit",125);Files.write(Path.of(prefix+".rejected.json"),WindowsFiles.JSON.writeValueAsBytes(value));assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));
    }
    @Test void 拒否recordのpartial本人差argv差過大とdone併存はunknown() throws Exception {
        Commands commands=commands();Path prefix=rejected(commands),path=Path.of(prefix+".rejected.json");byte[] original=Files.readAllBytes(path);
        for(byte[] bad:List.of("{".getBytes(StandardCharsets.UTF_8),"x".repeat(4097).getBytes(StandardCharsets.UTF_8))){Files.write(path,bad);assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));}
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)WindowsFiles.JSON.readTree(original);((com.fasterxml.jackson.databind.node.ObjectNode)value.path("state")).put("pid",1);Files.write(path,WindowsFiles.JSON.writeValueAsBytes(value));assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));
        value=(com.fasterxml.jackson.databind.node.ObjectNode)WindowsFiles.JSON.readTree(original);((com.fasterxml.jackson.databind.node.ArrayNode)value.path("argv")).set(1,WindowsFiles.JSON.getNodeFactory().textNode("changed"));Files.write(path,WindowsFiles.JSON.writeValueAsBytes(value));assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));
        Files.write(path,original);Files.copy(Path.of(prefix+".state.json"),Path.of(prefix+".done.json"));assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));
    }
    @Test void 生存PIDでもimage不一致と開始時刻不明はsignalせずunknown() throws Exception {
        Commands commands=commands();Path prefix=rejected(commands),state=Path.of(prefix+".state.json");Files.delete(Path.of(prefix+".rejected.json"));var own=(com.fasterxml.jackson.databind.node.ObjectNode)WindowsFiles.json(state);long pid=ProcessHandle.current().pid(),started=ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        for(long value:List.of(started,started+1)){own.put("pid",pid).put("started",value);Files.write(state,WindowsFiles.JSON.writeValueAsBytes(own));IOException error=assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT,WindowsFiles.JSON.createArrayNode()));assertTrue(error.getMessage().contains("実所有"));assertTrue(ProcessHandle.current().isAlive());assertFalse(Files.exists(Path.of(prefix+".done.json")));}
    }
    private Path rejected(Commands commands) throws Exception {
        List<String> argv=List.of("/fixture-command","--distribution","MCIcon-"+"a".repeat(24),"--user","root","--exec","/usr/bin/python3","/usr/lib/mcicon/guestbridge.py");commands.capture(argv,Duration.ofSeconds(3));Path intent;
        try(var files=Files.list(temporary)){intent=files.filter(path->path.toString().endsWith(".intent.json")).findFirst().orElseThrow();}
        Path prefix=Path.of(intent.toString().replace(".intent.json",""));Files.delete(Path.of(prefix+".done.json"));var value=WindowsFiles.JSON.createObjectNode().put("schema",1).put("kind","reject-no-job").put("phase","before-job-create").put("mode","exec-bridge").put("reason","witness").put("jobCreated",false).put("childCreated",false).put("plannedGuardExit",1);value.set("state",WindowsFiles.json(Path.of(prefix+".state.json")));value.set("argv",WindowsFiles.JSON.valueToTree(argv));Files.write(Path.of(prefix+".rejected.json"),WindowsFiles.JSON.writeValueAsBytes(value));return prefix;
    }

}
