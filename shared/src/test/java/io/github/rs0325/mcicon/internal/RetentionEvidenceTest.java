package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

class RetentionEvidenceTest {
    @TempDir Path root;
    static ObjectNode evidence() {
        ObjectNode value=JSON.createObjectNode().put("schemaVersion",1).put("kind","runtime-retention").put("reason","stopped");
        for(String name:new String[]{"hostId","epoch","jobId","attempt","ownerId","generationOwner"})value.put(name,UUID.randomUUID().toString());
        for(String name:new String[]{"bootSha256","descriptorSha256","registrationSha256","incarnationSha256","leaseSha256","receiptSha256","terminalJournalSha256","keeperProofSha256"})value.put(name,"b".repeat(64));
        value.set("generation",ActiveReferencesTest.generation());
        value.put("rootKey","root-id").put("registrationKey","registration-id").put("receiptKey","receipt-id");
        value.putNull("holder");value.putObject("stopped").put("proofKey","stop-id").put("proofSha256","c".repeat(64));return value;
    }
    @Test void 両reasonのcanonicalとstrict形式と元job結合を検証する() throws Exception {
        ObjectNode value=evidence();assertEquals(value,RetentionEvidence.decode(RetentionEvidence.encode(value)));
        ObjectNode state=ActiveReferencesTest.payload();for(String name:new String[]{"hostId","epoch","rootKey","bootSha256"})state.set(name,value.required(name));
        ObjectNode entry=JSON.createObjectNode().put("phase","admitted");for(String name:new String[]{"jobId","attempt","ownerId","descriptorSha256","receiptKey","receiptSha256","generation"})entry.set(name,value.required(name));
        RetentionEvidence.verifyBinding(RetentionEvidence.encode(value),state,entry);
        entry.put("attempt",UUID.randomUUID().toString());assertThrows(IOException.class,()->RetentionEvidence.verifyBinding(RetentionEvidence.encode(value),state,entry));
        value.put("reason","same-incarnation-holder");assertThrows(IOException.class,()->RetentionEvidence.encode(value));
        value.putNull("stopped");ObjectNode holder=value.putObject("holder");
        for(String name:new String[]{"jobId","attempt","ownerId"})holder.put(name,UUID.randomUUID().toString());
        holder.put("receiptKey","holder-receipt");for(String name:new String[]{"receiptSha256","leaseSha256","journalSha256"})holder.put(name,"d".repeat(64));
        assertEquals(value,RetentionEvidence.decode(RetentionEvidence.encode(value)));
        holder.set("jobId",value.required("jobId"));assertThrows(IOException.class,()->RetentionEvidence.encode(value));
    }
    @Test void hash未知field重複末尾recordと上限bytesを拒否する() throws Exception {
        ObjectNode value=evidence(),envelope=(ObjectNode)read(RetentionEvidence.encode(value));
        ((ObjectNode)envelope.path("payload")).put("receiptKey","changed");assertThrows(IOException.class,()->RetentionEvidence.decode(JSON.writeValueAsBytes(envelope)));
        value.put("unknown",1);assertThrows(IOException.class,()->RetentionEvidence.encode(value));
        String valid=new String(RetentionEvidence.encode(evidence()),java.nio.charset.StandardCharsets.UTF_8);
        for(String invalid:new String[]{valid+"{}",valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")," ".repeat(65537)})assertThrows(IOException.class,()->RetentionEvidence.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void job追加証拠は元root排他でCASとreadbackを行い置換と競合を拒否する() throws Exception {
        root=root.toRealPath();Files.createDirectory(root.resolve("locks"));
        var generator=KeyPairGenerator.getInstance("Ed25519");LeaseTrust trust=IdentityFixtures.trust(TrustFixtures.trust(generator.generateKeyPair().getPublic(),generator.generateKeyPair().getPublic()));
        ObjectNode value=evidence();String id=value.path("jobId").asText();Files.createDirectories(root.resolve("jobs/"+id+"/tmp"));
        var store=new JobReferenceEvidence(root,id,trust,JobReferenceEvidence.Kind.RETENTION);
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
            var first=store.publish(null,value,held,end);assertEquals(value,RetentionEvidence.decode(first.bytes()));
            store.publish(first,value,held,end);
            assertThrows(IOException.class,()->store.publish(first,value,held,end));
            var actual=store.read(end);Path file=root.resolve("jobs/"+id+"/tmp/runtime-retention-v1.json"),replacement=file.resolveSibling("replacement");
            Files.write(replacement,actual.bytes());Files.move(replacement,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            assertThrows(IOException.class,()->store.publish(actual,value,held,end));
            assertEquals(value,RetentionEvidence.decode(store.read(end).bytes()));
        }
    }
}
