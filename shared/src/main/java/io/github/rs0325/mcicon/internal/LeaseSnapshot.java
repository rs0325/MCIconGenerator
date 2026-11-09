package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.time.Instant;

/** 元descriptorの信頼と所有記録を照合する読取り専用snapshot。OS操作権限は持たない。 */
public final class LeaseSnapshot {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path root,file,helperRoot;
    private final LeaseTrust trust;
    private final ObjectNode lease;
    private final JsonNode descriptor,inventory;
    private final Map<Path,byte[]> bytes=new LinkedHashMap<>();
    private final Map<Path,Object> keys=new LinkedHashMap<>();
    private LeaseSnapshot(Path root,Path file,ObjectNode lease,JsonNode descriptor,JsonNode helperArtifact,LeaseTrust trust,byte[] leaseBytes) throws IOException {
        this.trust=trust;this.root=root;this.file=file;this.lease=lease.deepCopy();this.descriptor=descriptor.deepCopy();
        helperRoot=root.resolve("helpers").resolve(text(descriptor.get("host"),"os",16)+"-"+text(descriptor.get("host"),"arch",16)).resolve(hash(helperArtifact,"sha256"));
        byte[] inventoryBytes=readFile(file.getParent().resolve(relative(helperArtifact,"inventoryPath")),4*1024*1024);if(!digest(inventoryBytes).equals(hash(helperArtifact,"inventorySha256")))throw invalid("snapshotのinventoryが変化しました");inventory=read(inventoryBytes);
        capture(root.resolve("host-id"));capture(file);capture(file.resolveSibling("lease.lock"));capture(file.resolveSibling("descriptor.json"));
        for(JsonNode artifact:descriptor.get("artifacts"))capture(file.getParent().resolve(relative(artifact,"inventoryPath")));
        Path signature=LeaseTrust.evidence(root,hash(lease,"descriptorSha256"));if(!LeaseTrust.legacy(hash(lease,"descriptorSha256")))capture(signature);
        if(!Arrays.equals(leaseBytes,bytes.get(file))||!Arrays.equals(receipt(identity(lease)),bytes.get(file.resolveSibling("lease.lock")))||!digest(bytes.get(file.resolveSibling("descriptor.json"))).equals(hash(lease,"descriptorSha256")))throw invalid("検証中にlease/receipt/descriptorが変化しました");
        if(!new String(bytes.get(root.resolve("host-id")),StandardCharsets.US_ASCII).trim().equals(text(lease,"hostId",36)))throw invalid("検証中にhostIdが変化しました");
        for(JsonNode artifact:descriptor.get("artifacts"))if(!digest(bytes.get(file.getParent().resolve(relative(artifact,"inventoryPath")))).equals(hash(artifact,"inventorySha256")))throw invalid("検証中にinventoryが変化しました");
        helperExecutable();verifyUnchanged();
    }
    /** 全fileを検証した独立snapshotを返す。信頼入力はpackage由来に限定する。 */
    public static LeaseSnapshot read(Path root,Path file,LeaseTrust trust) throws IOException {
        if(!root.isAbsolute()||!file.isAbsolute())throw invalid("snapshotには絶対pathが必要です");if(!root.toRealPath().equals(root))throw invalid("管理rootにlinkがあります");
        byte[] leaseBytes=readFile(file,1024*1024);ObjectNode lease=(ObjectNode)read(leaseBytes);ObjectNode identity=identity(lease);
        Path expected=root.resolve("jobs").resolve(text(identity,"jobId",36)).resolve("lease.json");
        if(!file.equals(expected)||!expected.getParent().toRealPath().equals(expected.getParent()))throw invalid("job pathが一致しません");
        String host=new String(readFile(root.resolve("host-id"),64),StandardCharsets.US_ASCII).trim();if(!host.equals(text(identity,"hostId",36)))throw invalid("管理rootのhostIdが一致しません");
        checkReceipt(expected,identity);byte[] original=readFile(expected.resolveSibling("descriptor.json"),1024*1024);
        if(!digest(original).equals(hash(identity,"descriptorSha256")))throw invalid("descriptor元bytesが一致しません");JsonNode descriptor=read(original);trust.verify(root,original);
        fields(descriptor,"schemaVersion","runtimeId","toolVersion","host","minimumOsVersion","helper","worker","rendering","artifacts");
        if(integer(descriptor,"schemaVersion",1)!=1||!text(descriptor,"runtimeId",96).equals(text(identity,"runtimeId",96))||!text(descriptor,"toolVersion",256).equals(text(identity,"toolVersion",256)))throw invalid("descriptor世代が一致しません");
        fields(descriptor.get("host"),"os","arch");String os=text(descriptor.get("host"),"os",16),arch=text(descriptor.get("host"),"arch",16);
        if(!Set.of("macos","windows","linux").contains(os)||!Set.of("aarch64","x86_64").contains(arch))throw invalid("descriptor hostが不正です");
        JsonNode worker=descriptor.get("worker");fields(worker,"toolVersion","agentSha256","protocolVersion","adapterSetSha256");
        if(integer(worker,"protocolVersion",1)!=1||!text(worker,"toolVersion",256).equals(text(identity,"toolVersion",256))||!hash(worker,"agentSha256").equals(hash(identity,"workerSha256")))throw invalid("worker世代が一致しません");
        JsonNode helper=descriptor.get("helper");fields(helper,"protocolVersion","artifactId","executable");if(integer(helper,"protocolVersion",1)!=1)throw invalid("helper protocolが一致しません");
        JsonNode artifacts=descriptor.get("artifacts");if(!artifacts.isArray()||artifacts.size()<2||artifacts.size()>3)throw invalid("artifact表が不正です");
        JsonNode helperArtifact=null;Set<String> ids=new HashSet<>(),roles=new HashSet<>();Set<Path> inventories=new HashSet<>();
        for(JsonNode artifact:artifacts) {
            fields(artifact,"id","role","url","bytes","sha256","unpackedBytes","format","inventoryPath","inventorySha256","licenseFiles","noticeFiles","sourceArchives");
            String id=text(artifact,"id",96),role=text(artifact,"role",32);if(!id.matches("[a-z0-9][a-z0-9._-]{0,95}")||!ids.add(id)||!roles.add(role)||!Set.of("host-helper","guest-base","renderer-image").contains(role))throw invalid("artifact参照が不正です");
            hash(artifact,"sha256");integer(artifact,"bytes",1);String format=text(artifact,"format",16);if(!Set.of("zip","tar.gz","tar.xz","qcow2").contains(format))throw invalid("artifact形式が不正です");
            String inventory=relative(artifact,"inventoryPath"),first=inventory.split("/")[0];if(Set.of("lease.json","lease.lock","descriptor.json","tmp").contains(first)||first.startsWith("helper-"))throw invalid("inventoryが所有記録と衝突しています");
            Path path=expected.getParent().resolve(inventory);if(!path.toRealPath().equals(path))throw invalid("inventoryにlinkがあります");
            for(Path existing:inventories)if(path.startsWith(existing)||existing.startsWith(path))throw invalid("inventory pathが衝突しています");inventories.add(path);
            if(!digest(readFile(path,4*1024*1024)).equals(hash(artifact,"inventorySha256")))throw invalid("inventory元bytesが一致しません");
            if(id.equals(text(helper,"artifactId",96))&&role.equals("host-helper"))helperArtifact=artifact;
        }
        if(helperArtifact==null||!roles.contains("renderer-image"))throw invalid("helper/image参照がありません");
        Path installed=root.resolve("helpers").resolve(os+"-"+arch).resolve(hash(helperArtifact,"sha256")).resolve(relative(helper,"executable"));

        LeaseResources.validate(lease.get("resources"),os);
        if(text(lease,"state",16).equals("released")&&!lease.get("resources").isEmpty())throw invalid("released資源が残っています");
        return new LeaseSnapshot(root,expected,lease,descriptor,helperArtifact,trust,leaseBytes);
    }
    public ObjectNode lease(){return lease.deepCopy();}
    public JsonNode descriptor(){return descriptor.deepCopy();}
    public Path file(){return file;}
    LeaseTrust trust(){return trust;}
    public String receiptKey(){return keys.get(file.resolveSibling("lease.lock")).toString();}
    public Path helperExecutable() throws IOException {
        Path executable=helperFile(relative(descriptor.get("helper"),"executable"));
        for(JsonNode row:inventory.path("contents").path("files"))if(row.path("path").asText().endsWith(".jar"))helperFile(row.path("path").asText());
        return executable;
    }
    /** 元inventoryのarchive相対pathでpinを指定する。 */
    public Path helperFile(String relative) throws IOException {
        if(!relative.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")||Arrays.stream(relative.split("/")).anyMatch(x->x.equals(".")||x.equals("..")))throw invalid("helper相対pathが不正です");
        JsonNode files=inventory.path("contents").path("files");if(!files.isArray())throw invalid("helper inventoryのfile表がありません");
        Set<String> names=new HashSet<>();JsonNode selected=null;
        for(JsonNode row:files){String name=text(row,"path",4096);if(!names.add(name))throw invalid("helper inventoryのpathが重複しています");if(name.equals(relative))selected=row;}
        if(selected==null)throw invalid("helper inventoryにpinがありません");
        Path path=helperRoot.resolve(relative);if(!path.toRealPath().equals(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=integer(selected,"bytes",0))throw invalid("helper実体のpath/sizeが一致しません");
        MessageDigest digest=sha256();
        try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("helper検証を中断しました");digest.update(buffer,0,count);}}
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash(selected,"sha256")))throw invalid("helper実体のhashが一致しません");
        return path;
    }
    public void verifyUnchanged() throws IOException {
        for(var entry:bytes.entrySet())if(!Objects.equals(keys.get(entry.getKey()),key(entry.getKey()))||!Arrays.equals(entry.getValue(),readFile(entry.getKey(),4*1024*1024)))throw invalid("snapshotのfile identity/bytesが変化しました");
    }
    private void capture(Path path) throws IOException {if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(root,LinkOption.NOFOLLOW_LINKS)))throw invalid("snapshot fileのOS所有者が一致しません");Object before=key(path);byte[] original=readFile(path,4*1024*1024);if(!before.equals(key(path)))throw invalid("snapshot保存中にfileが置換されました");keys.put(path,before);bytes.put(path,original);}
    private Object key(Path path) throws IOException {if(!path.toRealPath().equals(path))throw invalid("snapshotにlinkがあります");return trust.identity(path);}
    public static byte[] receipt(ObjectNode identity) throws IOException {return digest(JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(JSON.convertValue(identity,Map.class))).getBytes(StandardCharsets.US_ASCII);}
    static byte[] readFile(Path file,int limit) throws IOException {
        if(!file.toRealPath().equals(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw invalid("所有fileを読み取れません: "+file.getFileName());
        try(InputStream input=Files.newInputStream(file)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw invalid("所有fileが上限を超えています");return bytes;}
    }
    public static ObjectNode activeIdentity(ObjectNode lease) throws IOException {if(!text(lease,"state",16).equals("active"))throw invalid("操作leaseがactiveではありません");return identity(lease);}
    public static void verifyReceipt(Path lease,ObjectNode identity) throws IOException {checkReceipt(lease,identity);}
    private static ObjectNode identity(ObjectNode lease) throws IOException {
        fields(lease,"schemaVersion","jobId","ownerId","toolVersion","workerSha256","runtimeId","descriptorSha256","hostId","pid","processStartedAt","createdAt","requirements","state","resources");
        if(integer(lease,"schemaVersion",1)!=1||!Set.of("active","released").contains(text(lease,"state",16))||uuid(lease,"jobId").equals(uuid(lease,"ownerId")))throw invalid("lease状態/所有者が不正です");uuid(lease,"hostId");integer(lease,"pid",1);
        if(!lease.get("processStartedAt").isNull())instant(lease,"processStartedAt");instant(lease,"createdAt");hash(lease,"workerSha256");hash(lease,"descriptorSha256");
        if(!text(lease,"runtimeId",96).matches("[a-z0-9][a-z0-9._-]{0,95}")||!text(lease,"toolVersion",256).matches("[0-9]+[.][0-9]+[.][0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"))throw invalid("lease版が不正です");
        JsonNode requirements=lease.get("requirements");fields(requirements,"containerMemoryMiB","heapMemoryMiB","cpus","preparationDeadline");long memory=integer(requirements,"containerMemoryMiB",1),heap=integer(requirements,"heapMemoryMiB",1);if(memory>Integer.MAX_VALUE||heap>=memory||integer(requirements,"cpus",1)!=4)throw invalid("worker予算が不正です");instant(requirements,"preparationDeadline");
        ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));return identity;
    }
    private static void checkReceipt(Path lease,ObjectNode identity) throws IOException {
        Path file=lease.resolveSibling("lease.lock");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw invalid("receiptがありません");
        try(FileChannel input=FileChannel.open(file,StandardOpenOption.READ)) {
            if(input.size()!=64)throw invalid("receiptは厳密64bytesが必要です");ByteBuffer saved=ByteBuffer.allocate(64);while(saved.hasRemaining())if(input.read(saved)<0)throw invalid("receiptが不足しています");if(!Arrays.equals(saved.array(),receipt(identity)))throw invalid("receiptが一致しません");
        }
    }
    static JsonNode read(byte[] bytes) throws IOException {
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        JsonNode node=JSON.readTree(text);if(node==null||!node.isObject())throw invalid("JSON objectが必要です");return node;
    }
    static void fields(JsonNode node,Set<String> required,Set<String> allowed) throws IOException {
        if(node==null||!node.isObject())throw invalid("objectが必要です");var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);
        if(!names.containsAll(required)||!allowed.containsAll(names))throw invalid("必須fieldまたは未知fieldが不正です");
    }
    static void fields(JsonNode node,String... names) throws IOException {fields(node,Set.of(names),Set.of(names));}
    static String text(JsonNode node,String name,int limit) throws IOException {JsonNode value=node.get(name);if(value==null||!value.isTextual()||value.textValue().isEmpty()||value.textValue().length()>limit)throw invalid("文字列が不正です: "+name);return value.textValue();}
    static long integer(JsonNode node,String name,long minimum) throws IOException {JsonNode value=node.get(name);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<minimum)throw invalid("整数が不正です: "+name);return value.longValue();}
    static String hash(JsonNode node,String name) throws IOException {String value=text(node,name,64);if(!value.matches("[0-9a-f]{64}"))throw invalid("SHA-256が不正です: "+name);return value;}
    static String uuid(JsonNode node,String name) throws IOException {String value=text(node,name,36);try {if(!UUID.fromString(value).toString().equals(value))throw invalid("UUIDが不正です");}catch(IllegalArgumentException failure){throw invalid("UUIDが不正です: "+name);}return value;}
    static void instant(JsonNode node,String name) throws IOException {String value=text(node,name,64);try {if(!value.endsWith("Z"))throw invalid("UTC時刻が必要です");Instant.parse(value);}catch(java.time.DateTimeException failure){throw invalid("時刻が不正です: "+name);}}
    static String relative(JsonNode node,String name) throws IOException {String value=text(node,name,512);if(!value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")||Arrays.stream(value.split("/")).anyMatch(part->part.equals(".")||part.equals("..")))throw invalid("相対pathが不正です: "+name);return value;}
    static IOException invalid(String message) {return new IOException(message);}
    static String digest(byte[] bytes) {return HexFormat.of().formatHex(sha256().digest(bytes));}
    static String fileDigest(Path path) throws IOException {
        if(!path.toRealPath().equals(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw invalid("実体pinにlinkがあります");
        MessageDigest digest=sha256();try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest sha256() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new AssertionError(failure);}}
}
