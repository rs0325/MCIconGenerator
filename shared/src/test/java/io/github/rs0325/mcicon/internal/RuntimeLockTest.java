package io.github.rs0325.mcicon.internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLockTest {
    @TempDir Path root;
    @Test void 取消される待機者は保持者のfileを別FDで開かない() throws Exception {
        Path file=root.toRealPath().resolve("root.lock");AtomicInteger reads=new AtomicInteger();
        LeaseTrust.IdentityReader reader=path->{reads.incrementAndGet();return "fixture";};
        CountDownLatch started=new CountDownLatch(1);AtomicReference<Throwable> result=new AtomicReference<>();
        Thread waiter;
        try(var held=RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(5).toNanos(),reader)) {
            int before=reads.get();
            waiter=Thread.ofPlatform().start(()->{started.countDown();try(var unexpected=RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(5).toNanos(),reader)){result.set(new AssertionError("保持中のlockを取得しました"));}catch(Throwable failure){result.set(failure);}});
            assertTrue(started.await(1,TimeUnit.SECONDS));waiter.interrupt();waiter.join(Duration.ofSeconds(2));
            assertFalse(waiter.isAlive());assertInstanceOf(InterruptedIOException.class,result.get());assertEquals(before,reads.get());
            assertThrows(IOException.class,()->RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(1).toNanos(),reader));
        }
        try(var again=RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(2).toNanos(),reader)){assertNotNull(again);}
    }
    @Test void 期限不足はfile生成前に拒否する() throws Exception {
        Path file=root.resolve("never-opened.lock");
        assertThrows(IOException.class,()->RuntimeLock.acquire(file,System.nanoTime()-1,path->{fail("期限後にreaderを呼びました");return "fixture";}));
        assertFalse(Files.exists(file));
    }
    @Test void 取消しsupplierと別threadのtoken利用を拒否する() throws Exception {
        Path file=root.toRealPath().resolve("cancelled.lock");
        assertThrows(IOException.class,()->RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(2).toNanos(),null,()->true));
        assertFalse(Files.exists(file));
        try(var held=RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(3).toNanos(),path->"fixture")) {
            var result=new FutureTask<Throwable>(()->{try{held.requireHeld(file);return new AssertionError("別threadのtokenを受理しました");}catch(Throwable failure){return failure;}});
            Thread thread=Thread.ofPlatform().start(result);
            try{assertInstanceOf(IOException.class,result.get(1,TimeUnit.SECONDS));}finally{thread.join(Duration.ofSeconds(1));}
            assertFalse(thread.isAlive());held.requireHeld(file);
        }
    }
    @Test void 別classloaderの待機者をFD生成前に拒否する() throws Exception {
        Path file=root.toRealPath().resolve("loader.lock");
        try(var held=RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(3).toNanos(),path->"fixture");
            var loader=new java.net.URLClassLoader(new java.net.URL[]{RuntimeLock.class.getProtectionDomain().getCodeSource().getLocation()},ClassLoader.getPlatformClassLoader())) {
            Class<?> other=loader.loadClass(RuntimeLock.class.getName()),reader=loader.loadClass(LeaseTrust.IdentityReader.class.getName());
            var method=other.getMethod("acquire",Path.class,long.class,reader);
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,file,System.nanoTime()+Duration.ofSeconds(1).toNanos(),null));
            assertInstanceOf(IOException.class,failure.getCause());assertTrue(failure.getCause().getMessage().contains("classloader"));
            held.requireHeld(file);
        }
    }
}
