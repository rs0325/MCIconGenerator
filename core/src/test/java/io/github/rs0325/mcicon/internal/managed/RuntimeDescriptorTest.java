package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.*;
import io.github.rs0325.mcicon.McIconException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeDescriptorTest {
    @Test void acceptsRecoveryKeyAndRejectsTamperedSignature() throws Exception {
        ManagedFixture fixture=new ManagedFixture();byte[] bytes=fixture.bytes();
        var signer=java.security.Signature.getInstance("Ed25519");signer.initSign(fixture.recovery.getPrivate());signer.update(bytes);byte[] signature=signer.sign();
        assertEquals("fixture",RuntimeDescriptor.verify(bytes,signature,fixture.trust,fixture.pin(bytes)).runtimeId());
        signature[0]^=1;assertInvalid(()->RuntimeDescriptor.verify(bytes,signature,fixture.trust,fixture.pin(bytes)));
    }
    @Test void originalBytesAndPinnedCompatibility() throws Exception {
        ManagedFixture fixture=new ManagedFixture();byte[] bytes=fixture.bytes();
        RuntimeDescriptor descriptor=fixture.descriptor();assertEquals("fixture",descriptor.runtimeId());
        byte[] whitespace=(new String(bytes,StandardCharsets.UTF_8)+"\n").getBytes(StandardCharsets.UTF_8);
        assertInvalid(()->RuntimeDescriptor.verify(whitespace,fixture.sign(bytes),fixture.trust,fixture.pin(whitespace)));
        assertInvalid(()->RuntimeDescriptor.verify(bytes,fixture.sign(bytes),new ManagedFixture().trust,fixture.pin(bytes)));
        var wrong=new RuntimeDescriptor.Pin(fixture.pin(bytes).descriptorSha256(),"1.2.0",ManagedFixture.HASH,ManagedFixture.HASH,descriptor.host(),java.util.Set.of(21));
        assertInvalid(()->RuntimeDescriptor.verify(bytes,fixture.sign(bytes),fixture.trust,wrong));
    }
    @Test void rejectsUnknownDuplicateAndUnsafeFields() throws Exception {
        reject(j->j.put("unknown",1));reject(j->j.put("schemaVersion",1.0));
        reject(j->((ObjectNode)j.get("helper")).put("protocolVersion",2));
        reject(j->((ObjectNode)j.get("helper")).put("executable","bin/../helper"));
        reject(j->((ObjectNode)j.get("helper")).put("executable","C:\\helper"));
        reject(j->((ObjectNode)j.get("helper")).put("artifactId","image"));
        reject(j->((ObjectNode)j.get("artifacts").get(0)).put("url","http://fixture.invalid/helper"));
        reject(j->((ObjectNode)j.get("artifacts").get(0)).put("bytes",Long.MAX_VALUE+"0"));
        reject(j->((ObjectNode)j.get("artifacts").get(0)).put("role","tool-payload"));
        reject(j->((ArrayNode)j.get("rendering").get("javaExecutables")).add(j.get("rendering").get("javaExecutables").get(0).deepCopy()));
        ManagedFixture fixture=new ManagedFixture();byte[] duplicate=new String(fixture.bytes(),StandardCharsets.UTF_8).replaceFirst("\\{","{\"schemaVersion\":1,").getBytes(StandardCharsets.UTF_8);
        assertInvalid(()->RuntimeDescriptor.verify(duplicate,fixture.sign(duplicate),fixture.trust,fixture.pin(duplicate)));
        byte[] utf16=new String(fixture.bytes(),StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_16);
        assertInvalid(()->RuntimeDescriptor.verify(utf16,fixture.sign(utf16),fixture.trust,fixture.pin(utf16)));
        byte[] trailing=(new String(fixture.bytes(),StandardCharsets.UTF_8)+" {}").getBytes(StandardCharsets.UTF_8);
        assertInvalid(()->RuntimeDescriptor.verify(trailing,fixture.sign(trailing),fixture.trust,fixture.pin(trailing)));
    }
    @Test void inventoryUsesSignedDigest() throws Exception {ManagedFixture fixture=new ManagedFixture();var descriptor=fixture.descriptor();assertInvalid(()->descriptor.verifyInventory(descriptor.artifacts().getFirst(),new byte[]{1}));}
    private void reject(Consumer<ObjectNode> mutation) throws Exception {ManagedFixture fixture=new ManagedFixture();mutation.accept(fixture.json);assertInvalid(fixture::descriptor);}
    static void assertInvalid(org.junit.jupiter.api.function.Executable action) {assertEquals("MANAGED_RUNTIME_INVALID",assertThrows(McIconException.class,action).code());}
}
