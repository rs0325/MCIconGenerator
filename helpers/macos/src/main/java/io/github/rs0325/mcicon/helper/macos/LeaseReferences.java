package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 他世代をskipせず、全jobのbusy/不明を共有VMの停止禁止へ反映する。 */
final class LeaseReferences {
    record Scan(boolean active,List<String> diagnostics,long memoryMiB,boolean pristine,boolean activeStarted) {boolean restartAllowed(){return !activeStarted&&!unknown();}boolean unknown(){return !diagnostics.isEmpty();}boolean stopAllowed(){return !active&&!unknown();}}
    private LeaseReferences() {}
    static Scan inspect(HelperProtocol.Command command,Path executable,String descriptorSha256) throws IOException {
        Path jobs=command.root().resolve("jobs");if(!jobs.toRealPath().equals(jobs))throw new IOException("job一覧にlinkがあります");
        String hostId=Files.readString(command.root().resolve("host-id"),StandardCharsets.US_ASCII).trim();
        boolean active=false,pristine=true,activeStarted=false;List<String> diagnostics=new ArrayList<>();long memory=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(jobs)) {
            for(Path directory:entries) {
                Path lease=directory.resolve("lease.json");if(lease.equals(command.lease()))continue;
                try {
                    if(!UUID.fromString(directory.getFileName().toString()).toString().equals(directory.getFileName().toString())||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toRealPath().equals(directory))throw new IOException("未知のjob directoryです");
                    Path receipt=directory.resolve("lease.lock");if(!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS))throw new IOException("他jobのreceiptがありません");
                    try(FileChannel channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                        FileLock lock=null;try{lock=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){}
                        try {
                            ObjectNode value=(ObjectNode)MacFiles.json(lease);
                            if(value.path("state").asText().equals("released")) {
                                if(lock==null)throw new IOException("released記録のlockが保持されています");
                                if(!value.path("jobId").asText().equals(directory.getFileName().toString()))throw new IOException("released job識別が不正です");
                                validateReleased(value,hostId);
                                ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));
                                if(channel.size()!=64||!Files.readString(receipt,StandardCharsets.US_ASCII).equals(new String(LeaseFile.receipt(identity),StandardCharsets.US_ASCII)))throw new IOException("released receiptが一致しません");
                            }else {
                                active=true;
                                LeaseFile current=LeaseFile.open(new HelperProtocol.Command(command.root(),lease,"release",List.of()),executable);JsonNode checked=current.read();
                                if(lock!=null||!live(checked))throw new IOException("他jobのactive所有者が不明です");
                                memory=Math.addExact(memory,checked.path("requirements").path("containerMemoryMiB").longValue());
                            }
                            boolean released=value.path("state").asText().equals("released"),same=value.path("descriptorSha256").asText().equals(descriptorSha256);
                            if(!released&&!same)pristine=false;
                            if(!value.path("resources").isEmpty()||!emptyJournal(directory,value)){if(same||!released)pristine=false;if(!released)activeStarted=true;}
                        }finally {if(lock!=null)lock.release();}
                    }
                }catch(IOException|RuntimeException failure){pristine=false;diagnostics.add(directory.getFileName()+": "+failure.getMessage());}
            }
        }
        return new Scan(active,List.copyOf(diagnostics),memory,pristine,activeStarted);
    }
    private static boolean emptyJournal(Path job,JsonNode lease) throws IOException {
        Path temporary=job.resolve("tmp"),directory=temporary.resolve("macos-operations");
        try {Files.readAttributes(temporary,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){return false;}
        if(!Files.isDirectory(temporary,LinkOption.NOFOLLOW_LINKS)||!temporary.toRealPath().equals(temporary))throw new IOException("過去job tmpの位置が不明です");
        try {Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){return true;}
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toRealPath().equals(directory))throw new IOException("過去journalの位置が不明です");
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries) {
                if(entry.getFileName().toString().equals("released.json")) {
                    ObjectNode expected=MacFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))expected.set(key,lease.required(key));if(!MacFiles.json(entry).equals(expected))throw new IOException("過去tombstoneの所有が不明です");
                }else {try{UUID.fromString(entry.getFileName().toString());}catch(IllegalArgumentException failure){throw new IOException("過去journalに未知の記録があります",failure);}if(!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))throw new IOException("過去operationのtypeが不明です");return false;}
            }
        }
        return true;
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
    static boolean live(JsonNode lease) {
        if(!lease.path("processStartedAt").isTextual())return false;
        try {return ProcessHandle.of(lease.path("pid").longValue()).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).filter(Instant.parse(lease.path("processStartedAt").asText())::equals).isPresent();}
        catch(RuntimeException failure){return false;}
    }
}
