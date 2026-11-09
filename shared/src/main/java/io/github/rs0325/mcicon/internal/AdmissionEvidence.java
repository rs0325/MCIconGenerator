package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

/** 未着手/未公開のadmission証拠。診断codeだけでは延期を許可しない。 */
public final class AdmissionEvidence {
    private AdmissionEvidence() {}
    public static final class Busy extends IOException {
        private final JsonNode job;
        public Busy(JsonNode job){super("既知live jobと容量不足のため候補を延期します");this.job=job.deepCopy();}
        public JsonNode job(){return job.deepCopy();}
    }
    public static void capacity(Path root,LeaseSnapshot candidate,List<LeaseSnapshot> references,JsonNode budget) throws IOException {
        if(references.isEmpty())throw invalid("他live jobに起因する容量不足ではありません");
        validateBudget(candidate.lease(),references.stream().map(LeaseSnapshot::lease).map(v->(JsonNode)v).toList(),budget);
        RuntimeMaintenanceVerifier.unstarted(candidate);
        ObjectNode value=JSON.createObjectNode().put("schemaVersion",1).put("kind","job-admission").put("cause","CAPACITY");value.set("lease",candidate.lease());value.put("receiptKey",candidate.receiptKey()).put("receiptSha256",digest(readFile(candidate.file().resolveSibling("lease.lock"),64)));
        value.put("journalSha256",journal(candidate.lease()));value.set("budget",budget);var proofs=value.putArray("references");
        for(LeaseSnapshot reference:references){RuntimeMaintenanceVerifier.requireLive(reference);proofs.add(RuntimeMaintenanceVerifier.proof(reference));}
        Path file=candidate.file().getParent().resolve("tmp/admission-v1.json");candidate.verifyUnchanged();save(file,value,candidate.trust());
        throw new Busy(jobReference(root,candidate.lease().path("jobId").asText()));
    }
    private static String journal(JsonNode lease) throws IOException {
        ObjectNode identity=((ObjectNode)lease).deepCopy();identity.remove(List.of("state","resources"));return digest(JSON.writeValueAsBytes(identity));
    }
    public static ObjectNode jobReference(Path root,String job) throws IOException {
        Path directory=root.resolve("jobs/"+job);JsonNode lease=read(readFile(directory.resolve("lease.json"),1024*1024));
        return JSON.createObjectNode().put("jobId",uuid(lease,"jobId")).put("ownerId",uuid(lease,"ownerId")).put("descriptorSha256",hash(lease,"descriptorSha256")).put("receiptSha256",digest(readFile(directory.resolve("lease.lock"),64))).put("evidenceSha256",digest(readFile(directory.resolve("tmp/admission-v1.json"),1024*1024)));
    }
    public static JsonNode verifyJob(Path root,JsonNode reference,String descriptor,long pid,String started,LeaseTrust trust,boolean released) throws IOException {
        fields(reference,"jobId","ownerId","descriptorSha256","receiptSha256","evidenceSha256");
        LeaseSnapshot snapshot=LeaseSnapshot.read(root,root.resolve("jobs/"+uuid(reference,"jobId")+"/lease.json"),trust);JsonNode lease=snapshot.lease();
        if(!uuid(lease,"ownerId").equals(uuid(reference,"ownerId"))||!hash(lease,"descriptorSha256").equals(descriptor)||!hash(reference,"descriptorSha256").equals(descriptor)||integer(lease,"pid",1)!=pid||!text(lease,"processStartedAt",64).equals(started)||!digest(readFile(snapshot.file().resolveSibling("lease.lock"),64)).equals(hash(reference,"receiptSha256")))throw invalid("容量延期jobの試行が一致しません");
        byte[] bytes=readFile(snapshot.file().getParent().resolve("tmp/admission-v1.json"),1024*1024);if(!digest(bytes).equals(hash(reference,"evidenceSha256")))throw invalid("容量延期証拠が変化しました");
        JsonNode value=read(bytes);fields(value,"schemaVersion","kind","cause","evidenceKey","lease","receiptKey","receiptSha256","journalSha256","budget","references");
        ObjectNode prior=((ObjectNode)value.path("lease")).deepCopy(),current=snapshot.lease();prior.remove(List.of("state","resources"));current.remove(List.of("state","resources"));
        if(integer(value,"schemaVersion",1)!=1||!text(value,"kind",32).equals("job-admission")||!text(value,"cause",32).equals("CAPACITY")||!prior.equals(current)||!snapshot.receiptKey().equals(text(value,"receiptKey",4096))||!hash(value,"receiptSha256").equals(hash(reference,"receiptSha256"))||!journal(lease).equals(hash(value,"journalSha256")))throw invalid("容量延期のimmutable/未着手証拠が不一致です");
        if(!trust.identity(snapshot.file().getParent().resolve("tmp/admission-v1.json")).toString().equals(text(value,"evidenceKey",4096)))throw invalid("容量延期証拠のfile identityが変化しました");
        JsonNode budget=value.path("budget");fields(budget,"domain","constraint","hostMiB","requiredMiB","availableMiB","generations");
        if(!budget.path("generations").isArray()||!text(budget,"domain",32).equals("macos-vm")||integer(budget,"requiredMiB",1)<=integer(budget,"availableMiB",0)||!value.path("references").isArray()||value.path("references").isEmpty())throw invalid("既知liveと容量不足の肯定証拠がありません");
        RuntimeMaintenanceVerifier.unstarted(snapshot);if(released)RuntimeMaintenanceVerifier.requireTerminal(snapshot);
        else if(!lease.path("state").asText().equals("active"))throw invalid("延期判定前のlease状態が不正です");
        for(JsonNode proof:value.path("references")) {
            JsonNode original=proof.path("lease");LeaseSnapshot other=LeaseSnapshot.read(root,root.resolve("jobs/"+uuid(original,"jobId")+"/lease.json"),trust);
            if(!other.descriptor().path("host").equals(snapshot.descriptor().path("host")))throw invalid("容量参照元のhostが一致しません");
            ObjectNode before=((ObjectNode)original).deepCopy(),after=other.lease();before.remove(List.of("state","resources"));after.remove(List.of("state","resources"));
            if(!before.equals(after)||!other.receiptKey().equals(text(proof,"receiptKey",4096))||!digest(readFile(other.file().resolveSibling("lease.lock"),64)).equals(hash(proof,"receiptSha256")))throw invalid("容量判定の参照元が変化しました");
            if(other.lease().path("state").asText().equals("active"))RuntimeMaintenanceVerifier.requireLive(other);else RuntimeMaintenanceVerifier.requireReleased(other);
        }
        validateBudget(lease,java.util.stream.StreamSupport.stream(value.path("references").spliterator(),false).map(v->v.path("lease")).toList(),budget);
        snapshot.verifyUnchanged();return value;
    }

