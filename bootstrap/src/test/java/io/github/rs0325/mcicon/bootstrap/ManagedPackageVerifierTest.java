package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedPackageVerifierTest {
    @TempDir Path root;
    @Test void signedSeedAndCoreUseIdenticalDescriptorSignatureAndTrust() throws Exception {Input input=new Input();input.verify();}
    @Test void rejectsPackageTrustDifferentFromRuntimeTrust() throws Exception {Input input=new Input();Path other=root.resolve("other.der");Files.write(other,KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());assertThrows(Exception.class,()->ManagedPackageVerifier.verify(input.resources,input.fixture.installed.resolve("seed"),other,"1.1.0",input.currentCore));}
    @Test void rejectsCoreResourceMismatch() throws Exception {Input input=new Input();input.body.put("META-INF/mcicon/runtime/inventory/helper.json",new byte[]{9});input.publish();assertThrows(Exception.class,input::verify);}
    @Test void rejectsCoreResourceMissing() throws Exception {Input input=new Input();input.body.remove("META-INF/mcicon/runtime/inventory/helper.json");input.publish();assertThrows(Exception.class,input::verify);}
    @Test void rejectsRuntimeResourcesInAnotherJar() throws Exception {Input input=new Input();input.fixture.files.put("app/lib/extra.jar",input.jar(input.body));input.fixture.publish();assertThrows(Exception.class,input::verify);}
    @Test void rejectsSeedDescriptorDifferentFromCoreEvenWithValidSignature() throws Exception {Input input=new Input();input.fixture.descriptor.put("fixtureExtra",true);input.fixture.publish();assertThrows(Exception.class,input::verify);}
    @Test void rejectsPayloadAgentMismatch() throws Exception {Input input=new Input();input.fixture.files.put("app/worker/worker-agent.jar",new byte[]{9});input.fixture.publish();assertThrows(Exception.class,input::verify);}
    @Test void rejectsVersionMismatch() throws Exception {Input input=new Input();assertThrows(Exception.class,()->ManagedPackageVerifier.verify(input.resources,input.fixture.installed.resolve("seed"),input.fixture.installed.resolve("trust.der"),"1.2.0",input.currentCore));}
    @Test void rejectsOtherCoreBodyWithIdenticalRuntimeResources() throws Exception {Input input=new Input();input.body.put("old-code.class",new byte[]{1});input.publish();assertThrows(Exception.class,input::verify);}
    final class Input {
        final Fixture fixture=new Fixture(root);final Path resources=Files.createDirectory(root.resolve("resources"));final Map<String,byte[]> body=new TreeMap<>();final Path currentCore=root.resolve("current-core.jar");
        Input() throws Exception {
            fixture.descriptor.putObject("worker").put("agentSha256",Seed.hash(new byte[]{1}));fixture.publish();
            ObjectNode pins=Seed.JSON.createObjectNode().put("schemaVersion",1);pins.putArray("runtimes").addObject().put("descriptorPath","descriptor.json").put("descriptorSha256",Seed.hash(Files.readAllBytes(fixture.installed.resolve("seed/stable.json"))));
            ObjectNode entry=(ObjectNode)pins.path("runtimes").get(0);entry.set("host",Seed.JSON.valueToTree(fixture.host));entry.put("descriptorSha256",Seed.hash(Seed.JSON.writeValueAsBytes(fixture.descriptor))).put("trustKeyPath","trust.der");
            body.put("META-INF/mcicon/runtime/pins.json",Seed.JSON.writeValueAsBytes(pins));body.put("META-INF/mcicon/runtime/descriptor.json",Seed.JSON.writeValueAsBytes(fixture.descriptor));body.put("META-INF/mcicon/runtime/descriptor.json.sig",fixture.sign(body.get("META-INF/mcicon/runtime/descriptor.json")));body.put("META-INF/mcicon/runtime/trust.der",fixture.publicKey.getEncoded());body.put("META-INF/mcicon/runtime/inventory/helper.json",new byte[]{1});
            for(var entryBytes:body.entrySet()){Path path=resources.resolve(entryBytes.getKey());Files.createDirectories(path.getParent());Files.write(path,entryBytes.getValue());}
            fixture.files.put("app/lib/worker-1.1.0.jar",jar(Map.of()));publish();Files.write(currentCore,fixture.files.get("app/lib/core-1.1.0.jar"));
        }
        byte[] jar(Map<String,byte[]> entries) throws Exception {var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes)){for(var entry:entries.entrySet()){jar.putNextEntry(new JarEntry(entry.getKey()));jar.write(entry.getValue());jar.closeEntry();}}return bytes.toByteArray();}
        void publish() throws Exception {fixture.files.put("app/lib/core-1.1.0.jar",jar(body));fixture.publish();}
        void verify() throws Exception {ManagedPackageVerifier.verify(resources,fixture.installed.resolve("seed"),fixture.installed.resolve("trust.der"),"1.1.0",currentCore);}
    }
}
