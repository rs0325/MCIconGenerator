package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.*;
import io.github.rs0325.mcicon.internal.LeaseSnapshot;
import io.github.rs0325.mcicon.internal.LeaseTrust;
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
        return open(command,executable,trust(command,executable));
    }
    /** 自jobの署名descriptorを元にpackageの固定信頼入力を構成する。 */
    public static LeaseTrust trust(HelperProtocol.Command command,Path executable) throws IOException {
        JsonNode descriptor=HelperProtocol.read(readFile(command.lease().resolveSibling("descriptor.json"),1024*1024));
        LeaseTrust trust=LeaseTrust.packaged(executable,text(descriptor.get("host"),"os",16));
        JsonNode helper=null;for(JsonNode artifact:descriptor.path("artifacts"))if(artifact.path("id").equals(descriptor.path("helper").path("artifactId")))helper=artifact;
        if(helper==null)throw invalid("helper inventoryがありません");
        trust.bindReader(command.root(),readFile(command.lease().resolveSibling("descriptor.json"),1024*1024),readFile(command.lease().getParent().resolve(relative(helper,"inventoryPath")),4*1024*1024),executable);
        return trust;
    }
    static LeaseFile open(HelperProtocol.Command command,Path executable,LeaseTrust trust) throws IOException {
        LeaseSnapshot snapshot=inspect(command.root(),command.lease(),trust);ObjectNode value=snapshot.lease();
        if(!text(value,"state",16).equals("active")||!snapshot.helperExecutable().equals(executable.toAbsolutePath().normalize()))throw invalid("起動helperの世代が一致しません");
        return new LeaseFile(command.root(),command.lease(),identity(value),snapshot.descriptor(),text(snapshot.descriptor().get("host"),"os",16));
    }
    /** 異世代を参照する入口。返却型はresources更新やOS操作を提供しない。 */
    public static LeaseSnapshot inspect(Path root,Path file,LeaseTrust trust) throws IOException {return LeaseSnapshot.read(root,file,trust);}
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
    public static byte[] receipt(ObjectNode identity) throws IOException {return LeaseSnapshot.receipt(identity);}
    private static ObjectNode identity(ObjectNode lease) throws IOException {return LeaseSnapshot.activeIdentity(lease);}
    private static void checkReceipt(Path lease,ObjectNode identity) throws IOException {LeaseSnapshot.verifyReceipt(lease,identity);}
    private static void checkInterrupted() throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("helper更新が中断されました");}
    private static byte[] readFile(Path file,int limit) throws IOException {if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw invalid("所有fileを読み取れません: "+file.getFileName());try(InputStream input=Files.newInputStream(file)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw invalid("所有fileが上限を超えています");return bytes;}}
    private static String digest(byte[] bytes) {return HexFormat.of().formatHex(sha256().digest(bytes));}
    private static MessageDigest sha256() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new AssertionError(failure);}}
}
