package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

/** 署名済みpackageから使う読取り専用検証器。旧helperのロード・資源回収は行わない。 */
public final class RuntimeMaintenanceVerifier {
    private RuntimeMaintenanceVerifier() {}
    /** 署名済みslotのclassloaderから呼ぶ、JDK型だけの保守境界。 */
    public static byte[] verifyFailed(Path root,long pid,String started,String descriptor,byte[] trustBytes,java.util.function.Function<Path,String> identity) throws IOException {
        LeaseTrust trust=trusted(trustBytes,identity,descriptor);ObjectNode result=JSON.createObjectNode();var jobs=result.putArray("jobs");List<JsonNode> hosts=new ArrayList<>();JsonNode candidateHost=null;directory(root.resolve("jobs"));
        try(var entries=Files.newDirectoryStream(root.resolve("jobs"))) {
            for(Path entry:entries) {
                LeaseSnapshot snapshot=LeaseSnapshot.read(root,entry.resolve("lease.json"),trust);JsonNode lease=snapshot.lease();hosts.add(snapshot.descriptor().path("host"));
                if(lease.path("pid").asLong()==pid&&lease.path("processStartedAt").asText().equals(started)&&lease.path("descriptorSha256").asText().equals(descriptor)) {
                    candidateHost=snapshot.descriptor().path("host");requireTerminal(snapshot);jobs.add(proof(snapshot));
                }else requireReleased(snapshot);
            }
        }
        if(jobs.isEmpty())throw invalid("元childに結合した候補jobの肯定証拠がありません");
        for(JsonNode host:hosts)if(!host.equals(candidateHost))throw invalid("保守参照元のhostが一致しません");
        return JSON.writeValueAsBytes(result);
    }
    public static byte[] verifyDeferred(Path root,byte[] message,byte[] trustBytes,java.util.function.Function<Path,String> identity) throws IOException {
        JsonNode value=read(message);LeaseTrust trust=trusted(trustBytes,identity,hash(value.path("slot"),"runtimeDescriptorSha256"));
        if(value.has("admission")){
            if(!System.getProperty("os.name").startsWith("Windows"))throw invalid("旧Windows reader延期のhostが一致しません");
            return JSON.writeValueAsBytes(AdmissionEvidence.verifyPreAdmission(root,value.path("admission"),hash(value.path("slot"),"runtimeDescriptorSha256"),integer(value,"pid",1),text(value,"startedAt",64),trust));
        }
        ObjectNode result=JSON.createObjectNode();var jobs=result.putArray("jobs");
        if(!value.path("jobs").isArray()||value.path("jobs").isEmpty())throw invalid("容量延期のjob証拠がありません");
        for(JsonNode job:value.path("jobs"))jobs.add(AdmissionEvidence.verifyJob(root,job,hash(value.path("slot"),"runtimeDescriptorSha256"),integer(value,"pid",1),text(value,"startedAt",64),trust,true));
        return JSON.writeValueAsBytes(result);
    }
    private static LeaseTrust trusted(byte[] bytes,java.util.function.Function<Path,String> identity,String descriptor) throws IOException {
        TrustSet trust=TrustSet.parse(bytes);if(identity==null)return LeaseTrust.pinned(trust,descriptor,null);
        return LeaseTrust.pinned(trust,descriptor,path->{try{return identity.apply(path);}catch(java.io.UncheckedIOException failure){throw failure.getCause();}});
    }

