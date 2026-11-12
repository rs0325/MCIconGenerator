package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.internal.LeaseSnapshot;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LeaseReferencesWorkersTest {
    @TempDir Path root;
    static LeaseReferences.Scan empty(){return new LeaseReferences.Scan(false,List.of(),0,true,false,false,List.of());}

    @Test void 期限不足ではworkerを開始しない() {
        AtomicInteger started=new AtomicInteger();
        IOException failure=assertThrows(IOException.class,()->LeaseReferences.scans(List.of(()->{started.incrementAndGet();return empty();}),System.nanoTime()-1));
        assertTrue(failure.getMessage().contains("期限不足"));assertEquals(0,started.get());
    }
    @Test void workerの通常失敗をTIMEOUTへ読み替えず全threadを回収する() throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        IOException failure=assertThrows(IOException.class,()->LeaseReferences.scans(List.of(()->{throw new IllegalStateException("fixture");},LeaseReferencesWorkersTest::empty),end));
        assertInstanceOf(IllegalStateException.class,failure.getCause());assertFalse(failure.getMessage().contains("TIMEOUT"));assertNoWorkers();assertTrue(System.nanoTime()<end);
    }
    @Test void worker開始後の期限切れで全threadとreceiptを期限内に回収する() throws Exception {cancel(false,false);}
    @Test void worker開始後の割込みで全threadとreceiptを元期限内に回収する() throws Exception {cancel(true,false);}
    @Test void 期限なしの割込みでもworkerを残さずflagを復元する() throws Exception {cancel(true,true);}

    void cancel(boolean interrupt,boolean unlimited) throws Exception {
        CountDownLatch entered=new CountDownLatch(4);AtomicInteger started=new AtomicInteger(),closed=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicLong returned=new AtomicLong();AtomicBoolean flag=new AtomicBoolean();
        List<Path> receipts=new ArrayList<>();List<Callable<LeaseReferences.Scan>> tasks=new ArrayList<>();
        for(int i=0;i<4;i++) {
            Path receipt=root.resolve("receipt-"+i);Files.write(receipt,new byte[64]);receipts.add(receipt);
            tasks.add(()->{
                try {
                    try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.lock(64,1,false)) {
                        started.incrementAndGet();entered.countDown();Thread.sleep(10_000);return empty();
                    }
                }finally{closed.incrementAndGet();}
            });
        }
        long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        Thread caller=Thread.ofPlatform().start(()->{
            try{LeaseReferences.scans(tasks,unlimited?Long.MAX_VALUE:end);failure.set(new AssertionError("取消しを成功にしました"));}
            catch(Throwable error){failure.set(error);}
            finally{flag.set(Thread.currentThread().isInterrupted());returned.set(System.nanoTime());}
        });
        try {
            assertTrue(entered.await(1,TimeUnit.SECONDS),"worker開始境界へ到達していません");assertEquals(4,started.get());
            if(interrupt)caller.interrupt();
            long left=end-System.nanoTime();assertTrue(left>0);assertTrue(caller.join(Duration.ofNanos(left)));
            assertFalse(caller.isAlive());assertTrue(returned.get()<end,"worker/lock回収が元期限を超えました");
            assertInstanceOf(IOException.class,failure.get());
            if(interrupt){assertInstanceOf(InterruptedIOException.class,failure.get());assertFalse(failure.get().getMessage().contains("TIMEOUT"));assertTrue(flag.get());}
            else{assertTrue(failure.get().getMessage().contains("期限不足"));assertFalse(flag.get());}
            assertEquals(4,closed.get());assertNoWorkers();
            for(Path receipt:receipts)try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.tryLock(64,1,false)){assertNotNull(lock);}
            assertTrue(System.nanoTime()<end,"receipt再取得も元期限内に完了する必要があります");
        }finally{if(caller.isAlive()){caller.interrupt();caller.join(Duration.ofSeconds(3));}}
    }
    static void assertNoWorkers(){assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread->thread.isAlive()&&thread.getName().startsWith("mcicon-lease-scan-")));}

    @Test void 非応答workerの期限切れはhelper境界で全lockを回収する() throws Exception {boundary(false);}
    @Test void 非応答workerの割込みもhelper境界で全lockを回収する() throws Exception {boundary(true);}
    void boundary(boolean interrupt) throws Exception {
        Path root=Files.createDirectory(this.root.resolve(interrupt?"interrupt":"deadline"));
        String classpath=Arrays.stream(new Class<?>[]{LeaseReferencesBoundary.class,LeaseReferences.class,HelperProtocol.class,LeaseSnapshot.class,JsonNode.class,ObjectMapper.class,JsonFactory.class,JsonProperty.class}).map(type->{try{return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}catch(Exception failure){throw new RuntimeException(failure);}}).distinct().collect(Collectors.joining(File.pathSeparator));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        Process child=new ProcessBuilder(java.toString(),"-cp",classpath,LeaseReferencesBoundary.class.getName(),root.toString(),Boolean.toString(interrupt)).redirectOutput(root.resolve("child.stdout").toFile()).redirectError(root.resolve("child.stderr").toFile()).start();
        try {
            long startupEnd=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(!Files.exists(root.resolve("ready"))&&child.isAlive()&&System.nanoTime()<startupEnd)Thread.sleep(5);
            assertTrue(Files.exists(root.resolve("ready")),Files.readString(root.resolve("child.stderr")));
            long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            Files.writeString(root.resolve("go"),Instant.now().plusSeconds(2).toString());
            while(!Files.exists(root.resolve("worker-started"))&&child.isAlive()&&System.nanoTime()<end)Thread.sleep(5);
            assertTrue(Files.exists(root.resolve("worker-started")),"待機前の拒否だけで合格してはいけません");
            if(interrupt)Files.createFile(root.resolve("interrupt"));
            long left=end-System.nanoTime();assertTrue(left>0);assertTrue(child.waitFor(left,TimeUnit.NANOSECONDS),"helperが元期限内に終了していません");
            assertFalse(child.isAlive());assertEquals(1,child.exitValue());assertFalse(Files.exists(root.resolve("EOF")));assertFalse(Files.exists(root.resolve("success")));
            assertEquals("active",Files.readString(root.resolve("lease-state")));if(interrupt)assertTrue(Files.exists(root.resolve("interrupt-observed")));
            for(String name:List.of("journal","generation","receipt"))try(var channel=FileChannel.open(root.resolve(name),StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.tryLock(name.equals("receipt")?64:0,1,false)){assertNotNull(lock,name);}
            assertTrue(System.nanoTime()<end,"helper/全worker/全lockの終端が元期限を超えました");
        }finally{if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}}
    }
}

