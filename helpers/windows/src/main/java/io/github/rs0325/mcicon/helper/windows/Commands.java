package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** native Jobの終端recordだけを信頼する。guest終端は別のfenceで検査する。 */
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
    synchronized void cleanup(){deadline=Math.min(deadline,System.nanoTime()+Duration.ofSeconds(9).toNanos());}
    private Duration runBudget(Duration maximum) throws IOException {
        Duration remaining=left(maximum);if(deadline==Long.MAX_VALUE)return remaining;
        long nanos=Math.min(maximum.toNanos(),deadline-System.nanoTime()-Duration.ofSeconds(2).toNanos());if(nanos<=0)throw new IOException("native回収枠を残せません");return Duration.ofNanos(nanos);
    }
    Result capture(List<String> args,Duration maximum) throws IOException {return capture(args,maximum,null);}
    Result capture(List<String> args,Duration maximum,Path input) throws IOException {return exchange(args,null,input,null,maximum);}
    Result request(List<String> args,byte[] request,Path input,Duration maximum) throws IOException {return exchange(args,request,input,null,maximum);}
    void fetch(List<String> args,byte[] request,Path target,Duration maximum) throws IOException {exchange(args,request,null,target,maximum);}
    private Result exchange(List<String> args,byte[] request,Path input,Path target,Duration maximum) throws IOException {
        runBudget(maximum);Path out=target==null?Files.createTempFile(temporary,"stdout-",".bin"):target,err=Files.createTempFile(temporary,"stderr-",".bin");Process process=null;Thread writer=null;IOException error=null;Result result=null;AtomicReference<IOException> writerFailure=new AtomicReference<>();
        try {
            ProcessBuilder builder=(request==null?builder(args):bridgeBuilder(args)).redirectOutput(out.toFile()).redirectError(err.toFile());if(request==null&&input!=null)builder.redirectInput(input.toFile());process=builder.start();Process child=process;
            if(request!=null)writer=Thread.ofVirtual().start(()->{try(OutputStream stream=child.getOutputStream()){stream.write(request);stream.write('\n');if(input!=null)try(InputStream source=Files.newInputStream(input)){source.transferTo(stream);}}catch(IOException failure){writerFailure.set(failure);child.destroy();}});
            else if(input==null)process.getOutputStream().close();
            int exit=waitFor(process,runBudget(maximum));
            if(writerFailure.get()!=null)throw writerFailure.get();
            if(Files.size(out)>(target==null?1024*1024:64L*1024*1024)||Files.size(err)>1024*1024)throw new IOException("Windows応答が上限を超えました");
            if(target!=null&&exit!=0)throw new IOException("guest PNGの取得に失敗しました");result=new Result(exit,target==null?Files.readAllBytes(out):new byte[0],Files.readAllBytes(err));
        }catch(IOException failure){error=failure;}
        finally {
            if(process!=null) {
                if(process.isAlive())try{stop(process);}catch(IOException failure){error=merge(error,failure);}
                if(writer!=null) {
                    try{join(writer,Duration.ofMillis(500));}catch(IOException failure){error=merge(error,failure);}
                    if(writerFailure.get()!=null&&writerFailure.get()!=error)error=merge(error,writerFailure.get());
                }
            }
            try{if(target==null)Files.deleteIfExists(out);Files.deleteIfExists(err);}catch(IOException failure){error=merge(error,failure);}
        }
        if(error!=null)throw error;return result;
    }
    Running attached(List<String> args) throws IOException {runBudget(Duration.ofSeconds(30));Path prefix=temporary.resolve("native-"+UUID.randomUUID());return new Running(bridgeBuilder(args,prefix).redirectError(ProcessBuilder.Redirect.INHERIT).start(),prefix);}
    void keeper(JsonNode value) throws IOException {
        if(!Set.of("ready","closing").contains(value.path("state").asText())||!value.path("client").isObject())throw new IOException("keeper ACKがありません");keeper=value.deepCopy();
    }
    private ProcessBuilder bridgeBuilder(List<String> args) throws IOException {return bridgeBuilder(args,temporary.resolve("native-"+UUID.randomUUID()));}
    private ProcessBuilder bridgeBuilder(List<String> args,Path prefix) throws IOException {
        if(keeper==null)throw new IOException("専用WSL keeperが確立していません");JsonNode client=keeper.path("client");
        return builder(args,"exec-bridge",client.path("clientPid").asLong(),client.path("clientStarted").asLong(),prefix);
    }
    Running keep(List<String> args,long core,long started) throws IOException {
        runBudget(Duration.ofMinutes(3));Path prefix=temporary.resolve("native-"+UUID.randomUUID());return new Running(builder(args,"exec-keeper",core,started,prefix).start(),prefix);
    }
    private ProcessBuilder builder(List<String> args) throws IOException {return builder(args,"exec",0,0,temporary.resolve("native-"+UUID.randomUUID()));}
    private ProcessBuilder builder(List<String> args,String mode,long witness,long witnessStarted,Path record) throws IOException {
        if(args.isEmpty()||!Path.of(args.getFirst()).isAbsolute())throw new IOException("実行物は固定絶対pathが必要です");
        long parent=ProcessHandle.current().pid(),started=ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("helper開始identity不明")).toEpochMilli();
        var intent=WindowsFiles.JSON.createObjectNode().put("parent",parent).put("started",started).put("native",nativeHost.toString()).put("argvSha256",WindowsFiles.hash(WindowsFiles.JSON.writeValueAsBytes(args)));Files.write(record.resolveSibling(record.getFileName()+".intent.json"),WindowsFiles.JSON.writeValueAsBytes(intent),StandardOpenOption.CREATE_NEW);
        List<String> command=new ArrayList<>(List.of(nativeHost.toString(),mode,Long.toString(parent),Long.toString(started),record.toString(),"--"));if(!mode.equals("exec"))command.addAll(List.of(Long.toString(witness),Long.toString(witnessStarted),"--"));command.addAll(args);
        ProcessBuilder result=new ProcessBuilder(command).directory(temporary.toFile());result.environment().clear();result.environment().putAll(environment);return result;
    }
    void reap(String boot,JsonNode transports) throws IOException {reap(boot,transports,null);}
    void reap(String boot,JsonNode transports,Path keeperPrefix) throws IOException {
        Set<String> recorded=new HashSet<>();IOException error=null;
        try(DirectoryStream<Path> records=Files.newDirectoryStream(temporary,"native-*.intent.json")) {
            for(Path intent:records) {
                try {
                    Receipt receipt=record(intent);JsonNode value=receipt.value();recorded.add(value.path("pid").asLong()+":"+value.path("started").asLong());
                    boolean rejected=noJob(intent,receipt);
                    if(receipt.done()||!value.path("boot").asText().equals(boot)||keeperPrefix!=null&&intent.equals(Path.of(keeperPrefix+".intent.json")))continue;
                    ProcessHandle process=ProcessHandle.of(value.path("pid").asLong()).orElse(null);
                    if(process!=null&&process.isAlive()) {
                        requireOwned(process,value);
                        try{process.onExit().get(runBudget(Duration.ofSeconds(2)).toNanos(),TimeUnit.NANOSECONDS);}
                        catch(TimeoutException timeout){}catch(ExecutionException failure){throw new IOException("native Jobの自然終端が不明です",failure.getCause());}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("native Job回復が中断されました");}
                        Receipt observed=record(intent);if(!observed.value().equals(value))throw new IOException("待機中にnative本人recordが変化しました");rejected=noJob(intent,observed);if(observed.done())continue;
                        if(process.isAlive()){requireOwned(process,value);process.destroyForcibly();throw new IOException("本人native Job残留を強制回収しました。Job0は未確認です");}
                    }
                    if(rejected)continue;
                    throw new IOException("同bootのnative Job終端recordがありません。guard死亡では完了にしません");
                }catch(IOException failure){error=merge(error,failure);}
            }
        }
        for(JsonNode transport:transports)if(transport.has("pid")) {
            String key=transport.path("pid").asLong()+":"+java.time.Instant.parse(transport.path("processStartedAt").asText()).toEpochMilli();if(!recorded.contains(key))error=merge(error,new IOException("輸送とnative Jobの所有recordが一致しません"));
        }
        if(error!=null)throw error;
    }
    private void requireOwned(ProcessHandle process,JsonNode value) throws IOException {
        if(!process.info().startInstant().map(java.time.Instant::toEpochMilli).filter(time->time==value.path("started").asLong()).isPresent()||!process.info().command().map(Path::of).filter(nativeHost::equals).isPresent())throw new IOException("native Jobの実所有が不明です");
    }
    boolean terminal(Path prefix) throws IOException {return record(Path.of(prefix+".intent.json")).done();}
    boolean terminal(Running running,long started) throws IOException {
        Path intent=Path.of(running.prefix()+".intent.json");Receipt receipt=record(intent);
        if(receipt.value().path("pid").asLong()!=running.process().pid()||receipt.value().path("started").asLong()!=started)throw new IOException("保持native Processとrecordが一致しません");
        return noJob(intent,receipt)||receipt.done();
    }
    private boolean noJob(Path intent,Receipt receipt) throws IOException {
        Path path=Path.of(intent.toString().replace(".intent.json",".rejected.json"));
        try{var info=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!info.isRegularFile()||info.size()>4096)throw new IOException("native起動拒否recordを安全に読めません");}catch(NoSuchFileException absent){return false;}
        byte[] bytes;try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(4097);}if(bytes.length>4096)throw new IOException("native起動拒否recordが上限を超えました");
        JsonNode value=WindowsFiles.JSON.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes),expected=WindowsFiles.json(intent);
        if(receipt.done()||value==null||!value.isObject()||value.size()!=10||!value.path("schema").isIntegralNumber()||!value.path("schema").canConvertToInt()||value.path("schema").asInt()!=1||!value.path("kind").asText().equals("reject-no-job")||!value.path("phase").asText().equals("before-job-create")||!Set.of("exec-bridge","exec-keeper").contains(value.path("mode").asText())||!Set.of("witness","child-argv").contains(value.path("reason").asText())||!value.path("state").equals(receipt.value())||!value.path("jobCreated").isBoolean()||value.path("jobCreated").asBoolean()||!value.path("childCreated").isBoolean()||value.path("childCreated").asBoolean()||!value.path("plannedGuardExit").isIntegralNumber()||!value.path("plannedGuardExit").canConvertToInt()||value.path("plannedGuardExit").asInt()!=1||!value.path("argv").isArray()||value.path("argv").size()!=8)throw new IOException("native起動拒否の肯定証拠が不正です");
        for(JsonNode arg:value.path("argv"))if(!arg.isTextual())throw new IOException("native起動拒否argvが不正です");
        if(!WindowsFiles.hash(WindowsFiles.JSON.writeValueAsBytes(value.path("argv"))).equals(expected.path("argvSha256").asText()))throw new IOException("native起動拒否argvがintentと一致しません");return true;
    }
    long end(){return deadline;}
    synchronized void end(long value){deadline=Math.min(deadline,value);}
    private record Receipt(JsonNode value,boolean done) {}
    private Receipt record(Path intent) throws IOException {
        JsonNode expected=WindowsFiles.json(intent);if(!expected.path("native").asText().equals(nativeHost.toString()))throw new IOException("native intent実行物が一致しません");
        String base=intent.getFileName().toString().replace(".intent.json","");Path state=temporary.resolve(base+".state.json"),done=temporary.resolve(base+".done.json");
        JsonNode value=WindowsFiles.json(state);if(!value.path("parent").equals(expected.path("parent"))||!value.path("parentStarted").equals(expected.path("started"))||!HostFacts.uuid(value.path("boot").asText())||value.path("pid").asLong()<1||value.path("started").asLong()<1)throw new IOException("native Job所有recordが一致しません");
        try{Files.readAttributes(done,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){return new Receipt(value,false);}
        if(!WindowsFiles.json(done).equals(value))throw new IOException("native Job終端recordが一致しません");return new Receipt(value,true);
    }
    int waitFor(Process process,Duration maximum) throws IOException {
        try{if(!process.waitFor(Math.max(0,maximum.toNanos()),TimeUnit.NANOSECONDS))throw new IOException("Windows native操作がtimeoutしました");return process.exitValue();}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("Windows native待機が中断されました");}
    }
    void join(Thread thread,Duration maximum) throws IOException {
        boolean interrupted=Thread.interrupted();
        try{long nanos=Math.max(0,Math.min(maximum.toNanos(),deadline-System.nanoTime()));if(nanos>0)thread.join(Duration.ofNanos(nanos));if(thread.isAlive())throw new IOException("Windows入力/出力clientの回収が未確認です");}
        catch(InterruptedException failure){interrupted=true;throw new IOException("Windows client回収が中断されました",failure);}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    void stop(Process process) throws IOException {
        boolean interrupted=Thread.interrupted();
        try {
            long end=Math.min(deadline,System.nanoTime()+Duration.ofSeconds(2).toNanos());
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
        throw new IOException(message);
    }
}
