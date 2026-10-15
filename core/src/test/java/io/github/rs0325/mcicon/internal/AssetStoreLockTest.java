package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetStoreLockTest {
    @TempDir Path root;
    @Test void crossedProcessWaitsCompleteWithoutRejectingIndependentHolders() throws Exception {
        try(var first=new Actor(root,"a","b");var second=new Actor(root,"b","a")) {
            assertEquals("HELD",first.read());assertEquals("HELD",second.read());
            first.send("wait");assertEquals("WAITING",first.read());
            var firstResult=first.next();
            assertThrows(TimeoutException.class,()->firstResult.get(200,TimeUnit.MILLISECONDS));
            second.send("wait");assertEquals("WAITING",second.read());
            var secondResult=second.next();
            assertThrows(TimeoutException.class,()->secondResult.get(200,TimeUnit.MILLISECONDS));
            first.send("release");second.send("release");
            assertTrue(firstResult.get(5,TimeUnit.SECONDS).startsWith("DONE "));
            assertTrue(secondResult.get(5,TimeUnit.SECONDS).startsWith("DONE "));
            assertTrue(first.read().startsWith("DONE "));assertTrue(second.read().startsWith("DONE "));
        }
        for(String name:new String[]{"a","b"}) {
            assertArrayEquals(new byte[]{1},Files.readAllBytes(root.resolve(name)));
            assertEquals(FilesSupport.hash(new byte[]{1}),Files.readString(root.resolve(name+".sha256")));
        }
        try(var paths=Files.list(root)){assertFalse(paths.anyMatch(p->p.toString().endsWith(".part")));}
    }
    @Test void cancellationStopsAnExternalLockWaitAndAllowsLaterAcquisition() throws Exception {
        checkInterruptedWait(false);
    }
    @Test void interruptionPreservesTheFlagAndAllowsLaterAcquisition() throws Exception {
        checkInterruptedWait(true);
    }
    private void checkInterruptedWait(boolean interrupt) throws Exception {
        try(var actor=new Actor(root,"a","b")) {
            assertEquals("HELD",actor.read());
            var token=new CancellationToken();
            var store=new AssetStore(root,true,token,uri->{throw new AssertionError("offline取得がnetworkへ進んだ");});
            var started=new CountDownLatch(1);var thread=new AtomicReference<Thread>();var flag=new AtomicBoolean();
            var pool=Executors.newSingleThreadExecutor();
            try {
                var result=pool.submit(()-> {
                    thread.set(Thread.currentThread());started.countDown();
                    try {return store.obtain("a",URI.create("https://example.test/a"),null,1,false);}
                    catch(Exception e){flag.set(Thread.currentThread().isInterrupted());throw e;}
                });
                assertTrue(started.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->result.get(100,TimeUnit.MILLISECONDS));
                System.out.println("LOCK_WAIT "+AssetLockProcess.awaitLockWait(thread.get()));
                if(interrupt)thread.get().interrupt();else token.cancel();
                Throwable cause=assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS)).getCause();
                if(cause instanceof McIconException error)assertEquals("CANCELLED",error.code());
                else assertInstanceOf(FileLockInterruptionException.class,cause);
                assertEquals(interrupt,flag.get());
            } finally {actor.send("release");pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            assertEquals("DONE a",actor.read());
            var next=new AssetStore(root,true,new CancellationToken(),uri->{throw new AssertionError("offline取得がnetworkへ進んだ");});
            assertArrayEquals(new byte[]{1},Files.readAllBytes(next.obtain("a",URI.create("https://example.test/a"),null,1,false)));
        }
    }
    private static final class Actor implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;
        private final PrintWriter input;
        private final ExecutorService reader=Executors.newSingleThreadExecutor();
        Actor(Path cache,String held,String waited) throws Exception {
            String classpath=Path.of(AssetLockProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    +File.pathSeparator+Path.of(AssetStore.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,
                    AssetLockProcess.class.getName(),cache.toString(),held,waited).redirectError(cache.resolve(held+".stderr").toFile()).start();
            output=process.inputReader();input=new PrintWriter(process.outputWriter(),true);
        }
        void send(String command){input.println(command);}
        Future<String> next(){return reader.submit(output::readLine);}
        String read() throws Exception {return next().get(5,TimeUnit.SECONDS);}
        @Override public void close() throws Exception {
            send("release");
            if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            assertFalse(process.isAlive(),"所有lock actorが終了しなかった");
            input.close();output.close();reader.shutdownNow();
        }
    }
}
