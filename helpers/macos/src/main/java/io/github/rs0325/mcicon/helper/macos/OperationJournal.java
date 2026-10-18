package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** job単位の生成と回収を直列化し、異常終了したdaemon操作を未確定のまま保持する。 */
final class OperationJournal implements AutoCloseable {
    record Operation(Path directory,String sha256) {
        Path completion() {return directory.resolve("client-complete");}
    }
    private final Path directory,tombstone;
    private final JsonNode identity;
    private final FileChannel channel;
    private final FileLock lock;
    private OperationJournal(Path directory,JsonNode identity,FileChannel channel,FileLock lock) {
        this.directory=directory;this.tombstone=directory.resolve("released.json");this.identity=identity.deepCopy();this.channel=channel;this.lock=lock;
    }
    static OperationJournal acquire(Path temporary,JsonNode lease,Duration budget) throws IOException {
        if(!temporary.toRealPath().equals(temporary))throw new IOException("operation親directoryにlinkがあります");
        Path path=temporary.resolve("helper-operation.lock");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("operation lockが通常fileではありません");
        FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try {
            long deadline=System.nanoTime()+budget.toNanos();FileLock lock=null;
            while(lock==null) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("job操作が中断されました");
                try {lock=channel.tryLock(0,1,false);}catch(OverlappingFileLockException busy){}
                if(lock!=null)break;
                if(System.nanoTime()>=deadline)throw new IOException("job操作の排他期限を超えました");
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            try {
                Path directory=temporary.resolve("macos-operations");MacFiles.privateDirectory(directory);
                ObjectNode identity=MacFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,lease.required(key));
                return new OperationJournal(directory,identity,channel,lock);
            }catch(Throwable failure){lock.release();throw failure;}
        }catch(Throwable failure){channel.close();throw failure;}
    }
    Operation begin(String kind) throws IOException {
        if(kind.equals("start"))throw new IOException("startには登録済みfull IDが必要です");
        return begin(kind,null,null);
    }
    Operation beginStart(String id,JsonNode mac) throws IOException {
        if(!id.matches("[0-9a-f]{64}"))throw new IOException("start IDが不正です");String name=null;
        for(JsonNode container:mac.path("containers"))if(container.path("id").asText().equals(id))name=container.path("name").asText();
        if(name==null)throw new IOException("start対象はresourcesへ登録済みのfull IDだけです");return begin("start",id,name);
    }
    private Operation begin(String kind,String id,String name) throws IOException {
        requireActive();
        if(!Set.of("prepare","create","start","remove","stop").contains(kind))throw new IOException("未定義のdaemon操作です");
        Path operation=directory.resolve(UUID.randomUUID().toString());MacFiles.privateDirectory(operation);
        ObjectNode intent=MacFiles.JSON.createObjectNode();intent.set("identity",identity);intent.put("operationId",operation.getFileName().toString());intent.put("kind",kind);if(id!=null){intent.put("containerId",id);intent.put("containerName",name);}
        byte[] bytes=MacFiles.JSON.writeValueAsBytes(intent);durable(operation.resolve("intent.json"),bytes);force(directory);
        return new Operation(operation,MacFiles.hash(bytes));
    }
    /** 正常なdaemon応答と完全なOS所有照合を保存した後にだけ呼ぶ。異常応答は保存しない。 */
    void settled(Operation operation,JsonNode evidence) throws IOException {
        verify(operation);clientComplete(operation);
        if(evidence==null||!evidence.isObject()||evidence.isEmpty())throw new IOException("daemon操作の終端証拠がありません");
        ObjectNode outcome=MacFiles.JSON.createObjectNode();outcome.put("intentSha256",operation.sha256());outcome.set("evidence",evidence.deepCopy());
        durable(operation.directory().resolve("settled.json"),MacFiles.JSON.writeValueAsBytes(outcome));
    }
    boolean unstarted() throws IOException {requireHeld();try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)){for(Path entry:entries)if(!entry.equals(tombstone))return false;}return true;}
    boolean removed(String id) throws IOException {
        requireHeld();
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries) {
                if(entry.equals(tombstone))continue;checkDirectory(entry);Operation operation=new Operation(entry,MacFiles.hash(entry.resolve("intent.json")));verify(operation);
                JsonNode intent=MacFiles.json(entry.resolve("intent.json"));Path outcome=entry.resolve("settled.json");
                if(intent.path("kind").asText().equals("remove")&&Files.exists(outcome,LinkOption.NOFOLLOW_LINKS)) {
                    clientComplete(operation);JsonNode saved=MacFiles.json(outcome);if(!saved.path("intentSha256").asText().equals(operation.sha256()))throw new IOException("remove証拠の結合が一致しません");
                    if(saved.path("evidence").path("removedFullId").asText().equals(id)&&saved.path("evidence").path("absenceVerified").asBoolean())return true;
                }
            }
        }
        return false;
    }
    void settleStarts(String id,JsonNode removal) throws IOException {
        for(Operation operation:pendingStarts().values()) {
            JsonNode intent=MacFiles.json(operation.directory().resolve("intent.json"));
            if(intent.path("containerId").asText().equals(id)&&!Files.exists(operation.directory().resolve("settled.json"),LinkOption.NOFOLLOW_LINKS))settled(operation,removal);
        }
    }
    Map<String,Operation> pendingStarts() throws IOException {
        requireHeld();Map<String,Operation> result=new LinkedHashMap<>();
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries) {
                if(entry.equals(tombstone))continue;checkDirectory(entry);
                Operation operation=new Operation(entry,MacFiles.hash(entry.resolve("intent.json")));verify(operation);
                JsonNode intent=MacFiles.json(entry.resolve("intent.json"));if(intent.path("kind").asText().equals("start"))result.put(entry.getFileName().toString(),operation);
            }
        }
        return result;
    }
    void requireRecoverable() throws IOException {requireRecords(true);}
    void awaitRecoverable(Duration budget) throws IOException {
        long deadline=System.nanoTime()+Math.min(Duration.ofSeconds(3).toNanos(),budget.toNanos());
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("client回収待機が中断されました");
            try {requireRecoverable();return;}catch(ClientPending pending) {
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("client群の回収印を期限内に確認できません",pending);
                java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(10)));
            }
        }
    }
    void requireActive() throws IOException {
        requireHeld();
        if(Files.exists(tombstone,LinkOption.NOFOLLOW_LINKS)){verifyTombstone();throw new IOException("release済みjobは副作用を再開できません");}
    }
    boolean released() throws IOException {
        requireHeld();
        if(!Files.exists(tombstone,LinkOption.NOFOLLOW_LINKS))return false;verifyTombstone();return true;
    }
    void requireSettled() throws IOException {requireRecords(false);}
    private void requireRecords(boolean boundedStart) throws IOException {
        requireHeld();boolean pending=false;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries) {
                if(entry.equals(tombstone)){verifyTombstone();continue;}
                try {UUID.fromString(entry.getFileName().toString());}catch(IllegalArgumentException failure){throw new IOException("未知のoperation記録です",failure);}
                if(!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)||!entry.toRealPath().equals(entry))throw new IOException("operation記録の位置が一致しません");
                checkDirectory(entry);Operation operation=new Operation(entry,MacFiles.hash(entry.resolve("intent.json")));verify(operation);
                try(DirectoryStream<Path> files=Files.newDirectoryStream(entry)) {
                    for(Path file:files) {
                        if(file.getFileName().toString().equals("client-complete.pending")) {
                            java.nio.file.attribute.BasicFileAttributes attributes;try{attributes=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException published){continue;}
                            if(!attributes.isRegularFile()||attributes.size()>65)throw new IOException("native pendingのtype/sizeが不正です");
                            if(Files.exists(entry.resolve("client-complete"),LinkOption.NOFOLLOW_LINKS)&&Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("完了印と未知pendingが併存しています");
                        }else if(!Set.of("intent.json","settled.json","client-complete").contains(file.getFileName().toString()))throw new IOException("未知のoperation fileです");
                    }
                }
                try{clientComplete(operation);}catch(ClientPending missing){if(!boundedStart)throw missing;pending=true;}
                if(boundedStart&&MacFiles.json(entry.resolve("intent.json")).path("kind").asText().equals("start")&&!Files.exists(entry.resolve("settled.json"),LinkOption.NOFOLLOW_LINKS))continue;
                JsonNode outcome=MacFiles.json(entry.resolve("settled.json"));
                if(!outcome.path("intentSha256").asText().equals(operation.sha256())||!outcome.path("evidence").isObject()||outcome.path("evidence").isEmpty())throw new IOException("daemon操作終端が未確定です");
            }
        }
        if(pending)throw new ClientPending();
    }
    void markReleased() throws IOException {
        requireSettled();if(released())return;
        durable(tombstone,MacFiles.JSON.writeValueAsBytes(identity));
    }
    /** 元の完了印を補完せず、旧bootで確定していたcreateとbounded startだけを固定する。 */
    static ObjectNode rebootSnapshot(Path temporary,JsonNode lease,String generation) throws IOException {
        if(!temporary.toRealPath().equals(temporary))throw new IOException("旧journal親の位置が不明です");
        boolean prepared=false;Path directory=temporary.resolve("macos-operations");ObjectNode files=MacFiles.JSON.createObjectNode(),created=MacFiles.JSON.createObjectNode();
        ObjectNode identity=MacFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,lease.required(key));
        if(CacheState.absent(directory)&&lease.path("resources").isEmpty())return files;
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toRealPath().equals(directory))throw new IOException("旧journalの位置が不明です");
        List<Path> entries;try(var stream=Files.list(directory)){entries=stream.sorted().toList();}if(entries.size()>4096)throw new IOException("旧journalの件数を超えています");
        for(Path entry:entries) {
            if(entry.getFileName().toString().equals("released.json")){if(!MacFiles.json(entry).equals(identity))throw new IOException("旧tombstoneの所有が不明です");continue;}
            checkDirectory(entry);JsonNode intent=MacFiles.json(entry.resolve("intent.json"));validateIntent(intent,identity,entry.getFileName().toString());
            String digest=MacFiles.hash(entry.resolve("intent.json"));boolean start=intent.path("kind").asText().equals("start");
            try(var contents=Files.list(entry)) {
                for(Path file:contents.toList()) {
                    String name=file.getFileName().toString();if(!Set.of("intent.json","client-complete","client-complete.pending","settled.json").contains(name)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file))throw new IOException("旧operationに未知fileがあります");
                    if(name.equals("client-complete.pending")){if(Files.size(file)>65)throw new IOException("旧native pendingが大きすぎます");byte[] pending=Files.readAllBytes(file);String expected=digest+"\n";if(!start||pending.length>65||!expected.startsWith(new String(pending,StandardCharsets.US_ASCII))||Files.exists(entry.resolve("client-complete"),LinkOption.NOFOLLOW_LINKS))throw new IOException("旧native pendingが不正です");}
                    files.put(entry.getFileName()+"/"+name,MacFiles.hash(file));
                }
            }
            boolean complete=Files.exists(entry.resolve("client-complete"),LinkOption.NOFOLLOW_LINKS),settled=Files.exists(entry.resolve("settled.json"),LinkOption.NOFOLLOW_LINKS);
            if(complete&&!Files.readString(entry.resolve("client-complete"),StandardCharsets.US_ASCII).equals(digest+"\n"))throw new IOException("旧client完了印が不正です");
            if(!start&&(!complete||!settled)||start&&settled&&!complete)throw new IOException("旧prepare/create等の終端が未確定です");
            if(settled) {
                JsonNode outcome=MacFiles.json(entry.resolve("settled.json"));Set<String> outcomeFields=new HashSet<>();outcome.fieldNames().forEachRemaining(outcomeFields::add);if(!outcomeFields.equals(Set.of("intentSha256","evidence")))throw new IOException("旧daemon終端fieldが不正です");if(!outcome.path("intentSha256").asText().equals(digest)||!outcome.path("evidence").isObject()||outcome.path("evidence").isEmpty())throw new IOException("旧daemon終端証拠が不正です");
                if(intent.path("kind").asText().equals("prepare")&&outcome.path("evidence").path("phase").asText().equals("ready")&&outcome.path("evidence").path("generationOwner").asText().equals(generation)&&outcome.path("evidence").path("machineIdSha256").equals(lease.path("resources").path("macos").path("machineIdSha256")))prepared=true;
                if(intent.path("kind").asText().equals("create")) {
                    JsonNode actual=outcome.path("evidence"),labels=actual.path("Config").path("Labels");String id=actual.path("Id").asText();
                    if(!id.matches("[0-9a-f]{64}")||!actual.path("Name").asText().startsWith("/")||(generation!=null&&!labels.path("io.github.rs0325.mcicon.generation").asText().equals(generation)))throw new IOException("旧create所有が不明です");
                    try{String owner=labels.path("io.github.rs0325.mcicon.generation").asText();if(!UUID.fromString(owner).toString().equals(owner))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("旧createの世代形式が不正です");}
                    for(String key:List.of("jobId","ownerId","descriptorSha256"))if(!labels.path("io.github.rs0325.mcicon."+key).equals(identity.path(key)))throw new IOException("旧create identityが不正です");
                    created.put(id,actual.path("Name").asText().substring(1));
                }
            }
        }
        for(Path entry:entries) {
            if(entry.getFileName().toString().equals("released.json"))continue;JsonNode intent=MacFiles.json(entry.resolve("intent.json"));
            if(intent.path("kind").asText().equals("start")) {
                if(!created.path(intent.path("containerId").asText()).asText().equals(intent.path("containerName").asText()))throw new IOException("旧startのcreate/full ID所有が不明です");
                if(Files.exists(entry.resolve("settled.json"),LinkOption.NOFOLLOW_LINKS)) {JsonNode proof=MacFiles.json(entry.resolve("settled.json")).path("evidence");if(!proof.path("removedFullId").equals(intent.path("containerId"))||!proof.path("absenceVerified").asBoolean())throw new IOException("旧startの正常除去証拠が不正です");}
                else {boolean found=false;for(JsonNode container:lease.path("resources").path("macos").path("containers"))if(container.path("id").equals(intent.path("containerId"))&&container.path("name").equals(intent.path("containerName")))found=true;if(!found)throw new IOException("未完startの元resourcesがありません");}
            }
        }
        for(JsonNode container:lease.path("resources").path("macos").path("containers"))if(!created.path(container.path("id").asText()).asText().equals(container.path("name").asText()))throw new IOException("旧resourcesのcreate/full ID所有が不明です");
        if(!lease.path("resources").isEmpty()&&!prepared)throw new IOException("旧readyに結合する正常prepare証拠がありません");return files;
    }
    void markEndedByReboot(ObjectNode proof) throws IOException {
        requireHeld();if(!proof.path("identity").equals(identity))throw new IOException("reboot終了証拠のidentityが不正です");Path path=directory.getParent().resolve("macos-ended-by-reboot.json");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){if(!MacFiles.json(path).equals(proof))throw new IOException("reboot終了証拠が変化しました");}else durable(path,MacFiles.JSON.writeValueAsBytes(proof));
        if(!released())durable(tombstone,MacFiles.JSON.writeValueAsBytes(identity));
    }
    private void verify(Operation operation) throws IOException {
        requireHeld();
        if(!operation.directory().getParent().equals(directory)||!operation.directory().toRealPath().equals(operation.directory()))throw new IOException("operation所有位置が一致しません");
        JsonNode intent=MacFiles.json(operation.directory().resolve("intent.json"));validateIntent(intent,identity,operation.directory().getFileName().toString());
        if(!MacFiles.hash(operation.directory().resolve("intent.json")).equals(operation.sha256()))throw new IOException("operation所有記録が一致しません");
    }
    private static void validateIntent(JsonNode intent,JsonNode identity,String operationId) throws IOException {
        try{if(!UUID.fromString(operationId).toString().equals(operationId))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("operation IDが不正です");}
        Set<String> fields=new HashSet<>();intent.fieldNames().forEachRemaining(fields::add);boolean start=intent.path("kind").asText().equals("start");
        if(!fields.equals(start?Set.of("identity","operationId","kind","containerId","containerName"):Set.of("identity","operationId","kind"))||(start&&(!intent.path("containerId").asText().matches("[0-9a-f]{64}")||intent.path("containerName").asText().isEmpty()))||!Set.of("prepare","create","start","remove","stop").contains(intent.path("kind").asText())||!intent.path("identity").equals(identity)||!intent.path("operationId").asText().equals(operationId))throw new IOException("operation形式/所有が一致しません");
    }
    private void clientComplete(Operation operation) throws IOException {
        Path path=operation.completion();
        try{Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException missing){if(MacFiles.json(operation.directory().resolve("intent.json")).path("kind").asText().equals("start"))throw new ClientPending();throw missing;}
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=65||!Files.readString(path,StandardCharsets.US_ASCII).equals(operation.sha256()+"\n"))throw new IOException("client群の回収が未確定です");
    }
    private static final class ClientPending extends IOException {ClientPending(){super("bounded startのclient回収印はまだありません");}}
    private static void checkDirectory(Path entry) throws IOException {if(!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)||!entry.toRealPath().equals(entry)||!Files.isRegularFile(entry.resolve("intent.json"),LinkOption.NOFOLLOW_LINKS))throw new IOException("operation記録の位置/typeが不正です");}
    private void requireHeld() throws IOException {if(!lock.isValid())throw new IOException("job操作の排他がありません");}
    private void verifyTombstone() throws IOException {if(!MacFiles.json(tombstone).equals(identity))throw new IOException("release記録の所有者が一致しません");}
    static void durable(Path path,byte[] bytes) throws IOException {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("operation記録は上書きできません");
        Path staged=Files.createTempFile(path.getParent(),"record-",".tmp");
        try {
            try(FileChannel output=FileChannel.open(staged,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}
            Files.move(staged,path,StandardCopyOption.ATOMIC_MOVE);force(path.getParent());
        }finally {Files.deleteIfExists(staged);}
    }
    private static void force(Path directory) throws IOException {try(FileChannel channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}}
    public void close() throws IOException {try{lock.release();}finally{channel.close();}}
}
