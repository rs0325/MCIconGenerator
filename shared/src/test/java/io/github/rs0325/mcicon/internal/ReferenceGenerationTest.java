package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceGenerationTest {
    @Test void reservedも同世代なら16まで数え17と別世代を拒否する() throws Exception {
        ObjectNode value=ActiveReferencesTest.payload(),generation=ActiveReferencesTest.generation();
        ActiveReferences.requireCapacity(value,generation);
        for(int i=0;i<16;i++) {
            ActiveReferences.requireCapacity(value,generation);
            value.withArray("entries").add(ActiveReferences.reserved(ActiveReferencesTest.identity(),generation,UUID.randomUUID().toString(),"c".repeat(64)));
        }
        assertThrows(IOException.class,()->ActiveReferences.requireCapacity(value,generation));
        value.withArray("entries").remove(15);ActiveReferences.requireCapacity(value,generation);
        for(String field:new String[]{"descriptorSha256","inventorySha256","helperExecutable"}) {
            ObjectNode other=generation.deepCopy();other.put(field,field.equals("helperExecutable")?generation.path(field).asText()+"2":"e".repeat(64));
            assertThrows(IOException.class,()->ActiveReferences.requireCapacity(value,other));
        }
        value.put("phase","recovering");assertThrows(IOException.class,()->ActiveReferences.requireCapacity(value,generation));
    }
    @Test void 世代はcanonicalなhostとpathに結合しidentity置換を別世代に変換しない() throws Exception {
        ObjectNode value=ActiveReferencesTest.generation();
        for(String path:new String[]{"../helper","helpers/windows-x86_64/"+"a".repeat(64)+"/../helper","elsewhere/helper"}) {
            value.put("helperExecutable",path);assertThrows(IOException.class,()->ReferenceGeneration.canonical(value));
        }
        ObjectNode identity=ActiveReferencesTest.identity();identity.put("descriptorSha256","e".repeat(64));
        assertThrows(IOException.class,()->ActiveReferences.reserved(identity,ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64)));
    }
    @Test void sidecarは集合と元attemptと世代の一致が必要() throws Exception {
        ObjectNode value=ActiveReferencesTest.payload(),entry=ActiveReferences.reserved(ActiveReferencesTest.identity(),ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64));
        ObjectNode sidecar=ActiveReferences.sidecar(value,entry);
        byte[] bytes=ActiveReferences.encodeSidecar(sidecar);ActiveReferences.verifySidecar(bytes,value,entry);
        sidecar.put("attempt",UUID.randomUUID().toString());
        assertThrows(IOException.class,()->ActiveReferences.verifySidecar(ActiveReferences.encodeSidecar(sidecar),value,entry));
        sidecar.put("unknown",1);assertThrows(IOException.class,()->ActiveReferences.encodeSidecar(sidecar));
    }
}
