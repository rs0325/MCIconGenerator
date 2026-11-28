package io.github.rs0325.mcicon.helper.macos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {
    @TempDir Path temporary;
    private static class Client extends Process {
        boolean alive;long granted;
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public InputStream getInputStream(){return InputStream.nullInputStream();}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor(){return 0;}
        public boolean waitFor(long timeout,TimeUnit unit){granted=unit.toNanos(timeout);return !alive;}
        public int exitValue(){return 0;}
        public void destroy(){}
        public boolean isAlive(){return alive;}
    }
    private List<String> args(){return List.of(temporary.resolve("client").toString());}
    private Commands commands(Client client,long delay) {
        return new Commands(temporary.resolve("guard"),Map.of(),temporary) {
            @Override Process launch(ProcessBuilder builder) throws IOException {
                Files.write(builder.redirectOutput().file().toPath(),new byte[0]);Files.write(builder.redirectError().file().toPath(),new byte[0]);
                if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException();}
                return client;
            }
        };
    }
    private void empty() throws IOException {try(var files=Files.list(temporary)){assertEquals(0,files.count());}}
    @Test void clientの完了印と出力は終了時に消す() throws Exception {
        assertEquals(0,commands(new Client(),0).capture(args(),Duration.ofSeconds(1)).exit());empty();
    }
    @Test void 期限切れで起動しなくても一時ファイルを残さない() throws Exception {
        Commands commands=commands(new Client(),0);commands.cleanupDeadline(System.nanoTime()+Duration.ofMillis(50).toNanos());
        assertThrows(IOException.class,()->commands.capture(args(),Duration.ofSeconds(5)));empty();
    }
    @Test void clientの失敗と回収失敗を両方保持する() throws Exception {
        Client client=new Client();client.alive=true;Commands commands=commands(client,0);commands.cleanupDeadline(System.nanoTime()+Duration.ofSeconds(4).toNanos());
        var failure=assertThrows(IOException.class,()->commands.capture(args(),Duration.ofSeconds(5)));
        assertEquals("client操作の期限を超えました",failure.getMessage());assertEquals(1,failure.getSuppressed().length);assertEquals("client guardの有限回収を確認できません",failure.getSuppressed()[0].getMessage());empty();
    }
    @Test void client起動時間を待機期限へ足し戻さない() throws Exception {
        Client client=new Client();assertEquals(0,commands(client,150).capture(args(),Duration.ofMillis(400)).exit());assertTrue(client.granted>0&&client.granted<Duration.ofMillis(300).toNanos());empty();
    }
    @Test void 起動中に期限を超えたら終了済みでも成功にしない() throws Exception {
        Client client=new Client();assertThrows(IOException.class,()->commands(client,150).capture(args(),Duration.ofMillis(50)));assertEquals(0,client.granted);empty();
    }
    @Test void guardProtocolを維持して生成の持ち主を渡す() throws Exception {
        Commands commands=new Commands(temporary.resolve("guard"),Map.of(),temporary) {
            @Override Process launch(ProcessBuilder builder) throws IOException {
                List<String> argv=builder.command();assertEquals("--completion",argv.get(1));assertEquals("--intent-sha",argv.get(3));assertEquals(64,argv.get(4).length());assertEquals(List.of("--owner","123","456"),argv.subList(5,8));
                Path completion=Path.of(argv.get(2));Files.writeString(completion,argv.get(4)+"\n");Files.write(builder.redirectOutput().file().toPath(),new byte[0]);Files.write(builder.redirectError().file().toPath(),new byte[0]);return new Client();
            }
        };
        assertEquals(0,commands.generate(args(),Duration.ofSeconds(1),new Commands.Owner(123,456)).exit());empty();
    }
}
