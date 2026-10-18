package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.McIconException;
import io.github.rs0325.mcicon.ManagedOptions;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** ジョブの世代と所有証拠を固定する。PID単独でプロセスやVMを停止しない。 */
public final class RuntimeLease implements AutoCloseable {
    static final long ACTIVE_LOCK_OFFSET=64,ACTIVE_LOCK_LENGTH=1;
    private final Path file;
    private final ObjectNode identity;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    private RuntimeLease(Path file,ObjectNode identity,FileChannel channel,FileLock lock) {this.file=file;this.identity=identity;this.channel=channel;this.lock=lock;}
    public static RuntimeLease create(RuntimeStore store,RuntimeDescriptor descriptor,ManagedOptions options,Instant preparationDeadline) throws IOException {
        try(var ignored=store.lock("generations")) {
            Path host=store.path("host-id");
            if(!Files.exists(host))FilesSupport.atomicWrite(host,UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String hostId=Files.readString(host).trim();
            try {UUID.fromString(hostId);}catch(IllegalArgumentException failure){throw mismatch("管理rootのhostIdが不正です");}
            UUID job=UUID.randomUUID(),owner=UUID.randomUUID();
            Path directory=store.path("jobs/"+job);Files.createDirectories(directory.getParent());Files.createDirectory(directory);
            FileChannel channel=FileChannel.open(directory.resolve("lease.lock"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            FileLock lock=null;
            try {
                lock=channel.lock(ACTIVE_LOCK_OFFSET,ACTIVE_LOCK_LENGTH,false);ObjectNode identity=RuntimeDescriptor.JSON.createObjectNode();
                identity.put("schemaVersion",1);identity.put("jobId",job.toString());identity.put("ownerId",owner.toString());
                identity.put("toolVersion",descriptor.toolVersion());identity.put("workerSha256",descriptor.agentSha256());identity.put("runtimeId",descriptor.runtimeId());identity.put("descriptorSha256",descriptor.digest());
                identity.put("hostId",hostId);identity.put("pid",ProcessHandle.current().pid());
                var started=ProcessHandle.current().info().startInstant();
                if(started.isPresent())identity.put("processStartedAt",started.get().toString());else identity.putNull("processStartedAt");
                identity.put("createdAt",Instant.now().toString());
                identity.putObject("requirements").put("containerMemoryMiB",options.containerMemoryMiB()).put("heapMemoryMiB",options.heapMemoryMiB()).put("cpus",4).put("preparationDeadline",preparationDeadline.toString());
                identity=(ObjectNode)RuntimeDescriptor.JSON.readTree(RuntimeDescriptor.JSON.writeValueAsBytes(identity));
                java.nio.ByteBuffer receipt=java.nio.ByteBuffer.wrap(receipt(identity));
                while(receipt.hasRemaining())channel.write(receipt);channel.force(true);
                ObjectNode document=identity.deepCopy();document.put("state","active");document.putObject("resources");
                Path file=directory.resolve("lease.json");FilesSupport.atomicWrite(file,RuntimeDescriptor.JSON.writeValueAsBytes(document));
                return new RuntimeLease(file,identity,channel,lock);
            }catch(IOException|RuntimeException failure){if(lock!=null)lock.release();channel.close();throw failure;}
        }
    }
    public Path file() {return file;}
    /** helperの書込み完了後だけ呼ぶ。immutableなfieldの追加・変更・削除を拒否する。 */
    public synchronized ObjectNode read() throws IOException {
        if(closed)throw mismatch("解放済みleaseです");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>1024*1024)throw mismatch("leaseを安全に読み取れません");
        JsonNode value=RuntimeDescriptor.read(Files.readAllBytes(file));
        if(value==null||!value.isObject()||value.size()!=identity.size()+2||!value.path("resources").isObject()||!value.path("state").isTextual())throw mismatch("lease形式が一致しません");
        for(var fields=identity.fields();fields.hasNext();) {var field=fields.next();if(!field.getValue().equals(value.get(field.getKey())))throw mismatch("leaseの所有証拠が一致しません: "+field.getKey());}
        if(!value.path("state").asText().equals("active"))throw mismatch("lease状態が一致しません");
        return (ObjectNode)value;
    }
    /** helperが所有照合と回収に成功した場合だけ記録する。失敗・不明時は元の証拠を残す。 */
    public synchronized void released() throws IOException {
        ObjectNode value=read();
        if(!value.path("resources").isEmpty())throw mismatch("helperが回収済みresourceを残しています");
        value.put("state","released");FilesSupport.atomicWrite(file,RuntimeDescriptor.JSON.writeValueAsBytes(value));
    }
    /** 回収失敗時もJVMを止めない。lease.jsonは削除せず、次回の不明lease診断に残す。 */
    @Override public synchronized void close() throws IOException {if(!closed){closed=true;try {lock.release();}finally{channel.close();}}}
    static McIconException mismatch(String message) {return new McIconException("MANAGED_OWNERSHIP_MISMATCH",message,1);}
    /** 回収可能性だけを判定し、VMや世代を削除しない。不明な過去leaseも稼働扱いにする。 */
    public static boolean idle(RuntimeStore store,RuntimeDescriptor descriptor) throws IOException {
        Path jobs=store.path("jobs");if(!Files.isDirectory(jobs,LinkOption.NOFOLLOW_LINKS))return true;
        Path host=store.path("host-id");
        if(!Files.isRegularFile(host,LinkOption.NOFOLLOW_LINKS)||Files.size(host)>64)return false;
        String hostId=Files.readString(host).trim();
        try(var directories=Files.list(jobs)) {
            for(Path directory:directories.toList()) {
                if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))return false;
                Path file=directory.resolve("lease.json"),lock=directory.resolve("lease.lock");
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>1024*1024||!Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS))return false;
                JsonNode value;
                try {value=RuntimeDescriptor.read(Files.readAllBytes(file));}catch(IOException failure){return false;}
                if(!validIdentity(value,directory.getFileName().toString(),hostId))return false;
                try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
                    FileLock acquired;
                    try {acquired=channel.tryLock(ACTIVE_LOCK_OFFSET,ACTIVE_LOCK_LENGTH,false);}catch(OverlappingFileLockException busy){return false;}
                    if(acquired==null)return false;
                    try {
                        if(channel.size()!=64)return false;
                        java.nio.ByteBuffer saved=java.nio.ByteBuffer.allocate(64);
                        while(saved.hasRemaining())if(channel.read(saved)<0)return false;
                        ObjectNode actual=((ObjectNode)value).deepCopy();actual.remove(List.of("state","resources"));
                        if(!Arrays.equals(saved.array(),receipt(actual)))return false;
                        if(!value.path("descriptorSha256").asText().equals(descriptor.digest()))continue;
                        if(!value.path("toolVersion").asText().equals(descriptor.toolVersion())||!value.path("workerSha256").asText().equals(descriptor.agentSha256())||!value.path("runtimeId").asText().equals(descriptor.runtimeId()))return false;
                        if(!value.path("state").asText().equals("released")||!value.path("resources").isObject()||!value.path("resources").isEmpty())return false;
                    }finally {acquired.release();}
                }
            }
        }
        return true;
    }
    static byte[] receipt(ObjectNode identity) throws IOException {
        byte[] bytes=RuntimeDescriptor.JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(RuntimeDescriptor.JSON.convertValue(identity,Map.class));
        return FilesSupport.hash(bytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static boolean validIdentity(JsonNode value,String directory,String hostId) {
        try {
            RuntimeDescriptor.fields(value,"schemaVersion","jobId","ownerId","toolVersion","workerSha256","runtimeId","descriptorSha256","hostId","pid","processStartedAt","createdAt","requirements","state","resources");
            RuntimeDescriptor.constant(value,"schemaVersion",1);
            String job=RuntimeDescriptor.text(value,"jobId",36),owner=RuntimeDescriptor.text(value,"ownerId",36),host=RuntimeDescriptor.text(value,"hostId",36);
            if(!UUID.fromString(job).toString().equals(job)||!job.equals(directory)||!UUID.fromString(owner).toString().equals(owner)||owner.equals(job)||!UUID.fromString(host).toString().equals(host)||!host.equals(hostId))return false;
            RuntimeDescriptor.version(value,"toolVersion");RuntimeDescriptor.hash(value,"workerSha256");RuntimeDescriptor.hash(value,"descriptorSha256");RuntimeDescriptor.id(value,"runtimeId");
            RuntimeDescriptor.positive(value,"pid");
            Instant.parse(RuntimeDescriptor.text(value,"processStartedAt",64));Instant.parse(RuntimeDescriptor.text(value,"createdAt",64));
            JsonNode requirements=value.get("requirements");RuntimeDescriptor.fields(requirements,"containerMemoryMiB","heapMemoryMiB","cpus","preparationDeadline");
            long memory=RuntimeDescriptor.positive(requirements,"containerMemoryMiB"),heap=RuntimeDescriptor.positive(requirements,"heapMemoryMiB");
            if(memory>Integer.MAX_VALUE||heap>=memory)return false;
            RuntimeDescriptor.constant(requirements,"cpus",4);String deadline=RuntimeDescriptor.text(requirements,"preparationDeadline",64);
            if(!deadline.endsWith("Z"))return false;Instant.parse(deadline);
            RuntimeDescriptor.choice(value,"state","active","released");return value.get("resources").isObject();
        }catch(RuntimeException invalid){return false;}
    }
}
