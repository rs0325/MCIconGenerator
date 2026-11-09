package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** 不良候補確定とは別に、肯定証拠とCASでtrialだけを解除する。 */
final class TrialRecovery {
    private final Bootstrap bootstrap;
    TrialRecovery(Bootstrap bootstrap){this.bootstrap=bootstrap;}
    JsonNode settings(long deadline) throws IOException {
        Path file=bootstrap.root.resolve("config.json");ObjectNode result=Seed.JSON.createObjectNode();
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS))return result.put("present",false);
        bootstrap.file(file,deadline);return result.put("present",true).put("sha256",Seed.hash(Seed.read(file,65536))).put("fileKey",bootstrap.fileIdentity(file,deadline));
    }
    void defer(String trial,JsonNode reference,JsonNode expected,Path run,FileChannel channel,ObjectNode record,JsonNode request,JsonNode message,JsonNode expectedSettings,long deadline) throws IOException {
        StartupControl.deferred(message);
        for(String field:List.of("nonce","invocation","slot","pid","startedAt"))if(!message.path(field).equals(request.path(field)))throw Seed.invalid("延期REQUESTの結合が一致しません");
        requireReceipt(trial,reference,run,channel,record,"prepare",deadline);requireDead(record.path("child"));JsonNode proof=verifyDeferred(message,deadline);
        ObjectNode evidence=Seed.JSON.createObjectNode().put("schemaVersion",1).put("kind","deferred").put("capability","deferred-v1");
        evidence.set("request",request);evidence.set("message",message);evidence.set("receipt",record.deepCopy());evidence.set("expectedState",expected);evidence.set("settings",expectedSettings);evidence.set("runtimeProof",proof);
        Path path=evidencePath(trial,"deferred");
        try(FileChannel output=bootstrap.create(path,deadline)){evidence.put("evidenceKey",bootstrap.fileIdentity(path,deadline));Bootstrap.write(output,evidence);}
        clear(trial,reference,expected,expectedSettings,run,channel,record,"prepare",proof,message,deadline);
    }
    void resume(String trial) throws IOException {
        UpdatePreparation.uuid(trial);long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();JsonNode expected,configuration;
        // 新package自身の署名seedを先に検証する。旧slotのhelperをロードしない。
        new Seed(bootstrap.installed.resolve("seed"),bootstrap.key,bootstrap.host);
        try(var ignored=bootstrap.lock(deadline)) {
            expected=bootstrap.state(deadline);if(expected==null||!trial.equals(expected.path("trialInvocation").asText())||expected.path("pending").isNull()||Bootstrap.badCandidate(expected.path("pending"),expected)||!new UpdatePreparation(bootstrap).enabled(deadline))throw Seed.invalid("再開対象trialまたは更新設定が一致しません");
            bootstrap.verifySlot(bootstrap.accepted(expected,deadline),deadline);bootstrap.verifySlot(bootstrap.accepted(expected.path("pending"),expected,deadline),deadline);configuration=settings(deadline);
        }
        Path run=bootstrap.root.resolve("runs/"+trial+".json");bootstrap.file(run,deadline);
        try(FileChannel channel=FileChannel.open(run,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock held;try{held=channel.tryLock();}catch(OverlappingFileLockException busy){throw Seed.invalid("元receiptが使用中です");}if(held==null)throw Seed.invalid("元receiptが使用中です");
            try(held) {
                ObjectNode record=(ObjectNode)bootstrap.receipt(channel);requireDead(record.path("bootstrap"));requireDead(record.path("child"));
                if(record.path("startup").asText().equals("prepare")){resumeDeferred(trial,expected,configuration,run,channel,record,deadline);return;}
                requireReceipt(trial,expected.path("pending"),run,channel,record,"failed",deadline);
                JsonNode failure=record.path("initialization");StartupControl.failure(failure);
                for(String field:List.of("pid","startedAt"))if(!failure.path(field).equals(record.path("child").path(field)))throw Seed.invalid("元FAILEDのchildが一致しません");
                if(!failure.path("invocation").asText().equals(trial)||!failure.path("slot").equals(expected.path("pending")))throw Seed.invalid("元FAILEDの試行が一致しません");
                JsonNode proof=verifyFailed(record,expected.path("pending"),deadline);
                saveResume(trial,expected,configuration,record,proof,"failed",deadline);
                if(!proof.equals(verifyFailed(record,expected.path("pending"),deadline)))throw Seed.invalid("保守snapshotが変化しました");
                clear(trial,expected.path("pending"),expected,configuration,run,channel,record,"failed",proof,null,deadline);
            }
        }
    }
    private void resumeDeferred(String trial,JsonNode expected,JsonNode configuration,Path run,FileChannel channel,ObjectNode record,long deadline) throws IOException {
        Path path=evidencePath(trial,"deferred");bootstrap.file(path,deadline);JsonNode saved=Seed.json(Seed.read(path,1024*1024));
        Seed.fields(saved,"schemaVersion","kind","capability","request","message","receipt","expectedState","settings","runtimeProof","evidenceKey");Seed.constant(saved,"schemaVersion",1);Seed.exact(saved,"kind","deferred");Seed.exact(saved,"capability","deferred-v1");
        JsonNode priorSettings=saved.path("settings");
        if(!priorSettings.path("present").isBoolean())throw Seed.invalid("保存済み設定snapshotの形式が不正です");
        if(priorSettings.path("present").asBoolean()){Seed.fields(priorSettings,"present","sha256","fileKey");Seed.digest(priorSettings,"sha256");UpdatePreparation.key(priorSettings,"fileKey");}
        else Seed.fields(priorSettings,"present");
        if(!saved.path("evidenceKey").asText().equals(bootstrap.fileIdentity(path,deadline))||!saved.path("receipt").equals(record)||!saved.path("expectedState").equals(expected))throw Seed.invalid("保存済み延期証拠が変化しました");
        requireReceipt(trial,expected.path("pending"),run,channel,record,"prepare",deadline);StartupControl.deferred(saved.path("message"));
        for(String field:List.of("nonce","invocation","slot","pid","startedAt"))if(!saved.path("message").path(field).equals(saved.path("request").path(field)))throw Seed.invalid("保存済み延期REQUESTが不一致です");
        if(!saved.path("message").path("invocation").asText().equals(trial)||!saved.path("message").path("slot").equals(record.path("slot"))||!saved.path("message").path("pid").equals(record.path("child").path("pid"))||!saved.path("message").path("startedAt").equals(record.path("child").path("startedAt")))throw Seed.invalid("保存済み延期childが不一致です");
        JsonNode proof=verifyDeferred(saved.path("message"),deadline);if(!proof.equals(saved.path("runtimeProof")))throw Seed.invalid("延期のruntime証拠が変化しました");
        saveResume(trial,expected,configuration,record,proof,"prepare",deadline);
        if(!proof.equals(verifyDeferred(saved.path("message"),deadline)))throw Seed.invalid("延期snapshotが変化しました");
        clear(trial,expected.path("pending"),expected,configuration,run,channel,record,"prepare",proof,saved.path("message"),deadline);
    }
    private void saveResume(String trial,JsonNode state,JsonNode configuration,JsonNode record,JsonNode proof,String startup,long deadline) throws IOException {
        ObjectNode value=Seed.JSON.createObjectNode().put("schemaVersion",1).put("kind","resume").put("startup",startup);value.set("expectedState",state);value.set("settings",configuration);value.set("receipt",record);value.set("runtimeProof",proof);
        Path path=evidencePath(trial,"resume");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))path=evidencePath(trial,"resume-"+UUID.randomUUID());
        try(FileChannel output=bootstrap.create(path,deadline)){value.put("evidenceKey",bootstrap.fileIdentity(path,deadline));Bootstrap.write(output,value);}
    }
    private Path evidencePath(String trial,String kind) throws IOException {UpdatePreparation.uuid(trial);return bootstrap.root.resolve("logs/"+trial+"."+kind+"-v1.json");}
    private void clear(String trial,JsonNode reference,JsonNode expected,JsonNode configuration,Path run,FileChannel channel,ObjectNode record,String startup,JsonNode proof,JsonNode message,long deadline) throws IOException {
        try(var ignored=bootstrap.lock(deadline)) {
            ObjectNode current=(ObjectNode)bootstrap.state(deadline);
            if(!current.equals(expected)||!current.path("trialInvocation").asText().equals(trial)||!current.path("pending").equals(reference)||Bootstrap.badCandidate(reference,current)||!new UpdatePreparation(bootstrap).enabled(deadline)||!settings(deadline).equals(configuration))throw Seed.invalid("trial解除のstate/settings CASが不一致です");
            JsonNode observed=message==null?verifyFailed(record,reference,deadline):verifyDeferred(message,deadline);if(!observed.equals(proof))throw Seed.invalid("CAS直前にruntime証拠が変化しました");
            requireReceipt(trial,reference,run,channel,record,startup,deadline);
            current.putNull("trialInvocation");bootstrap.atomic(bootstrap.root.resolve("active.json"),current,deadline);
        }
    }
    private void requireReceipt(String trial,JsonNode reference,Path run,FileChannel channel,JsonNode record,String startup,long deadline) throws IOException {
        if(!bootstrap.fileIdentity(run,deadline).equals(record.path("receiptKey").asText())||!bootstrap.receipt(channel).equals(Seed.json(Seed.JSON.writeValueAsBytes(record)))||!record.path("state").asText().equals("finished")||!record.path("startup").asText().equals(startup)||!record.path("invocationId").asText().equals(trial)||!record.path("slot").equals(reference)||startup.equals("prepare")&&!record.path("initialization").isNull())throw Seed.invalid("trialのreceipt/inode/phaseが不一致です");
    }
    private static void requireDead(JsonNode child) throws IOException {
        Bootstrap.process(child);long pid=Seed.positive(child,"pid");Instant started=Instant.parse(Seed.text(child,"startedAt"));Optional<ProcessHandle> process=ProcessHandle.of(pid);
        if(process.isPresent()&&process.get().isAlive()) {
            Instant actual=process.get().info().startInstant().orElseThrow(()->Seed.invalid("元processの開始identityが不明です"));if(actual.equals(started))throw Seed.invalid("元processが生存しています");
        }
    }
    boolean legacyCandidateBlocked(JsonNode pending,long deadline) throws IOException {
        if(!bootstrap.host.os().equals("windows")||!io.github.rs0325.mcicon.internal.LeaseTrust.legacy(pending.path("runtimeDescriptorSha256").asText()))return false;
        JsonNode result=verifier("legacyReaderActive",new Class<?>[]{Path.class,byte[].class,Function.class},new Object[]{runtimeRoot(),trustBytes(),identity(deadline)},deadline);
        Seed.fields(result,"blocked");if(!result.path("blocked").isBoolean())throw Seed.invalid("旧readerの確認結果が不正です");return result.path("blocked").asBoolean();
    }
    private JsonNode verifyFailed(JsonNode record,JsonNode reference,long deadline) throws IOException {
        return verifier("verifyFailed",new Class<?>[]{Path.class,long.class,String.class,String.class,byte[].class,Function.class},new Object[]{runtimeRoot(),Seed.positive(record.path("child"),"pid"),Seed.text(record.path("child"),"startedAt"),Seed.digest(reference,"runtimeDescriptorSha256"),trustBytes(),identity(deadline)},deadline);
    }
    private JsonNode verifyDeferred(JsonNode message,long deadline) throws IOException {
        return verifier("verifyDeferred",new Class<?>[]{Path.class,byte[].class,byte[].class,Function.class},new Object[]{runtimeRoot(),Seed.JSON.writeValueAsBytes(message),trustBytes(),identity(deadline)},deadline);
    }
    private byte[] trustBytes() throws IOException {return Seed.read(bootstrap.installed.resolve("trust-set.bin"),96);}
    private Function<Path,String> identity(long deadline) throws IOException {
        if(!bootstrap.host.os().equals("windows"))return null;
        WindowsPrivateFiles runtime=new WindowsPrivateFiles(bootstrap.installed,runtimeRoot());
        return path->{try{return runtime.fileIdentity(path,deadline);}catch(IOException failure){throw new UncheckedIOException(failure);}};
    }
    private Path runtimeRoot() throws IOException {
        Path home=Path.of(System.getProperty("user.home"));
        if(bootstrap.host.os().equals("macos"))return home.resolve("Library/Application Support/MCIconGenerator");
        if(bootstrap.host.os().equals("windows")){Path data=io.github.rs0325.mcicon.internal.WindowsDataHome.configuredRoot();String local=System.getenv("LOCALAPPDATA");return data!=null?data.resolve("runtime"):local==null?home.resolve("AppData/Local/MCIconGenerator"):Path.of(local).resolve("MCIconGenerator");}
        throw Seed.invalid("このhostのtrial保守は未対応です");
    }
    private JsonNode verifier(String method,Class<?>[] types,Object[] arguments,long deadline) throws IOException {
        Seed packaged=new Seed(bootstrap.installed.resolve("seed"),bootstrap.key,bootstrap.host);if(!packaged.version.equals(Seed.text(bootstrap.info,"bootstrapVersion")))throw Seed.invalid("保守seedと新bootstrapの版が一致しません");bootstrap.copyArchive(packaged,deadline);Path slot=bootstrap.verifySlot(packaged,deadline);
        List<URL> jars=new ArrayList<>();try(var entries=Files.newDirectoryStream(slot.resolve("app/lib"),"*.jar")){for(Path jar:entries)jars.add(jar.toUri().toURL());}
        try(URLClassLoader loader=new URLClassLoader(jars.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())) {
            Class<?> type=Class.forName("io.github.rs0325.mcicon.internal.RuntimeMaintenanceVerifier",true,loader);Seed.check(deadline);
            byte[] result=(byte[])type.getMethod(method,types).invoke(null,arguments);Seed.check(deadline);return Seed.json(result);
        }catch(java.lang.reflect.InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof IOException)throw (IOException)cause;throw new IOException("署名済み保守検証器が失敗しました",cause);}
        catch(ReflectiveOperationException failure){throw new IOException("新packageの署名seedに保守検証器がありません",failure);}
    }
}
