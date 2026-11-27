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
    @Test void 強制終了したnativeはdoneを作らなくても回収できる() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),Long.MAX_VALUE);Path prefix=temporary.resolve("native-test");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",Path.of(WaitingProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),WaitingProcess.class.getName()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            WindowsFiles.write(Path.of(prefix+".state.json"),WindowsFiles.JSON.createObjectNode().put("pid",process.pid()).put("started",process.info().startInstant().orElseThrow().toEpochMilli()).put("boot",OwnershipTest.BOOT));
            process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));assertTrue(commands.terminal(prefix));
            assertDoesNotThrow(()->commands.reap(OwnershipTest.BOOT));assertFalse(Files.exists(Path.of(prefix+".done.json")));
        } finally {if(process.isAlive())process.destroyForcibly();assertTrue(process.waitFor(2,TimeUnit.SECONDS));process.getOutputStream().close();process.getInputStream().close();process.getErrorStream().close();}
    }
    public static class WaitingProcess {public static void main(String[] args) throws Exception {System.in.read();}}
    @Test void doneがなくてもnativeのPIDと開始時刻で終了を判定する() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),Long.MAX_VALUE);Path prefix=temporary.resolve("native-test");
        var state=WindowsFiles.JSON.createObjectNode().put("pid",ProcessHandle.current().pid()).put("started",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());
        WindowsFiles.write(Path.of(prefix+".state.json"),state);assertFalse(commands.terminal(prefix));
        state.put("started",state.path("started").asLong()+1);WindowsFiles.write(Path.of(prefix+".state.json"),state);assertTrue(commands.terminal(prefix));
        assertFalse(Files.exists(Path.of(prefix+".done.json")));
    }
    @Test void 生存中nativeの回収期限超過ではPIDを殺さず対象と対処を示す() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),Long.MAX_VALUE);Path prefix=temporary.resolve("native-test");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",Path.of(WaitingProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),WaitingProcess.class.getName()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            WindowsFiles.write(Path.of(prefix+".state.json"),WindowsFiles.JSON.createObjectNode().put("pid",process.pid()).put("started",process.info().startInstant().orElseThrow().toEpochMilli()).put("boot",OwnershipTest.BOOT));
            commands.end(System.nanoTime()+Duration.ofMillis(100).toNanos());IOException failure=assertThrows(IOException.class,()->commands.reap(OwnershipTest.BOOT));
            assertTrue(failure.getMessage().contains(prefix.toString()));assertTrue(failure.getMessage().contains("wsl --shutdown"));assertTrue(process.isAlive());
        } finally {process.destroyForcibly();assertTrue(process.waitFor(2,TimeUnit.SECONDS));process.getOutputStream().close();process.getInputStream().close();process.getErrorStream().close();}
    }
    @Test void 再起動前のnative記録は現在のPIDが生存していても待機しない() throws Exception {
        Commands commands=new Commands(temporary.resolve("unused"),temporary,Map.of(),Long.MAX_VALUE);Path prefix=temporary.resolve("native-test");
        WindowsFiles.write(Path.of(prefix+".state.json"),WindowsFiles.JSON.createObjectNode().put("pid",ProcessHandle.current().pid()).put("started",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()).put("boot",java.util.UUID.randomUUID().toString()));
        assertDoesNotThrow(()->commands.reap(OwnershipTest.BOOT));
    }
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
