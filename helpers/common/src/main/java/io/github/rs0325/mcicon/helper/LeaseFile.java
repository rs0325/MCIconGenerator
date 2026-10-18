package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.github.rs0325.mcicon.helper.HelperProtocol.*;

/** 所有記録を再確認しresourcesだけを更新する内部境界。OS資源を停止する権限は与えない。 */
public final class LeaseFile {
    public record Artifact(Path path,long bytes,String sha256) {}
    private final Path root,file;
    private final ObjectNode identity;
    private final JsonNode descriptor;
    private final String os;
    private LeaseFile(Path root,Path file,ObjectNode identity,JsonNode descriptor,String os) {this.root=root;this.file=file;this.identity=identity;this.descriptor=descriptor;this.os=os;}
    /** coreが公開したlease・receipt・元descriptor/inventoryと現在のhelper世代を照合する。 */
    public static LeaseFile open(HelperProtocol.Command command,Path executable) throws IOException {
        Path root=command.root();if(!root.toRealPath().equals(root))throw invalid("管理rootにlinkがあります");
        ObjectNode lease=(ObjectNode)HelperProtocol.read(readFile(command.lease(),1024*1024));ObjectNode identity=identity(lease);
        Path expected=root.resolve("jobs").resolve(text(identity,"jobId",36)).resolve("lease.json");
        if(!command.lease().equals(expected)||!expected.getParent().toRealPath().equals(expected.getParent()))throw invalid("job pathが一致しません");
        String host=new String(readFile(root.resolve("host-id"),64),StandardCharsets.US_ASCII).trim();if(!host.equals(text(identity,"hostId",36)))throw invalid("管理rootのhostIdが一致しません");
        checkReceipt(expected,identity);byte[] original=readFile(expected.resolveSibling("descriptor.json"),1024*1024);
        if(!digest(original).equals(hash(identity,"descriptorSha256")))throw invalid("descriptor元bytesが一致しません");JsonNode descriptor=HelperProtocol.read(original);
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
        if(!installed.equals(executable.toAbsolutePath().normalize())||!installed.toRealPath().equals(installed)||!Files.isRegularFile(installed,LinkOption.NOFOLLOW_LINKS))throw invalid("起動helperの世代が一致しません");
        ResourceRecords.validate(lease.get("resources"),os);return new LeaseFile(root,expected,identity,descriptor,os);
    }
    /** open時に元bytesのhashを照合したdescriptorの独立snapshotを返す。変更は内部状態へ反映しない。 */
    public JsonNode descriptor() {return descriptor.deepCopy();}
    /** 毎回immutable identityとreceiptを再照合する。呼出し元が返却nodeを変更しても内部identityは変わらない。 */
    public ObjectNode read() throws IOException {
        ObjectNode value=(ObjectNode)HelperProtocol.read(readFile(file,1024*1024));if(!identity(value).equals(identity))throw invalid("lease immutable identityが変化しました");checkReceipt(file,identity);ResourceRecords.validate(value.get("resources"),os);return value;
    }
    /** OS I/Oの前後で呼ぶ短いCAS更新。state/identityは引数に取らず、更新lockをOS I/O中は保持しない。 */
    public void replaceResources(ObjectNode expected,ObjectNode replacement) throws IOException {
        checkInterrupted();ResourceRecords.validate(replacement,os);Path temp=file.getParent().resolve("tmp");if(!temp.toRealPath().equals(temp))throw invalid("job tmpにlinkがあります");Path lockPath=temp.resolve("helper-lease.lock");
        if(Files.isSymbolicLink(lockPath))throw invalid("helper更新lockにlinkがあります");
        try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);FileLock lock=null;
            while(lock==null) {
                checkInterrupted();try {lock=channel.tryLock(0,1,false);}catch(OverlappingFileLockException busy){}
                if(lock!=null)break;checkInterrupted();if(System.nanoTime()>=deadline)throw invalid("helper更新lockの期限を超えました");
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            try {
                checkInterrupted();ObjectNode current=read();if(!current.get("resources").equals(expected))throw invalid("resourcesが別helperに更新されました");current.set("resources",replacement.deepCopy());byte[] bytes=JSON.writeValueAsBytes(current);if(bytes.length>1024*1024)throw invalid("leaseが上限を超えています");
                Path staged=Files.createTempFile(temp,"lease-update-",".json");
                try {try(FileChannel output=FileChannel.open(staged,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}checkInterrupted();Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                finally {Files.deleteIfExists(staged);}
            }finally {lock.release();}
        }
    }
    /** prepare時の既存artifactだけを検証する。preflightでは呼ばず、取得や展開を行わない。 */
    public Artifact verifiedArtifact(String role) throws IOException {
        read();JsonNode selected=null;for(JsonNode artifact:descriptor.get("artifacts"))if(text(artifact,"role",32).equals(role))selected=artifact;
        if(selected==null||role.equals("host-helper"))throw invalid("guest/image artifactがありません");
        Path path=root.resolve("runtimes").resolve(text(identity,"runtimeId",96)).resolve(hash(identity,"descriptorSha256")).resolve(text(selected,"id",96)+"."+text(selected,"format",16));
        if(!path.toRealPath().equals(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=integer(selected,"bytes",1))throw invalid("artifact世代/sizeが一致しません");
        MessageDigest digest=sha256();try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("artifact検証が中断されました");digest.update(buffer,0,count);}}
        String actual=HexFormat.of().formatHex(digest.digest());if(!actual.equals(hash(selected,"sha256")))throw invalid("artifact hashが一致しません");return new Artifact(path,integer(selected,"bytes",1),actual);
    }
    /** coreと同じ全階層key sort・整数の10進表現・空白無しUTF-8からreceiptを求める。 */
    public static byte[] receipt(ObjectNode identity) throws IOException {return digest(JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(JSON.convertValue(identity,Map.class))).getBytes(StandardCharsets.US_ASCII);}
    private static ObjectNode identity(ObjectNode lease) throws IOException {
        fields(lease,"schemaVersion","jobId","ownerId","toolVersion","workerSha256","runtimeId","descriptorSha256","hostId","pid","processStartedAt","createdAt","requirements","state","resources");
        if(integer(lease,"schemaVersion",1)!=1||!text(lease,"state",16).equals("active")||uuid(lease,"jobId").equals(uuid(lease,"ownerId")))throw invalid("lease状態/所有者が不正です");uuid(lease,"hostId");integer(lease,"pid",1);
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
    private static void checkInterrupted() throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("helper更新が中断されました");}
    private static byte[] readFile(Path file,int limit) throws IOException {if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw invalid("所有fileを読み取れません: "+file.getFileName());try(InputStream input=Files.newInputStream(file)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw invalid("所有fileが上限を超えています");return bytes;}}
    private static String digest(byte[] bytes) {return HexFormat.of().formatHex(sha256().digest(bytes));}
    private static MessageDigest sha256() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new AssertionError(failure);}}
}
