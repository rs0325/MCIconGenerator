package io.github.rs0325.mcicon.bootstrap;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.SocketTimeoutException;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StartupControlTest {
    ObjectNode slot(){return Seed.JSON.createObjectNode().put("toolVersion","1.1.0").put("payloadSha256","a".repeat(64)).put("runtimeDescriptorSha256","b".repeat(64)).put("manifestSha256","c".repeat(64));}
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
            var result=writer.submit(()->assertThrows(IOException.class,()->StartupControl.write(socket,Seed.JSON.createObjectNode().put("protocol",3),System.nanoTime()+TimeUnit.SECONDS.toNanos(30))));
            assertTrue(socket.entered.await(1,TimeUnit.SECONDS));StartupControl.expire(socket,System.nanoTime()-1);assertEquals(0,socket.closed.getCount());assertNotNull(result.get(2,TimeUnit.SECONDS));
        }finally{socket.close();writer.shutdownNow();assertTrue(writer.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void nonceArgsSlotAndProcessAreBoundToTheOwnedChild() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sleep")));
        Process child=new ProcessBuilder("/bin/sleep","3").start();
        try(StartupControl control=new StartupControl(slot(),UUID.randomUUID().toString(),List.of("--help"))) {
            String property=control.property();var context=Seed.json(Base64.getUrlDecoder().decode(property.substring(property.indexOf('=')+1)));
            ObjectNode request=Seed.JSON.createObjectNode().put("protocol",3).put("type","REQUEST").put("pid",child.pid()).put("startedAt",child.info().startInstant().orElseThrow().toString()).put("command","help").put("managed",false).put("offline",false).put("help",true).put("preparationMillis",1).put("startupMillis",1).put("itemMillis",1);
            for(String key:List.of("nonce","invocation","slot"))request.set(key,context.get(key));request.set("args",Seed.JSON.valueToTree(List.of("--help")));control.validate(request,child);
            for(String field:List.of("nonce","invocation","slot","args","pid","startedAt","protocol")){ObjectNode changed=request.deepCopy();changed.put(field,"wrong");assertThrows(IOException.class,()->control.validate(changed,child),field);}
        }finally{child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}
    }
    @Test void failureNotificationRejectsDifferentTrialProcessSlotAndOldProtocol() throws Exception {
        for(String changed:List.of("none","nonce","invocation","slot","pid","startedAt","protocol","jobs","extra")) {
            ObjectNode request=Seed.JSON.createObjectNode().put("nonce",UUID.randomUUID().toString()).put("invocation",UUID.randomUUID().toString()).put("pid",ProcessHandle.current().pid()).put("startedAt",java.time.Instant.now().toString());request.set("slot",slot());request=(ObjectNode)Seed.json(Seed.JSON.writeValueAsBytes(request));
            ObjectNode failure=request.deepCopy().put("protocol",3).put("type","FAILED").put("failure","BUNDLED_PINS_INVALID").put("cleanup","NOT_STARTED");failure.putArray("jobs");
            switch(changed) {
                case "none"->{}
                case "nonce","invocation"->failure.put(changed,UUID.randomUUID().toString());
                case "slot"->((ObjectNode)failure.get("slot")).put("payloadSha256","d".repeat(64));
                case "pid"->failure.put("pid",ProcessHandle.current().pid()+1);
                case "startedAt"->failure.put("startedAt",java.time.Instant.EPOCH.toString());
                case "protocol"->failure.put("protocol",2);
                case "jobs"->failure.withArray("jobs").addObject().put("job","unproven");
                case "extra"->failure.put("extra",true);
            }
            try(StartupControl control=new StartupControl(slot(),UUID.randomUUID().toString(),List.of());java.net.ServerSocket server=new java.net.ServerSocket(0);java.net.Socket sender=new java.net.Socket("127.0.0.1",server.getLocalPort());java.net.Socket receiver=server.accept();StartupControl.Session session=control.new Session(receiver,request,System.nanoTime()+TimeUnit.SECONDS.toNanos(2))) {
                StartupControl.write(sender,failure);
                if(changed.equals("none"))assertEquals(failure,session.ready(System.nanoTime()+TimeUnit.SECONDS.toNanos(2)));
                else assertThrows(IOException.class,()->session.ready(System.nanoTime()+TimeUnit.SECONDS.toNanos(2)),changed);
            }
        }
    }
    @Test void missingRequestHasAFiniteAbsoluteBudget() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sleep")));
        Process child=new ProcessBuilder("/bin/sleep","3").start();try(StartupControl control=new StartupControl(slot(),UUID.randomUUID().toString(),List.of())){long start=System.nanoTime();assertThrows(SocketTimeoutException.class,()->control.request(child,Duration.ofMillis(100)));assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));}finally{child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}
    }
}
