package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.*;
import io.github.rs0325.mcicon.internal.FilesSupport;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeResourceGeneratorTest {
    @TempDir Path root;
    @Test void emitsPinnedOriginalBytesWithoutGeneratedClasspathResources() throws Exception {
        Input input=new Input();Path output=input.generate();Path resources=output.resolve("META-INF/mcicon/runtime");
        assertArrayEquals(Files.readAllBytes(input.dir.resolve("descriptor.json")),Files.readAllBytes(resources.resolve("descriptor.json")));
        assertArrayEquals(Files.readAllBytes(input.dir.resolve("descriptor.json.sig")),Files.readAllBytes(resources.resolve("descriptor.json.sig")));
        assertEquals(input.definition.path("runtimes"),RuntimeDescriptor.read(Files.readAllBytes(resources.resolve("pins.json"))).path("runtimes"));
        assertFalse(Files.exists(resources.resolve("artifacts")));assertFalse(Files.exists(resources.resolve("input.json")));
    }
    @Test void rejectsUnadoptedBytes() throws Exception {Input input=new Input();input.definition.put("finalBytesConfirmed",false);input.save();assertThrows(Exception.class,input::generate);assertFalse(Files.exists(input.output));}
    @Test void rejectsInvalidSignature() throws Exception {Input input=new Input();Files.write(input.dir.resolve("descriptor.json.sig"),new byte[64]);assertThrows(Exception.class,input::generate);}
    @Test void rejectsUntrustedKey() throws Exception {Input input=new Input();Files.write(input.dir.resolve("trust.der"),KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());assertThrows(Exception.class,input::generate);}
    @Test void rejectsAgentMismatch() throws Exception {Input input=new Input();Files.write(input.agent,new byte[]{2});assertThrows(Exception.class,input::generate);}
    @Test void rejectsAdapterMismatch() throws Exception {Input input=new Input();Files.writeString(input.adapters.resolve("adapters/releases.json"),"changed");assertThrows(Exception.class,input::generate);}
    @Test void rejectsToolMismatch() throws Exception {Input input=new Input();assertThrows(Exception.class,()->RuntimeResourceGenerator.generate(input.dir,input.output,"1.2.0",input.agent,input.adapters));}
    @Test void rejectsMissingInventory() throws Exception {Input input=new Input();Files.delete(input.dir.resolve("inventory/helper.json"));assertThrows(Exception.class,input::generate);}
    @Test void rejectsInventoryCorruption() throws Exception {Input input=new Input();Files.writeString(input.dir.resolve("inventory/helper.json"),"changed");assertThrows(Exception.class,input::generate);}
    @Test void rejectsDuplicateResourcePlacement() throws Exception {Input input=new Input();((ObjectNode)input.fixture.json.path("artifacts").get(1)).put("inventoryPath","inventory/helper.json");input.publish();assertThrows(Exception.class,input::generate);}
    @Test void rejectsNullUrlBeforeGenerating() throws Exception {Input input=new Input();((ObjectNode)input.fixture.json.path("artifacts").get(0)).putNull("url");input.publish();assertThrows(Exception.class,input::generate);assertFalse(Files.exists(input.output));}
    @Test void rejectsArchiveCorruption() throws Exception {Input input=new Input();Files.write(input.dir.resolve("artifacts/helper.zip"),new byte[]{9});assertThrows(Exception.class,input::generate);}
    @Test void rejectsHelperTraversalThroughExistingArchiveValidator() throws Exception {Input input=new Input();input.helper("../escape");input.publish();assertThrows(Exception.class,input::generate);assertFalse(Files.exists(root.resolve("escape")));}
    @Test void rejectsDuplicateHosts() throws Exception {Input input=new Input();((ArrayNode)input.definition.get("runtimes")).add(input.definition.path("runtimes").get(0).deepCopy());input.save();assertThrows(Exception.class,input::generate);}
    final class Input {
        final Path dir=Files.createDirectory(root.resolve(UUID.randomUUID().toString())),output=root.resolve(UUID.randomUUID().toString()),agent=dir.resolve("agent.jar"),adapters=Files.createDirectory(dir.resolve("adapter-root"));
        final ManagedFixture fixture=new ManagedFixture();final ObjectNode definition=RuntimeDescriptor.JSON.createObjectNode();
        Input() throws Exception {
            Files.write(agent,new byte[]{1});Files.createDirectory(adapters.resolve("adapters"));Files.writeString(adapters.resolve("adapters/legacy-mappings.json"),"{}");Files.writeString(adapters.resolve("adapters/releases.json"),"{}");
            ((ObjectNode)fixture.json.get("host")).put("os","macos").put("arch","aarch64");fixture.json.put("minimumOsVersion","15.7.7");
            String adapterHash;try(var loader=new java.net.URLClassLoader(new java.net.URL[]{adapters.toUri().toURL()},null)){adapterHash=ManagedRuntime.adapterDigest(loader);}
            ((ObjectNode)fixture.json.get("worker")).put("agentSha256",FilesSupport.hash(new byte[]{1})).put("adapterSetSha256",adapterHash);
            ArrayNode java=((ObjectNode)fixture.json.get("rendering")).putArray("javaExecutables");for(int major:List.of(8,17,21,25))java.addObject().put("major",major).put("executable","/opt/java/java"+major+"/bin/java").put("sha256",ManagedFixture.HASH);
            Files.createDirectory(dir.resolve("inventory"));Files.createDirectory(dir.resolve("artifacts"));Files.write(dir.resolve("trust.der"),fixture.key.getPublic().getEncoded());
            for(var artifact:fixture.json.path("artifacts")){String id=artifact.path("id").asText();byte[] inventory="{}".getBytes();Files.write(dir.resolve("inventory/"+id+".json"),inventory);((ObjectNode)artifact).put("inventorySha256",FilesSupport.hash(inventory));}
            helper("bin/helper");byte[] image={7};Files.write(dir.resolve("artifacts/image.zip"),image);((ObjectNode)fixture.json.path("artifacts").get(1)).put("bytes",image.length).put("sha256",FilesSupport.hash(image));
            definition.put("schemaVersion",1).put("finalBytesConfirmed",true);definition.putArray("runtimes").addObject().set("host",fixture.json.get("host").deepCopy());publish();
        }
        void helper(String name) throws Exception {Path path=dir.resolve("artifacts/helper.zip");try(var zip=new ZipArchiveOutputStream(path)){var entry=new ZipArchiveEntry(name);entry.setUnixMode(0100700);zip.putArchiveEntry(entry);zip.write(new byte[]{1});zip.closeArchiveEntry();}((ObjectNode)fixture.json.path("artifacts").get(0)).put("bytes",Files.size(path)).put("sha256",FilesSupport.hash(path,"SHA-256"));}
        void publish() throws Exception {byte[] bytes=fixture.bytes();Files.write(dir.resolve("descriptor.json"),bytes);Files.write(dir.resolve("descriptor.json.sig"),fixture.sign(bytes));((ObjectNode)definition.path("runtimes").get(0)).put("descriptorPath","descriptor.json").put("descriptorSha256",FilesSupport.hash(bytes)).put("trustKeyPath","trust.der");save();}
        void save() throws Exception {Files.write(dir.resolve("input.json"),RuntimeDescriptor.JSON.writeValueAsBytes(definition));}
        Path generate() throws Exception {RuntimeResourceGenerator.generate(dir,output,"1.1.0",agent,adapters);return output;}
    }
}
