package io.github.rs0325.mcicon.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class StartupControlTest {
    @TempDir Path directory;
    @Test void rejectsOldBootstrapProtocolsBeforeConnecting() throws Exception {
        Path cli=jar();
        try {for(int protocol:new int[]{2,3}){property(context(1).put("protocol",protocol));assertTrue(assertThrows(IOException.class,()->StartupControl.open(request(),"1.1.0",cli)).getMessage().contains("protocol"));}}
        finally {System.clearProperty(StartupControl.PROPERTY);}
    }
    static final class BlockedSocket extends java.net.Socket {
        final CountDownLatch entered=new CountDownLatch(1),closed=new CountDownLatch(1);
        @Override public OutputStream getOutputStream(){return new OutputStream(){@Override public void write(int value) throws IOException {entered.countDown();try{closed.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException();}throw new java.net.SocketException("closed");}};}
        @Override public void close(){closed.countDown();}
    }
    @Test void cancelledSuccessfulWriteWatcherDoesNotCloseSocket() throws Exception {
        BlockedSocket socket=new BlockedSocket();Thread guard=Thread.ofPlatform().start(()->{Thread.currentThread().interrupt();StartupControl.expire(socket,System.nanoTime()-1);});guard.join(1000);assertFalse(guard.isAlive());assertEquals(1,socket.closed.getCount());socket.close();
    }
    @Test void watcherStartedAfterDeadlineClosesAndUnblocksOwnedWrite() throws Exception {
        BlockedSocket socket=new BlockedSocket();ExecutorService writer=Executors.newSingleThreadExecutor();
        try {
            var result=writer.submit(()->assertThrows(IOException.class,()->StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",4),System.nanoTime()+TimeUnit.SECONDS.toNanos(30))));
            assertTrue(socket.entered.await(1,TimeUnit.SECONDS));StartupControl.expire(socket,System.nanoTime()-1);assertEquals(0,socket.closed.getCount());assertNotNull(result.get(2,TimeUnit.SECONDS));
        }finally{socket.close();writer.shutdownNow();assertTrue(writer.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void actualParserOwnsOfflineAndBackendDecision() {
        for(String command:List.of("list","render"))for(String[] flags:new String[][]{{"--offline"},{"--offline=false"},{"--docker-image","mcicon-render:dev"},{"--java-home","/opt/java"}}) {
            List<String> args=new ArrayList<>(List.of(command,"--version","1.20.6"));if(command.equals("render"))args.addAll(List.of("--id","stone","--out",directory.toString()));args.addAll(List.of(flags));
            var line=Main.commandLine();var parsed=line.parseArgs(args.toArray(String[]::new));var request=Main.request(parsed);assertEquals(args,StartupControl.JSON.convertValue(request.get("args"),List.class));assertEquals(command,request.path("command").asText());assertEquals(flags[0].equals("--offline"),request.path("offline").asBoolean());assertEquals(flags[0].startsWith("--offline"),request.path("managed").asBoolean());
        }
        for(String[] args:new String[][]{{"--help"},{"--version"},{"extract","--help"},{"cache","status","--offline"},{"versions","--offline"}}) {var request=Main.request(Main.commandLine().parseArgs(args));assertFalse(request.path("managed").asBoolean());}
    }
    ObjectNode request(){ObjectNode value=StartupControl.JSON.createObjectNode().put("command","render").put("managed",true).put("offline",false).put("help",false).put("preparationMillis",5000).put("startupMillis",1000).put("itemMillis",1000);value.putArray("args").add("render");return value;}
    @Test void runtimeとrepairは実parserのleaf名を非managedとして通知する() {
        for(String[] args:new String[][]{{"runtime"},{"runtime","repair","--job",UUID.randomUUID().toString(),"--runtime-root",directory.toString()}}) {
            var request=Main.request(Main.commandLine().parseArgs(args));
            assertEquals(args.length==1?"runtime":"repair",request.path("command").asText());
            assertFalse(request.path("managed").asBoolean());assertFalse(request.path("offline").asBoolean());assertFalse(request.path("help").asBoolean());
            assertEquals(List.of(args),StartupControl.JSON.convertValue(request.path("args"),List.class));
        }
    }
    Path jar() throws Exception {Path path=directory.resolve("1.1.0/"+"a".repeat(64)+"/app/lib/cli.jar");Files.createDirectories(path.getParent());Files.write(path,new byte[]{1});return path;}
    ObjectNode context(int port) {ObjectNode value=StartupControl.JSON.createObjectNode().put("protocol",4).put("port",port).put("nonce",UUID.randomUUID().toString()).put("invocation",UUID.randomUUID().toString());value.putObject("slot").put("toolVersion","1.1.0").put("payloadSha256","a".repeat(64)).put("runtimeDescriptorSha256","b".repeat(64)).put("manifestSha256","c".repeat(64));return value;}
    void property(ObjectNode context) throws Exception {System.setProperty(StartupControl.PROPERTY,Base64.getUrlEncoder().withoutPadding().encodeToString(StartupControl.JSON.writeValueAsBytes(context)));}
    @Test void runAndParseOnlySwitchUseTheSameControlWithoutSideEffects() throws Exception {
        Path jar=jar();for(String type:List.of("RUN","SWITCH")) {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                property(context(server.getLocalPort()));ExecutorService pool=Executors.newSingleThreadExecutor();
                try {
                    var peer=pool.submit(()->{try(Socket socket=server.accept()){var value=StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));assertEquals("REQUEST",value.path("type").asText());StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",4).put("type",type));}return null;});
                    try(StartupControl control=StartupControl.open(request(),"1.1.0",jar)){assertEquals(type.equals("SWITCH"),control.switching());}peer.get(3,TimeUnit.SECONDS);
                }finally{System.clearProperty(StartupControl.PROPERTY);pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
            }
        }
    }
    @Test void readyBlocksPngUntilGoAndParentDeathCancels() throws Exception {
        Path jar=jar();for(boolean sendGo:List.of(true,false)) {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                property(context(server.getLocalPort()));ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean cancelled=new AtomicBoolean(),closed=new AtomicBoolean();Path png=directory.resolve("png-"+sendGo);
                try {
                    var peer=pool.submit(()->{try(Socket socket=server.accept()){StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",4).put("type","PREPARE"));assertEquals("READY",StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(2)).path("type").asText());ready.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));assertFalse(Files.exists(png));if(sendGo)StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",4).put("type","GO"));}return null;});
                    try(StartupControl control=StartupControl.open(request(),"1.1.0",jar)) {
                        control.cancellation(()->cancelled.set(true));Thread job=Thread.ofPlatform().start(()->{try{control.ready();Files.write(png,new byte[]{1});}catch(Exception failure){assertFalse(sendGo);}finally{closed.set(true);}});
                        try{assertTrue(ready.await(2,TimeUnit.SECONDS));assertFalse(Files.exists(png));release.countDown();job.join(3000);assertFalse(job.isAlive());assertEquals(sendGo,Files.exists(png));assertEquals(!sendGo,cancelled.get());assertTrue(closed.get());}finally{release.countDown();if(job.isAlive()){job.interrupt();job.join(2000);}}
                    }peer.get(3,TimeUnit.SECONDS);
                }finally{release.countDown();System.clearProperty(StartupControl.PROPERTY);pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
            }
        }
    }
    @Test void typedPreResourceFailureAndGenericPermissionErrorHaveDifferentEvidence() throws Exception {
        Path jar=jar();
        for(boolean bundled:List.of(true,false))try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            ObjectNode context=context(server.getLocalPort());property(context);ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                var peer=pool.submit(()->{try(Socket socket=server.accept()){var request=StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(3));StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",4).put("type","PREPARE"));var failure=StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(3));for(String name:List.of("nonce","invocation","slot","pid","startedAt"))assertEquals(request.get(name),failure.get(name));return failure;}});
                try(StartupControl control=StartupControl.open(request(),"1.1.0",jar)) {
                    Throwable cause=new AccessDeniedException("fixture");
                    if(bundled)cause=assertThrows(io.github.rs0325.mcicon.McIconException.class,()->io.github.rs0325.mcicon.internal.managed.ManagedRuntime.prepare(io.github.rs0325.mcicon.ManagedOptions.defaults(),false,new io.github.rs0325.mcicon.CancellationToken()));
                    control.initializationFailure(new CompletionException(cause));var failure=peer.get(3,TimeUnit.SECONDS);assertEquals("FAILED",failure.path("type").asText());assertEquals(bundled?"BUNDLED_PINS_INVALID":"UNKNOWN",failure.path("failure").asText());assertEquals(bundled?"NOT_STARTED":"UNKNOWN",failure.path("cleanup").asText());assertTrue(failure.path("jobs").isEmpty());
                }
            }finally{System.clearProperty(StartupControl.PROPERTY);pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test void typedBusyUsesDeferredOnlyWithCapabilityAndNoCleanupFailure() throws Exception {
        Path cli=jar();for(String mode:List.of("old-bootstrap","deferred","cleanup-failed")) {
            if(!mode.equals("old-bootstrap"))System.setProperty(StartupControl.CAPABILITIES_PROPERTY,"deferred-v1");
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                property(context(server.getLocalPort()));ExecutorService pool=Executors.newSingleThreadExecutor();
                try {
                    var peer=pool.submit(()->{try(Socket socket=server.accept()){var request=StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(3));StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",4).put("type","PREPARE"));var result=StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(3));for(String name:List.of("nonce","invocation","slot","pid","startedAt"))assertEquals(request.path(name),result.path(name));return result;}});
                    try(StartupControl control=StartupControl.open(request(),"1.1.0",cli)) {
                        var type=io.github.rs0325.mcicon.internal.managed.ManagedBusyFailure.class;var constructor=type.getDeclaredConstructor(com.fasterxml.jackson.databind.JsonNode.class);constructor.setAccessible(true);
                        var claim=StartupControl.JSON.createObjectNode().put("attemptId",UUID.randomUUID().toString()).put("evidenceSha256","a".repeat(64));
                        Throwable cause=constructor.newInstance(claim);if(mode.equals("cleanup-failed"))cause.addSuppressed(new IOException("回収失敗"));
                        control.initializationFailure(new CompletionException(cause));var result=peer.get(3,TimeUnit.SECONDS);
                        assertEquals(mode.equals("deferred")?"DEFERRED":"FAILED",result.path("type").asText());assertEquals(4,result.path("protocol").asInt());
                        if(mode.equals("deferred")){assertEquals(claim,result.path("admission"));assertTrue(result.path("jobs").isEmpty());}
                        else {assertEquals("UNKNOWN",result.path("failure").asText());assertFalse(result.has("admission"));}
                    }
                }finally{System.clearProperty(StartupControl.PROPERTY);pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
            }finally{System.clearProperty(StartupControl.CAPABILITIES_PROPERTY);}
        }
    }
    @Test void protocolTwoContextIsRejectedBeforeConnecting() throws Exception {
        Path jar=jar();ObjectNode context=context(1);context.put("protocol",2);property(context);
        try{assertThrows(IOException.class,()->StartupControl.open(request(),"1.1.0",jar));}finally{System.clearProperty(StartupControl.PROPERTY);}
    }
    @Test void candidateChecksItsOwnSlotAndRejectsWrongExpectedIdentity() throws Exception {Path jar=jar();ObjectNode slot=(ObjectNode)context(1).get("slot");StartupControl.verifySlot(slot,"1.1.0",jar);slot.put("payloadSha256","d".repeat(64));assertThrows(IOException.class,()->StartupControl.verifySlot(slot,"1.1.0",jar));}
    @Test void fragmentedFrameStillHonoursAbsoluteDeadlineAndSizeLimit() throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));Socket client=new Socket("127.0.0.1",server.getLocalPort());Socket peer=server.accept()) {
            Thread sender=Thread.ofPlatform().start(()->{try{for(byte value:new byte[]{0,0,0,10,123,32,32,32,32}){peer.getOutputStream().write(value);peer.getOutputStream().flush();Thread.sleep(40);}}catch(Exception stopped){}});
            try{long start=System.nanoTime();assertThrows(IOException.class,()->StartupControl.read(client,start+TimeUnit.MILLISECONDS.toNanos(100)));assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));}finally{peer.close();sender.interrupt();sender.join(1000);assertFalse(sender.isAlive());}
        }
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));Socket client=new Socket("127.0.0.1",server.getLocalPort());Socket peer=server.accept()){peer.getOutputStream().write(java.nio.ByteBuffer.allocate(4).putInt(65537).array());assertThrows(IOException.class,()->StartupControl.read(client,System.nanoTime()+TimeUnit.SECONDS.toNanos(1)));}
    }
}
