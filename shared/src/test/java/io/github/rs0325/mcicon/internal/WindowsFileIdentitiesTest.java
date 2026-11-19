package io.github.rs0325.mcicon.internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsFileIdentitiesTest {
    @TempDir Path root;

    @Test void native待機後の期限切れでもworkerとreceiptを期限内に解放する() throws Exception {waitFailure(false);}
    @Test void native待機中の割込みでも回収して中断状態とreceiptを保持する() throws Exception {waitFailure(true);}

    private void waitFailure(boolean interrupt) throws Exception {
        root=root.toRealPath();
        String binary=System.getProperty("mcicon.testIdentityWaitFixture");
        assertNotNull(binary,"Windows試験には実build済み待機fixtureが必要です");
        Path executable=Path.of(binary).toRealPath(),receipt=Files.writeString(root.resolve("receipt"),"original");
        var reader=new WindowsFileIdentities(executable,LeaseSnapshot.fileDigest(executable));
        long start=System.nanoTime(),end=start+Duration.ofSeconds(2).toNanos();
        AtomicBoolean interrupted=new AtomicBoolean();
        AtomicLong returnedAt=new AtomicLong();
        FutureTask<IOException> result=new FutureTask<>(()->{
            try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);
                var lock=channel.lock(64,1,false)) {
                try {
                    reader.readAll(List.of(receipt),root,end);
                    throw new AssertionError("待機fixtureを成功にしました");
                }catch(IOException failure) {return failure;}
            }finally {interrupted.set(Thread.currentThread().isInterrupted());returnedAt.set(System.nanoTime());}
        });
        Thread worker=Thread.ofPlatform().unstarted(result);
        ProcessHandle child=null;
        worker.start();
        try {
            boolean waiting=false;
            while(System.nanoTime()<end-TimeUnit.MILLISECONDS.toNanos(250)&&!result.isDone()) {
                child=ProcessHandle.current().descendants().filter(process->process.info().command()
                        .map(command->Path.of(command).equals(executable)).orElse(false)).findFirst().orElse(null);
                waiting=child!=null&&child.isAlive()&&java.util.Arrays.stream(worker.getStackTrace())
                        .anyMatch(frame->frame.getClassName().equals("java.lang.ProcessImpl")&&frame.getMethodName().equals("waitFor"));
                if(waiting)break;
                Thread.sleep(1);
            }
            assertTrue(waiting,"native待機後の境界へ到達しませんでした");
            assertNotNull(child);
            if(interrupt)worker.interrupt();
            IOException failure=result.get(3,TimeUnit.SECONDS);
            worker.join(250);
            assertFalse(worker.isAlive());assertFalse(child.isAlive());
            assertTrue(returnedAt.get()<=end,"receipt解放後のworker復帰が絶対期限を超えました");
            assertTrue(System.nanoTime()-start<Duration.ofMillis(2250).toNanos(),"絶対期限後にworker回収が長引きました");
            if(interrupt){assertInstanceOf(InterruptedIOException.class,failure);assertTrue(interrupted.get());}
            else {assertTrue(failure.getMessage().contains("絶対期限"));assertFalse(interrupted.get());}
            try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);
                var lock=channel.tryLock(64,1,false)){assertNotNull(lock);}
            try(var files=Files.list(root)){assertEquals(List.of(receipt),files.toList(),failure+" "+java.util.Arrays.toString(failure.getSuppressed()));}
        }finally {
            if(worker.isAlive()){worker.interrupt();worker.join(3000);}
            if(child!=null&&child.isAlive()){child.destroyForcibly();child.onExit().get(2,TimeUnit.SECONDS);}
            assertFalse(worker.isAlive());
        }
    }
}