    private static void validateBudget(JsonNode candidate,List<JsonNode> references,JsonNode budget) throws IOException {
        fields(budget,"domain","constraint","hostMiB","requiredMiB","availableMiB","generations");
        if(!text(budget,"domain",32).equals("macos-vm")||!budget.path("generations").isArray())throw invalid("VM予算のdomainが不正です");
        Map<String,Long> requests=new HashMap<>();String current=hash(candidate,"descriptorSha256");
        requests.put(current,integer(candidate.path("requirements"),"containerMemoryMiB",1));Set<String> jobs=new HashSet<>();jobs.add(uuid(candidate,"jobId"));
        try {
            for(JsonNode reference:references){if(!jobs.add(uuid(reference,"jobId"))||!reference.path("state").asText().equals("active"))throw invalid("VM予算の参照が重複または非activeです");requests.merge(hash(reference,"descriptorSha256"),integer(reference.path("requirements"),"containerMemoryMiB",1),Math::addExact);}
            long total=0,currentReservation=0;Set<String> generations=new HashSet<>();
            for(JsonNode row:budget.path("generations")) {
                fields(row,"descriptorSha256","reservedMiB","requestedMiB");String digest=hash(row,"descriptorSha256");long reserved=integer(row,"reservedMiB",1),requested=integer(row,"requestedMiB",1);
                if(!generations.add(digest)||!Objects.equals(requests.get(digest),requested))throw invalid("世代別VM予算と元leaseが一致しません");
                if(digest.equals(current))currentReservation=reserved;else if(Math.addExact(requested,1024)>reserved)throw invalid("他世代VM要求が予約を超えています");
                total=Math.addExact(total,reserved);
            }
            if(!generations.equals(requests.keySet())||references.isEmpty())throw invalid("VM予算の世代集合が一致しません");
            long available,required;String constraint=text(budget,"constraint",16);long host=integer(budget,"hostMiB",2049);
            if(currentReservation>host-2048)throw invalid("候補単独でもhost容量が不足しています");
            if(Math.addExact(integer(candidate.path("requirements"),"containerMemoryMiB",1),1024)>currentReservation)throw invalid("候補単独でもVM容量が不足しています");
            if(constraint.equals("host")){required=total;available=host-2048;}
            else if(constraint.equals("generation")){required=Math.addExact(requests.get(current),1024);available=currentReservation;}
            else throw invalid("VM予算の制約が不正です");
            if(required<=available||required!=integer(budget,"requiredMiB",1)||available!=integer(budget,"availableMiB",0))throw invalid("VM容量不足の計算が一致しません");
        }catch(ArithmeticException failure){throw new IOException("VM予算の計算が範囲外です",failure);}
    }

