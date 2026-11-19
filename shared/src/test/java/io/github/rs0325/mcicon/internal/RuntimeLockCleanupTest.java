package io.github.rs0325.mcicon.internal;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLockCleanupTest {
    @TempDir Path root;
    @Test void 取得時の元失敗へreleaseとcloseの失敗を両方追加する() throws Exception {
        var original=new InterruptedIOException("元の取消し");var release=new IOException("release失敗");var close=new IOException("close失敗");
        var channel=new FailingChannel(close);var lock=lock(channel,release);
        RuntimeLock.closeResources(original,lock,channel);
        assertArrayEquals(new Throwable[]{release,close},original.getSuppressed());assertFalse(channel.isOpen());assertTrue(channel.closeAttempted);
    }
    @Test void 通常解放では最初のrelease失敗を保持しclose失敗を追加する() {
        var release=new IOException("release失敗");var close=new IOException("close失敗");var channel=new FailingChannel(close);
        IOException actual=assertThrows(IOException.class,()->RuntimeLock.closeResources(null,lock(channel,release),channel));
        assertSame(release,actual);assertArrayEquals(new Throwable[]{close},actual.getSuppressed());assertTrue(channel.closeAttempted);
    }
    @Test void releaseが成功してcloseだけ失敗する場合も元の取得失敗を保持する() throws Exception {
        var original=new IOException("元のidentity不一致");var close=new IOException("close失敗");var channel=new FailingChannel(close);
        RuntimeLock.closeResources(original,lock(channel,null),channel);
        assertArrayEquals(new Throwable[]{close},original.getSuppressed());assertTrue(channel.closeAttempted);
    }
    @Test void 通常closeの両解放失敗後もgateを解放して別threadが取得できる() throws Exception {
        Path file=root.toRealPath().resolve("fault.lock");long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        RuntimeLock held=RuntimeLock.acquire(file,end,path->"fixture");
        var lockField=RuntimeLock.class.getDeclaredField("lock");var channelField=RuntimeLock.class.getDeclaredField("channel");
        lockField.setAccessible(true);channelField.setAccessible(true);
        FileLock actualLock=(FileLock)lockField.get(held);FileChannel actualChannel=(FileChannel)channelField.get(held);
        var release=new IOException("release失敗");var close=new IOException("close失敗");var channel=new FailingChannel(close);
        try {
            actualLock.release();actualChannel.close();
            lockField.set(held,lock(channel,release));channelField.set(held,channel);
            IOException failure=assertThrows(IOException.class,held::close);assertSame(release,failure);assertArrayEquals(new Throwable[]{close},failure.getSuppressed());assertTrue(channel.closeAttempted);
        }finally {if(actualLock.isValid())actualLock.release();actualChannel.close();held.close();}
        var task=new FutureTask<Boolean>(()->{try(var next=RuntimeLock.acquire(file,System.nanoTime()+Duration.ofSeconds(2).toNanos(),path->"fixture")){next.requireHeld(file);return true;}});
        Thread thread=Thread.ofPlatform().start(task);
        try{assertTrue(task.get(3,TimeUnit.SECONDS));}finally{if(thread.isAlive())thread.interrupt();thread.join(Duration.ofSeconds(1));}
        assertFalse(thread.isAlive());
    }
    private static FileLock lock(FileChannel channel,IOException failure) {
        return new FileLock(channel,0,1,false) {
            @Override public boolean isValid(){return true;}
            @Override public void release() throws IOException {if(failure!=null)throw failure;}
        };
    }
    private static final class FailingChannel extends FileChannel {
        final IOException failure;boolean closeAttempted;
        FailingChannel(IOException failure){this.failure=failure;}
        @Override protected void implCloseChannel() throws IOException {closeAttempted=true;throw failure;}
        @Override public int read(ByteBuffer value){throw new UnsupportedOperationException();}
        @Override public long read(ByteBuffer[] value,int offset,int length){throw new UnsupportedOperationException();}
        @Override public int write(ByteBuffer value){throw new UnsupportedOperationException();}
        @Override public long write(ByteBuffer[] value,int offset,int length){throw new UnsupportedOperationException();}
        @Override public long position(){throw new UnsupportedOperationException();}
        @Override public FileChannel position(long value){throw new UnsupportedOperationException();}
        @Override public long size(){throw new UnsupportedOperationException();}
        @Override public FileChannel truncate(long value){throw new UnsupportedOperationException();}
        @Override public void force(boolean value){throw new UnsupportedOperationException();}
        @Override public long transferTo(long position,long count,WritableByteChannel target){throw new UnsupportedOperationException();}
        @Override public long transferFrom(ReadableByteChannel source,long position,long count){throw new UnsupportedOperationException();}
        @Override public int read(ByteBuffer value,long position){throw new UnsupportedOperationException();}
        @Override public int write(ByteBuffer value,long position){throw new UnsupportedOperationException();}
        @Override public MappedByteBuffer map(MapMode mode,long position,long size){throw new UnsupportedOperationException();}
        @Override public FileLock lock(long position,long size,boolean shared){throw new UnsupportedOperationException();}
        @Override public FileLock tryLock(long position,long size,boolean shared){throw new UnsupportedOperationException();}
    }
}
