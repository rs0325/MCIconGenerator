package io.github.rs0325.mcicon.helper;

import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {
    @TempDir Path temporary;
    private Path cache,agent,stage;
    private List<String> arguments;
    @BeforeEach void setup() throws Exception {
        Path root=temporary.toRealPath();cache=root.resolve("cache");Files.createDirectories(cache.resolve("worker-one/game"));agent=cache.resolve("worker-one/worker-agent.jar");Files.writeString(agent,"agent");
        Files.writeString(cache.resolve("worker-one/agent.json"),"{\"version\":\"fixture\",\"mappings\":\"/mcicon/cache/versions/fixture/mappings.txt\"}");Files.writeString(cache.resolve("worker-one/game/options.txt"),"soundCategory_master:0.0\n");
        Files.createDirectories(cache.resolve("versions/fixture/natives-linux"));Files.writeString(cache.resolve("versions/fixture/natives-linux/libfixture.so"),"native");Files.writeString(cache.resolve("versions/fixture/client.jar"),"client");Files.writeString(cache.resolve("versions/fixture/mappings.txt"),"mappings");
        Files.createDirectories(cache.resolve("assets/indexes"));Files.createDirectories(cache.resolve("assets/objects/aa"));Files.writeString(cache.resolve("assets/objects/aa/"+"a".repeat(40)),"asset");
        Files.writeString(cache.resolve("assets/indexes/fixture.json"),"{\"objects\":{\"fixture.png\":{\"hash\":\""+"a".repeat(40)+"\",\"size\":5},\"ignored.ogg\":{\"hash\":\""+"b".repeat(40)+"\",\"size\":99}}}");
        Files.writeString(cache.resolve("unrelated-secret"),"excluded");stage=root.resolve("staged-cache");arguments=List.of("java","-Djava.library.path=/mcicon/cache/versions/fixture/natives-linux","-javaagent:/mcicon/worker.jar=/mcicon/cache/worker-one/agent.json","-cp","/mcicon/cache/versions/fixture/client.jar","main","--gameDir","/mcicon/cache/worker-one/game","--assetsDir","/mcicon/cache/assets","--assetIndex","fixture");
    }
    @Test void copiesOnlyReferencedInputsAndPublishesCompletedJobOutput() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));assertFalse(Files.exists(stage.resolve("unrelated-secret")));assertFalse(Files.exists(stage.resolve("assets/objects/bb")));assertEquals("asset",Files.readString(stage.resolve("assets/objects/aa/"+"a".repeat(40))));
        Path output=snapshot.output("/mcicon/cache/worker-one/icon.png");Files.writeString(stage.resolve(output),"png");assertFalse(Files.exists(cache.resolve(output)));snapshot.publish(output);assertEquals("png",Files.readString(cache.resolve(output)));assertThrows(IOException.class,()->snapshot.output("/mcicon/cache/worker-other/icon.png"));
    }
    @Test void rejectsMissingAssetAndInputLinkWithoutCopyingOtherCache() throws Exception {
        Files.delete(cache.resolve("versions/fixture/client.jar"));Files.createSymbolicLink(cache.resolve("versions/fixture/client.jar"),cache.resolve("unrelated-secret"));assertThrows(IOException.class,()->Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent)));
    }
    @Test void agentHashAndChangedOutputParentAreRejected() throws Exception {
        assertThrows(IOException.class,()->Snapshot.create(cache,agent,stage,arguments,"0".repeat(64)));
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));Path output=snapshot.output("/mcicon/cache/worker-one/icon.png");Files.writeString(stage.resolve(output),"png");Path moved=cache.resolve("moved");Files.move(cache.resolve("worker-one"),moved);Files.createSymbolicLink(cache.resolve("worker-one"),moved);assertThrows(IOException.class,()->snapshot.publish(output));
    }
    @Test void 応答の元bytesとdelimiterを保ちPNG同期後に転送する() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));Path output=snapshot.output("/mcicon/cache/worker-one/icon.png");Files.writeString(stage.resolve(output),"png");
        String ready="{ \"type\": \"ready\", \"protocolVersion\": 1 }\r\n";
        String result="{\"requestId\":\"one\",\"type\":\"result\",\"result\":{\"path\":\"/mcicon/cache/worker-one/icon.png\"}}\n";
        Queue<WorkerProxy.Pending> pending=new ArrayDeque<>();pending.add(new WorkerProxy.Pending("one","/mcicon/cache/worker-one/icon.png",output));
        ByteArrayOutputStream received=new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes,int offset,int length) {if(new String(bytes,offset,length,java.nio.charset.StandardCharsets.UTF_8).contains("requestId"))assertTrue(Files.exists(cache.resolve(output)));super.write(bytes,offset,length);}
        };
        byte[] original=(ready+result).getBytes(java.nio.charset.StandardCharsets.UTF_8);WorkerProxy.relay(new ByteArrayInputStream(original),received,pending,snapshot);assertArrayEquals(original,received.toByteArray());assertEquals("png",Files.readString(cache.resolve(output)));
    }
    @Test void 未完成応答と順序違反では部分PNGを公開しない() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));Path output=snapshot.output("/mcicon/cache/worker-one/icon.png");Files.writeString(stage.resolve(output),"partial");
        String ready="{\"type\":\"ready\",\"protocolVersion\":1}\n";
        for(String response:List.of("{\"type\":\"result\",\"requestId\":\"other\"}\n","{\"type\":\"result\",\"requestId\":\"one\"}","")) {
            Queue<WorkerProxy.Pending> pending=new ArrayDeque<>();pending.add(new WorkerProxy.Pending("one","/mcicon/cache/worker-one/icon.png",output));
            assertThrows(IOException.class,()->WorkerProxy.relay(new ByteArrayInputStream((ready+response).getBytes()),new ByteArrayOutputStream(),pending,snapshot));assertFalse(Files.exists(cache.resolve(output)));
        }
        assertThrows(IOException.class,()->snapshot.output("/mcicon/cache/worker-one/../worker-two/icon.png"));
    }
    @Test void workerエラーでは部分PNGを公開しない() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));Path output=snapshot.output("/mcicon/cache/worker-one/icon.png");Files.writeString(stage.resolve(output),"partial");
        String response="{\"type\":\"ready\",\"protocolVersion\":1}\n{\"type\":\"error\",\"requestId\":\"one\"}\n";
        Queue<WorkerProxy.Pending> pending=new ArrayDeque<>();pending.add(new WorkerProxy.Pending("one","/mcicon/cache/worker-one/icon.png",output));ByteArrayOutputStream received=new ByteArrayOutputStream();WorkerProxy.relay(new ByteArrayInputStream(response.getBytes()),received,pending,snapshot);assertEquals(response,received.toString());assertFalse(Files.exists(cache.resolve(output)));
    }
    @Test void 初期agent照合後の置換はsnapshotへ公開しない() throws Exception {
        String expected=SnapshotTestHash.hash(agent);byte[] original=Files.readAllBytes(agent);Files.writeString(agent,"replaced-agent");Path target=temporary.resolve("agent-copy.jar");
        assertThrows(IOException.class,()->Snapshot.copy(agent,target,expected));assertFalse(Files.exists(target));assertEquals("replaced-agent",Files.readString(agent));assertFalse(Arrays.equals(original,Files.readAllBytes(agent)));
    }
    @Test void 不正pathは子のstdinEOF自然終了より先に輸送失敗へ記録する() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));InputStream original=System.in;PrintStream stdout=System.out;
        try {
            for(String path:List.of("/mcicon/cache/worker-one/\0icon.png","/outside/icon.png")) {
                ObjectNode request=HelperProtocol.JSON.createObjectNode().put("command","render").put("requestId","one").put("path",path);
                System.setIn(new ByteArrayInputStream((HelperProtocol.JSON.writeValueAsString(request)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));System.setOut(new PrintStream(new ByteArrayOutputStream()));
                EofProcess child=new EofProcess();assertThrows(IOException.class,()->WorkerProxy.transfer(child,snapshot,process->{process.destroy();if(process.isAlive())throw new IOException("fixture未終端");}));assertEquals(0,child.exitValue());
            }
        }finally {System.setIn(original);System.setOut(stdout);}
    }
    @Test void protocol失敗を有限stop失敗が上書きしない() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));InputStream original=System.in;
        Process child=new Process(){
            public OutputStream getOutputStream(){return new ByteArrayOutputStream();}public InputStream getInputStream(){return new ByteArrayInputStream("{\"type\":\"result\"}\n".getBytes());}public InputStream getErrorStream(){return InputStream.nullInputStream();}public int waitFor(){return 1;}public int exitValue(){throw new IllegalThreadStateException();}public boolean isAlive(){return true;}public void destroy(){}
        };
        try {System.setIn(InputStream.nullInputStream());IOException failure=assertThrows(IOException.class,()->WorkerProxy.transfer(child,snapshot,process->{throw new IOException("fixture-cleanup");}));assertTrue(failure.getMessage().contains("ready"));assertEquals(1,failure.getSuppressed().length);assertEquals("fixture-cleanup",failure.getSuppressed()[0].getMessage());}
        finally{System.setIn(original);}
    }
    @Test void 入力失敗は既stopperで同期relayを解除し元causeを残す() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));InputStream original=System.in;PrintStream stdout=System.out;
        PipedInputStream input=new PipedInputStream();PipedOutputStream childOutput=new PipedOutputStream(input);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        Process child=new Process(){boolean ended;public OutputStream getOutputStream(){return new ByteArrayOutputStream();}public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public boolean isAlive(){return !ended;}public void destroy(){throw new AssertionError("direct destroy禁止");}};
        IOException source=new IOException("fixture input failure");
        try {System.setIn(new InputStream(){public int read() throws IOException {throw source;}});System.setOut(new PrintStream(new ByteArrayOutputStream()));
            IOException actual=assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->assertThrows(IOException.class,()->WorkerProxy.transfer(child,snapshot,process->{calls.incrementAndGet();childOutput.close();throw new IOException("fixture finite cleanup");})));
            assertSame(source,actual);assertTrue(calls.get()>=1);assertTrue(Arrays.stream(actual.getSuppressed()).anyMatch(error->error.getMessage().equals("fixture finite cleanup")));
        }finally {System.setIn(original);System.setOut(stdout);input.close();childOutput.close();}
    }
    @Test void stdinclose失敗もdirectdestroyせず既stopperへ渡す() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));InputStream original=System.in;PrintStream stdout=System.out;
        PipedInputStream input=new PipedInputStream();PipedOutputStream childOutput=new PipedOutputStream(input);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();IOException source=new IOException("fixture close failure");
        Process child=new Process(){public OutputStream getOutputStream(){return new ByteArrayOutputStream(){public void close() throws IOException {throw source;}};}public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public boolean isAlive(){return false;}public void destroy(){throw new AssertionError("direct destroy禁止");}};
        try {System.setIn(InputStream.nullInputStream());System.setOut(new PrintStream(new ByteArrayOutputStream()));IOException actual=assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->assertThrows(IOException.class,()->WorkerProxy.transfer(child,snapshot,process->{calls.incrementAndGet();childOutput.close();})));assertSame(source,actual);assertEquals(1,calls.get());}
        finally {System.setIn(original);System.setOut(stdout);input.close();childOutput.close();}
    }
    @Test void pending後stdinwrite失敗はrelay未完と回収失敗をsuppressedへ保つ() throws Exception {
        Snapshot snapshot=Snapshot.create(cache,agent,stage,arguments,SnapshotTestHash.hash(agent));InputStream original=System.in;PrintStream stdout=System.out;
        PipedInputStream input=new PipedInputStream();PipedOutputStream childOutput=new PipedOutputStream(input);IOException source=new IOException("fixture pending write failure");java.util.concurrent.CountDownLatch inputEnded=new java.util.concurrent.CountDownLatch(1);
        Process child=new Process(){public OutputStream getOutputStream(){return new OutputStream(){public void write(int value)throws IOException{throw source;}public void close(){inputEnded.countDown();}};}public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public boolean isAlive(){return false;}public void destroy(){throw new AssertionError("direct destroy禁止");}};
        try {System.setIn(new ByteArrayInputStream("{\"command\":\"catalog\",\"requestId\":\"one\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));System.setOut(new PrintStream(new ByteArrayOutputStream()));
            IOException actual=assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->assertThrows(IOException.class,()->WorkerProxy.transfer(child,snapshot,process->{childOutput.close();throw new IOException("fixture cleanup failure");})));
            assertSame(source,actual);assertTrue(Arrays.stream(actual.getSuppressed()).anyMatch(error->error.getMessage().equals("worker応答が未完了です")));
            assertTrue(inputEnded.await(1,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(Arrays.stream(actual.getSuppressed()).anyMatch(error->error.getMessage().equals("fixture cleanup failure")));
        }finally {System.setIn(original);System.setOut(stdout);input.close();childOutput.close();}
    }
    private static final class EofProcess extends Process {
        private final PipedInputStream stdout=new PipedInputStream();
        private final PipedOutputStream childOutput;
        private volatile boolean ended;
        EofProcess() throws IOException {childOutput=new PipedOutputStream(stdout);}
        @Override public OutputStream getOutputStream() {return new ByteArrayOutputStream(){@Override public void close() throws IOException {ended=true;childOutput.close();}};}
        @Override public InputStream getInputStream(){return stdout;}
        @Override public InputStream getErrorStream(){return InputStream.nullInputStream();}
        @Override public int waitFor(){return 0;}
        @Override public int exitValue(){if(!ended)throw new IllegalThreadStateException();return 0;}
        @Override public boolean isAlive(){return !ended;}
        @Override public void destroy(){}
    }
    private static final class SnapshotTestHash {
        static String hash(Path path) throws Exception {return hash(Files.readAllBytes(path));}
        static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    }
}
