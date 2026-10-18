package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HelperCommonTest {
    @TempDir Path root;
    static JsonNode examples() throws Exception {return HelperProtocol.read(Files.readAllBytes(Path.of("..","..","schemas","managed-helper-v1.examples.json")));}
    @Test void fixedBytesReceiptAndControlMatchCoreFixtures() throws Exception {
        var example=examples();var receipt=example.get("receipt");assertEquals(9007199254740993L,receipt.path("identity").path("pid").longValue());
        assertEquals(receipt.path("sha256").asText(),new String(LeaseFile.receipt((ObjectNode)receipt.get("identity")),StandardCharsets.US_ASCII));
        for(var item:example.get("control")) {
            List<HelperProtocol.Diagnostic> diagnostics=new ArrayList<>();for(var value:item.path("response").path("diagnostics"))diagnostics.add(new HelperProtocol.Diagnostic(value.path("code").asText(),value.path("detail").asText(),value.path("requiresPrivilege").booleanValue(),value.path("requiresRestart").booleanValue()));
            String status=item.path("response").path("status").asText();assertEquals(item.path("exitCode").intValue(),HelperProtocol.exitCode(status));assertEquals(item.get("response"),HelperProtocol.read(HelperProtocol.response(status,diagnostics)));
        }
    }
    @Test void exactArgvPreservesSpacesAndRejectsProtocolAndShellShapes() throws Exception {
        String path=root.resolve("空白 root").toString(),lease=root.resolve("job/lease.json").toString();
        var command=HelperProtocol.parse(new String[]{"--protocol","1","--root",path,"--lease",lease,"docker","--","run","-v",path+":/cache","image"});assertEquals(path+":/cache",command.dockerArguments().get(2));
        for(String[] args:List.of(new String[]{"--protocol","2","--root",path,"--lease",lease,"prepare"},new String[]{"--protocol","1","--root","relative","--lease",lease,"prepare"},new String[]{"--protocol","1","--root",path,"--lease",lease,"docker","--"},new String[]{"--protocol","1","--root",path,"--lease",lease,"release","extra"}))assertThrows(IOException.class,()->HelperProtocol.parse(args));
    }
    @Test void resourcesAndSchemaUseTheSameClosedFixtures() throws Exception {
        for(var item:examples().get("resourceExamples")) {
            if(item.path("helperValid").booleanValue())assertDoesNotThrow(()->ResourceRecords.validate(item.get("resources"),item.path("os").asText()),item.path("name").asText());
            else assertThrows(IOException.class,()->ResourceRecords.validate(item.get("resources"),item.path("os").asText()),item.path("name").asText());
        }
    }
    @Test void activeCoreLockDoesNotCoverReceiptAndHelperOnlyChangesResources() throws Exception {
        Fixture fixture=new Fixture();
        try(var channel=FileChannel.open(fixture.file.resolveSibling("lease.lock"),StandardOpenOption.WRITE);var active=channel.lock(64,1,false)) {
            LeaseFile lease=fixture.open();ObjectNode before=lease.read(),resources=fixture.starting();lease.replaceResources((ObjectNode)before.get("resources"),resources);
            ObjectNode after=lease.read();assertEquals("active",after.path("state").asText());after.set("resources",before.get("resources"));assertEquals(before,after);
            assertThrows(IOException.class,()->lease.replaceResources(HelperProtocol.JSON.createObjectNode(),resources));
            ObjectNode illegal=resources.deepCopy();illegal.put("state","released");assertThrows(IOException.class,()->lease.replaceResources(resources,illegal));
        }
    }
    @Test void alreadyInterruptedUpdateCannotCommitWithFreeLock() throws Exception {
        Fixture fixture=new Fixture();LeaseFile lease=fixture.open();byte[] before=Files.readAllBytes(fixture.file);var replacement=fixture.starting();
        try {
            Thread.currentThread().interrupt();assertThrows(java.io.InterruptedIOException.class,()->lease.replaceResources(HelperProtocol.JSON.createObjectNode(),replacement));assertTrue(Thread.currentThread().isInterrupted());
        }finally {Thread.interrupted();}
        assertArrayEquals(before,Files.readAllBytes(fixture.file));
    }
    @Test void interruptedBusyUpdateCannotCommitAndDoesNotWaitForOwner() throws Exception {
        Fixture fixture=new Fixture();LeaseFile lease=fixture.open();byte[] before=Files.readAllBytes(fixture.file);var replacement=fixture.starting();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var started=new CountDownLatch(1);
        try(var channel=FileChannel.open(fixture.file.getParent().resolve("tmp/helper-lease.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var held=channel.lock(0,1,false)) {
            Thread thread=Thread.ofPlatform().daemon().start(()->{started.countDown();try{lease.replaceResources(HelperProtocol.JSON.createObjectNode(),replacement);}catch(Throwable cause){failure.set(cause);}});
            try {
                assertTrue(started.await(1,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(thread.isAlive()&&thread.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.sleep(1);
                assertEquals(Thread.State.TIMED_WAITING,thread.getState());thread.interrupt();thread.join(1000);assertFalse(thread.isAlive());assertInstanceOf(java.io.InterruptedIOException.class,failure.get());
            }finally {thread.interrupt();thread.join(1000);}
            assertArrayEquals(before,Files.readAllBytes(fixture.file));
        }
    }
    @Test void competingHelpersCannotLoseAnUpdate() throws Exception {
        Fixture fixture=new Fixture();LeaseFile first=fixture.open(),second=fixture.open();var expected=HelperProtocol.JSON.createObjectNode();var latch=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> update=()->{latch.await();try{first.replaceResources(expected,fixture.starting());return true;}catch(IOException failure){return false;}};
            var a=executor.submit(update);var b=executor.submit(()->{latch.await();try{second.replaceResources(expected,fixture.starting());return true;}catch(IOException failure){return false;}});latch.countDown();assertNotEquals(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS));
        }
        assertFalse(first.read().path("resources").isEmpty());
    }
    @Test void unknownOrChangedIdentityStateAndRequirementsRemainEvidence() throws Exception {
        Fixture fixture=new Fixture();LeaseFile lease=fixture.open();byte[] original=Files.readAllBytes(fixture.file);
        for(String field:List.of("ownerId","state","requirements")) {
            ObjectNode changed=(ObjectNode)HelperProtocol.read(original);changed.remove(field);byte[] evidence=HelperProtocol.JSON.writeValueAsBytes(changed);Files.write(fixture.file,evidence);
            assertThrows(IOException.class,()->lease.replaceResources(HelperProtocol.JSON.createObjectNode(),fixture.starting()));assertArrayEquals(evidence,Files.readAllBytes(fixture.file));Files.write(fixture.file,original);
        }
        ObjectNode changed=(ObjectNode)HelperProtocol.read(original);changed.put("state","released");Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(changed));assertThrows(IOException.class,lease::read);
    }
    @Test void receiptsAreExactly64BytesAndDescriptorInventoryAndHelperMustMatch() throws Exception {
        Fixture fixture=new Fixture();Path receipt=fixture.file.resolveSibling("lease.lock");byte[] original=Files.readAllBytes(receipt);
        for(byte[] invalid:List.of(new byte[0],new byte[63],new byte[65],"0".repeat(64).getBytes(StandardCharsets.US_ASCII))) {Files.write(receipt,invalid);assertThrows(IOException.class,fixture::open);}Files.write(receipt,original);
        Path descriptor=fixture.file.resolveSibling("descriptor.json");byte[] definition=Files.readAllBytes(descriptor);Files.writeString(descriptor,"{}");assertThrows(IOException.class,fixture::open);Files.write(descriptor,definition);
        Path inventory=fixture.file.resolveSibling("inventory/helper.json");Files.writeString(inventory,"corrupt");assertThrows(IOException.class,fixture::open);Files.writeString(inventory,"{}");assertThrows(IOException.class,()->LeaseFile.open(fixture.command,root.resolve("foreign-helper")));
    }
    @Test void descriptorSnapshotPreservesVerifiedContentWithoutExposingInternalState() throws Exception {
        Fixture fixture=new Fixture();LeaseFile lease=fixture.open();JsonNode descriptor=lease.descriptor();
        byte[] original=Files.readAllBytes(fixture.file.resolveSibling("descriptor.json"));assertEquals(lease.read().path("descriptorSha256").asText(),hash(original));assertArrayEquals(original,HelperProtocol.JSON.writeValueAsBytes(descriptor));
        assertEquals("15",descriptor.path("minimumOsVersion").asText());assertEquals("aarch64",descriptor.path("host").path("arch").asText());
        ((ObjectNode)descriptor.get("host")).put("arch","foreign");((ObjectNode)descriptor.get("rendering")).put("imageId","foreign");
        assertArrayEquals(original,HelperProtocol.JSON.writeValueAsBytes(lease.descriptor()));assertArrayEquals(original,Files.readAllBytes(fixture.file.resolveSibling("descriptor.json")));
    }
    @Test void duplicateOrTrailingJsonIsRejectedAndArtifactValidationDoesNotDownload() throws Exception {
        assertThrows(IOException.class,()->HelperProtocol.read("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8)));assertThrows(IOException.class,()->HelperProtocol.read("{} {}".getBytes(StandardCharsets.UTF_8)));
        Fixture fixture=new Fixture();LeaseFile lease=fixture.open();assertThrows(IOException.class,()->lease.verifiedArtifact("renderer-image"));assertFalse(Files.exists(fixture.image));
        Files.createDirectories(fixture.image.getParent());Files.write(fixture.image,new byte[]{1,2,3});assertEquals(3,lease.verifiedArtifact("renderer-image").bytes());Files.write(fixture.image,new byte[]{3,2,1});assertThrows(IOException.class,()->lease.verifiedArtifact("renderer-image"));assertArrayEquals(new byte[]{3,2,1},Files.readAllBytes(fixture.image));
    }
    final class Fixture {
        final Path base=Files.createTempDirectory(root,"fixture-").toRealPath(),file,executable,image;final HelperProtocol.Command command;
        Fixture() throws Exception {
            ObjectNode identity=((ObjectNode)examples().path("receipt").get("identity")).deepCopy();Files.writeString(base.resolve("host-id"),identity.path("hostId").asText());Path job=base.resolve("jobs").resolve(identity.path("jobId").asText());Files.createDirectories(job.resolve("tmp"));Files.createDirectory(job.resolve("inventory"));
            ObjectNode descriptor=HelperProtocol.JSON.createObjectNode().put("schemaVersion",1).put("runtimeId","fixture").put("toolVersion","1.1.0").put("minimumOsVersion","15");descriptor.putObject("host").put("os","macos").put("arch","aarch64");descriptor.putObject("worker").put("toolVersion","1.1.0").put("protocolVersion",1).put("agentSha256",identity.path("workerSha256").asText()).put("adapterSetSha256","a".repeat(64));descriptor.putObject("helper").put("protocolVersion",1).put("artifactId","helper").put("executable","bin/helper");descriptor.putObject("rendering");var artifacts=descriptor.putArray("artifacts");
            for(String id:List.of("helper","image")) {
                String digest=id.equals("helper")?"1".repeat(64):hash(new byte[]{1,2,3});var artifact=artifacts.addObject().put("id",id).put("role",id.equals("helper")?"host-helper":"renderer-image").put("url","https://fixture.invalid/"+id).put("bytes",3).put("sha256",digest).put("unpackedBytes",3).put("format","zip").put("inventoryPath","inventory/"+id+".json").put("inventorySha256",hash("{}".getBytes(StandardCharsets.UTF_8)));artifact.putArray("licenseFiles").add("LICENSE");artifact.putArray("noticeFiles");artifact.putArray("sourceArchives");Files.writeString(job.resolve("inventory/"+id+".json"),"{}");
            }
            byte[] definition=HelperProtocol.JSON.writeValueAsBytes(descriptor);identity.put("descriptorSha256",hash(definition));Files.write(job.resolve("descriptor.json"),definition);file=job.resolve("lease.json");ObjectNode lease=identity.deepCopy().put("state","active");lease.putObject("resources");Files.write(file,HelperProtocol.JSON.writeValueAsBytes(lease));Files.write(job.resolve("lease.lock"),LeaseFile.receipt(identity));executable=base.resolve("helpers/macos-aarch64/"+"1".repeat(64)+"/bin/helper");Files.createDirectories(executable.getParent());Files.writeString(executable,"test-only");image=base.resolve("runtimes/fixture/"+identity.path("descriptorSha256").asText()+"/image.zip");command=new HelperProtocol.Command(base,file,"preflight",List.of());
        }
        LeaseFile open() throws Exception {return LeaseFile.open(command,executable);}
        ObjectNode starting() {var resources=HelperProtocol.JSON.createObjectNode();resources.putObject("macos").put("phase","starting").put("generationOwner",UUID.randomUUID().toString()).put("instanceDir",base.resolve("instance").toString()).putArray("containers");return resources;}
    }
    static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
