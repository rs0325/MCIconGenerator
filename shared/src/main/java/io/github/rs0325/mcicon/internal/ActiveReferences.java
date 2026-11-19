package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.Closeable;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

/** 参加版の稼働候補。実leaseの所有・終端検証の代わりにはならない。 */
public final class ActiveReferences {
    public static final int MAXIMUM=ReferenceContract.MAXIMUM_ENTRIES,MAX_BYTES=65536;
    private static final String[] ROOT_FIELDS={"schemaVersion","kind","hostId","rootKey","bootSha256","epoch","revision","phase","macDiskSeals","repair","entries"};
    private static final String[] ENTRY_FIELDS={"jobId","ownerId","descriptorSha256","attempt","phase","jobKey","receiptKey","receiptSha256","admissionBootSha256","identity","generation"};
    private static final String[] IDENTITY_FIELDS={"schemaVersion","jobId","ownerId","toolVersion","workerSha256","runtimeId","descriptorSha256","hostId","pid","processStartedAt","createdAt","requirements"};
    private static final String[] SEAL_FIELDS={"descriptorSha256","generationOwner","hostId","rootKey","bootSha256","registrationKey","registrationSha256","diskKey","incarnationSha256","epoch","coveredRevision","historyProofSha256"};
    private static final String[] REPAIR_FIELDS={"schemaVersion","attempt","controller","epoch","bootSha256","expiresAt","operation","jobId","leaseSha256","receiptKey","receiptSha256","journalSha256","grantKey","grantSha256"};
    private final Path root,file;
    private final LeaseTrust trust;
    private final String rootKey,hostId;

