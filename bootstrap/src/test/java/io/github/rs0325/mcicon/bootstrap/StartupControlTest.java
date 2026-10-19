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
            var result=writer.submit(()->assertThrows(IOException.class,()->StartupControl.write(socket,Seed.JSON.createObjectNode().put("protocol",2),System.nanoTime()+TimeUnit.SECONDS.toNanos(30))));
            assertTrue(socket.entered.await(1,TimeUnit.SECONDS));StartupControl.expire(socket,System.nanoTime()-1);assertEquals(0,socket.closed.getCount());assertNotNull(result.get(2,TimeUnit.SECONDS));
        }finally{socket.close();writer.shutdownNow();assertTrue(writer.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void nonceArgsSlotAndProcessAreBoundToTheOwnedChild() throws Exception {
        Process child=new ProcessBuilder("/bin/sleep","3").start();
        try(StartupControl control=new StartupControl(slot(),UUID.randomUUID().toString(),List.of("--help"))) {
            String property=control.property();var context=Seed.json(Base64.getUrlDecoder().decode(property.substring(property.indexOf('=')+1)));
            ObjectNode request=Seed.JSON.createObjectNode().put("protocol",2).put("type","REQUEST").put("pid",child.pid()).put("startedAt",child.info().startInstant().orElseThrow().toString()).put("command","help").put("managed",false).put("offline",false).put("help",true).put("preparationMillis",1).put("startupMillis",1).put("itemMillis",1);
            for(String key:List.of("nonce","invocation","slot"))request.set(key,context.get(key));request.set("args",Seed.JSON.valueToTree(List.of("--help")));control.validate(request,child);
            for(String field:List.of("nonce","invocation","slot","args","pid","startedAt","protocol")){ObjectNode changed=request.deepCopy();changed.put(field,"wrong");assertThrows(IOException.class,()->control.validate(changed,child),field);}
        }finally{child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}
    }
    @Test void missingRequestHasAFiniteAbsoluteBudget() throws Exception {
        Process child=new ProcessBuilder("/bin/sleep","3").start();try(StartupControl control=new StartupControl(slot(),UUID.randomUUID().toString(),List.of())){long start=System.nanoTime();assertThrows(SocketTimeoutException.class,()->control.request(child,Duration.ofMillis(100)));assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));}finally{child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}
    }
}
