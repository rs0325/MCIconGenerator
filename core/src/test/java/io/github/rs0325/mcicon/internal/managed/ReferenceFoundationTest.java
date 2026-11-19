package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceFoundationTest {
    @TempDir Path directory;
    @Test void 署名inventoryに結合した能力だけを認証し置換と不一致を拒否する() throws Exception {
        var source=new ManagedFixture();Path root=directory.toRealPath();
        byte[] contract=RuntimeDescriptor.JSON.writeValueAsBytes(ReferenceContract.declaration());
        Path helper=root.resolve("helpers/linux-x86_64/"+ManagedFixture.HASH),executable=helper.resolve("bin/helper"),capability=helper.resolve("support/reference-contract.json");
        Files.createDirectories(executable.getParent());Files.createDirectories(capability.getParent());Files.writeString(executable,"fixture");Files.write(capability,contract);
        ObjectNode listing=RuntimeDescriptor.JSON.createObjectNode();var files=listing.putObject("contents").putArray("files");
        for(Path file:List.of(executable,capability))files.addObject().put("path",helper.relativize(file).toString().replace('\\','/')).put("bytes",Files.size(file)).put("sha256",FilesSupport.hash(Files.readAllBytes(file)));
        byte[] inventory=RuntimeDescriptor.JSON.writeValueAsBytes(listing);
        for(var artifact:source.json.path("artifacts"))((ObjectNode)artifact).put("inventorySha256",FilesSupport.hash(inventory));
        byte[] descriptor=source.bytes();LeaseTrust.publish(root,descriptor,source.sign(descriptor),source.trust);
        LeaseTrust trust=IdentityFixtures.trust(source.trust);
        assertThrows(IOException.class,()->ReferenceContract.authenticate(trust,"support/reference-contract.json"));
        trust.bindReader(root,descriptor,inventory,executable);
        ReferenceContract.authenticate(trust,"support/reference-contract.json");
        String originalKey=trust.fileIdentity(capability);Files.writeString(capability,new String(contract,java.nio.charset.StandardCharsets.UTF_8).replace("\"maximumGenerations\":1","\"maximumGenerations\":2"));
        assertEquals(originalKey,trust.fileIdentity(capability));assertThrows(IOException.class,()->ReferenceContract.authenticate(trust,"support/reference-contract.json"));
        Files.write(capability,contract);ReferenceContract.authenticate(trust,"support/reference-contract.json");
        byte[] changed=descriptor.clone();changed[changed.length-1]=' ';
        assertThrows(IOException.class,()->trust.bindReader(root,changed,inventory,executable));
        assertThrows(IOException.class,()->trust.bindReader(root,descriptor,"{}".getBytes(),executable));
    }
    @Test void 稼働集合が欠落または破損していても既存job公開とcloseは参加必須化しない() throws Exception {
        var fixture=new WindowsAdmissionTest.Fixture(directory);
        Path state=fixture.store.root().resolve("logs/active-references-v1/state.json");
        assertFalse(Files.exists(state));
        try(var lease=fixture.oldJob()) {
            assertFalse(Files.exists(lease.file().resolveSibling("tmp/active-reference-v1.json")));
            LeaseSnapshot snapshot=LeaseSnapshot.read(fixture.store.root(),lease.file(),fixture.trust,lease.receiptChannel());
            assertEquals(fixture.old.digest(),ReferenceGeneration.of(snapshot).path("descriptorSha256").asText());
            Files.createDirectories(state.getParent());Files.writeString(state,"corrupt");
            lease.released();assertEquals("released",RuntimeDescriptor.read(Files.readAllBytes(lease.file())).path("state").asText());
        }
        try(var lease=fixture.oldJob()){lease.released();}
        assertEquals("corrupt",Files.readString(state));
    }
}
