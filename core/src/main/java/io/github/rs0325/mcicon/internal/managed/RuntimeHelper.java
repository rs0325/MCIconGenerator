package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.FilesSupport;
import io.github.rs0325.mcicon.internal.RuntimePhaseLog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 検証済みhelperのprotocol 1だけを起動する内部境界。shellやホストPATHを変更しない。 */
public final class RuntimeHelper {
    public record Diagnostic(String code,String detail,boolean requiresPrivilege,boolean requiresRestart) {}
    public record Result(String status,List<Diagnostic> diagnostics) {public boolean ok(){return status.equals("ok");}}
    private final Path executable,root;
    private final RuntimeLease lease;
    private final Map<String,String> environment;
    private final boolean windows;
    private Process keeper;
    private Path keeperOutput;
    private String keeperAttempt;
    private boolean keeperReady;
    private RuntimePhaseLog keeperTrace;
    private volatile boolean closing;
    private volatile IOException keeperFailure;

    public RuntimeHelper(Path executable,Path root,RuntimeLease lease,Map<String,String> environment) {
        this(executable,root,lease,environment,false);
    }
    RuntimeHelper(Path executable,Path root,RuntimeLease lease,Map<String,String> environment,boolean windows) {
        this.windows=windows;this.executable=executable.toAbsolutePath().normalize();this.root=root.toAbsolutePath().normalize();this.lease=lease;this.environment=Map.copyOf(environment);
    }
    /** 検証済み世代から確定した絶対pathとargvを使う。Docker応答と終了codeは呼出し元へ透過する。 */
    public ProcessBuilder docker(List<String> args) throws IOException {
        if(windows)keeperRequired();lease.read();List<String> command=prefix("docker");command.add("--");command.addAll(args);
        return builder(command);
    }
    public Result control(String operation,Duration timeout,CancellationToken token) throws IOException {
        if(!Set.of("preflight","prepare","release").contains(operation))throw new IllegalArgumentException("未対応のhelper操作です");
        if(operation.equals("release")&&keeper==null&&windows) {
            long start=System.nanoTime(),budget=Math.min(timeout.toNanos(),Duration.ofSeconds(18).toNanos()),end=start+budget;
            if(!lease.read().path("resources").isEmpty()||pendingKeeper()){
                RuntimePhaseLog trace=new RuntimePhaseLog(lease.file().getParent().resolve("tmp"),"core","recover",null);trace.event("recovery-start",start+timeout.toNanos(),timeout.toNanos());trace.event("recovery-budget",end,budget);
                try{keep(start+budget/2,token,true);Result result=releaseKeeper(token,end);trace.event(result.ok()?"recovery-ok":"recovery-failed",end,0);return result;}
                catch(IOException|RuntimeException|Error failure){trace.event("recovery-failed",end,0);throw failure;}
            }
        }
        if(operation.equals("release")&&keeper!=null)return releaseKeeper(token,System.nanoTime()+Math.min(timeout.toNanos(),Duration.ofSeconds(9).toNanos()));
        if(operation.equals("prepare")&&keeper!=null)keeperRequired();
        token.check();lease.read();Path log=lease.file().getParent().resolve("helper-"+operation+"-"+UUID.randomUUID()+".stdout.json");
        Path error=log.resolveSibling(log.getFileName()+".stderr.log");
        ByteArrayOutputStream output=new ByteArrayOutputStream(),errors=new ByteArrayOutputStream();
        Process process=null;boolean succeeded=false;Throwable operationFailure=null;java.util.Optional<java.time.Instant> started=java.util.Optional.empty();
        try {
            process=builder(prefix(operation)).start();
            started=process.info().startInstant();
            long deadline=System.nanoTime()+timeout.toNanos();
            while(true) {
                drain(process,output,errors);
                token.check();if(keeper!=null&&!closing)keeperRequired();
                if(!process.isAlive())break;
                if(System.nanoTime()>=deadline)throw new IOException("helper操作の期限を超えました: "+operation);
                process.waitFor(50,TimeUnit.MILLISECONDS);
            }
            drain(process,output,errors);token.check();
            Result result=parse(output.toByteArray(),process.exitValue());lease.read();
            if(operation.equals("release")&&result.ok())lease.released();
            succeeded=result.ok();return result;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();IOException interrupted=new IOException("helper操作が中断されました",failure);operationFailure=interrupted;throw interrupted;}
        catch(IOException|RuntimeException|Error failure){operationFailure=failure;throw failure;}
        finally {
            IOException cleanupFailure=null;
            try {if(process!=null&&process.isAlive())terminate(process,log.resolveSibling(log.getFileName()+".cleanup.json"));}
            catch(IOException failure){cleanupFailure=failure;if(operationFailure!=null)operationFailure.addSuppressed(failure);else throw failure;}
            finally {
                try {if(!succeeded) {
                    if(process!=null)try{drain(process,output,errors);}catch(IOException|RuntimeException failure){if(operationFailure!=null)operationFailure.addSuppressed(failure);else if(cleanupFailure!=null)cleanupFailure.addSuppressed(failure);else operationFailure=failure;}
                    var record=RuntimeDescriptor.JSON.createObjectNode();record.put("operation",operation);record.put("stdout",log.getFileName().toString());record.put("stderr",error.getFileName().toString());
                    record.put("started",process!=null);record.put("directProcessTerminated",process!=null&&!process.isAlive());
                    if(process!=null){record.put("pid",process.pid());if(!process.isAlive())record.put("exitCode",process.exitValue());else record.putNull("exitCode");}
                    if(started.isPresent())record.put("processStartedAt",started.get().toString());else record.putNull("processStartedAt");
                    if(operationFailure!=null){record.put("failureType",operationFailure.getClass().getSimpleName());record.put("failureDetail",operationFailure.getMessage());}
                    if(cleanupFailure!=null)record.put("cleanupFailure",cleanupFailure.getMessage());
                    try {Files.write(log,output.toByteArray(),StandardOpenOption.CREATE_NEW);Files.write(error,errors.toByteArray(),StandardOpenOption.CREATE_NEW);FilesSupport.atomicWrite(log.resolveSibling(log.getFileName()+".result.json"),RuntimeDescriptor.JSON.writeValueAsBytes(record));}
                    catch(IOException failure){if(operationFailure!=null)operationFailure.addSuppressed(failure);else if(cleanupFailure!=null)cleanupFailure.addSuppressed(failure);else {operationFailure=failure;throw failure;}}
                }} finally {
                    IOException closeFailure=null;
                    if(process!=null)for(Closeable stream:List.of(process.getInputStream(),process.getErrorStream(),process.getOutputStream()))try{stream.close();}catch(IOException failure){if(closeFailure==null)closeFailure=failure;else closeFailure.addSuppressed(failure);}
                    if(closeFailure!=null){if(operationFailure!=null)operationFailure.addSuppressed(closeFailure);else if(cleanupFailure!=null)cleanupFailure.addSuppressed(closeFailure);else throw closeFailure;}
                }
            }
        }
    }
    private static void drain(Process process,ByteArrayOutputStream output,ByteArrayOutputStream errors) throws IOException {
        Exception failure=null;
        try{drain(process.getInputStream(),output);}catch(IOException|RuntimeException error){failure=error;}
        try{drain(process.getErrorStream(),errors);}catch(IOException|RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure instanceof IOException error)throw error;if(failure instanceof RuntimeException error)throw error;
    }
    /** 子孫へ診断fileのhandleを継承させず、EOFを待たずに確定応答だけを受け取る。 */
    private static void drain(InputStream input,ByteArrayOutputStream output) throws IOException {
        byte[] bytes=new byte[8192];int available;
        while((available=input.available())>0){int count=input.read(bytes,0,Math.min(bytes.length,available));if(count<0)break;if(output.size()+count>1024*1024)throw RuntimeDescriptor.invalid("helper応答が上限を超えています");output.write(bytes,0,count);}
    }
    private boolean pendingKeeper() throws IOException {
        JsonNode owner=lease.read();Path tmp=lease.file().getParent().resolve("tmp"),journal=tmp.resolve("windows-operation.json");
        if(!Files.exists(journal,LinkOption.NOFOLLOW_LINKS))return false;
        JsonNode operation=keeperEvidence(journal);String state=operation.path("state").asText();
        if(Set.of("unstarted","released").contains(state))return false;
        if(!state.equals("pending")||!operation.path("operation").asText().equals("keeper-start")||!RuntimeLease.ownerDead(owner.path("pid").asLong(),java.time.Instant.parse(owner.path("processStartedAt").asText())))throw new IOException("未公開keeperの回復根拠がありません");
        var identity=RuntimeDescriptor.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))identity.set(key,owner.required(key));
        if(!operation.path("identity").equals(identity))throw new IOException("keeper操作の所有が一致しません");identity.remove("hostId");
        JsonNode prior=keeperEvidence(tmp.resolve("keeper.json"));String attempt=RuntimeDescriptor.text(prior,"attempt",36);try{if(!UUID.fromString(attempt).toString().equals(attempt))throw new IllegalArgumentException();}catch(IllegalArgumentException error){throw new IOException("keeper attemptが不正です",error);}
        if(!Set.of("starting","ready").contains(prior.path("state").asText())||!prior.path("identity").equals(identity)||!prior.path("recovery").isBoolean()||prior.path("recovery").asBoolean()||prior.path("controllerPid").asLong()!=owner.path("pid").asLong()||prior.path("controllerStarted").asLong()!=java.time.Instant.parse(owner.path("processStartedAt").asText()).toEpochMilli()||!prior.path("boot").equals(operation.path("boot"))||!prior.equals(keeperEvidence(tmp.resolve("keeper-"+attempt+".json"))))throw new IOException("未公開keeperの開始証拠が一致しません");
        Path prefix=Path.of(RuntimeDescriptor.text(prior,"prefix",4096));if(!prefix.isAbsolute()||!prefix.getParent().equals(tmp)||!prefix.getFileName().toString().matches("native-[0-9a-f-]{36}"))throw new IOException("keeper native保存位置が不正です");
        JsonNode intent=keeperEvidence(Path.of(prefix+".intent.json")),nativeState=keeperEvidence(Path.of(prefix+".state.json")),client=keeperEvidence(Path.of(prefix+".client.json"));
        try{String boot=nativeState.path("boot").asText();if(!UUID.fromString(boot).toString().equals(boot))throw new IllegalArgumentException();}catch(IllegalArgumentException error){throw new IOException("keeper native bootが不正です",error);}
        String system=environment.get("SystemRoot");if(system==null||!Path.of(system).isAbsolute())throw new IOException("固定WSL実行位置が不明です");
        List<String> guest=List.of(Path.of(system).resolve("System32/wsl.exe").toString(),"--distribution","MCIcon-"+owner.path("descriptorSha256").asText().substring(0,24),"--user","root","--exec","/usr/bin/python3","/usr/lib/mcicon/guestbridge.py");
        if(!intent.path("argvSha256").asText().equals(FilesSupport.hash(RuntimeDescriptor.JSON.writeValueAsBytes(guest))))throw new IOException("keeper nativeの固定argvが一致しません");
        if(!intent.path("native").asText().equals(executable.getParent().resolve("app/support/windows-host.exe").toString())||!intent.path("parent").equals(nativeState.path("parent"))||!intent.path("started").equals(nativeState.path("parentStarted"))||!nativeState.path("boot").equals(prior.path("boot"))||nativeState.path("parent").asLong()<1||nativeState.path("parentStarted").asLong()<1||nativeState.path("pid").asLong()<1||nativeState.path("started").asLong()<1||!nativeState.equals(keeperEvidence(Path.of(prefix+".done.json"))))throw new IOException("未公開keeperのJob0証拠がありません");
        for(String field:List.of("parent","parentStarted","pid","started","boot"))if(!client.path(field).equals(nativeState.path(field)))throw new IOException("keeper clientのnative所有が一致しません");
        if(client.path("corePid").asLong()!=prior.path("controllerPid").asLong()||client.path("coreStarted").asLong()!=prior.path("controllerStarted").asLong()||client.path("clientPid").asLong()<1||client.path("clientStarted").asLong()<1||(prior.has("client")||prior.path("state").asText().equals("ready"))&&!prior.path("client").equals(client))throw new IOException("keeper clientのcontrollerが一致しません");
        return true;
    }
    private static JsonNode keeperEvidence(Path path) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>1024*1024)throw new IOException("keeper証拠を安全に読めません");return RuntimeDescriptor.read(Files.readAllBytes(path));
    }
    void keep(Duration timeout,CancellationToken token,boolean recovery) throws IOException {keep(System.nanoTime()+timeout.toNanos(),token,recovery);}
    private void keep(long end,CancellationToken token,boolean recovery) throws IOException {
        if(keeper!=null)throw new IOException("keeperは一回だけ開始できます");
        token.check();keeperAttempt=UUID.randomUUID().toString();phase("keeper-start",end,Math.max(0,end-System.nanoTime()));if(System.nanoTime()>=end)throw new IOException("keeper開始前に元期限を超えました");
        keeperOutput=Files.createTempFile(lease.file().getParent(),"helper-keep-",".stdout.json");
        List<String> args=prefix("keep");ProcessHandle core=ProcessHandle.current();long started=core.info().startInstant().orElseThrow(()->new IOException("core開始identity不明")).toEpochMilli();
        args.addAll(List.of("--controller",Long.toString(core.pid()),Long.toString(started),"--attempt",keeperAttempt,"--deadline",java.time.Instant.now().plusNanos(Math.max(0,end-System.nanoTime())).toString(),"--recovery",Boolean.toString(recovery)));
        if(System.nanoTime()>=end)throw new IOException("keeper開始前に元期限を超えました");
        phase("keeper-spawn",end,0);keeper=builder(args).redirectOutput(keeperOutput.toFile()).redirectError(keeperOutput.resolveSibling(keeperOutput.getFileName()+".stderr.log").toFile()).start();phase("keeper-started",end,0);
        keeper.onExit().thenRun(()->{if(!closing)keeperFailure=new IOException("keeper helperが正式release前に終了しました");});
        try {
            while(true){token.check();if(Files.size(keeperOutput)>1024*1024)throw new IOException("keeper応答が上限を超えました");byte[] output=Files.readAllBytes(keeperOutput);int newline=-1;for(int i=0;i<output.length;i++)if(output[i]=='\n'){newline=i;break;}
                if(newline>=0){phase("keeper-ack-read",end,0);JsonNode ack=RuntimeDescriptor.read(Arrays.copyOf(output,newline));if(!ack.path("state").asText().equals("ready")||!ack.path("attempt").asText().equals(keeperAttempt)||ack.path("controllerPid").asLong()!=core.pid()||ack.path("controllerStarted").asLong()!=started)throw new IOException("keeper ACKが一致しません");keeperReady=true;keeperRequired();phase("keeper-ready",end,0);return;}
                if(!keeper.isAlive()||System.nanoTime()>=end)throw new IOException("keeper ACK前に終了又は期限となりました");
                try{Thread.sleep(10);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("keeper開始が中断されました",failure);}
            }
        }catch(IOException|RuntimeException|Error failure){phase("keeper-start-failed",end,0);abortKeeper(recovery?Math.min(end,System.nanoTime()+Duration.ofSeconds(2).toNanos()):System.nanoTime()+Duration.ofSeconds(2).toNanos(),failure);throw failure;}
    }
    private void phase(String name,long end,long budget) {
        if(!windows)return;if(keeperTrace==null)keeperTrace=new RuntimePhaseLog(lease.file().getParent().resolve("tmp"),"core","keeper",keeperAttempt);keeperTrace.event(name,end,budget,keeper);
    }
    private void keeperRequired() throws IOException {if(keeperFailure!=null)throw keeperFailure;if(!keeperReady||keeper==null||!keeper.isAlive())throw new IOException("keeperの実寿命が終了又は未確立です");}
    private Result releaseKeeper(CancellationToken token,long end) throws IOException {
        phase("release-start",end,Math.max(0,end-System.nanoTime()));
        closing=true;Thread writer=null;java.util.concurrent.atomic.AtomicReference<IOException> writerFailure=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            // 正常終了の受取1秒とabort2秒を予約。回復時は開始で余った全体予算も本体へ渡す。
            token.check();long helperEnd=end-Duration.ofSeconds(3).toNanos();if(System.nanoTime()>=helperEnd)throw new IOException("keeper releaseの本体予算がありません");
            phase("release-body-budget",helperEnd,Math.max(0,helperEnd-System.nanoTime()));
            keeperRequired();var request=RuntimeDescriptor.JSON.createObjectNode().put("operation","release").put("attempt",keeperAttempt).put("deadline",java.time.Instant.now().plusNanos(Math.max(0,helperEnd-System.nanoTime())).toString());
            writer=Thread.ofVirtual().start(()->{try(OutputStream output=keeper.getOutputStream()){phase("release-write",helperEnd,0);output.write(RuntimeDescriptor.JSON.writeValueAsBytes(request));output.write('\n');}catch(IOException error){writerFailure.set(error);}});
            long bodyEnd=end-Duration.ofSeconds(2).toNanos();
            phase("keeper-response-wait",bodyEnd,Math.max(0,bodyEnd-System.nanoTime()));
            while(keeper.isAlive()&&System.nanoTime()<bodyEnd){token.check();if(writerFailure.get()!=null)throw writerFailure.get();if(Files.size(keeperOutput)>1024*1024)throw new IOException("keeper最終応答が上限を超えました");try{keeper.waitFor(10,TimeUnit.MILLISECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("keeper releaseが中断されました",error);}}
            if(keeper.isAlive())throw new IOException("keeper releaseの本体期限を超えました");
            phase("keeper-exited",end,0);joinWriter(writer,end,writerFailure);phase("keeper-response-read",end,0);byte[] output=Files.readAllBytes(keeperOutput);int newline=-1;for(int i=0;i<output.length;i++)if(output[i]=='\n'){newline=i;break;}
            if(newline<0)throw new IOException("keeper ACKがありません");Result result=parse(Arrays.copyOfRange(output,newline+1,output.length),keeper.exitValue());if(result.ok())lease.released();phase(result.ok()?"release-ok":"release-failed",end,0);return result;
        }catch(IOException|RuntimeException|Error failure){phase("release-failed",end,0);abortKeeper(end,failure);try{joinWriter(writer,end,writerFailure);}catch(IOException error){if(error!=failure)failure.addSuppressed(error);}throw failure;}
    }
    static void joinWriter(Thread writer,long end,java.util.concurrent.atomic.AtomicReference<IOException> failure) throws IOException {
        if(writer==null)return;try{long left=end-System.nanoTime();if(left>0)writer.join(Duration.ofNanos(left));if(writer.isAlive())throw new IOException("keeper release writer終端が不明です");if(failure.get()!=null)throw failure.get();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("keeper writer回収が中断されました",error);}
    }
    private void abortKeeper(long end,Throwable failure) {
        if(keeper==null)return;
        phase("keeper-abort",end,0);
        try{keeper.getOutputStream().close();}catch(IOException error){failure.addSuppressed(error);}
        try {
            long tail=Math.min(end-Duration.ofMillis(250).toNanos(),System.nanoTime()+Duration.ofSeconds(2).toNanos());
            while(keeper.isAlive()&&System.nanoTime()<tail)keeper.waitFor(Math.max(1,tail-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(keeper.isAlive()){keeper.destroyForcibly();while(keeper.isAlive()&&System.nanoTime()<end)keeper.waitFor(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS);}
            var record=RuntimeDescriptor.JSON.createObjectNode().put("pid",keeper.pid()).put("terminated",!keeper.isAlive());
            var started=keeper.info().startInstant();if(started.isPresent())record.put("processStartedAt",started.get().toString());else record.putNull("processStartedAt");
            FilesSupport.atomicWrite(keeperOutput.resolveSibling(keeperOutput.getFileName()+".cleanup.json"),RuntimeDescriptor.JSON.writeValueAsBytes(record));
            if(keeper.isAlive())throw new IOException("keeper helper終端が不明です");
            phase("keeper-aborted",end,0);
        }catch(IOException|InterruptedException error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();failure.addSuppressed(error);}
    }
    static void terminate(Process process,Path evidence) throws IOException {
        boolean interrupted=Thread.interrupted(),terminated=false;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        var started=process.info().startInstant();
        try {
            process.destroyForcibly();
            while(!terminated&&System.nanoTime()<deadline) {
                try {terminated=process.waitFor(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
                catch(InterruptedException failure){interrupted=true;}
            }
            var record=RuntimeDescriptor.JSON.createObjectNode();record.put("pid",process.pid());
            if(started.isPresent())record.put("processStartedAt",started.get().toString());else record.putNull("processStartedAt");
            record.put("terminated",terminated);FilesSupport.atomicWrite(evidence,RuntimeDescriptor.JSON.writeValueAsBytes(record));
            if(!terminated)throw new IOException("helper processの終了を確認できません。回収記録とleaseを確認してください");
        }finally {if(interrupted)Thread.currentThread().interrupt();}
    }
    private List<String> prefix(String operation) {return new ArrayList<>(List.of(executable.toString(),"--protocol","1","--root",root.toString(),"--lease",lease.file().toString(),operation));}
    private ProcessBuilder builder(List<String> command) {
        ProcessBuilder builder=new ProcessBuilder(command);builder.environment().clear();builder.environment().putAll(environment);
        if(windows)builder.environment().put("PATH",executable.getParent().resolve("app").toString());return builder;
    }
    static Result parse(byte[] bytes,int exitCode) throws IOException {
        JsonNode root=RuntimeDescriptor.read(bytes);RuntimeDescriptor.fields(root,"protocolVersion","status","diagnostics");RuntimeDescriptor.constant(root,"protocolVersion",1);
        String status=RuntimeDescriptor.choice(root,"status","ok","setup-required","failed");
        if(exitCode!=switch(status){case "ok"->0;case "setup-required"->2;default->1;})throw RuntimeDescriptor.invalid("helper statusと終了codeが一致しません");
        List<Diagnostic> diagnostics=new ArrayList<>();
        for(JsonNode item:RuntimeDescriptor.array(root,"diagnostics",0,4096)) {
            RuntimeDescriptor.fields(item,"code","detail","requiresPrivilege","requiresRestart");
            if(!item.get("requiresPrivilege").isBoolean()||!item.get("requiresRestart").isBoolean())throw RuntimeDescriptor.invalid("helper診断flagが不正です");
            diagnostics.add(new Diagnostic(RuntimeDescriptor.text(item,"code",256),RuntimeDescriptor.text(item,"detail",16384),item.get("requiresPrivilege").booleanValue(),item.get("requiresRestart").booleanValue()));
        }
        return new Result(status,List.copyOf(diagnostics));
    }
}
