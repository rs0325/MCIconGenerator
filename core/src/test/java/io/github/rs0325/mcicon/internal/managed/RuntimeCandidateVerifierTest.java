package io.github.rs0325.mcicon.internal.managed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCandidateVerifierTest {
    @Test void unsignedParserRetainsPinAndSchemaChecks() throws Exception {
        var fixture=new ManagedFixture();byte[] raw=fixture.bytes();
        assertEquals("fixture",RuntimeDescriptor.parse(raw,fixture.pin(raw)).runtimeId());
        byte[] changed=raw.clone();changed[0]='[';
        assertThrows(Exception.class,()->RuntimeDescriptor.parse(changed,fixture.pin(raw)));
        fixture.json.put("unexpected",true);byte[] unknown=fixture.bytes();
        assertThrows(Exception.class,()->RuntimeDescriptor.parse(unknown,fixture.pin(unknown)));
    }
    @Test void unsignedParsingDoesNotPermitProductionVerificationWithoutSignature() throws Exception {
        var fixture=new ManagedFixture();byte[] raw=fixture.bytes();
        RuntimeDescriptor.parse(raw,fixture.pin(raw));
        assertThrows(Exception.class,()->RuntimeDescriptor.verify(raw,new byte[64],fixture.trust,fixture.pin(raw)));
    }
}
