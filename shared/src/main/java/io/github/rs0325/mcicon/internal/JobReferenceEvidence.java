package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

/** jobの追加証拠を元root排他下でCAS公開する。既存lease/receipt/journalへは書き込まない。 */
public final class JobReferenceEvidence {
    public enum Kind { SIDECAR, RETENTION }
    public static final class Snapshot {
        private final byte[] bytes;
        private final String key;
        private Snapshot(byte[] bytes,String key){this.bytes=bytes.clone();this.key=key;}
        public byte[] bytes(){return bytes.clone();}
    }
    private final Path root,job,file;
    private final LeaseTrust trust;
    private final Kind kind;
    public JobReferenceEvidence(Path root,String jobId,LeaseTrust trust,Kind kind) throws IOException {
        uuid(JSON.createObjectNode().put("jobId",jobId),"jobId");
        this.root=root.toAbsolutePath().normalize();this.trust=trust;this.kind=kind;
        job=this.root.resolve("jobs").resolve(jobId);
        file=job.resolve("tmp/"+(kind==Kind.SIDECAR?"active-reference-v1.json":"runtime-retention-v1.json"));
    }
    public Snapshot read(long end) throws IOException {
        WindowsFileIdentities.check(end);parents();String key=trust.fileIdentity(file,root,end);
        byte[] bytes=readFile(file,65536);
        if(!decode(bytes).path("jobId").asText().equals(job.getFileName().toString()))throw invalid("job参照証拠の保存先が不一致です");
        if(!key.equals(trust.fileIdentity(file,root,end)))throw invalid("job参照証拠が読取中に置換されました");
        WindowsFileIdentities.check(end);return new Snapshot(bytes,key);
    }
    private ObjectNode decode(byte[] bytes) throws IOException {return kind==Kind.SIDECAR?ActiveReferences.decodeSidecar(bytes):RetentionEvidence.decode(bytes);}
    /** 期待する元bytesとidentityを照合し、force/atomic rename/readbackを同一絶対期限に含める。 */
    public Snapshot publish(Snapshot expected,ObjectNode value,RuntimeLock held,long end) throws IOException {
        trust.requireCaptureEnded();held.requireHeld(root.resolve("locks/generations.lock"));WindowsFileIdentities.check(end);
        byte[] bytes=kind==Kind.SIDECAR?ActiveReferences.encodeSidecar(value):RetentionEvidence.encode(value);
        if(!value.path("jobId").asText().equals(job.getFileName().toString()))throw invalid("job参照証拠の保存先が不一致です");
        parents();String parentKey=trust.fileIdentity(file.getParent(),root,end);Path stage=Files.createTempFile(file.getParent(),"reference-",".part");
        try(var cleanup=(Closeable)()->Files.deleteIfExists(stage)) {
            try(FileChannel channel=FileChannel.open(stage,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()){WindowsFileIdentities.check(end);channel.write(buffer);}channel.force(true);
            }
            if(!parentKey.equals(trust.fileIdentity(file.getParent(),root,end)))throw invalid("job参照証拠の親が置換されました");
            if(expected==null) {
                try{Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);throw invalid("job参照証拠が既に存在します");}catch(NoSuchFileException absent){}
            }else {
                Snapshot actual=read(end);if(!expected.key.equals(actual.key)||!Arrays.equals(expected.bytes,actual.bytes))throw invalid("job参照証拠のCASが不一致です");
            }
            held.requireHeld(root.resolve("locks/generations.lock"));WindowsFileIdentities.check(end);
            Files.move(stage,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
            if(Files.getFileStore(file).supportsFileAttributeView("unix"))try(FileChannel directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}
            Snapshot actual=read(end);if(!Arrays.equals(bytes,actual.bytes))throw invalid("job参照証拠のreadbackが不一致です");return actual;
        }
    }
    private void parents() throws IOException {
        for(Path path:new Path[]{root,root.resolve("jobs"),job,file.getParent()})if(!path.toRealPath().equals(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw invalid("job参照証拠の親にlinkがあります");
    }
}
