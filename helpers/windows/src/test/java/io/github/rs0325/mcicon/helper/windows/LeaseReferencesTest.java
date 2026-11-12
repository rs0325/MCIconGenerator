package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LeaseReferencesTest {
    @TempDir Path root;
    static List<Path> released(Path root,ObjectNode template,int count) throws Exception {
        List<Path> jobs=new ArrayList<>();
        for(int i=0;i<count;i++) {
            String id=UUID.randomUUID().toString();Path job=Files.createDirectories(root.resolve("jobs").resolve(id));Files.createDirectory(job.resolve("tmp"));
            ObjectNode value=template.deepCopy().put("jobId",id).put("ownerId",UUID.randomUUID().toString()).put("state","released");value.putObject("resources");
            ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));
            WindowsFiles.write(job.resolve("lease.json"),value);Files.write(job.resolve("lease.lock"),LeaseFile.receipt(identity));
            ObjectNode journal=WindowsFiles.JSON.createObjectNode().put("state","released");ObjectNode owner=journal.putObject("identity");for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))owner.set(key,value.path(key));
            WindowsFiles.write(job.resolve("tmp/windows-operation.json"),journal);jobs.add(job);
        }
        return jobs;
    }
    ObjectNode template() {
        ObjectNode value=WindowsFiles.JSON.createObjectNode().put("schemaVersion",1).put("toolVersion","1.1.2").put("workerSha256","b".repeat(64)).put("runtimeId","fixture").put("descriptorSha256","a".repeat(64)).put("hostId",UUID.randomUUID().toString()).put("pid",ProcessHandle.current().pid()).putNull("processStartedAt").put("createdAt",Instant.EPOCH.toString());
        value.putObject("requirements").put("containerMemoryMiB",3072).put("heapMemoryMiB",2048).put("cpus",4).put("preparationDeadline",Instant.EPOCH.toString());return value;
    }
    @Test void 多数releasedでも後段枠を残し毎回receiptとjournalとlockを再照合する() throws Exception {
        root=root.toRealPath();ObjectNode template=template();Files.writeString(root.resolve("host-id"),template.path("hostId").asText());List<Path> jobs=released(root,template,104);
        var command=new HelperProtocol.Command(root,root.resolve("current/lease.json"),"release",List.of());Path exe=root.resolve("helper.exe");
        long end=System.nanoTime()+Duration.ofSeconds(6).toNanos();var scan=LeaseReferences.inspect(command,exe,"a".repeat(64),end-Commands.releaseTail(6).toNanos());assertTrue(scan.stopAllowed(),scan.diagnostics().toString());assertTrue(scan.currentPristine());assertFalse(scan.active());assertTrue(end-System.nanoTime()>=Commands.releaseTail(6).toNanos());
        Path peer=jobs.getLast(),receipt=peer.resolve("lease.lock"),journal=peer.resolve("tmp/windows-operation.json");byte[] original=Files.readAllBytes(receipt),originalJournal=Files.readAllBytes(journal);
        Files.write(receipt,new byte[64]);assertTrue(LeaseReferences.inspect(command,exe,"a".repeat(64),Long.MAX_VALUE).unknown());Files.write(receipt,original);
        ObjectNode changed=(ObjectNode)WindowsFiles.json(journal);((ObjectNode)changed.path("identity")).put("ownerId",UUID.randomUUID().toString());WindowsFiles.write(journal,changed);assertTrue(LeaseReferences.inspect(command,exe,"a".repeat(64),Long.MAX_VALUE).unknown());Files.write(journal,originalJournal);
        try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.lock(64,1,false)){assertTrue(LeaseReferences.inspect(command,exe,"a".repeat(64),Long.MAX_VALUE).unknown(),"busy releasedはkeeper証明がなければ停止を許可しない");}
        assertTrue(LeaseReferences.inspect(command,exe,"a".repeat(64),Long.MAX_VALUE).stopAllowed());
        var missing=new HelperProtocol.Command(root.resolve("missing"),root.resolve("none"),"release",List.of());
        IOException failure=assertThrows(IOException.class,()->LeaseReferences.inspect(missing,exe,"a".repeat(64),System.nanoTime()-1));assertTrue(failure.getMessage().contains("後続予約枠"),failure.getMessage());
    }
    @Test void 予算の有無によらず割込みを同じUNKNOWNへ分類してflagとreceiptを保持する() throws Exception {
        root=root.toRealPath();ObjectNode template=template();Files.writeString(root.resolve("host-id"),template.path("hostId").asText());Path peer=released(root,template,1).getFirst();
        var command=new HelperProtocol.Command(root,root.resolve("current/lease.json"),"release",List.of());
        for(long end:new long[]{Long.MAX_VALUE,System.nanoTime()+Duration.ofSeconds(5).toNanos()}) {
            Thread.currentThread().interrupt();
            try {
                var scan=LeaseReferences.inspect(command,root.resolve("helper.exe"),"a".repeat(64),end);
                assertTrue(scan.unknown());assertTrue(scan.diagnostics().stream().noneMatch(value->value.contains("TIMEOUT")));assertFalse(scan.stopAllowed());assertFalse(scan.restartAllowed());assertTrue(Thread.currentThread().isInterrupted());
            }finally{Thread.interrupted();}
            try(var channel=FileChannel.open(peer.resolve("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.tryLock(64,1,false)){assertNotNull(lock);}
        }
    }

}
