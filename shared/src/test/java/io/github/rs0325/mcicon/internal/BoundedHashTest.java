package io.github.rs0325.mcicon.internal;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHashTest {
    @TempDir Path root;
    private static final byte[] BYTES=new byte[3*65536];

    @Test void 元sizeのhashと空fileを検証できる() throws Exception {
        Path file=Files.write(root.resolve("file"),BYTES);long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        assertEquals(LeaseSnapshot.digest(BYTES),LeaseSnapshot.fileDigest(file,BYTES.length,end));
        Files.write(file,new byte[0]);assertEquals(LeaseSnapshot.digest(new byte[0]),LeaseSnapshot.fileDigest(file,0,end));
    }
    @Test void hash途中の期限切れは次の読取りへ進まない() throws Exception {
        LeaseSnapshot.digest(new byte[0]);long end=System.nanoTime()+Duration.ofMillis(200).toNanos();AtomicInteger reads=new AtomicInteger();
        try(InputStream input=afterRead(new ByteArrayInputStream(BYTES),count->{reads.incrementAndGet();while(System.nanoTime()<end)Thread.onSpinWait();})) {
            assertThrows(IOException.class,()->LeaseSnapshot.fileDigest(input,BYTES.length,end));assertEquals(1,reads.get());
        }
    }
    @Test void hash途中の取消しは中断状態を保持して次の読取りを拒否する() throws Exception {
        AtomicInteger reads=new AtomicInteger();long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        try(InputStream input=afterRead(new ByteArrayInputStream(BYTES),count->{reads.incrementAndGet();Thread.currentThread().interrupt();})) {
            assertThrows(InterruptedIOException.class,()->LeaseSnapshot.fileDigest(input,BYTES.length,end));
            assertEquals(1,reads.get());assertTrue(Thread.currentThread().isInterrupted());
        }finally {Thread.interrupted();}
    }
    @Test void 同じfileへ追記され続けても初期sizeと一byteを超えて読まない() throws Exception {
        Path file=Files.write(root.resolve("growing"),BYTES);AtomicLong consumed=new AtomicLong();long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        try(InputStream input=afterRead(Files.newInputStream(file),count->{consumed.addAndGet(count);Files.write(file,new byte[65536],StandardOpenOption.APPEND);})) {
            IOException failure=assertThrows(IOException.class,()->LeaseSnapshot.fileDigest(input,BYTES.length,end));
            assertTrue(failure.getMessage().contains("size"));assertEquals(BYTES.length+1,consumed.get());
            assertTrue(Files.size(file)>BYTES.length);
        }
    }
    @FunctionalInterface private interface ReadAction {void run(int count) throws IOException;}
    private static InputStream afterRead(InputStream input,ReadAction action) {
        return new FilterInputStream(input) {
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                int count=in.read(bytes,offset,length);if(count>0)action.run(count);return count;
            }
        };
    }
}
