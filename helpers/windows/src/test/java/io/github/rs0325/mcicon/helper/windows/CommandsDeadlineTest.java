package io.github.rs0325.mcicon.helper.windows;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommandsDeadlineTest {
    @TempDir Path temporary;
    @Test void 待機開始前に期限を超えた終了済みexit0を成功にしない() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),Long.MAX_VALUE);
        long expired=System.nanoTime()-Duration.ofMillis(1).toNanos();
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,process.exitValue());assertTrue(process.waitFor(0,TimeUnit.NANOSECONDS));
            IOException failure=assertThrows(IOException.class,()->commands.waitUntil(process,expired));assertTrue(failure.getMessage().contains("timeout"));
        } finally {
            if(process.isAlive())process.destroyForcibly();assertTrue(process.waitFor(2,TimeUnit.SECONDS));
            process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();
        }
    }
    @Test void 待機成功を受け取った時点で期限超過なら失敗する() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),Long.MAX_VALUE);
        boolean[] waitReturned={false};
        Process completed=new Process(){
            public InputStream getInputStream(){return InputStream.nullInputStream();}public InputStream getErrorStream(){return InputStream.nullInputStream();}public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
            public int waitFor(){return 0;}public int exitValue(){return 0;}public void destroy(){}public boolean isAlive(){return false;}
            public boolean waitFor(long timeout,TimeUnit unit) throws InterruptedException {assertTrue(timeout>0);TimeUnit.NANOSECONDS.sleep(unit.toNanos(timeout)+Duration.ofMillis(20).toNanos());waitReturned[0]=true;return true;}
        };
        long end=System.nanoTime()+Duration.ofSeconds(1).toNanos();
        IOException failure=assertThrows(IOException.class,()->commands.waitUntil(completed,end));
        assertTrue(waitReturned[0],"待機後検査の前にwaitForが成功復帰している必要があります");assertTrue(failure.getMessage().contains("timeout"));
    }
}