    public static final class State {
        private final ObjectNode payload;
        private final byte[] bytes;
        private final String key;
        private State(ObjectNode payload,byte[] bytes,String key){this.payload=payload.deepCopy();this.bytes=bytes.clone();this.key=key;}
        public ObjectNode payload(){return payload.deepCopy();}
    }
    /** root所有とreaderを認証した呼出し元から使う。初期化/修復の全件検証は呼出し元が行う。 */
    public ActiveReferences(Path root,LeaseTrust trust,long end) throws IOException {
        this.root=root.toAbsolutePath().normalize();this.trust=trust;
        if(!this.root.toRealPath().equals(this.root))throw invalid("稼働参照rootにlinkがあります");
        check(end);rootKey=trust.fileIdentity(this.root,this.root,end);hostId=new String(readFile(this.root.resolve("host-id"),64),StandardCharsets.US_ASCII).trim();check(end);
        uuid(JSON.createObjectNode().put("hostId",hostId),"hostId");
        file=this.root.resolve("logs/active-references-v1/state.json");
    }
    /** 存在しない集合と破損は区別する。通常closeは両方で共有停止を拒否する。 */
    public State read(long end) throws IOException {
        check(end);parents();String before=trust.fileIdentity(file,root,end);byte[] bytes=readFile(file,MAX_BYTES);
        ObjectNode payload=decode(bytes);
        if(!before.equals(trust.fileIdentity(file,root,end)))throw invalid("稼働参照fileが読取中に変化しました");
        binding(payload,end);check(end);return new State(payload,bytes,before);
    }
    public ObjectNode initial(String boot,String epoch) throws IOException {
        ObjectNode value=JSON.createObjectNode().put("schemaVersion",1).put("kind","active-references").put("hostId",hostId).put("rootKey",rootKey).put("bootSha256",boot).put("epoch",epoch).put("revision",1).put("phase","ready");
        value.putArray("macDiskSeals");value.putNull("repair");value.putArray("entries");validate(value);return value;
    }
    /** 元root lockと期待snapshotを持つ更新だけを同一volumeのatomic renameで公開する。 */
    public State publish(State expected,ObjectNode value,RuntimeLock held,long end) throws IOException {
        trust.requireCaptureEnded();
        held.requireHeld(root.resolve("locks/generations.lock"));check(end);binding(value,end);
        if(expected!=null&&integer(expected.payload,"revision",1)==Long.MAX_VALUE)throw invalid("稼働参照revisionが上限です");
        long next=expected==null?1:integer(expected.payload,"revision",1)+1;
        if(integer(value,"revision",1)!=next)throw invalid("稼働参照revisionが連続していません");
        if(expected!=null&&!value.path("epoch").equals(expected.payload.path("epoch"))&&!expected.payload.path("entries").isEmpty())throw invalid("未終端参照があるepochを変更できません");
        if(expected!=null&&!value.path("bootSha256").equals(expected.payload.path("bootSha256"))&&!value.path("phase").asText().equals("recovering"))throw invalid("boot変更は元epochの回復が必要です");
        byte[] bytes=encode(value);
        Path logs=root.resolve("logs");Files.createDirectories(logs);
        if(!logs.toRealPath().equals(logs)||!Files.isDirectory(logs,LinkOption.NOFOLLOW_LINKS))throw invalid("稼働参照logsにlinkがあります");
        Files.createDirectories(file.getParent());parents();String parentKey=trust.fileIdentity(file.getParent(),root,end);
        Path stage=Files.createTempFile(file.getParent(),"state-",".part");
        try(var cleanup=(Closeable)()->Files.deleteIfExists(stage)) {
            try(FileChannel output=FileChannel.open(stage,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()){check(end);output.write(buffer);}output.force(true);}
            if(!parentKey.equals(trust.fileIdentity(file.getParent(),root,end)))throw invalid("稼働参照directoryが変化しました");
            if(expected==null) {
                try{Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);throw invalid("稼働参照が既に存在します");}catch(NoSuchFileException absent){}
            }else {
                State actual=read(end);
                if(!expected.key.equals(actual.key)||!Arrays.equals(expected.bytes,actual.bytes))throw invalid("稼働参照CASが不一致です");
            }
            check(end);held.requireHeld(root.resolve("locks/generations.lock"));
            Files.move(stage,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel output=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){output.force(true);}
            if(Files.getFileStore(file).supportsFileAttributeView("unix"))try(FileChannel directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}
            State actual=read(end);if(!Arrays.equals(bytes,actual.bytes))throw invalid("稼働参照公開後のbytesが一致しません");return actual;
        }
    }
    public static ObjectNode reserved(ObjectNode identity,JsonNode generation,String attempt,String boot) throws IOException {
        ObjectNode lease=identity.deepCopy().put("state","active");lease.putObject("resources");activeIdentity(lease);instant(identity,"processStartedAt");
        ObjectNode entry=JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))entry.set(key,identity.required(key));
        entry.put("attempt",attempt).put("phase","reserved").putNull("jobKey").putNull("receiptKey");entry.put("receiptSha256",digest(receipt(identity))).put("admissionBootSha256",boot);entry.set("identity",identity.deepCopy());entry.set("generation",ReferenceGeneration.canonical(generation));validateEntry(entry);return entry;
    }
    public static ObjectNode sidecar(JsonNode payload,JsonNode entry) throws IOException {
        ObjectNode value=JSON.createObjectNode().put("schemaVersion",1).put("kind","active-reference");
        for(String name:List.of("hostId","epoch"))value.set(name,payload.required(name));
        for(String name:List.of("attempt","jobId","ownerId","descriptorSha256","receiptSha256","admissionBootSha256"))value.set(name,entry.required(name));
        value.set("generation",ReferenceGeneration.canonical(entry.path("generation")));validateSidecar(value);return value;
    }
    /** 認証済みの全参照（予約・未除外terminalを含む）と新候補の両上限を判定する。 */
    public static void requireCapacity(ObjectNode value,JsonNode generation) throws IOException {
        validate(value);ReferenceGeneration.canonical(generation);
        if(!value.path("phase").asText().equals("ready"))throw invalid("回復中は新規予約できません");
        if(value.path("entries").size()>=MAXIMUM)throw invalid("稼働参照が件数上限です");
        Set<JsonNode> generations=new HashSet<>();generations.add(ReferenceGeneration.canonical(generation));
        for(JsonNode entry:value.path("entries"))generations.add(ReferenceGeneration.canonical(entry.path("generation")));
        if(generations.size()>ReferenceContract.MAXIMUM_GENERATIONS)throw invalid("稼働参照が世代数上限です");
    }
    /** 同じroot排他・期待snapshotに対して予約をCAS公開する。運用からはまだ呼ばない。 */
    public State reserve(State expected,ObjectNode identity,JsonNode generation,String attempt,String boot,RuntimeLock held,long end) throws IOException {
        ObjectNode value=expected.payload();requireCapacity(value,generation);
        value.withArray("entries").add(reserved(identity,generation,attempt,boot));
        value.put("revision",integer(value,"revision",1)+1);return publish(expected,value,held,end);
    }
    public static byte[] encodeSidecar(ObjectNode value) throws IOException {
        validateSidecar(value);ObjectNode ordered=ordered(value,"schemaVersion","kind","hostId","epoch","attempt","jobId","ownerId","descriptorSha256","receiptSha256","admissionBootSha256","generation");
        ordered.set("generation",ReferenceGeneration.canonical(value.path("generation")));return JSON.writeValueAsBytes(ordered);
    }
    public static ObjectNode decodeSidecar(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES)throw invalid("参照sidecarがsize上限を超えています");
        JsonNode value=LeaseSnapshot.read(bytes);validateSidecar(value);return (ObjectNode)value.deepCopy();
    }
    public static void verifySidecar(byte[] bytes,JsonNode state,JsonNode entry) throws IOException {
        if(!decodeSidecar(bytes).equals(sidecar(state,entry)))throw invalid("参照sidecarが元集合と一致しません");
    }
    private static void validateSidecar(JsonNode value) throws IOException {
        fields(value,"schemaVersion","kind","hostId","epoch","attempt","jobId","ownerId","descriptorSha256","receiptSha256","admissionBootSha256","generation");
        if(integer(value,"schemaVersion",1)!=1||!text(value,"kind",32).equals("active-reference"))throw invalid("参照sidecarのversionが不正です");
        for(String name:List.of("hostId","epoch","attempt","jobId","ownerId"))uuid(value,name);
        for(String name:List.of("descriptorSha256","receiptSha256","admissionBootSha256"))hash(value,name);
        ReferenceGeneration.canonical(value.path("generation"));
        if(!value.path("generation").path("descriptorSha256").equals(value.path("descriptorSha256")))throw invalid("参照sidecarの世代が不一致です");
    }
    public static byte[] encode(ObjectNode value) throws IOException {
        validate(value);ObjectNode ordered=ordered(value,ROOT_FIELDS);
        var entries=ordered.putArray("entries");List<JsonNode> sorted=new ArrayList<>();value.path("entries").forEach(sorted::add);sorted.sort(Comparator.comparing(v->v.path("jobId").asText()));
        for(JsonNode entry:sorted){ObjectNode node=ordered(entry,ENTRY_FIELDS);ObjectNode identity=ordered(entry.path("identity"),IDENTITY_FIELDS);identity.set("requirements",ordered(entry.path("identity").path("requirements"),"containerMemoryMiB","heapMemoryMiB","cpus","preparationDeadline"));node.set("identity",identity);node.set("generation",ReferenceGeneration.canonical(entry.path("generation")));entries.add(node);}
        var seals=ordered.putArray("macDiskSeals");sorted=new ArrayList<>();value.path("macDiskSeals").forEach(sorted::add);sorted.sort(Comparator.comparing(v->v.path("descriptorSha256").asText()+v.path("generationOwner").asText()));for(JsonNode seal:sorted)seals.add(ordered(seal,SEAL_FIELDS));
        if(!value.path("repair").isNull()){ObjectNode repair=ordered(value.path("repair"),REPAIR_FIELDS);repair.set("controller",ordered(value.path("repair").path("controller"),"pid","startedAt"));ordered.set("repair",repair);}
        ObjectNode envelope=JSON.createObjectNode();envelope.set("payload",ordered);envelope.put("payloadSha256",digest(JSON.writeValueAsBytes(ordered)));byte[] bytes=JSON.writeValueAsBytes(envelope);
        if(bytes.length>MAX_BYTES)throw invalid("稼働参照がsize上限を超えています");return bytes;
    }
    public static ObjectNode decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES)throw invalid("稼働参照がsize上限を超えています");
        JsonNode envelope=LeaseSnapshot.read(bytes);fields(envelope,"payload","payloadSha256");if(!envelope.path("payload").isObject())throw invalid("稼働参照payloadがobjectではありません");ObjectNode value=(ObjectNode)envelope.required("payload");validate(value);
        JsonNode canonical=LeaseSnapshot.read(encode(value));if(!hash(envelope,"payloadSha256").equals(canonical.path("payloadSha256").asText()))throw invalid("稼働参照のhashが一致しません");return value.deepCopy();
    }
    private static ObjectNode ordered(JsonNode value,String... names) {ObjectNode result=JSON.createObjectNode();for(String name:names)result.set(name,value.get(name));return result;}
    private static void validate(ObjectNode value) throws IOException {
        fields(value,ROOT_FIELDS);if(integer(value,"schemaVersion",1)!=1||!text(value,"kind",32).equals("active-references"))throw invalid("稼働参照versionが不正です");
        uuid(value,"hostId");text(value,"rootKey",512);hash(value,"bootSha256");uuid(value,"epoch");integer(value,"revision",1);
        String phase=text(value,"phase",16);if(!Set.of("ready","recovering").contains(phase)||phase.equals("ready")&&!value.path("repair").isNull())throw invalid("稼働参照の回復状態が不正です");
        if(!value.path("entries").isArray()||value.path("entries").size()>MAXIMUM||!value.path("macDiskSeals").isArray()||value.path("macDiskSeals").size()>MAXIMUM)throw invalid("稼働参照が件数上限を超えています");
        Set<String> jobs=new HashSet<>(),attempts=new HashSet<>(),generations=new HashSet<>();
        for(JsonNode entry:value.path("entries")){validateEntry(entry);if(!jobs.add(uuid(entry,"jobId"))||!attempts.add(uuid(entry,"attempt")))throw invalid("稼働参照が重複しています");if(!entry.path("identity").path("hostId").equals(value.path("hostId")))throw invalid("稼働参照のhostが一致しません");}
        for(JsonNode seal:value.path("macDiskSeals")) {
            fields(seal,SEAL_FIELDS);hash(seal,"descriptorSha256");uuid(seal,"generationOwner");uuid(seal,"hostId");text(seal,"rootKey",512);hash(seal,"bootSha256");text(seal,"registrationKey",512);hash(seal,"registrationSha256");text(seal,"diskKey",512);hash(seal,"incarnationSha256");uuid(seal,"epoch");hash(seal,"historyProofSha256");
            if(integer(seal,"coveredRevision",1)>integer(value,"revision",1)||!generations.add(seal.path("descriptorSha256").asText()+seal.path("generationOwner").asText()))throw invalid("disk sealの世代/revisionが不正です");
            for(String name:List.of("hostId","rootKey","bootSha256","epoch"))if(!seal.path(name).equals(value.path(name)))throw invalid("disk sealのroot結合が不一致です");
        }
        JsonNode repair=value.path("repair");
        if(!repair.isNull()) {
            fields(repair,REPAIR_FIELDS);
            if(integer(repair,"schemaVersion",1)!=1)throw invalid("回復grantのversionが不正です");uuid(repair,"attempt");fields(repair.path("controller"),"pid","startedAt");integer(repair.path("controller"),"pid",1);instant(repair.path("controller"),"startedAt");uuid(repair,"epoch");hash(repair,"bootSha256");instant(repair,"expiresAt");uuid(repair,"jobId");
            if(!Set.of("release","repair-disk-references").contains(text(repair,"operation",32)))throw invalid("回復grantの操作が不正です");
            for(String name:List.of("leaseSha256","receiptSha256","journalSha256","grantSha256"))hash(repair,name);for(String name:List.of("receiptKey","grantKey"))text(repair,name,512);
            if(!repair.path("epoch").equals(value.path("epoch"))||!repair.path("bootSha256").equals(value.path("bootSha256"))||!jobs.contains(repair.path("jobId").asText()))throw invalid("回復grantの対象が不一致です");
        }
    }
    private static void validateEntry(JsonNode entry) throws IOException {
        fields(entry,ENTRY_FIELDS);uuid(entry,"jobId");uuid(entry,"ownerId");hash(entry,"descriptorSha256");uuid(entry,"attempt");hash(entry,"admissionBootSha256");hash(entry,"receiptSha256");
        String phase=text(entry,"phase",16);if(!Set.of("reserved","admitted").contains(phase))throw invalid("稼働参照phaseが不正です");
        for(String key:List.of("jobKey","receiptKey")){if(phase.equals("reserved")){if(!entry.path(key).isNull())throw invalid("予約に実体keyがあります");}else text(entry,key,512);}
        ReferenceGeneration.canonical(entry.path("generation"));
        if(!entry.path("generation").path("descriptorSha256").equals(entry.path("descriptorSha256")))throw invalid("参照世代と元identityが不一致です");
        JsonNode raw=entry.path("identity");fields(raw,IDENTITY_FIELDS);ObjectNode identity=(ObjectNode)raw;
        ObjectNode lease=identity.deepCopy().put("state","active");lease.putObject("resources");activeIdentity(lease);instant(identity,"processStartedAt");
        for(String key:List.of("jobId","ownerId","descriptorSha256"))if(!entry.path(key).equals(identity.path(key)))throw invalid("予約identityが不一致です");
        if(!digest(receipt(identity)).equals(entry.path("receiptSha256").asText()))throw invalid("予定receiptが不一致です");
    }
    private void binding(JsonNode value,long end) throws IOException {if(!value.path("rootKey").asText().equals(rootKey)||!value.path("hostId").asText().equals(hostId)||!rootKey.equals(trust.fileIdentity(root,root,end)))throw invalid("稼働参照rootの所有が変化しました");}
    private void parents() throws IOException {for(Path directory:List.of(root.resolve("logs"),file.getParent()))if(!directory.toRealPath().equals(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw invalid("稼働参照directoryにlinkがあります");}
    private static void check(long end) throws IOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("稼働参照操作が中断されました");if(System.nanoTime()>=end)throw invalid("稼働参照操作の絶対期限を超えました");}
}
