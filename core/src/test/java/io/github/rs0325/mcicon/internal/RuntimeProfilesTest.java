package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.McIconException;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RuntimeProfilesTest {
    @Test void everyFrozenExactReleaseHasAnAdapterAndUnknownReleasesAreRejected() throws Exception {
        try(var input=getClass().getResourceAsStream("/adapters/releases.json")) {
            var versions=Json.MAPPER.readTree(input).path("versions");assertEquals(51,versions.size());
            for(var version:versions)assertNotNull(RuntimePreparer.profileFor(version.path("id").asText()));
        }
        assertEquals("ADAPTER_UNAVAILABLE",assertThrows(McIconException.class,()->RuntimePreparer.profileFor("26.4")).code());
        assertThrows(McIconException.class,()->RuntimePreparer.profileFor("1.12.2"));
    }
    @Test void choosesLtsRuntimeForJava16ClientsAndPreservesOtherMajors() {
        assertEquals(17,RuntimePreparer.runtimeJavaMajor(16));
        for(int major:new int[]{8,17,21,25})assertEquals(major,RuntimePreparer.runtimeJavaMajor(major));
    }
}
