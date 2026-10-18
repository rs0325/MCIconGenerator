package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 同hostの旧bootだけを閉じ、残る専用diskの既知containerを新workerより先に回収する。 */
final class RebootRecovery {
    private final MacRuntime runtime;
    RebootRecovery(MacRuntime runtime){this.runtime=runtime;}
    boolean release(OperationJournal journal) throws IOException {
        if(!runtime.cache.previousBoot())return false;
        ObjectNode registration=runtime.cache.registered(),current=runtime.lease.read();requireDead(current);references(registration,false);
        boolean missing=observe(registration);Path ended=runtime.temporary.resolve("macos-ended-by-reboot.json");ObjectNode original=current.deepCopy();
        if(!CacheState.absent(ended)&&current.path("resources").isEmpty())original.set("resources",MacFiles.json(ended).required("resources"));
        checkResources(original,registration);ObjectNode snapshot=OperationJournal.rebootSnapshot(runtime.temporary,original,registration.path("generationOwner").asText());
        ObjectNode proof=proof(registration,original,snapshot,missing,runtime.host.bootSessionId());journal.markEndedByReboot(proof);
        runtime.cleanupBudget(java.time.Duration.ofNanos(1));runtime.lease.replaceResources((ObjectNode)current.path("resources"),MacFiles.JSON.createObjectNode());return true;
    }
    void prepare(OperationJournal journal) throws IOException {
        if(runtime.cache.registrationMissing()||!runtime.cache.previousBoot())return;
        if(!journal.unstarted()||!runtime.lease.read().path("resources").isEmpty())throw new IOException("新boot準備は未着手jobだけです");
        ObjectNode before=runtime.cache.registered();references(before,true);boolean missing=observe(before);runtime.cache.transitionBoot(missing);
    }
    void cleanupDisk(OperationJournal journal) throws IOException {
        String generation=runtime.cache.registered().path("generationOwner").asText();
        try(var jobs=Files.list(runtime.command.root().resolve("jobs"))) {
            for(Path job:jobs.toList()) {
                Path ended=job.resolve("tmp/macos-ended-by-reboot.json");if(CacheState.absent(ended))continue;JsonNode proof=MacFiles.json(ended);
                if(!proof.path("generationOwner").asText().equals(generation))continue;
                JsonNode lease=MacFiles.json(job.resolve("lease.json"));released(lease,job);
                ObjectNode before=runtime.cache.history(generation,proof.path("oldBootSessionId").asText()),original=verifyEnded(job,lease,proof,before);
                Path cleaned=job.resolve("tmp/macos-reboot-cleanup.json");ObjectNode result=MacFiles.JSON.createObjectNode().put("endedProofSha256",MacFiles.hash(ended));
                if(!CacheState.absent(cleaned)){if(!MacFiles.json(cleaned).equals(result))throw new IOException("旧disk回収証拠が一致しません");continue;}
                for(JsonNode container:original.path("resources").path("macos").path("containers"))new DockerTransport(runtime).removeRebooted(journal,original,container);
                OperationJournal.durable(cleaned,MacFiles.JSON.writeValueAsBytes(result));
            }
        }
    }
    void requireDiskCleaned() throws IOException {
        String generation=runtime.cache.registered().path("generationOwner").asText();
        try(var jobs=Files.list(runtime.command.root().resolve("jobs"))) {
            for(Path job:jobs.toList()) {
                Path ended=job.resolve("tmp/macos-ended-by-reboot.json");if(CacheState.absent(ended))continue;JsonNode proof=MacFiles.json(ended);
                try{String owner=proof.path("generationOwner").textValue();if(!UUID.fromString(owner).toString().equals(owner))throw new IllegalArgumentException();}catch(RuntimeException failure){throw new IOException("旧boot終了証拠の世代が不明です");}
                if(!proof.path("generationOwner").asText().equals(generation))continue;
                Path cleaned=job.resolve("tmp/macos-reboot-cleanup.json");if(CacheState.absent(cleaned)||!MacFiles.json(cleaned).equals(MacFiles.JSON.createObjectNode().put("endedProofSha256",MacFiles.hash(ended))))throw new IOException("旧diskの回収が未確定のため現在資源を保持します");
            }
        }
    }
    private ObjectNode verifyEnded(Path job,JsonNode lease,JsonNode saved,JsonNode registration) throws IOException {
        ObjectNode original=((ObjectNode)lease).deepCopy();original.set("resources",saved.required("resources"));checkResources(original,registration);
        ObjectNode snapshot=OperationJournal.rebootSnapshot(job.resolve("tmp"),original,registration.path("generationOwner").asText());
        ObjectNode expected=proof(registration,original,snapshot,saved.path("cacheMissing").asBoolean(),saved.path("currentBootSessionId").asText());if(!saved.equals(expected))throw new IOException("旧boot終了証拠が変化しました");return original;
    }
    private boolean observe(JsonNode registration) throws IOException {
        boolean missing=runtime.cache.anchorMissing();
        if(!missing) {
            runtime.cache.verifyPrevious();JsonNode record=MacFiles.json(runtime.cache.anchor().resolve("vm.json"));
            if(!record.path("identity").equals(registration))throw new IOException("旧VM登録snapshotが一致しません");
            ObjectNode resources=(ObjectNode)record.required("resources");checkMac(resources,registration);runtime.checkDisk(resources);
            if(!runtime.instance().path("status").asText().equals("Stopped"))throw new IOException("旧VMはOwnedStoppedではありません");
        }
        HostFacts.requireNoLimaProcess(runtime.payload.path("host-probe"),runtime.payload.path("bin/limactl"),runtime.commands);return missing;
    }
    private void references(JsonNode registration,boolean preparing) throws IOException {
        Path jobs=runtime.command.root().resolve("jobs");if(!jobs.toRealPath().equals(jobs))throw new IOException("reboot参照一覧の位置が不明です");
        try(var entries=Files.list(jobs)) {
            for(Path job:entries.toList()) {
                if(job.resolve("lease.json").equals(runtime.command.lease()))continue;
                if(!Files.isDirectory(job,LinkOption.NOFOLLOW_LINKS)||!job.toRealPath().equals(job))throw new IOException("reboot参照jobが不明です");
                JsonNode value=MacFiles.json(job.resolve("lease.json"));
                if(value.path("state").asText().equals("released")) {
                    released(value,job);Path ended=job.resolve("tmp/macos-ended-by-reboot.json");
                    if(CacheState.absent(ended))OperationJournal.rebootSnapshot(job.resolve("tmp"),value,null);
                    else {JsonNode proof=MacFiles.json(ended);if(proof.path("generationOwner").equals(registration.path("generationOwner"))) {ObjectNode before=proof.path("oldBootSessionId").equals(registration.path("bootSessionId"))?(ObjectNode)registration:runtime.cache.history(proof.path("generationOwner").asText(),proof.path("oldBootSessionId").asText());verifyEnded(job,value,proof,before);}}
                    continue;
                }
                JsonNode checked=LeaseFile.open(new HelperProtocol.Command(runtime.command.root(),job.resolve("lease.json"),"release",List.of()),runtime.executable).read();
                if(preparing) {
                    Path tmp=job.resolve("tmp");
                    if(!LeaseReferences.live(checked)||!checked.path("resources").isEmpty()||!CacheState.absent(tmp.resolve("macos-ended-by-reboot.json"))||!CacheState.absent(tmp.resolve("macos-operations/released.json"))||!OperationJournal.rebootSnapshot(tmp,checked,registration.path("generationOwner").asText()).isEmpty())throw new IOException("旧leaseまたは未着手と確認できないjobが残っています");
                    try(FileChannel channel=FileChannel.open(job.resolve("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                        FileLock lock=null;try{lock=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){}
                        if(lock!=null){lock.release();throw new IOException("新bootの待機core lockがありません");}
                    }
                    continue;
                }
                requireDead(checked);checkResources(checked,registration);
                Path ended=job.resolve("tmp/macos-ended-by-reboot.json");ObjectNode original=((ObjectNode)checked).deepCopy();if(checked.path("resources").isEmpty()&&!CacheState.absent(ended))original.set("resources",MacFiles.json(ended).required("resources"));
                OperationJournal.rebootSnapshot(job.resolve("tmp"),original,registration.path("generationOwner").asText());
            }
        }
    }
    private void released(JsonNode value,Path job) throws IOException {
        if(!job.getFileName().toString().equals(value.path("jobId").asText())||!value.path("resources").isObject()||!value.path("resources").isEmpty())throw new IOException("released参照が不正です");
        LeaseReferences.validateReleased(value,runtime.lease.read().path("hostId").asText());Path ended=job.resolve("tmp/macos-ended-by-reboot.json");
        if(!CacheState.absent(ended)) {ObjectNode owner=MacFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))owner.set(key,value.required(key));if(!MacFiles.json(job.resolve("tmp/macos-operations/released.json")).equals(owner))throw new IOException("reboot済みjobのtombstoneが不正です");}
        ObjectNode identity=((ObjectNode)value).deepCopy();identity.remove(List.of("state","resources"));Path receipt=job.resolve("lease.lock");
        if(!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS))throw new IOException("released参照receiptが不正です");
        try(FileChannel channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock=null;try{lock=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){}
            if(lock==null)throw new IOException("released参照のcore lockが保持されています");
            try {if(!Files.readString(receipt,StandardCharsets.US_ASCII).equals(new String(LeaseFile.receipt(identity),StandardCharsets.US_ASCII)))throw new IOException("released参照receiptが不正です");}finally {lock.release();}
        }
    }
    static void requireDead(JsonNode lease) throws IOException {
        if(!lease.path("processStartedAt").isTextual())throw new IOException("旧core開始identityが不明です");
        Optional<ProcessHandle> process=ProcessHandle.of(lease.path("pid").asLong()).filter(ProcessHandle::isAlive);if(process.isEmpty())return;
        Instant current=process.get().info().startInstant().orElseThrow(()->new IOException("旧core現在identityが不明です"));
        if(current.equals(Instant.parse(lease.path("processStartedAt").asText())))throw new IOException("元coreが現在も生存しています");
    }
    private void checkResources(JsonNode lease,JsonNode registration) throws IOException {
        if(lease.path("resources").isEmpty())return;checkMac(lease.path("resources").path("macos"),registration);
    }
    private void checkMac(JsonNode mac,JsonNode registration) throws IOException {
        if(!Set.of("ready","releasing").contains(mac.path("phase").asText())||!mac.path("generationOwner").equals(registration.path("generationOwner"))||!mac.path("instanceDir").asText().equals(runtime.cache.instance().toString())||!mac.path("hostAgent").path("bootSessionId").equals(registration.path("bootSessionId"))||!mac.path("machineIdSha256").asText().matches("[0-9a-f]{64}")||!mac.path("forwardConfigSha256").asText().matches("[0-9a-f]{64}"))throw new IOException("旧bootの完全なready所有を確認できません");
    }
    static ObjectNode proof(JsonNode registration,JsonNode lease,JsonNode snapshot,boolean missing,String currentBoot) throws IOException {
        try {if(!UUID.fromString(currentBoot).toString().equals(currentBoot)||currentBoot.equals(registration.path("bootSessionId").asText()))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("reboot終了のboot対応が不正です");}
        ObjectNode identity=((ObjectNode)lease).deepCopy();identity.remove(List.of("state","resources"));ObjectNode result=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("reason","ended-by-reboot");
        ObjectNode owner=result.putObject("identity");for(String key:List.of("jobId","ownerId","descriptorSha256"))owner.set(key,lease.required(key));
        result.put("receiptSha256",new String(LeaseFile.receipt(identity),StandardCharsets.US_ASCII));result.put("registrationSha256",MacFiles.hash(MacFiles.JSON.writeValueAsBytes(registration)));result.put("generationOwner",registration.path("generationOwner").asText());result.put("hostBindingSha256",registration.path("hostBindingSha256").asText());result.put("oldBootSessionId",registration.path("bootSessionId").asText());result.put("currentBootSessionId",currentBoot);result.put("cacheMissing",missing);result.set("resources",lease.path("resources").deepCopy());result.set("journalSnapshot",snapshot.deepCopy());return result;
    }
}