final class LeaseReferencesBoundary {
    public static void main(String[] arguments) throws Exception {
        Path root=Path.of(arguments[0]);
        for(String name:List.of("journal","generation","receipt"))Files.write(root.resolve(name),new byte[64]);
        Files.writeString(root.resolve("lease-state"),"active");
        try(var journal=FileChannel.open(root.resolve("journal"),StandardOpenOption.READ,StandardOpenOption.WRITE);var journalLock=journal.lock(0,1,false);var generation=FileChannel.open(root.resolve("generation"),StandardOpenOption.READ,StandardOpenOption.WRITE);var generationLock=generation.lock(0,1,false)) {
            Files.createFile(root.resolve("ready"));
            while(!Files.exists(root.resolve("go")))Thread.sleep(1);
            long end=System.nanoTime()+Math.max(0,Duration.between(Instant.now(),Instant.parse(Files.readString(root.resolve("go")))).toNanos());
            boolean interrupt=Boolean.parseBoolean(arguments[1]);Thread caller=Thread.currentThread();
            if(interrupt)Thread.ofPlatform().daemon(true).start(()->{try{while(!Files.exists(root.resolve("interrupt")))Thread.sleep(1);caller.interrupt();Files.createFile(root.resolve("interrupt-observed"));}catch(Exception failure){throw new RuntimeException(failure);}});
            Callable<LeaseReferences.Scan> blocked=()->{
                try(var channel=FileChannel.open(root.resolve("receipt"),StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.lock(64,1,false)) {
                    Files.createFile(root.resolve("worker-started"));
                    while(true)try{Thread.sleep(100_000);}catch(InterruptedException ignored){}
                }
            };
            Callable<LeaseReferences.Scan> empty=()->new LeaseReferences.Scan(false,List.of(),0,true,false,false,List.of());
            LeaseReferences.scans(List.of(blocked,empty,empty,empty),interrupt?Long.MAX_VALUE:end);
            Files.createFile(root.resolve("EOF"));Files.createFile(root.resolve("success"));
        }
    }
}
