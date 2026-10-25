package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 他世代をskipせず、全jobのbusy/不明を共有distroの停止禁止へ反映する。 */
final class LeaseReferences {
    record Scan(boolean active,List<String> diagnostics,long memoryMiB,boolean pristine,boolean activeStarted,boolean stopBlocked) {boolean restartAllowed(){return !activeStarted&&!unknown();}boolean unknown(){return !diagnostics.isEmpty();}boolean stopAllowed(){return !stopBlocked&&!unknown();}}
    private LeaseReferences() {}
    static Scan inspect(HelperProtocol.Command command,Path executable,String descriptorSha256) throws IOException {
        Path jobs=command.root().resolve("jobs");if(!jobs.toRealPath().equals(jobs))throw new IOException("job一覧にlinkがあります");
        String hostId=Files.readString(command.root().resolve("host-id"),StandardCharsets.US_ASCII).trim();
        boolean active=false,pristine=true,activeStarted=false,stopBlocked=false;List<String> diagnostics=new ArrayList<>();long memory=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(jobs)) {
            for(Path directory:entries) {
                Path lease=directory.resolve("lease.json");if(lease.equals(command.lease()))continue;
                try {
                    if(!UUID.fromString(directory.getFileName().toString()).toString().equals(directory.getFileName().toString())||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toRealPath().equals(directory))throw new IOException("未知のjob directoryです");
                    Path receipt=directory.resolve("lease.lock");if(!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS))throw new IOException("他jobのreceiptがありません");
                    try(FileChannel channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                        FileLock lock=null;try{lock=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){}
                        try {
                            ObjectNode value=(ObjectNode)WindowsFiles.json(lease);
                            if(value.path("state").asText().equals("released")) {
                                if(lock==null&&(!value.path("descriptorSha256").asText().equals(descriptorSha256)||!quiescent(directory,value,executable)))throw new IOException("released記録のlockが保持されています");
                                if(!value.path("jobId").asText().equals(directory.getFileName().toString()))throw new IOException("released job識別が不正です");
                                validateReleased(value,hostId);
                                ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));
                                if(channel.size()!=64)throw new IOException("released receiptは64bytesが必要です");
                                channel.position(0);ByteBuffer saved=ByteBuffer.allocate(64);
                                while(saved.hasRemaining()){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("released receipt検証が中断されました");if(channel.read(saved)<=0)throw new IOException("released receiptが不足しています");}
                                if(channel.size()!=64||!Arrays.equals(saved.array(),LeaseFile.receipt(identity)))throw new IOException("released receiptが一致しません");
                            }else {
                                active=true;
                                LeaseFile current=LeaseFile.open(new HelperProtocol.Command(command.root(),lease,"release",List.of()),executable);JsonNode checked=current.read();
                                if(lock!=null||!live(checked))throw new IOException("他jobのactive所有者が不明です");
                                memory=Math.addExact(memory,checked.path("requirements").path("containerMemoryMiB").longValue());
                            }
                            boolean released=value.path("state").asText().equals("released"),same=value.path("descriptorSha256").asText().equals(descriptorSha256);
                            if(!released) {
                                if(!same)stopBlocked=true;
                                else if(value.path("resources").isEmpty()&&emptyJournal(directory,value)&&!Files.exists(directory.resolve("tmp/keeper.json"),LinkOption.NOFOLLOW_LINKS)) {
                                    try(var records=Files.newDirectoryStream(directory.resolve("tmp"),"keeper-*.json")){if(records.iterator().hasNext())throw new IOException("keeper部分開始が残っています");}
                                }else if(!quiescent(directory,value,executable)) {
                                    keeperLive(directory,value,executable);stopBlocked=true;
                                }
                            }
                            if(!released&&!same)pristine=false;
                            if(!value.path("resources").isEmpty()||!emptyJournal(directory,value)){if(same||!released)pristine=false;if(!released)activeStarted=true;}
                        }finally {if(lock!=null&&lock.isValid())lock.release();}
                    }
                }catch(IOException|RuntimeException failure){pristine=false;diagnostics.add(directory.getFileName()+": "+failure.getMessage());}
            }
        }
        return new Scan(active,List.copyOf(diagnostics),memory,pristine,activeStarted,stopBlocked);
    }
    private static JsonNode keeper(Path job,JsonNode lease,Path executable) throws IOException {
        JsonNode keeper=WindowsFiles.json(job.resolve("tmp/keeper.json"));ObjectNode identity=WindowsFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,lease.required(key));
        if(!keeper.path("identity").equals(identity)||!HostFacts.uuid(keeper.path("attempt").asText()))throw new IOException("他keeperの所有が不明です");
        Path prefix=Path.of(keeper.path("prefix").asText());if(!prefix.getParent().equals(job.resolve("tmp"))||!prefix.getFileName().toString().matches("native-[0-9a-f-]{36}"))throw new IOException("他keeper保存位置が不正です");
        JsonNode client=keeper.path("client"),actual=WindowsFiles.json(Path.of(prefix+".client.json")),state=WindowsFiles.json(Path.of(prefix+".state.json")),intent=WindowsFiles.json(Path.of(prefix+".intent.json"));
        if(!client.equals(actual)||!intent.path("native").asText().equals(executable.getParent().resolve("app/support/windows-host.exe").toString()))throw new IOException("他keeperのnative pinが一致しません");
        for(String key:List.of("pid","started","parent","parentStarted","boot"))if(!client.path(key).equals(state.path(key)))throw new IOException("他keeper clientとguardの所有が一致しません");
        return keeper;
    }
    private static boolean quiescent(Path job,JsonNode lease,Path executable) throws IOException {
        JsonNode record=keeper(job,lease,executable);if(!record.path("state").asText().equals("closed"))return false;
        JsonNode proof=record.path("workProof");if(!record.path("closeIntent").asBoolean()||!record.path("jobEmpty").asBoolean()||!lease.path("resources").isEmpty()||!emptyJournal(job,lease)||!WindowsFiles.json(job.resolve("tmp/windows-operation.json")).path("state").asText().equals("released")||!proof.path("closed").asBoolean()||proof.path("actors").asInt(-1)!=0||proof.path("containers").asInt(-1)!=0||!proof.path("identity").equals(record.path("identity")))throw new IOException("他keeperのquiescent証明が不明です");
        if(!WindowsFiles.json(Path.of(record.path("prefix").asText()+".done.json")).equals(WindowsFiles.json(Path.of(record.path("prefix").asText()+".state.json"))))throw new IOException("他keeper Job0終端が不明です");return true;
    }
    private static void keeperLive(Path job,JsonNode lease,Path executable) throws IOException {
        JsonNode keeper=keeper(job,lease,executable);if(!keeper.path("state").asText().equals("ready"))throw new IOException("他keeperはstarting/closing/unknownです");
        JsonNode client=keeper.path("client");if(!ProcessHandle.of(client.path("clientPid").asLong()).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).map(Instant::toEpochMilli).filter(value->value==client.path("clientStarted").asLong()).isPresent())throw new IOException("他keeper実clientが不明です");
    }
    private static boolean emptyJournal(Path job,JsonNode lease) throws IOException {
        Path directory=job.resolve("tmp"),record=directory.resolve("windows-operation.json");
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toRealPath().equals(directory))throw new IOException("過去job tmpの位置が不明です");
        try {Files.readAttributes(record,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){return true;}
        JsonNode value=WindowsFiles.json(record),identity=value.path("identity");for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))if(!identity.path(key).equals(lease.path(key)))throw new IOException("過去operation所有が不明です");
        return Set.of("released","unstarted").contains(value.path("state").asText());
    }
    static void validateReleased(JsonNode value,String hostId) throws IOException {
        Set<String> fields=new HashSet<>();value.fieldNames().forEachRemaining(fields::add);
        if(!value.isObject()||!fields.equals(Set.of("schemaVersion","jobId","ownerId","toolVersion","workerSha256","runtimeId","descriptorSha256","hostId","pid","processStartedAt","createdAt","requirements","state","resources"))||!value.path("state").asText().equals("released")||!value.path("resources").isObject()||!value.path("resources").isEmpty())throw new IOException("released記録が不正です");

        if(value.path("schemaVersion").asLong()!=1||!value.path("schemaVersion").isIntegralNumber()||!value.path("schemaVersion").canConvertToLong()||!uuid(value,"jobId")||!uuid(value,"ownerId")||!uuid(value,"hostId")||!value.path("hostId").asText().equals(hostId)||value.path("jobId").equals(value.path("ownerId"))||!value.path("pid").isIntegralNumber()||!value.path("pid").canConvertToLong()||value.path("pid").asLong()<1)throw new IOException("released identityが不正です");
        for(String key:List.of("workerSha256","descriptorSha256"))if(!value.path(key).isTextual()||!value.path(key).asText().matches("[0-9a-f]{64}"))throw new IOException("released SHAが不正です");
        if(!value.path("runtimeId").isTextual()||!value.path("toolVersion").isTextual()||!value.path("runtimeId").asText().matches("[a-z0-9][a-z0-9._-]{0,95}")||value.path("toolVersion").asText().length()>256||!value.path("toolVersion").asText().matches("[0-9]+[.][0-9]+[.][0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"))throw new IOException("released版識別が不正です");
        if(!instant(value.get("createdAt"))||(!value.path("processStartedAt").isNull()&&!instant(value.get("processStartedAt"))))throw new IOException("released owner時刻が不正です");
        JsonNode requirements=value.path("requirements");Set<String> names=new HashSet<>();requirements.fieldNames().forEachRemaining(names::add);
        if(!requirements.isObject()||!names.equals(Set.of("containerMemoryMiB","heapMemoryMiB","cpus","preparationDeadline")))throw new IOException("released予算fieldが不正です");
        for(String key:List.of("containerMemoryMiB","heapMemoryMiB","cpus"))if(!requirements.path(key).isIntegralNumber()||!requirements.path(key).canConvertToLong()||requirements.path(key).asLong()<1)throw new IOException("released予算が不正です");
        if(requirements.path("containerMemoryMiB").asLong()>Integer.MAX_VALUE||requirements.path("heapMemoryMiB").asLong()>=requirements.path("containerMemoryMiB").asLong()||requirements.path("cpus").asLong()!=4||!instant(requirements.get("preparationDeadline")))throw new IOException("released予算値が不正です");
    }
    private static boolean uuid(JsonNode value,String key) {try {String text=value.path(key).textValue();return UUID.fromString(text).toString().equals(text);}catch(RuntimeException failure){return false;}}
    private static boolean instant(JsonNode value) {try {String text=value.textValue();return text.length()<=64&&text.endsWith("Z")&&Instant.parse(text)!=null;}catch(RuntimeException failure){return false;}}
    static boolean ownerDead(JsonNode lease) throws IOException {
        try{return ownerDead(ProcessHandle.of(lease.path("pid").longValue()),Instant.parse(lease.path("processStartedAt").asText()));}
        catch(SecurityException|java.time.DateTimeException failure){throw new IOException("旧coreの死亡identityを確認できません",failure);}
    }
    static boolean ownerDead(Optional<ProcessHandle> process,Instant expected) throws IOException {
        try {
            if(process.isEmpty()||!process.get().isAlive())return true;
            var started=process.get().info().startInstant();if(started.isPresent())return !started.get().equals(expected);
            if(!process.get().isAlive())return true;throw new IOException("旧coreの開始identityが不明です");
        }catch(SecurityException failure){throw new IOException("旧coreの生存状態が不明です",failure);}
    }
    static boolean live(JsonNode lease) {
        if(!lease.path("processStartedAt").isTextual())return false;
        try {return ProcessHandle.of(lease.path("pid").longValue()).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).filter(Instant.parse(lease.path("processStartedAt").asText())::equals).isPresent();}
        catch(RuntimeException failure){return false;}
    }
}
