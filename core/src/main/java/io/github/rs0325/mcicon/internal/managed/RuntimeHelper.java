package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.FilesSupport;
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
    public RuntimeHelper(Path executable,Path root,RuntimeLease lease,Map<String,String> environment) {
        this.executable=executable.toAbsolutePath().normalize();this.root=root.toAbsolutePath().normalize();this.lease=lease;this.environment=Map.copyOf(environment);
    }
    /** 検証済み世代から確定した絶対pathとargvを使う。Docker応答と終了codeは呼出し元へ透過する。 */
    public ProcessBuilder docker(List<String> args) throws IOException {
        lease.read();List<String> command=prefix("docker");command.add("--");command.addAll(args);
        return builder(command);
    }
    public Result control(String operation,Duration timeout,CancellationToken token) throws IOException {
        if(!Set.of("preflight","prepare","release").contains(operation))throw new IllegalArgumentException("未対応のhelper操作です");
        token.check();lease.read();Path log=Files.createTempFile(lease.file().getParent(),"helper-"+operation+"-",".stdout.json");
        Path error=log.resolveSibling(log.getFileName()+".stderr.log");
        Process process=null;boolean succeeded=false;Throwable operationFailure=null;java.util.Optional<java.time.Instant> started=java.util.Optional.empty();
        try {
            process=builder(prefix(operation)).redirectOutput(log.toFile()).redirectError(error.toFile()).start();
            started=process.info().startInstant();
            long deadline=System.nanoTime()+timeout.toNanos();
            while(!process.waitFor(50,TimeUnit.MILLISECONDS)) {
                token.check();
                if(Files.size(log)>1024*1024)throw RuntimeDescriptor.invalid("helper応答が上限を超えています");
                if(System.nanoTime()>=deadline)throw new IOException("helper操作の期限を超えました: "+operation);
            }
            token.check();if(Files.size(log)>1024*1024)throw RuntimeDescriptor.invalid("helper応答が上限を超えています");
            Result result=parse(Files.readAllBytes(log),process.exitValue());lease.read();
            if(operation.equals("release")&&result.ok())lease.released();
            succeeded=result.ok();return result;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();IOException interrupted=new IOException("helper操作が中断されました",failure);operationFailure=interrupted;throw interrupted;}
        catch(IOException|RuntimeException|Error failure){operationFailure=failure;throw failure;}
        finally {
            IOException cleanupFailure=null;
            try {if(process!=null&&process.isAlive())terminate(process,log.resolveSibling(log.getFileName()+".cleanup.json"));}
            catch(IOException failure){cleanupFailure=failure;if(operationFailure!=null)operationFailure.addSuppressed(failure);else throw failure;}
            finally {
                if(succeeded)Files.deleteIfExists(log);
                else {
                    var record=RuntimeDescriptor.JSON.createObjectNode();record.put("operation",operation);record.put("stdout",log.getFileName().toString());record.put("stderr",error.getFileName().toString());
                    record.put("started",process!=null);record.put("directProcessTerminated",process!=null&&!process.isAlive());
                    if(process!=null){record.put("pid",process.pid());if(!process.isAlive())record.put("exitCode",process.exitValue());else record.putNull("exitCode");}
                    if(started.isPresent())record.put("processStartedAt",started.get().toString());else record.putNull("processStartedAt");
                    if(operationFailure!=null){record.put("failureType",operationFailure.getClass().getSimpleName());record.put("failureDetail",operationFailure.getMessage());}
                    if(cleanupFailure!=null)record.put("cleanupFailure",cleanupFailure.getMessage());
                    try {FilesSupport.atomicWrite(log.resolveSibling(log.getFileName()+".result.json"),RuntimeDescriptor.JSON.writeValueAsBytes(record));}
                    catch(IOException failure){if(operationFailure!=null)operationFailure.addSuppressed(failure);else if(cleanupFailure!=null)cleanupFailure.addSuppressed(failure);else throw failure;}
                }
            }
        }
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
        ProcessBuilder builder=new ProcessBuilder(command);builder.environment().clear();builder.environment().putAll(environment);return builder;
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
