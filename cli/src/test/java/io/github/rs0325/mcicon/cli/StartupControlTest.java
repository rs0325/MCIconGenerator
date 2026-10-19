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
            var result=writer.submit(()->assertThrows(IOException.class,()->StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",2),System.nanoTime()+TimeUnit.SECONDS.toNanos(30))));
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
    Path jar() throws Exception {Path path=directory.resolve("1.1.0/"+"a".repeat(64)+"/app/lib/cli.jar");Files.createDirectories(path.getParent());Files.write(path,new byte[]{1});return path;}
    ObjectNode context(int port) {ObjectNode value=StartupControl.JSON.createObjectNode().put("protocol",2).put("port",port).put("nonce",UUID.randomUUID().toString()).put("invocation",UUID.randomUUID().toString());value.putObject("slot").put("toolVersion","1.1.0").put("payloadSha256","a".repeat(64)).put("runtimeDescriptorSha256","b".repeat(64)).put("manifestSha256","c".repeat(64));return value;}
    void property(ObjectNode context) throws Exception {System.setProperty(StartupControl.PROPERTY,Base64.getUrlEncoder().withoutPadding().encodeToString(StartupControl.JSON.writeValueAsBytes(context)));}
    @Test void runAndParseOnlySwitchUseTheSameControlWithoutSideEffects() throws Exception {
        Path jar=jar();for(String type:List.of("RUN","SWITCH")) {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                property(context(server.getLocalPort()));ExecutorService pool=Executors.newSingleThreadExecutor();
                try {
                    var peer=pool.submit(()->{try(Socket socket=server.accept()){var value=StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));assertEquals("REQUEST",value.path("type").asText());StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",2).put("type",type));}return null;});
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
                    var peer=pool.submit(()->{try(Socket socket=server.accept()){StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",2).put("type","PREPARE"));assertEquals("READY",StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(2)).path("type").asText());ready.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));assertFalse(Files.exists(png));if(sendGo)StartupControl.write(socket,StartupControl.JSON.createObjectNode().put("protocol",2).put("type","GO"));}return null;});
                    try(StartupControl control=StartupControl.open(request(),"1.1.0",jar)) {
                        control.cancellation(()->cancelled.set(true));Thread job=Thread.ofPlatform().start(()->{try{control.ready();Files.write(png,new byte[]{1});}catch(Exception failure){assertFalse(sendGo);}finally{closed.set(true);}});
                        try{assertTrue(ready.await(2,TimeUnit.SECONDS));assertFalse(Files.exists(png));release.countDown();job.join(3000);assertFalse(job.isAlive());assertEquals(sendGo,Files.exists(png));assertEquals(!sendGo,cancelled.get());assertTrue(closed.get());}finally{release.countDown();if(job.isAlive()){job.interrupt();job.join(2000);}}
                    }peer.get(3,TimeUnit.SECONDS);
                }finally{release.countDown();System.clearProperty(StartupControl.PROPERTY);pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
            }
        }
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
