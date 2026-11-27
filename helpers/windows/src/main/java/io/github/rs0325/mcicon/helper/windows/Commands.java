package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** native終了でJobの回収を確認し、guest終端はguest応答で確認する。 */
final class Commands {
    record Result(int exit,byte[] stdout,byte[] stderr) {}
    private final Path nativeHost,temporary;
    private final Map<String,String> environment;
    private volatile long deadline;
    private JsonNode keeper;
    record Running(Process process,Path prefix) {}
    Commands(Path nativeHost,Path temporary,Map<String,String> environment,long deadline){this.nativeHost=nativeHost;this.temporary=temporary;this.environment=Map.copyOf(environment);this.deadline=deadline;}
    Duration left(Duration maximum) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Windows操作が中断されました");long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Windows操作の期限を超えました");return Duration.ofNanos(Math.min(left,maximum.toNanos()));
    }
    synchronized void cleanup(){deadline=Math.min(deadline,System.nanoTime()+Duration.ofSeconds(30).toNanos());}
    Result capture(List<String> args) throws IOException {return capture(args,Duration.ofNanos(Long.MAX_VALUE));}
    Result capture(List<String> args,Duration maximum) throws IOException {return capture(args,maximum,null);}
    Result capture(List<String> args,Duration maximum,Path input) throws IOException {return exchange(args,null,input,null,maximum);}
    Result request(List<String> args,byte[] request,Path input,Duration maximum) throws IOException {return exchange(args,request,input,null,maximum);}
    void fetch(List<String> args,byte[] request,Path target,Duration maximum) throws IOException {exchange(args,request,null,target,maximum);}
    static byte[] boundedRequest(byte[] request,long end) throws IOException {
        JsonNode value=WindowsFiles.JSON.readTree(request);
        if(value==null||!value.isObject()||!value.path("timeoutSeconds").isNumber())return request;
        long remaining=end-System.nanoTime();if(remaining<=0)throw new IOException("guest送信前にnative操作がtimeoutしました");
        double seconds=Math.min(value.path("timeoutSeconds").asDouble(),remaining/1_000_000_000.0);
        ((ObjectNode)value).put("timeoutSeconds",seconds);return WindowsFiles.JSON.writeValueAsBytes(value);
    }
    private String operation(List<String> args,byte[] request) {
        if(request!=null)try{JsonNode node=WindowsFiles.JSON.readTree(request);String value=node==null?"":node.path("operation").asText();return Set.of("hello","daemon-prepare","daemon-recover","load","create","start","remove","fetch","release","release-recover").contains(value)?"guest-"+value:"guest-other";}catch(IOException failure){return "guest-other";}
        if(args.size()==2&&args.getFirst().equals(nativeHost.toString())&&args.get(1).equals("facts"))return "facts";
        if(args.size()>1)return switch(args.get(1)){case "--status"->"wsl-status";case "--list"->"wsl-list";case "--terminate"->"wsl-stop";case "--import"->"wsl-import";default->"native-other";};return "native-other";
    }
    private Result exchange(List<String> args,byte[] request,Path input,Path target,Duration maximum) throws IOException {
        String operation=operation(args,request);
        Path prefix=temporary.resolve("native-"+UUID.randomUUID()),out=target==null?Files.createTempFile(temporary,"stdout-",".bin"):target,err=Files.createTempFile(temporary,"stderr-",".bin");
        Process process=null;Thread writer=null;IOException error=null;Result result=null;AtomicReference<IOException> writerFailure=new AtomicReference<>();
        try {
            long end=System.nanoTime()+left(maximum).toNanos();
            ProcessBuilder builder=(request==null?builder(args,"exec",0,0,prefix):bridgeBuilder(args,prefix)).redirectOutput(out.toFile()).redirectError(err.toFile());if(request==null&&input!=null)builder.redirectInput(input.toFile());process=builder.start();Process child=process;
            if(request!=null)writer=Thread.ofVirtual().start(()->{try(OutputStream stream=child.getOutputStream()){stream.write(boundedRequest(request,end));stream.write('\n');if(input!=null)try(InputStream source=Files.newInputStream(input)){source.transferTo(stream);}}catch(IOException failure){writerFailure.set(failure);}});
            else if(input==null)process.getOutputStream().close();
            int exit=waitUntil(process,end);
            if(writer!=null)join(writer,left(Duration.ofNanos(Long.MAX_VALUE)));
            if(writerFailure.get()!=null)throw writerFailure.get();
            if(Files.size(out)>(target==null?1024*1024:64L*1024*1024)||Files.size(err)>1024*1024)throw new IOException("Windows応答が上限を超えました");
            result=new Result(exit,target==null?Files.readAllBytes(out):new byte[0],Files.readAllBytes(err));
            if(target!=null)zero(result,"guest PNG取得 "+target);
            checkDeadline(end);
        }catch(IOException failure){error=failure;}
        finally {
            if(process!=null&&process.isAlive())try{stop(process);}catch(IOException failure){error=merge(error,failure);}
            if(writer!=null)try{join(writer,Duration.ofSeconds(2));}catch(IOException failure){error=merge(error,failure);}
            if(error!=null) {
                String stderr="";try{stderr=stderrTail(err);}catch(IOException failure){error.addSuppressed(failure);}
                error=new IOException(operation+" file="+prefix+" exit="+(process!=null&&!process.isAlive()?process.exitValue():"不明")+": "+error.getMessage()+"\nstderr末尾: "+stderr+"\nwsl --shutdownを実行して再試行してください",error);
            }
            try{if(target==null)Files.deleteIfExists(out);Files.deleteIfExists(err);}catch(IOException failure){error=merge(error,failure);}
        }
        if(error!=null)throw error;return result;
    }
    private static String stderrTail(Path path) throws IOException {
        try(var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            long size=channel.size();ByteBuffer tail=ByteBuffer.allocate((int)Math.min(size,8192));channel.position(Math.max(0,size-8192));while(tail.hasRemaining()&&channel.read(tail)!=-1){}
            return new String(tail.array(),0,tail.position(),StandardCharsets.UTF_8);
        }
    }
    Running attached(List<String> args) throws IOException {left(Duration.ofSeconds(30));Path prefix=temporary.resolve("native-"+UUID.randomUUID());return new Running(bridgeBuilder(args,prefix).redirectError(ProcessBuilder.Redirect.INHERIT).start(),prefix);}
    void keeper(JsonNode value) throws IOException {
        if(!Set.of("ready","closing").contains(value.path("state").asText())||!value.path("client").isObject())throw new IOException("keeper ACKがありません");keeper=value.deepCopy();
    }
    private ProcessBuilder bridgeBuilder(List<String> args,Path prefix) throws IOException {
        if(keeper==null)throw new IOException("専用WSL keeperが確立していません");JsonNode client=keeper.path("client");
        return builder(args,"exec-bridge",client.path("clientPid").asLong(),client.path("clientStarted").asLong(),prefix);
    }
    Running keep(List<String> args,long core,long started) throws IOException {
        left(Duration.ofMinutes(3));Path prefix=temporary.resolve("native-"+UUID.randomUUID());return new Running(builder(args,"exec-keeper",core,started,prefix).start(),prefix);
    }
    private ProcessBuilder builder(List<String> args,String mode,long witness,long witnessStarted,Path record) throws IOException {
        if(args.isEmpty()||!Path.of(args.getFirst()).isAbsolute())throw new IOException("実行物は固定絶対pathが必要です");
        long parent=ProcessHandle.current().pid(),started=ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("helper開始identity不明")).toEpochMilli();
        List<String> command=new ArrayList<>(List.of(nativeHost.toString(),mode,Long.toString(parent),Long.toString(started),record.toString(),"--"));if(!mode.equals("exec"))command.addAll(List.of(Long.toString(witness),Long.toString(witnessStarted),"--"));command.addAll(args);
        ProcessBuilder result=new ProcessBuilder(command).directory(temporary.toFile());result.environment().clear();result.environment().putAll(environment);return result;
    }
    void reap(String boot) throws IOException {reap(boot,null);}
    void reap(String boot,Path keeperPrefix) throws IOException {
        try(DirectoryStream<Path> records=Files.newDirectoryStream(temporary,"native-*.state.json")) {
            for(Path file:records) {
                Path prefix=Path.of(file.toString().replace(".state.json",""));if(prefix.equals(keeperPrefix))continue;
                JsonNode state=WindowsFiles.json(file);if(!HostFacts.uuid(state.path("boot").asText()))throw new IOException("nativeのbootが不明です: "+file+"。wsl --shutdownを実行して確認してください");if(!state.path("boot").asText().equals(boot))continue;
                if(!terminal(state)) {
                    ProcessHandle process=ProcessHandle.of(state.path("pid").asLong()).orElse(null);if(process==null)continue;
                    try{process.onExit().get(left(Duration.ofNanos(Long.MAX_VALUE)).toNanos(),TimeUnit.NANOSECONDS);}
                    catch(TimeoutException failure){throw new IOException("nativeが稼働中です: "+file+"。wsl --shutdownを実行して再試行してください",failure);}
                    catch(ExecutionException failure){throw new IOException("native終了を確認できません: "+file,failure);}
                    catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("native回収が中断されました: "+file);}
                }
            }
        }
    }
    boolean terminal(Path prefix) throws IOException {
        try{return terminal(WindowsFiles.json(Path.of(prefix+".state.json")));}
        catch(IOException failure){throw new IOException("native終了を確認できません: "+prefix+"。wsl --shutdownを実行して再試行してください",failure);}
    }
    boolean terminal(Running running) {return !running.process().isAlive();}
    private static boolean terminal(JsonNode state) throws IOException {
        long pid=state.path("pid").asLong(),started=state.path("started").asLong();
        if(pid<1||started<1)throw new IOException("nativeのPID/開始時刻が不明です。jobのnative fileを確認し、wsl --shutdownを実行してください");
        return WindowsKeeper.ownerDead(ProcessHandle.of(pid),java.time.Instant.ofEpochMilli(started));
    }
    long end(){return deadline;}
    synchronized void end(long value){deadline=Math.min(deadline,value);}
    int waitFor(Process process,Duration maximum) throws IOException {
        try{if(!process.waitFor(Math.max(0,maximum.toNanos()),TimeUnit.NANOSECONDS))throw new IOException("Windows native操作がtimeoutしました");return process.exitValue();}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("Windows native待機が中断されました");}
    }
    int waitUntil(Process process,long end) throws IOException {
        checkDeadline(end);int exit=waitFor(process,Duration.ofNanos(Math.max(0,end-System.nanoTime())));checkDeadline(end);return exit;
    }
    private static void checkDeadline(long end) throws IOException {if(System.nanoTime()>=end)throw new IOException("Windows native操作がtimeoutしました");}
    void join(Thread thread,Duration maximum) throws IOException {
        boolean interrupted=Thread.interrupted();
        try{long nanos=Math.max(0,Math.min(maximum.toNanos(),deadline-System.nanoTime()));if(nanos>0)thread.join(Duration.ofNanos(nanos));if(thread.isAlive())throw new IOException("Windows入力/出力clientの回収が未確認です");}
        catch(InterruptedException failure){interrupted=true;throw new IOException("Windows client回収が中断されました",failure);}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    void stop(Process process) throws IOException {
        boolean interrupted=Thread.interrupted();
        try {
            long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            if(process.waitFor(Math.max(0,end-System.nanoTime()-Duration.ofMillis(250).toNanos()),TimeUnit.NANOSECONDS))return;
            process.destroy();if(!process.waitFor(Math.max(0,end-System.nanoTime()),TimeUnit.NANOSECONDS)){process.destroyForcibly();if(!process.waitFor(Math.max(0,end-System.nanoTime()),TimeUnit.NANOSECONDS))throw new IOException("native Job clientの回収が未確認です");}
        }catch(InterruptedException failure){interrupted=true;process.destroyForcibly();throw new IOException("native Job回収が中断されました",failure);}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    static IOException merge(IOException original,IOException additional){if(original==null)return additional;if(original!=additional)original.addSuppressed(additional);return original;}
    static void zero(Result result,String operation) throws IOException {
        if(result.exit()==0)return;
        String message=operation+"が失敗しました（exit "+result.exit()+"）";
        byte[] output=result.stderr().length==0?result.stdout():result.stderr();
        if(output.length>0) {
            int start=Math.max(0,output.length-8192);String source=result.stderr().length==0?"stdout":"stderr";
            message+="\n"+source+" ("+(start>0?"末尾8192 / ":"")+output.length+" bytes):\n"+new String(output,start,output.length-start,StandardCharsets.UTF_8);
        }
        throw new IOException(message+"\nwsl --shutdownを実行して再試行してください");
    }
}
