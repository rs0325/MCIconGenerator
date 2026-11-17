package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.CancellationToken;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FailureCleanupTest {
    @TempDir Path root;
    private Path temp() throws IOException {
        try(var paths=Files.list(root)){return paths.filter(p->p.getFileName().toString().startsWith(".download-")&&Files.isRegularFile(p)).findFirst().orElseThrow();}
    }
    private void obstruct(Path path) throws IOException {Files.deleteIfExists(path);Files.createDirectory(path);Files.writeString(path.resolve("owned"),"fixture");}
    @Test void 取得失敗を主とし削除失敗をsuppressedへ残す() throws Exception {
        AtomicInteger attempts=new AtomicInteger();IOException original=new IOException("一次取得失敗");
        var store=new AssetStore(root,false,new CancellationToken(),uri->{attempts.incrementAndGet();obstruct(temp());throw original;});
        IOException failure=assertThrows(IOException.class,()->store.obtain("asset",URI.create("https://fixture.invalid/asset"),null,1,false));
        assertSame(original,failure);assertEquals(3,attempts.get());assertEquals(3,failure.getSuppressed().length);
        for(Throwable cleanup:failure.getSuppressed())assertInstanceOf(DirectoryNotEmptyException.class,cleanup);
    }
    @Test void 成功後に回収だけ失敗したら回収例外を伝える() throws Exception {
        var store=new AssetStore(root,false,new CancellationToken(),uri->{
            Path temp=temp();return new ByteArrayInputStream(new byte[]{1}){
                @Override public void close() throws IOException {obstruct(temp);}
            };
        });
        assertThrows(DirectoryNotEmptyException.class,()->store.obtain("asset",URI.create("https://fixture.invalid/asset"),null,1,false));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(root.resolve("asset")));
    }
    @Test void 中断のcauseと割込みを削除失敗でも維持する() throws Exception {
        InterruptedException original=new InterruptedException("一次中断");
        var store=new AssetStore(root,false,new CancellationToken(),uri->{obstruct(temp());throw original;});
        try {
            IOException failure=assertThrows(IOException.class,()->store.obtain("asset",URI.create("https://fixture.invalid/asset"),null,1,false));
            assertSame(original,failure.getCause());assertTrue(Thread.currentThread().isInterrupted());assertInstanceOf(DirectoryNotEmptyException.class,failure.getSuppressed()[0]);
        }finally {Thread.interrupted();}
    }
}
