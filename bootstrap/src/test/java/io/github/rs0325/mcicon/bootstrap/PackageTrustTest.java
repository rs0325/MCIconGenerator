package io.github.rs0325.mcicon.bootstrap;

import io.github.rs0325.mcicon.internal.TrustFixtures;
import java.nio.file.*;
import java.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackageTrustTest {
    @TempDir Path directory;
    byte[] trust() throws Exception {var generator=KeyPairGenerator.getInstance("Ed25519");return TrustFixtures.bytes(generator.generateKeyPair().getPublic(),generator.generateKeyPair().getPublic());}

    @Test void rejectsDifferentValidRuntimeTrustBeforeReadingSeed() throws Exception {
        Path resources=directory.resolve("resources"),prefix=resources.resolve("META-INF/mcicon/runtime");Files.createDirectories(prefix);
        byte[] bytes=trust();Path trust=directory.resolve("trust-set.bin");Files.write(trust,bytes);Files.write(prefix.resolve("trust-set.bin"),trust());
        var pins=Seed.JSON.createObjectNode().put("schemaVersion",1);var row=pins.putArray("runtimes").addObject().put("descriptorPath","descriptor.json").put("descriptorSha256","a".repeat(64)).put("trustKeyPath","trust-set.bin");row.set("host",Seed.JSON.valueToTree(Main.host()));Files.write(prefix.resolve("pins.json"),Seed.JSON.writeValueAsBytes(pins));
        Exception failure=assertThrows(Exception.class,()->ManagedPackageVerifier.verify(resources,directory.resolve("absent-seed"),trust,"1.1.0",directory.resolve("absent-core.jar")));
        assertEquals("runtimeとpackageの公開trustが異なります",failure.getMessage());
    }

    @Test void rejectsLegacyPackageDerEvenUnderNewFileName() throws Exception {
        Path resources=directory.resolve("resources"),prefix=resources.resolve("META-INF/mcicon/runtime");Files.createDirectories(prefix);
        Path trust=directory.resolve("trust-set.bin");Files.write(trust,KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        Exception failure=assertThrows(Exception.class,()->ManagedPackageVerifier.verify(resources,directory.resolve("seed"),trust,"1.1.0",directory.resolve("core.jar")));
        assertEquals("信頼集合の形式が不正です",failure.getMessage());
    }
}