    public static byte[] legacyReaderActive(Path root,byte[] trustBytes,java.util.function.Function<Path,String> identity) throws IOException {
        ObjectNode result=JSON.createObjectNode().put("blocked",false);Path jobs=root.resolve("jobs");if(Files.notExists(jobs,LinkOption.NOFOLLOW_LINKS))return JSON.writeValueAsBytes(result);
        LeaseTrust trust=trusted(trustBytes,identity,null);directory(jobs);
        try(var entries=Files.newDirectoryStream(jobs)){
            for(Path entry:entries){LeaseSnapshot snapshot=LeaseSnapshot.read(root,entry.resolve("lease.json"),trust);if(snapshot.lease().path("state").asText().equals("released"))requireReleased(snapshot);else {requireLive(snapshot);if(LeaseTrust.legacy(snapshot.lease().path("descriptorSha256").asText()))result.put("blocked",true);}}
        }
        return JSON.writeValueAsBytes(result);
    }
    public static boolean live(JsonNode lease) throws IOException {
        Instant started;try{started=Instant.parse(text(lease,"processStartedAt",64));}catch(RuntimeException failure){throw new IOException("元core開始identityが不明です",failure);}
        return processMatches(integer(lease,"pid",1),started);
    }
    public static boolean processMatches(long pid,Instant started) throws IOException {
        try {
            Optional<ProcessHandle> process=ProcessHandle.of(pid);if(process.isEmpty()||!process.get().isAlive())return false;
            Optional<Instant> actual=process.get().info().startInstant();if(actual.isEmpty())throw invalid("process開始identityが不明です");
            return actual.get().equals(started);
        }catch(SecurityException failure){throw new IOException("process identityを確認できません",failure);}
    }
    /** active lockは待たず、canonical receipt・元core・必要なkeeperを照合する。 */
    public static void requireLive(LeaseSnapshot snapshot) throws IOException {
        ObjectNode lease=snapshot.lease();if(!text(lease,"state",16).equals("active")||!live(lease))throw invalid("元coreの生存を確認できません");
        try(FileChannel channel=FileChannel.open(snapshot.file().resolveSibling("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock held=null;try{held=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){}
            if(held!=null){held.release();throw invalid("active lockが保持されていません");}
            if(snapshot.descriptor().path("host").path("os").asText().equals("windows")&&!lease.path("resources").isEmpty())keeper(snapshot,true);
            else if(lease.path("resources").isEmpty())unstarted(snapshot);
            snapshot.verifyUnchanged();
        }
    }
    public static ObjectNode proof(LeaseSnapshot snapshot) throws IOException {
        snapshot.verifyUnchanged();ObjectNode lease=snapshot.lease(),proof=JSON.createObjectNode();
        proof.set("lease",lease);proof.put("receiptSha256",digest(readFile(snapshot.file().resolveSibling("lease.lock"),64)));
        proof.put("receiptKey",snapshot.receiptKey());proof.put("descriptorSha256",hash(lease,"descriptorSha256"));return proof;
    }
    /** 生成journalが未着手か、未着手のままreleaseされたことだけを確認する。 */
    public static void unstarted(LeaseSnapshot snapshot) throws IOException {
        JsonNode lease=snapshot.lease();if(!lease.path("resources").isEmpty())throw invalid("生成済み資源が残っています");
        Path temporary=snapshot.file().getParent().resolve("tmp");directory(temporary);
        if(snapshot.descriptor().path("host").path("os").asText().equals("macos")) {
            Path journal=temporary.resolve("macos-operations");
            if(Files.exists(journal,LinkOption.NOFOLLOW_LINKS)) {
                directory(journal);try(var entries=Files.newDirectoryStream(journal)) {
                    for(Path entry:entries) {
                        if(!entry.getFileName().toString().equals("released.json"))throw invalid("Mac生成journalが残っています");
                        ObjectNode expected=JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))expected.set(key,lease.required(key));
                        if(!read(readFile(entry,4096)).equals(expected))throw invalid("Mac tombstoneの所有が一致しません");
                    }
                }
            }
        }else if(snapshot.descriptor().path("host").path("os").asText().equals("windows")) {
            Path record=temporary.resolve("windows-operation.json");
            if(Files.exists(record,LinkOption.NOFOLLOW_LINKS)) {
                JsonNode operation=read(readFile(record,1024*1024));
                for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))if(!operation.path("identity").path(key).equals(lease.path(key)))throw invalid("Windows journal所有が一致しません");
                if(!Set.of("unstarted","released").contains(operation.path("state").asText()))throw invalid("Windows生成journalが残っています");
            }
            try(var entries=Files.newDirectoryStream(temporary,"keeper*.json")){if(entries.iterator().hasNext())throw invalid("keeper開始記録が残っています");}
        }else throw invalid("保守未対応のOSです");
    }
    /** native/read-clientの終端記録を照合する。PID不在だけでは完了としない。 */
    public static void requireTerminal(LeaseSnapshot snapshot) throws IOException {
        unstarted(snapshot);JsonNode lease=snapshot.lease();Path temporary=snapshot.file().getParent().resolve("tmp");
        if(!text(lease,"state",16).equals("released"))throw invalid("候補leaseがreleasedではありません");
        try(FileChannel channel=FileChannel.open(snapshot.file().resolveSibling("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock held=null;try{held=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){}
            if(held==null)throw invalid("候補leaseのactive lockが残っています");
            try {
                actors(snapshot);
                snapshot.verifyUnchanged();
            }finally{held.release();}
        }
    }
    /** 通常終了した参照元を確認する。未着手候補の保守条件とは分ける。 */
    public static void requireReleased(LeaseSnapshot snapshot) throws IOException {
        JsonNode lease=snapshot.lease();if(!lease.path("state").asText().equals("released")||!lease.path("resources").isEmpty())throw invalid("参照元の回収が未完了です");
        try(FileChannel channel=FileChannel.open(snapshot.file().resolveSibling("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock held=null;try{held=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){}
            if(held==null)throw invalid("参照元のactive lockが残っています");
            try {
                Path temporary=snapshot.file().getParent().resolve("tmp");directory(temporary);
                if(snapshot.descriptor().path("host").path("os").asText().equals("windows")&&Files.exists(temporary.resolve("keeper.json"),LinkOption.NOFOLLOW_LINKS)) {
                    JsonNode record=read(readFile(temporary.resolve("keeper.json"),1024*1024)),proof=record.path("workProof");
                    ObjectNode identity=JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,lease.required(key));
                    if(!record.path("identity").equals(identity)||!record.path("state").asText().equals("closed")||!record.path("closeIntent").asBoolean()||!record.path("jobEmpty").asBoolean()||!proof.path("closed").asBoolean()||proof.path("actors").asInt(-1)!=0||proof.path("containers").asInt(-1)!=0||!proof.path("identity").equals(identity))throw invalid("参照元keeperの回収が不明です");
                    Path prefix=Path.of(text(record,"prefix",4096));if(!prefix.getParent().equals(temporary)||!prefix.getFileName().toString().matches("native-[0-9a-f-]{36}")||!read(readFile(Path.of(prefix+".state.json"),65536)).equals(read(readFile(Path.of(prefix+".done.json"),65536))))throw invalid("参照元nativeの終端が不明です");
                }
                actors(snapshot);
                if(snapshot.descriptor().path("host").path("os").asText().equals("macos"))releasedMacJournal(snapshot);
                snapshot.verifyUnchanged();
            }finally{held.release();}
        }
    }
    private static void actors(LeaseSnapshot snapshot) throws IOException {
        Path temporary=snapshot.file().getParent().resolve("tmp");
                if(snapshot.descriptor().path("host").path("os").asText().equals("windows")) {
                    try(var entries=Files.newDirectoryStream(temporary,"native-*.intent.json")) {
                        for(Path intentFile:entries) {
                            String prefix=intentFile.toString().substring(0,intentFile.toString().length()-12);
                            JsonNode intent=read(readFile(intentFile,65536)),state=read(readFile(Path.of(prefix+".state.json"),65536)),done=read(readFile(Path.of(prefix+".done.json"),65536));
                            Path nativePath=snapshot.helperFile(Path.of(relative(snapshot.descriptor().path("helper"),"executable")).getParent().resolve("app/support/windows-host.exe").toString().replace('\\','/'));
                            if(!intent.path("native").asText().equals(nativePath.toString())||!state.equals(done)||!state.path("parent").equals(intent.path("parent"))||!state.path("parentStarted").equals(intent.path("started"))||processMatches(integer(state,"pid",1),Instant.ofEpochMilli(integer(state,"started",1))))throw invalid("native終端証拠が一致しません");
                        }
                    }
                }else {
                    try(var entries=Files.newDirectoryStream(temporary,"read-client-*")) {
                        for(Path client:entries){directory(client);String complete=new String(readFile(client.resolve("complete"),65),java.nio.charset.StandardCharsets.US_ASCII);if(!complete.matches("[0-9a-f]{64}\\n")||Files.exists(client.resolve("complete.pending"),LinkOption.NOFOLLOW_LINKS))throw invalid("read-client終端が不明です");}
                    }
                }
    }
    private static void releasedMacJournal(LeaseSnapshot snapshot) throws IOException {
        Path journal=snapshot.file().getParent().resolve("tmp/macos-operations");if(Files.notExists(journal,LinkOption.NOFOLLOW_LINKS))return;
        directory(journal);ObjectNode identity=JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,snapshot.lease().required(key));
        if(!read(readFile(journal.resolve("released.json"),4096)).equals(identity))throw invalid("参照元Mac tombstoneの所有が一致しません");
        try(var entries=Files.newDirectoryStream(journal)) {
            for(Path operation:entries) {
                String name=operation.getFileName().toString();if(name.equals("released.json"))continue;
                try{if(!UUID.fromString(name).toString().equals(name))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw invalid("参照元Mac operation IDが不正です");}
                directory(operation);byte[] original=readFile(operation.resolve("intent.json"),65536);JsonNode intent=read(original),settled=read(readFile(operation.resolve("settled.json"),1024*1024));
                if(!intent.path("identity").equals(identity)||!text(intent,"operationId",36).equals(name)||!Set.of("prepare","create","start","remove","stop").contains(text(intent,"kind",16))||!hash(settled,"intentSha256").equals(digest(original))||!settled.path("evidence").isObject()||settled.path("evidence").isEmpty())throw invalid("参照元Mac operation終端が不明です");
                if(!new String(readFile(operation.resolve("client-complete"),65),java.nio.charset.StandardCharsets.US_ASCII).equals(digest(original)+"\n"))throw invalid("参照元Mac client終端が一致しません");
                try(var files=Files.newDirectoryStream(operation)){for(Path file:files)if(!Set.of("intent.json","settled.json","client-complete").contains(file.getFileName().toString()))throw invalid("参照元Mac operationに未確定fileがあります");}
            }
        }
    }
    private static void keeper(LeaseSnapshot snapshot,boolean requireLive) throws IOException {
        Path temporary=snapshot.file().getParent().resolve("tmp");JsonNode lease=snapshot.lease(),keeper=read(readFile(temporary.resolve("keeper.json"),1024*1024));
        ObjectNode identity=JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,lease.required(key));
        if(!keeper.path("identity").equals(identity)||!keeper.path("state").asText().equals("ready"))throw invalid("keeper所有/状態が一致しません");
        Path prefix=Path.of(text(keeper,"prefix",4096));if(!prefix.getParent().equals(temporary)||!prefix.getFileName().toString().matches("native-[0-9a-f-]{36}"))throw invalid("keeper保存位置が不正です");
        JsonNode client=keeper.path("client"),actual=read(readFile(Path.of(prefix+".client.json"),65536)),state=read(readFile(Path.of(prefix+".state.json"),65536)),intent=read(readFile(Path.of(prefix+".intent.json"),65536));
        String executable=relative(snapshot.descriptor().path("helper"),"executable");
        Path nativePath=snapshot.helperFile(Path.of(executable).getParent().resolve("app/support/windows-host.exe").toString().replace('\\','/'));
        if(!client.equals(actual)||!intent.path("native").asText().equals(nativePath.toString()))throw invalid("keeper native pinが一致しません");
        for(String key:List.of("pid","started","parent","parentStarted","boot"))if(!client.path(key).equals(state.path(key)))throw invalid("keeper client identityが一致しません");
        if(requireLive&&!processMatches(integer(client,"clientPid",1),Instant.ofEpochMilli(integer(client,"clientStarted",1))))throw invalid("keeper実clientが不明です");
    }
    static void directory(Path path) throws IOException {if(!path.toRealPath().equals(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw invalid("所有directoryにlinkがあります");}
}
