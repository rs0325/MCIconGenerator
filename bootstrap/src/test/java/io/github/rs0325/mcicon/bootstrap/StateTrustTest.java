package io.github.rs0325.mcicon.bootstrap;

import io.github.rs0325.mcicon.internal.TrustFixtures;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StateTrustTest {
    @TempDir Path directory;
    @Test void stateRequiresCurrentTrustFingerprintAndRejectsOldSchemaWithoutChangingFile() throws Exception {
        Path installed=directory.resolve("package"),root=directory.resolve("state");Files.createDirectory(installed);Files.createDirectory(root);
        var generator=KeyPairGenerator.getInstance("Ed25519");byte[] trust=TrustFixtures.bytes(generator.generateKeyPair().getPublic(),generator.generateKeyPair().getPublic());Files.write(installed.resolve("trust-set.bin"),trust);
        var host=new Seed.Host("linux","x86_64");var info=Seed.JSON.createObjectNode().put("schemaVersion",1).put("bootstrapVersion","1.1.0").put("bootstrapProtocol",4).put("owner","archive").putNull("stableManifestUrl");info.set("host",Seed.JSON.valueToTree(host));Files.write(installed.resolve("bootstrap-package.json"),Seed.JSON.writeValueAsBytes(info));
        Bootstrap bootstrap=new Bootstrap(installed,root,host);
        var state=Seed.JSON.createObjectNode().put("schemaVersion",4).put("trustSetSha256",Seed.hash(trust)).put("channel","stable").put("highestAcceptedSequence",1);state.set("host",Seed.JSON.valueToTree(host));
        state.putObject("active").put("toolVersion","1.1.0").put("payloadSha256","a".repeat(64)).put("runtimeDescriptorSha256","b".repeat(64)).put("manifestSha256","c".repeat(64));state.putNull("pending").putNull("previous").putNull("trialInvocation").putNull("preparation");state.putArray("badCandidates");
        Path file=root.resolve("active.json");Files.write(file,Seed.JSON.writeValueAsBytes(state));assertEquals(state,bootstrap.state());
        state.put("highestAcceptedSequence",Long.MAX_VALUE);Files.write(file,Seed.JSON.writeValueAsBytes(state));assertEquals(state,bootstrap.state());
        state.put("highestAcceptedSequence",BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));Files.write(file,Seed.JSON.writeValueAsBytes(state));assertThrows(IOException.class,bootstrap::state);assertEquals(state,Seed.json(Files.readAllBytes(file)));
        state.put("highestAcceptedSequence",1);
        state.put("trustSetSha256","0".repeat(64));Files.write(file,Seed.JSON.writeValueAsBytes(state));assertTrue(assertThrows(IOException.class,bootstrap::state).getMessage().contains("trustSetSha256"));assertEquals(state,Seed.json(Files.readAllBytes(file)));
        for(int schema:new int[]{2,3}){state.put("schemaVersion",schema);Files.write(file,Seed.JSON.writeValueAsBytes(state));assertTrue(assertThrows(IOException.class,bootstrap::state).getMessage().contains("schemaVersion"));assertEquals(state,Seed.json(Files.readAllBytes(file)));}
        info.put("bootstrapProtocol",3);Files.write(installed.resolve("bootstrap-package.json"),Seed.JSON.writeValueAsBytes(info));assertThrows(IOException.class,()->new Bootstrap(installed,root,host));
    }
}