    public static void save(Path file,ObjectNode value,LeaseTrust trust) throws IOException {
        Files.createDirectories(file.getParent());RuntimeMaintenanceVerifier.directory(file.getParent());
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw invalid("admission証拠が既にあります");
        Path staged=Files.createTempFile(file.getParent(),"admission-",".tmp");
        try{value.put("evidenceKey",trust.identity(staged).toString());byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>1024*1024)throw invalid("admission証拠が上限を超えています");try(FileChannel channel=FileChannel.open(staged,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(staged);}
    }
    public static Path preAdmission(Path root,String attempt) throws IOException {
        try{if(!UUID.fromString(attempt).toString().equals(attempt))throw invalid("admission UUIDが不正です");}catch(IllegalArgumentException failure){throw invalid("admission UUIDが不正です");}
        return root.resolve("logs/"+attempt+".pre-admission-v1.json");
    }
    public static ObjectNode reference(Path root,String attempt) throws IOException {
        Path file=preAdmission(root,attempt);return JSON.createObjectNode().put("attemptId",attempt).put("evidenceSha256",digest(readFile(file,1024*1024)));
    }
    public static JsonNode verifyPreAdmission(Path root,JsonNode reference,String candidate,long pid,String started,LeaseTrust trust) throws IOException {
        Path path=root.resolve("locks/generations.lock");RuntimeMaintenanceVerifier.directory(path.getParent());if(!path.toRealPath().equals(path))throw invalid("generations lockにlinkがあります");
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            java.nio.channels.FileLock acquired=null;long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(acquired==null) {
                try{acquired=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){}
                if(acquired!=null)break;if(System.nanoTime()>=end)throw invalid("generations lockの期限を超えました");
                try{Thread.sleep(10);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("未公開証拠の再検証を中断しました");}
            }
            try(var held=acquired){return verifyPreAdmissionLocked(root,reference,candidate,pid,started,trust);}
        }
    }
    private static JsonNode verifyPreAdmissionLocked(Path root,JsonNode reference,String candidate,long pid,String started,LeaseTrust trust) throws IOException {
        fields(reference,"attemptId","evidenceSha256");String attempt=uuid(reference,"attemptId");Path file=preAdmission(root,attempt);byte[] original=readFile(file,1024*1024);
        if(!digest(original).equals(hash(reference,"evidenceSha256")))throw invalid("未公開証拠のhashが一致しません");
        JsonNode value=read(original);fields(value,"schemaVersion","kind","cause","evidenceKey","attemptId","root","hostId","pid","processStartedAt","descriptorSha256","references");
        if(integer(value,"schemaVersion",1)!=1||!text(value,"kind",32).equals("pre-admission")||!text(value,"cause",32).equals("LEGACY_READER_ACTIVE")||!text(value,"attemptId",36).equals(attempt)||!text(value,"root",4096).equals(root.toString())||!hash(value,"descriptorSha256").equals(candidate)||integer(value,"pid",1)!=pid||!text(value,"processStartedAt",64).equals(started)||!new String(readFile(root.resolve("host-id"),64),java.nio.charset.StandardCharsets.US_ASCII).trim().equals(uuid(value,"hostId")))throw invalid("未公開証拠の試行が一致しません");
        if(!trust.identity(file).toString().equals(text(value,"evidenceKey",4096)))throw invalid("未公開証拠のfile identityが変化しました");
        JsonNode references=value.path("references");if(!references.isArray()||references.isEmpty())throw invalid("旧readerの肯定証拠がありません");
        Set<String> jobs=new HashSet<>();
        for(JsonNode proof:references) {
            fields(proof,"lease","receiptSha256","receiptKey","descriptorSha256");JsonNode prior=proof.path("lease");String job=uuid(prior,"jobId");
            if(!jobs.add(job)||!LeaseTrust.legacy(hash(prior,"descriptorSha256"))||!prior.path("state").asText().equals("active"))throw invalid("旧reader証拠が不正です");
            LeaseSnapshot current=LeaseSnapshot.read(root,root.resolve("jobs/"+job+"/lease.json"),trust);
            if(!current.descriptor().path("host").path("os").asText().equals("windows")||!current.descriptor().path("host").path("arch").asText().equals("x86_64"))throw invalid("旧Windows readerのhostが一致しません");
            ObjectNode before=((ObjectNode)prior).deepCopy(),now=current.lease();before.remove(List.of("state","resources"));now.remove(List.of("state","resources"));
            if(!before.equals(now)||!current.receiptKey().equals(text(proof,"receiptKey",4096))||!digest(readFile(current.file().resolveSibling("lease.lock"),64)).equals(hash(proof,"receiptSha256")))throw invalid("旧readerのimmutable証拠が変化しました");
            if(current.lease().path("state").asText().equals("active"))RuntimeMaintenanceVerifier.requireLive(current);
            else RuntimeMaintenanceVerifier.requireReleased(current);
        }
        verifyUnpublishedJobs(root,candidate,pid,started,trust);
        return value;
    }
    private static void verifyUnpublishedJobs(Path root,String candidate,long pid,String started,LeaseTrust trust) throws IOException {
        try(var entries=Files.newDirectoryStream(root.resolve("jobs"))) {
            for(Path entry:entries) {
                LeaseSnapshot snapshot=LeaseSnapshot.read(root,entry.resolve("lease.json"),trust);JsonNode lease=snapshot.lease();
                if(lease.path("descriptorSha256").asText().equals(candidate)&&lease.path("pid").asLong()==pid&&lease.path("processStartedAt").asText().equals(started))throw invalid("未公開のはずの候補jobが存在します");
                if(!snapshot.descriptor().path("host").path("os").asText().equals("windows")||!snapshot.descriptor().path("host").path("arch").asText().equals("x86_64"))throw invalid("完全走査jobのhostが一致しません");
                if(lease.path("state").asText().equals("active"))RuntimeMaintenanceVerifier.requireLive(snapshot);else RuntimeMaintenanceVerifier.requireReleased(snapshot);
                snapshot.verifyUnchanged();
            }
        }
    }
}
