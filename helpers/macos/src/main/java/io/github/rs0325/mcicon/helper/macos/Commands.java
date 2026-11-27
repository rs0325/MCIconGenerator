package io.github.rs0325.mcicon.helper.macos;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 固定native guardを介し、自作成client groupを監督する。完了印は一時利用だけにする。 */
class Commands {
    static final long GUARD_RESERVE_NANOS=Duration.ofMillis(2250).toNanos();
    record Result(int exit,byte[] stdout,byte[] stderr) {}
    record Owner(long pid,long startedAtMillis) {}
    private final Path guard,temporary;
    private final Map<String,String> environment;
    private final Map<Process,Path> pipes=new HashMap<>();
    private long cleanupEnd=Long.MAX_VALUE;
    Commands(Path guard,Map<String,String> environment,Path temporary){this.guard=guard;this.environment=Map.copyOf(environment);this.temporary=temporary;}
    void cleanupDeadline(long deadline){cleanupEnd=Math.min(cleanupEnd,deadline);}
    Result capture(List<String> arguments,Duration timeout) throws IOException {return execute(arguments,timeout,null);}
    Result generate(List<String> arguments,Duration timeout,Owner owner) throws IOException {return execute(arguments,timeout,owner);}
    private Result execute(List<String> arguments,Duration timeout,Owner owner) throws IOException {
        long end=Math.min(System.nanoTime()+timeout.toNanos(),cleanupEnd==Long.MAX_VALUE?Long.MAX_VALUE:cleanupEnd-GUARD_RESERVE_NANOS);
        Path directory=Files.createTempDirectory(temporary,"client-");Path output=directory.resolve("stdout"),error=directory.resolve("stderr");Process process=null;IOException failure=null;
        try {
            if(System.nanoTime()>=end)throw new IOException("client開始前に回収余裕が不足しました");
            process=launch(builder(arguments,directory,owner).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile())).redirectOutput(output.toFile()).redirectError(error.toFile()));
            waitFor(process,end);if(Files.size(output)>1024*1024||Files.size(error)>1024*1024)throw new IOException("client応答が上限を超えています");
            return new Result(process.exitValue(),Files.readAllBytes(output),Files.readAllBytes(error));
        }catch(IOException original){failure=original;throw original;}
        finally {
            try{if(process!=null&&process.isAlive())stop(process,cleanupEnd);}
            catch(IOException cleanup){if(failure==null)throw cleanup;failure.addSuppressed(cleanup);}
            finally{erase(directory);}
        }
    }
    Process launch(ProcessBuilder builder) throws IOException {return builder.start();}
    Process generatedPipe(List<String> arguments,Owner owner,boolean worker) throws IOException {
        Path directory=Files.createTempDirectory(temporary,"client-");
        try {
            ProcessBuilder builder=builder(arguments,directory,owner);if(worker)builder.redirectError(ProcessBuilder.Redirect.INHERIT);else builder.inheritIO();
            Process process=launch(builder);pipes.put(process,directory);return process;
        }catch(IOException failure){erase(directory);throw failure;}
    }
    void finish(Process process) throws IOException {Path directory=pipes.remove(process);if(directory!=null)erase(directory);}
    private ProcessBuilder builder(List<String> arguments,Path directory,Owner owner) throws IOException {
        if(arguments.isEmpty()||!Path.of(arguments.getFirst()).isAbsolute())throw new IOException("clientには固定絶対pathが必要です");
        String digest=MacFiles.hash(String.join("\0",arguments).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> argv=new ArrayList<>(List.of(guard.toString(),"--completion",directory.resolve("complete").toString(),"--intent-sha",digest));
        if(owner!=null)argv.addAll(List.of("--owner",Long.toString(owner.pid()),Long.toString(owner.startedAtMillis())));
        argv.add("--");argv.addAll(arguments);ProcessBuilder builder=new ProcessBuilder(argv);builder.environment().clear();builder.environment().putAll(environment);return builder;
    }
    private static void erase(Path directory) throws IOException {
        try(var files=Files.newDirectoryStream(directory)){for(Path file:files)Files.delete(file);}Files.delete(directory);
    }
    private static void waitFor(Process process,long deadline) throws IOException {
        try{long left=deadline-System.nanoTime();if(left<=0||!process.waitFor(left,TimeUnit.NANOSECONDS)||System.nanoTime()>deadline)throw new IOException("client操作の期限を超えました");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("client操作が中断されました");}
    }
    static void stop(Process process) throws IOException {stop(process,Long.MAX_VALUE);}
    private static void stop(Process process,long deadline) throws IOException {
        boolean interrupted=Thread.interrupted();process.destroy();long remaining=deadline==Long.MAX_VALUE?TimeUnit.SECONDS.toNanos(3):Math.max(0,Math.min(TimeUnit.SECONDS.toNanos(3),deadline-System.nanoTime()));
        try{try{if(!process.waitFor(remaining,TimeUnit.NANOSECONDS))throw new IOException("client guardの有限回収を確認できません");}catch(InterruptedException failure){interrupted=true;throw new IOException("client guard回収中に中断されました",failure);}}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
}
