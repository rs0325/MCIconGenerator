package io.github.rs0325.mcicon.helper.macos;

import java.io.*;
import io.github.rs0325.mcicon.internal.RuntimePhaseLog;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 固定native guardを介し、helperの終了時にも自作成client groupを監督する。 */
class Commands {
    // native guardの200回×10msの回収に、終了記録とscheduleの余裕を加える。
    static final long GUARD_RESERVE_NANOS=Duration.ofMillis(2250).toNanos();
    record Result(int exit,byte[] stdout,byte[] stderr) {}
    record Owner(long pid,long startedAtMillis) {}
    private final Path guard;
    private final Map<String,String> environment;
    private final Path temporary;
    private long cleanupEnd=Long.MAX_VALUE;
    Commands(Path guard,Map<String,String> environment,Path temporary) {this.guard=guard;this.environment=Map.copyOf(environment);this.temporary=temporary;}
    void cleanupDeadline(long deadline){cleanupEnd=Math.min(cleanupEnd,deadline);}
    Result capture(List<String> arguments,Duration timeout) throws IOException {
        return capture(arguments,System.nanoTime()+timeout.toNanos(),builder(arguments,null,null),null);
    }
    Result generate(List<String> arguments,Duration timeout,OperationJournal.Operation operation,Owner owner) throws IOException {
        if(owner==null)throw new IOException("生成操作には元core identityが必要です");
        return capture(arguments,System.nanoTime()+timeout.toNanos(),builder(arguments,operation,owner),operation);
    }
    Result recover(List<String> arguments,Duration timeout,OperationJournal.Operation operation) throws IOException {
        return recoverUntil(arguments,System.nanoTime()+timeout.toNanos(),operation);
    }
    Result recoverUntil(List<String> arguments,long deadline,OperationJournal.Operation operation) throws IOException {
        return capture(arguments,deadline,builder(arguments,operation,null),operation);
    }
    private Result capture(List<String> arguments,long deadline,ProcessBuilder builder,OperationJournal.Operation operation) throws IOException {
        Path output=Files.createTempFile(temporary,"client-",".stdout"),error=Files.createTempFile(temporary,"client-",".stderr");
        Process process=null;IOException failure=null;
        RuntimePhaseLog phases=cleanupEnd==Long.MAX_VALUE?null:new RuntimePhaseLog(temporary,"macos-client",operation==null?"capture":"recover",operation==null?null:operation.directory().getFileName().toString());
        try {
            long clientEnd=deadline;
            if(cleanupEnd!=Long.MAX_VALUE)clientEnd=Math.min(clientEnd,cleanupEnd-GUARD_RESERVE_NANOS);
            long remaining=clientEnd-System.nanoTime();
            if(remaining<=0)throw new IOException("client開始前に回収余裕が不足しました");
            Duration wait=Duration.ofNanos(remaining);
            if(phases!=null)phases.event("client-start",cleanupEnd,wait.toNanos());
            process=launch(builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile())).redirectOutput(output.toFile()).redirectError(error.toFile()));
            if(phases!=null)phases.event("client-started",cleanupEnd,wait.toNanos());
            waitFor(process,clientEnd);if(phases!=null)phases.event("client-ended",cleanupEnd,0);if(Files.size(output)>1024*1024||Files.size(error)>1024*1024)throw new IOException("client応答が上限を超えています");
            return new Result(process.exitValue(),Files.readAllBytes(output),Files.readAllBytes(error));
        }catch(IOException original){failure=original;if(phases!=null)phases.event("client-failed",cleanupEnd,0);throw original;}
        finally {
            if(process!=null&&process.isAlive())try{if(phases!=null)phases.event("guard-reap-start",cleanupEnd,TimeUnit.SECONDS.toNanos(3));stop(process,cleanupEnd);if(phases!=null)phases.event("guard-reaped",cleanupEnd,0);}catch(IOException cleanup){if(phases!=null)phases.event("guard-reap-failed",cleanupEnd,0);if(failure==null)throw cleanup;failure.addSuppressed(cleanup);}
            if(phases!=null&&operation!=null)phases.event(Files.isRegularFile(operation.completion(),LinkOption.NOFOLLOW_LINKS)?"completion-present":"completion-absent",cleanupEnd,0);
        }
    }
    Process launch(ProcessBuilder builder) throws IOException {return builder.start();}
    int attached(List<String> arguments) throws IOException {
        Process process=builder(arguments,null,null).inheritIO().start();
        try {return process.waitFor();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("client輸送が中断されました");}
        finally {if(process.isAlive())stop(process);}
    }
    Process generatedPipe(List<String> arguments,OperationJournal.Operation operation,Owner owner,boolean worker) throws IOException {
        if(owner==null)throw new IOException("生成操作には元core identityが必要です");ProcessBuilder builder=builder(arguments,operation,owner);
        if(worker)builder.redirectError(ProcessBuilder.Redirect.INHERIT);else builder.inheritIO();return builder.start();
    }
    Process pipe(List<String> arguments) throws IOException {return builder(arguments,null,null).redirectError(ProcessBuilder.Redirect.INHERIT).start();}
    private ProcessBuilder builder(List<String> arguments,OperationJournal.Operation operation,Owner owner) throws IOException {
        if(arguments.isEmpty()||!Path.of(arguments.getFirst()).isAbsolute())throw new IOException("clientには固定絶対pathが必要です");
        Path completion;String digest;
        if(operation==null){Path directory=Files.createTempDirectory(temporary,"read-client-");completion=directory.resolve("complete");digest=MacFiles.hash(String.join("\0",arguments).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        else {completion=operation.completion();digest=operation.sha256();}
        List<String> argv=new ArrayList<>(List.of(guard.toString(),"--completion",completion.toString(),"--intent-sha",digest));
        if(owner!=null)argv.addAll(List.of("--owner",Long.toString(owner.pid()),Long.toString(owner.startedAtMillis())));
        argv.add("--");argv.addAll(arguments);ProcessBuilder builder=new ProcessBuilder(argv);builder.environment().clear();builder.environment().putAll(environment);return builder;
    }
    private static void waitFor(Process process,long deadline) throws IOException {
        try {long left=deadline-System.nanoTime();if(left<=0||!process.waitFor(left,TimeUnit.NANOSECONDS)||System.nanoTime()>deadline)throw new IOException("client操作の期限を超えました");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("client操作が中断されました");}
    }
    static void stop(Process process) throws IOException {stop(process,Long.MAX_VALUE);}
    private static void stop(Process process,long deadline) throws IOException {
        boolean interrupted=Thread.interrupted();process.destroy();
        long remaining=deadline==Long.MAX_VALUE?TimeUnit.SECONDS.toNanos(3):Math.max(0,Math.min(TimeUnit.SECONDS.toNanos(3),deadline-System.nanoTime()));
        try {try {if(!process.waitFor(remaining,TimeUnit.NANOSECONDS))throw new IOException("client guardの有限回収を確認できません");}catch(InterruptedException failure){interrupted=true;throw new IOException("client guard回収中に中断されました",failure);}}
        finally {if(interrupted)Thread.currentThread().interrupt();}
    }
}
