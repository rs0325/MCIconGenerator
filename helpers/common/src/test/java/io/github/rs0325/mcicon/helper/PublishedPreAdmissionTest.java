package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 公開旧bytesを参照するprotocol fixture。旧helper/WSLを起動せず、公開実更新を受入しない。 */
class PublishedPreAdmissionTest {
    @TempDir Path temporary;
    @Test void 保存済み公開前証拠の参照外へUNKNOWNが増えたら全入口で拒否する() throws Exception {
        String input=System.getProperty("mcicon.publishedDescriptors");Assumptions.assumeTrue(input!=null);Path published=Path.of(input),archive=published.resolve("mcicon-helper-1.1.1-windows-x86_64.zip");Assumptions.assumeTrue(Files.isRegularFile(archive),"明示した公開旧helper入力が必要");
        byte[] definition=Files.readAllBytes(published.resolve("1.1.1-windows-x86_64.json"));JsonNode descriptor=HelperProtocol.read(definition);String digest=HelperCommonTest.hash(definition);assertTrue(LeaseTrust.legacy(digest));
        byte[] trustBytes=Files.readAllBytes(published.resolve("public-trust-set.bin"));assertEquals("8529ace9f49e4b46c915e51d0cad5b576e6a9c536afe665ec779dd279ed51283",HelperCommonTest.hash(trustBytes));TrustSet keys=TrustSet.parse(trustBytes);keys.verify(definition,Files.readAllBytes(published.resolve("1.1.1-windows-x86_64.json.sig")));LeaseTrust trust=IdentityFixtures.trust(keys);
        Path root=temporary.toRealPath();Files.createDirectory(root.resolve("jobs"));Files.createDirectory(root.resolve("locks"));Files.write(root.resolve("locks/generations.lock"),new byte[0]);String host=UUID.randomUUID().toString();Files.writeString(root.resolve("host-id"),host);
        ObjectNode lease=((ObjectNode)HelperCommonTest.examples().path("receipt").path("identity")).deepCopy();lease.put("hostId",host).put("jobId",UUID.randomUUID().toString()).put("ownerId",UUID.randomUUID().toString()).put("descriptorSha256",digest).put("runtimeId",descriptor.path("runtimeId").asText()).put("toolVersion",descriptor.path("toolVersion").asText()).put("workerSha256",descriptor.path("worker").path("agentSha256").asText()).put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("state","active");lease.putObject("resources");
        JsonNode helper=null;for(JsonNode artifact:descriptor.path("artifacts"))if(artifact.path("role").asText().equals("host-helper"))helper=artifact;assertNotNull(helper);assertEquals(helper.path("bytes").asLong(),Files.size(archive));assertEquals(helper.path("sha256").asText(),HelperCommonTest.hash(Files.readAllBytes(archive)));
        byte[] inventory=Files.readAllBytes(published.resolve(helper.path("inventoryPath").asText()));assertEquals(helper.path("inventorySha256").asText(),HelperCommonTest.hash(inventory));Path helperRoot=root.resolve("helpers/windows-x86_64/"+helper.path("sha256").asText());
        try(ZipFile zip=new ZipFile(archive.toFile())){for(JsonNode row:HelperProtocol.read(inventory).path("contents").path("files")){String path=row.path("path").asText();if(!path.equals(descriptor.path("helper").path("executable").asText())&&!path.endsWith(".jar"))continue;Path file=helperRoot.resolve(path).normalize();assertTrue(file.startsWith(helperRoot));var entry=zip.getEntry(path);assertNotNull(entry,path);byte[] bytes;try(var stream=zip.getInputStream(entry)){bytes=stream.readAllBytes();}assertEquals(row.path("bytes").asLong(),bytes.length);assertEquals(row.path("sha256").asText(),HelperCommonTest.hash(bytes));Files.createDirectories(file.getParent());Files.write(file,bytes);}}
        Path old=job(root,lease,definition,descriptor,published);
        try(var channel=FileChannel.open(old.resolve("lease.lock"),StandardOpenOption.WRITE);var held=channel.lock(64,1,false)) {
            String attempt=UUID.randomUUID().toString(),candidate="f".repeat(64),started=lease.path("processStartedAt").asText();long pid=ProcessHandle.current().pid();
            ObjectNode value=HelperProtocol.JSON.createObjectNode().put("schemaVersion",1).put("kind","pre-admission").put("cause","LEGACY_READER_ACTIVE").put("attemptId",attempt).put("root",root.toString()).put("hostId",host).put("pid",pid).put("processStartedAt",started).put("descriptorSha256",candidate);value.putArray("references").add(RuntimeMaintenanceVerifier.proof(LeaseSnapshot.read(root,old.resolve("lease.json"),trust)));
            Path file=AdmissionEvidence.preAdmission(root,attempt);AdmissionEvidence.save(file,value,trust);JsonNode reference=AdmissionEvidence.reference(root,attempt);byte[] saved=Files.readAllBytes(file);
            assertNotNull(AdmissionEvidence.verifyPreAdmission(root,reference,candidate,pid,started,trust));
            ObjectNode extra=lease.deepCopy().put("jobId",UUID.randomUUID().toString()).put("ownerId",UUID.randomUUID().toString()).put("processStartedAt",Instant.EPOCH.toString());Path later=job(root,extra,definition,descriptor,published);
            assertThrows(IOException.class,()->AdmissionEvidence.verifyPreAdmission(root,reference,candidate,pid,started,trust));assertArrayEquals(saved,Files.readAllBytes(file));
            extra.put("state","released");Files.write(later.resolve("lease.json"),HelperProtocol.JSON.writeValueAsBytes(extra));assertNotNull(AdmissionEvidence.verifyPreAdmission(root,reference,candidate,pid,started,trust));
            Files.writeString(later.resolve("tmp/native-"+UUID.randomUUID()+".intent.json"),"{}");assertThrows(IOException.class,()->AdmissionEvidence.verifyPreAdmission(root,reference,candidate,pid,started,trust));assertArrayEquals(saved,Files.readAllBytes(file));
        }
    }
    private static Path job(Path root,ObjectNode lease,byte[] descriptor,JsonNode definition,Path published) throws Exception {
        Path directory=root.resolve("jobs/"+lease.path("jobId").asText());Files.createDirectories(directory.resolve("tmp"));Files.write(directory.resolve("descriptor.json"),descriptor);
        for(JsonNode artifact:definition.path("artifacts")){Path path=Path.of(artifact.path("inventoryPath").asText());byte[] bytes=Files.readAllBytes(published.resolve(path));assertEquals(artifact.path("inventorySha256").asText(),HelperCommonTest.hash(bytes));Files.createDirectories(directory.resolve(path).getParent());Files.write(directory.resolve(path),bytes);}
        ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(directory.resolve("lease.lock"),LeaseFile.receipt(identity));Files.write(directory.resolve("lease.json"),HelperProtocol.JSON.writeValueAsBytes(lease));return directory;
    }
}
