package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.JSON;

class ActiveReferencesTest {
    private static final String HOST=UUID.randomUUID().toString();
    static ObjectNode identity() throws IOException {
        ObjectNode value=JSON.createObjectNode().put("schemaVersion",1).put("jobId",UUID.randomUUID().toString()).put("ownerId",UUID.randomUUID().toString()).put("toolVersion","1.1.2").put("workerSha256","a".repeat(64)).put("runtimeId","fixture").put("descriptorSha256","b".repeat(64)).put("hostId",HOST).put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("createdAt",Instant.EPOCH.toString());
        value.putObject("requirements").put("containerMemoryMiB",3072).put("heapMemoryMiB",2048).put("cpus",4).put("preparationDeadline",Instant.EPOCH.toString());return (ObjectNode)JSON.readTree(JSON.writeValueAsBytes(value));
    }
    static ObjectNode generation() {
        return JSON.createObjectNode().put("os","windows").put("arch","x86_64").put("helperDirectory","helpers/windows-x86_64/"+"a".repeat(64))
                .put("helperExecutable","helpers/windows-x86_64/"+"a".repeat(64)+"/bin/helper").put("descriptorSha256","b".repeat(64))
                .put("archiveSha256","a".repeat(64)).put("inventorySha256","d".repeat(64));
    }
    static ObjectNode payload() {
        ObjectNode value=JSON.createObjectNode().put("schemaVersion",1).put("kind","active-references").put("hostId",HOST).put("rootKey","fixture-root").put("bootSha256","c".repeat(64)).put("epoch",UUID.randomUUID().toString()).put("revision",1).put("phase","ready");
        value.putArray("macDiskSeals");value.putNull("repair");value.putArray("entries");return value;
    }
    @Test void staging未作成でも予約から元ownerと全予定identityを復元できる() throws Exception {
        ObjectNode identity=identity(),value=payload(),entry=ActiveReferences.reserved(identity,ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64));value.withArray("entries").add(entry);
        var decoded=ActiveReferences.decode(ActiveReferences.encode(value));var saved=decoded.path("entries").get(0);
        assertEquals(identity,saved.path("identity"));assertEquals(ProcessHandle.current().pid(),saved.path("identity").path("pid").asLong());assertEquals(identity.path("processStartedAt"),saved.path("identity").path("processStartedAt"));assertTrue(saved.path("receiptKey").isNull());assertTrue(saved.path("jobKey").isNull());
        assertEquals(saved.path("admissionBootSha256"),ActiveReferences.sidecar(decoded,saved).path("admissionBootSha256"));
        identity.putNull("processStartedAt");assertThrows(IOException.class,()->ActiveReferences.reserved(identity,ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64)));
    }
    @Test void 件数と重複と予定receiptの不一致を公開前に拒否する() throws Exception {
        ObjectNode value=payload();for(int i=0;i<16;i++)value.withArray("entries").add(ActiveReferences.reserved(identity(),ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64)));
        assertEquals(16,ActiveReferences.decode(ActiveReferences.encode(value)).path("entries").size());
        value.withArray("entries").add(ActiveReferences.reserved(identity(),ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64)));assertThrows(IOException.class,()->ActiveReferences.encode(value));
        value.withArray("entries").remove(16);value.withArray("entries").set(1,value.path("entries").get(0));assertThrows(IOException.class,()->ActiveReferences.encode(value));
        ObjectNode single=payload(),entry=ActiveReferences.reserved(identity(),ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64));single.withArray("entries").add(entry);entry.put("receiptSha256","d".repeat(64));assertThrows(IOException.class,()->ActiveReferences.encode(single));
    }
    @Test void 旧hashのままの書換えと未知fieldとpayload型を拒否する() throws Exception {
        ObjectNode value=payload(),encoded=(ObjectNode)JSON.readTree(ActiveReferences.encode(value));((ObjectNode)encoded.path("payload")).put("revision",2);
        assertThrows(IOException.class,()->ActiveReferences.decode(JSON.writeValueAsBytes(encoded)));
        value.put("unknown",true);assertThrows(IOException.class,()->ActiveReferences.encode(value));
        encoded.putArray("payload");assertThrows(IOException.class,()->ActiveReferences.decode(JSON.writeValueAsBytes(encoded)));
    }
    @Test void canonicalはentry順とfield順に依存せず重複fieldと末尾recordを拒否する() throws Exception {
        ObjectNode value=payload();for(int index=0;index<2;index++)value.withArray("entries").add(ActiveReferences.reserved(identity(),generation(),UUID.randomUUID().toString(),"c".repeat(64)));
        byte[] canonical=ActiveReferences.encode(value);ObjectNode reversed=JSON.createObjectNode();var names=new java.util.ArrayList<String>();value.fieldNames().forEachRemaining(names::add);java.util.Collections.reverse(names);
        for(String name:names)reversed.set(name,value.required(name).deepCopy());
        reversed.withArray("entries").removeAll().add(value.path("entries").get(1)).add(value.path("entries").get(0));
        assertArrayEquals(canonical,ActiveReferences.encode(reversed));
        String valid=new String(canonical,java.nio.charset.StandardCharsets.UTF_8);
        for(String invalid:new String[]{valid+"{}",valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")," ".repeat(65537)})assertThrows(IOException.class,()->ActiveReferences.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
