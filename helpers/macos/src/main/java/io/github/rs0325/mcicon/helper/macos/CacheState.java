package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/** descriptorごとのVMと実行中の持ち主を1ファイルで保持する。 */
final class CacheState {
    private final Path root,anchor,registration;
    private final String digest,boot;
    CacheState(Path root,String hostId,String digest,long uid,String boot) {
        this.root=root;this.digest=digest;this.boot=boot;
        String token=MacFiles.hash((root+"\0"+hostId+"\0"+digest+"\0"+uid).getBytes(StandardCharsets.UTF_8)).substring(0,20);
        anchor=Path.of("/private/tmp/mcicon-"+uid+"-"+token);registration=root.resolve("macos").resolve(digest+".json");
    }
    Path anchor(){return anchor;}
    Path file(){return registration;}
    Path limaHome(){return anchor.resolve("l");}
    Path home(){return anchor.resolve("h");}
    Path instance(){return limaHome().resolve("v");}
    Path socket(){return instance().resolve("sock/docker.sock");}
    boolean previousBoot(JsonNode state){return !state.path("bootSessionId").asText().equals(boot);}
    ObjectNode read() throws IOException {
        if(Files.notExists(registration,LinkOption.NOFOLLOW_LINKS))return null;
        try {
            JsonNode value=MacFiles.json(registration);Set<String> names=new HashSet<>();value.fieldNames().forEachRemaining(names::add);
            if(!names.equals(Set.of("descriptorSha256","instanceDir","generationOwner","memoryMiB","bootSessionId","owner"))||!value.path("descriptorSha256").asText().equals(digest)||!value.path("instanceDir").asText().equals(instance().toString())||!value.path("memoryMiB").isInt()||value.path("memoryMiB").asInt()<1)throw new IOException("VM状態の形式が一致しません");
            UUID.fromString(value.path("generationOwner").asText());UUID.fromString(value.path("bootSessionId").asText());
            JsonNode owner=value.path("owner");if(!owner.isNull()) {
                Set<String> fields=new HashSet<>();owner.fieldNames().forEachRemaining(fields::add);
                if(!fields.equals(Set.of("pid","processStartedAt","jobId","ownerId"))||owner.path("pid").asLong()<1)throw new IOException("VMの持ち主を読めません");
                Instant.parse(owner.path("processStartedAt").asText());UUID.fromString(owner.path("jobId").asText());UUID.fromString(owner.path("ownerId").asText());
            }
            return (ObjectNode)value;
        }catch(IOException|RuntimeException failure){throw new IOException("VM状態を読めません: "+registration+"。LIMA_HOME="+limaHome()+" limactl delete vで専用VMを削除し、cache "+anchor+" と状態ファイル "+registration+" を削除してください",failure);}
    }
    ObjectNode create(int memory) {
        ObjectNode value=MacFiles.JSON.createObjectNode().put("descriptorSha256",digest).put("instanceDir",instance().toString()).put("generationOwner",UUID.randomUUID().toString()).put("memoryMiB",memory).put("bootSessionId",boot);
        value.putNull("owner");return value;
    }
    void save(ObjectNode value) throws IOException {MacFiles.write(registration,value);}
    void directories() throws IOException {
        MacFiles.privateDirectory(root.resolve("macos"));
        for(Path path:List.of(anchor,limaHome(),home(),anchor.resolve("d")))MacFiles.privateDirectory(path);
        checkSocketLength();
    }
    static boolean ownerLive(JsonNode state) throws IOException {
        JsonNode owner=state.path("owner");if(owner.isNull())return false;
        Optional<ProcessHandle> process=ProcessHandle.of(owner.path("pid").asLong());if(process.isEmpty()||!process.get().isAlive())return false;
        Instant actual=process.get().info().startInstant().orElseThrow(()->new IOException("VMの持ち主の開始時刻を確認できません"));
        return actual.equals(Instant.parse(owner.path("processStartedAt").asText()));
    }
    static boolean ownedBy(JsonNode state,JsonNode lease) {
        JsonNode owner=state.path("owner");for(String key:List.of("jobId","ownerId","pid","processStartedAt"))if(!owner.path(key).equals(lease.path(key)))return false;return true;
    }
    static void assign(ObjectNode state,JsonNode lease) {
        ObjectNode owner=state.putObject("owner");for(String key:List.of("jobId","ownerId","pid","processStartedAt"))owner.set(key,lease.required(key));
    }
    void currentBoot(ObjectNode state){state.put("bootSessionId",boot);}
    void checkSocketLength() throws IOException {
        for(Path path:List.of(instance().resolve("ssh.sock.1234567890123456"),socket(),instance().resolve("user-v2_fd.sock")))if(path.toString().getBytes(StandardCharsets.UTF_8).length>=104)throw new IOException("短cacheのsocket pathがmacOS上限を超えています: "+anchor);
    }
    Lock lock(Duration timeout) throws IOException {
        Path directory=root.resolve("locks");Files.createDirectories(directory);if(!directory.toRealPath().equals(directory))throw new IOException("lifecycle lock directoryにlinkがあります: "+directory);
        Path path=directory.resolve("macos-lifecycle.lock");if(Files.isSymbolicLink(path))throw new IOException("lifecycle lockにlinkがあります: "+path);
        FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);long end=System.nanoTime()+timeout.toNanos();
        try {
            while(true) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("lifecycle lock待機が中断されました");
                FileLock held=null;try{held=channel.tryLock();}catch(OverlappingFileLockException busy){}
                if(held!=null)return new Lock(channel,held);
                if(System.nanoTime()>=end)throw new IOException("lifecycle lockの期限を超えました: "+path+"。実行中の描画が終了してから再試行してください");LockSupport.parkNanos(10_000_000);
            }
        }catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
    record Lock(FileChannel channel,FileLock held) implements AutoCloseable {public void close() throws IOException {try{held.release();}finally{channel.close();}}}
}
