package io.github.rs0325.mcicon.bootstrap;

import io.github.rs0325.mcicon.internal.TrustFixtures;
import io.github.rs0325.mcicon.internal.TrustSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TrustSetTest {
    @TempDir Path directory;
    KeyPair pair() throws Exception {return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}
    byte[] sign(byte[] bytes,KeyPair key) throws Exception {Signature signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(bytes);return signer.sign();}

    @Test void acceptsEitherKeyAndRejectsUnknownOrTamperedSignatures() throws Exception {
        KeyPair operational=pair(),recovery=pair();TrustSet trust=TrustFixtures.trust(operational.getPublic(),recovery.getPublic());byte[] body={1,2,3};
        trust.verify(body,sign(body,operational));trust.verify(body,sign(body,recovery));
        assertThrows(IOException.class,()->trust.verify(body,sign(body,pair())));
        byte[] changed=sign(body,operational);changed[0]^=1;assertThrows(IOException.class,()->trust.verify(body,changed));
        assertThrows(IOException.class,()->trust.verify(new byte[]{1,2,4},sign(body,recovery)));
        for(int length:new int[]{0,63,65})assertThrows(IOException.class,()->trust.verify(body,new byte[length]));
        byte[] invalid=new byte[64];Arrays.fill(invalid,(byte)255);assertThrows(IOException.class,()->trust.verify(body,invalid));
    }

    @Test void rejectsDuplicatesWrongCountsInvalidDerLegacyAndNoncanonicalOrder() throws Exception {
        KeyPair first=pair(),second=pair();byte[] bytes=TrustFixtures.bytes(first.getPublic(),second.getPublic());assertEquals(96,bytes.length);TrustSet.parse(bytes);
        assertArrayEquals(bytes,TrustFixtures.bytes(second.getPublic(),first.getPublic()));
        assertThrows(IOException.class,()->TrustSet.parse(TrustFixtures.bytes(first.getPublic(),first.getPublic())));
        for(int length:new int[]{0,8,52,95,97,140})assertThrows(IOException.class,()->TrustSet.parse(Arrays.copyOf(bytes,length)));
        assertThrows(IOException.class,()->TrustSet.parse(first.getPublic().getEncoded()));
        byte[] invalid=bytes.clone();invalid[8]=0;assertThrows(IOException.class,()->TrustSet.parse(invalid));
        byte[] wrongVersion=bytes.clone();wrongVersion[6]='2';assertThrows(IOException.class,()->TrustSet.parse(wrongVersion));
        byte[] reverse=ByteBuffer.allocate(96).put(bytes,0,8).put(bytes,52,44).put(bytes,8,44).array();assertThrows(IOException.class,()->TrustSet.parse(reverse));
    }

    @Test void rejectsOffCurvePointsInEitherPosition() throws Exception {
        byte[] prefix=HexFormat.of().parseHex("302a300506032b6570032100");
        byte[] invalid=Arrays.copyOf(prefix,44);invalid[12]=2;
        for(int leading:new int[]{1,88}) {
            byte[] valid=Arrays.copyOf(prefix,44);Arrays.fill(valid,12,44,(byte)(leading==1?1:102));valid[12]=(byte)leading;
            byte[] first=leading==1?valid:invalid,second=leading==1?invalid:valid;
            assertTrue(Arrays.compareUnsigned(first,second)<0);
            byte[] bytes=ByteBuffer.allocate(96).put(new byte[]{'M','C','I','T','R','S','1','\n'}).put(first).put(second).array();
            assertThrows(IOException.class,()->TrustSet.parse(bytes));
        }
    }

    @Test void seedAcceptsOperationalAndRecoverySignaturesWithoutPlatformFixture() throws Exception {
        KeyPair operational=pair(),recovery=pair();TrustSet trust=TrustFixtures.trust(operational.getPublic(),recovery.getPublic());
        var manifest=Seed.JSON.createObjectNode().put("schemaVersion",1).put("channel","stable").put("sequence",1).put("toolVersion","1.1.0").put("minimumBootstrapProtocol",4);
        var row=manifest.putArray("platforms").addObject();row.putObject("host").put("os","linux").put("arch","x86_64");
        var payload=row.putObject("payload").put("id","tool").put("role","tool-payload").put("url","https://fixture.invalid/tool.zip").put("bytes",1).put("sha256","a".repeat(64)).put("unpackedBytes",1).put("format","zip").put("inventoryPath","inventory.json").put("inventorySha256","b".repeat(64));
        payload.putArray("licenseFiles").add("LICENSE");payload.putArray("noticeFiles");payload.putArray("sourceArchives");row.putObject("runtimeDescriptor").put("path","descriptor.json").put("sha256","c".repeat(64));
        var descriptor=Seed.JSON.createObjectNode().put("schemaVersion",1).put("toolVersion","1.1.0");descriptor.set("host",row.get("host"));descriptor.putObject("helper").put("protocolVersion",1);
        byte[] definition=Seed.JSON.writeValueAsBytes(descriptor),inventory={1};((com.fasterxml.jackson.databind.node.ObjectNode)row.get("runtimeDescriptor")).put("sha256",Seed.hash(definition));payload.put("inventorySha256",Seed.hash(inventory));
        Path slot=directory.resolve("slot");Files.createDirectory(slot);Files.write(slot.resolve("descriptor.json"),definition);Files.write(slot.resolve("inventory.json"),inventory);
        for(String path:List.of("java/bin/java","app/lib/cli-1.1.0.jar","app/lib/core-1.1.0.jar","app/lib/worker-1.1.0.jar","app/worker/worker-agent.jar","LICENSE")){Path target=slot.resolve(path);Files.createDirectories(target.getParent());Files.write(target,new byte[]{1});}
        byte[] raw=Seed.JSON.writeValueAsBytes(manifest);Files.write(directory.resolve("stable.json"),raw);
        for(KeyPair signer:List.of(operational,recovery)){
            Files.write(directory.resolve("stable.json.sig"),sign(raw,signer));Seed seed=new Seed(directory,trust,new Seed.Host("linux","x86_64"));assertEquals("1.1.0",seed.version);
            for(KeyPair descriptorSigner:List.of(operational,recovery)){Files.write(slot.resolve("descriptor.json.sig"),sign(definition,descriptorSigner));seed.verifyContents(slot);}
            Files.write(slot.resolve("descriptor.json.sig"),sign(definition,pair()));assertThrows(IOException.class,()->seed.verifyContents(slot));
        }
        Files.write(directory.resolve("stable.json.sig"),sign(raw,pair()));assertThrows(IOException.class,()->new Seed(directory,trust,new Seed.Host("linux","x86_64")));
        manifest.put("minimumBootstrapProtocol",2);raw=Seed.JSON.writeValueAsBytes(manifest);Files.write(directory.resolve("stable.json"),raw);Files.write(directory.resolve("stable.json.sig"),sign(raw,operational));
        assertThrows(IOException.class,()->new Seed(directory,trust,new Seed.Host("linux","x86_64")));
    }
}
