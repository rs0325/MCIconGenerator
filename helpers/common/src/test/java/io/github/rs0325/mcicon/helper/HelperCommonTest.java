package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.KeyPairGenerator;
import java.security.Signature;
import io.github.rs0325.mcicon.internal.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HelperCommonTest {
    @TempDir Path root;
    @Test void 公開旧版r1のfile表も元署名とJARのpinを照合する() throws Exception {
        Fixture fixture=new Fixture();ObjectNode inventory=(ObjectNode)HelperProtocol.read(fixture.inventory("helper"));
        ObjectNode contents=(ObjectNode)inventory.get("contents");JsonNode files=contents.remove("files");contents.putObject("r1").set("files",files);
        Path jar=fixture.executable.getParent().resolve("pinned.jar");Files.writeString(jar,"pinned-jar");
        ((ArrayNode)files).addObject().put("path","bin/pinned.jar").put("bytes",Files.size(jar)).put("sha256",hash(Files.readAllBytes(jar)));
        fixture.rebindInventory(inventory);LeaseSnapshot snapshot=LeaseFile.inspect(fixture.base,fixture.file,fixture.trust);
        assertEquals(fixture.executable,snapshot.helperExecutable());assertEquals(jar,snapshot.helperFile("bin/pinned.jar"));snapshot.verifyUnchanged();
        Files.writeString(jar,"broken-jar");assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,fixture.trust));
    }
    @Test void 直接と公開旧版r1のfile表の併存は選択せず拒否する() throws Exception {
        Fixture fixture=new Fixture();ObjectNode inventory=(ObjectNode)HelperProtocol.read(fixture.inventory("helper"));
        ((ObjectNode)inventory.get("contents")).putObject("r1").set("files",inventory.path("contents").path("files").deepCopy());
        fixture.rebindInventory(inventory);assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,fixture.trust));
    }
    @Test void 通常helperの信頼入口もdirectとr1の同じpinを採用する() throws Exception {
        for(boolean r1:List.of(false,true)) {
            Fixture fixture=new Fixture();fixture.packageTrust();
            ObjectNode inventory=(ObjectNode)HelperProtocol.read(fixture.inventory("helper"));
            if(r1){ObjectNode contents=(ObjectNode)inventory.get("contents");contents.putObject("r1").set("files",contents.remove("files"));}
            fixture.rebindInventory(inventory);
            assertNotNull(LeaseFile.trust(fixture.command,fixture.executable));
            assertEquals(fixture.executable,LeaseFile.inspect(fixture.base,fixture.file,fixture.trust).helperExecutable());
            if(!System.getProperty("os.name").startsWith("Windows"))assertNotNull(LeaseFile.open(fixture.command,fixture.executable));
        }
    }
    @Test void 通常helperとsnapshotはfile表の併存欠落重複pin欠落改変を同じく拒否する() throws Exception {
        for(boolean r1:List.of(false,true))for(String fault:List.of("both","absent","not-array","duplicate","duplicate-other","no-pin","bad-pin")) {
            Fixture fixture=new Fixture();fixture.packageTrust();
            ObjectNode inventory=(ObjectNode)HelperProtocol.read(fixture.inventory("helper"));
            ObjectNode contents=(ObjectNode)inventory.get("contents");ArrayNode files=(ArrayNode)contents.get("files");
            if(r1){contents.remove("files");contents.putObject("r1").set("files",files);}
            ObjectNode table=r1?(ObjectNode)contents.get("r1"):contents;
            switch(fault) {
                case "both" -> {if(r1)contents.set("files",files.deepCopy());else contents.putObject("r1").set("files",files.deepCopy());}
                case "absent" -> table.remove("files");
                case "not-array" -> table.put("files","invalid");
                case "duplicate" -> files.add(files.get(0).deepCopy());
                case "duplicate-other" -> {var row=files.addObject().put("path","unrelated").put("bytes",1).put("sha256","a".repeat(64));files.add(row.deepCopy());}
                case "no-pin" -> files.removeAll();
                case "bad-pin" -> ((ObjectNode)files.get(0)).put("sha256","f".repeat(64));
                default -> throw new AssertionError(fault);
            }
            fixture.rebindInventory(inventory);
            assertThrows(IOException.class,()->LeaseFile.trust(fixture.command,fixture.executable),r1+"/"+fault);
            assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,fixture.trust),r1+"/"+fault);
        }
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void Windows通常helperのr1readerPinも実nativeで結合し欠落重複を拒否する() throws Exception {
        Fixture fixture=new Fixture();
        Path reader=Path.of(System.getProperty("mcicon.testIdentityReader"));
        ObjectNode inventory=(ObjectNode)HelperProtocol.read(fixture.inventory("helper"));
        ObjectNode contents=(ObjectNode)inventory.get("contents");ArrayNode files=(ArrayNode)contents.remove("files");contents.putObject("r1").set("files",files);
        Path executable=fixture.base.resolve("helpers/windows-x86_64/"+"1".repeat(64)+"/bin/helper");
        Files.createDirectories(executable.getParent());Files.copy(fixture.executable,executable);
        Path support=executable.getParent().resolve("app/support");Files.createDirectories(support);Files.copy(reader,support.resolve("windows-host.exe"));
        var recovery=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.write(support.resolve("lease-trust.bin"),TrustFixtures.bytes(fixture.pair.getPublic(),recovery.getPublic()));
        ObjectNode descriptor=(ObjectNode)HelperProtocol.read(Files.readAllBytes(fixture.file.resolveSibling("descriptor.json")));
        ((ObjectNode)descriptor.path("host")).put("os","windows").put("arch","x86_64");Files.write(fixture.file.resolveSibling("descriptor.json"),HelperProtocol.JSON.writeValueAsBytes(descriptor));
        ObjectNode pin=files.addObject().put("path","bin/app/support/windows-host.exe").put("bytes",Files.size(reader)).put("sha256",hash(Files.readAllBytes(reader)));
        fixture.rebindInventory(inventory);assertNotNull(LeaseFile.open(fixture.command,executable));
        files.remove(files.size()-1);fixture.rebindInventory(inventory);
        assertThrows(IOException.class,()->LeaseFile.trust(fixture.command,executable));
        files.add(pin);files.add(pin.deepCopy());fixture.rebindInventory(inventory);
        assertThrows(IOException.class,()->LeaseFile.trust(fixture.command,executable));
    }
    @Test void 取得済みchannelで再照合と再生成しても別JVMへactiveLockを渡さない() throws Exception {
        Fixture fixture=new Fixture();Path receipt=fixture.file.resolveSibling("lease.lock");LeaseSnapshot snapshot=LeaseFile.inspect(fixture.base,fixture.file,fixture.trust);
        assertReceiptLock(receipt,"FREE");
        try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);var held=channel.lock(64,1,false)) {
            channel.position(19);assertReceiptLock(receipt,"BUSY");snapshot.verifyUnchanged(channel);assertEquals(19,channel.position());assertReceiptLock(receipt,"BUSY");
            LeaseSnapshot rebuilt=LeaseSnapshot.read(fixture.base,fixture.file,fixture.trust,channel);assertEquals(snapshot.lease(),rebuilt.lease());assertEquals(19,channel.position());assertReceiptLock(receipt,"BUSY");
            ObjectNode cleared=rebuilt.lease();cleared.put("state","released");Path staged=Files.createTempFile(fixture.file.getParent(),"receipt-test-",".json");Files.write(staged,HelperProtocol.JSON.writeValueAsBytes(cleared));Files.move(staged,fixture.file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            LeaseSnapshot released=LeaseSnapshot.read(fixture.base,fixture.file,fixture.trust,channel);released.verifyUnchanged(channel);assertEquals("released",released.lease().path("state").asText());assertReceiptLock(receipt,"BUSY");
        }
        assertReceiptLock(receipt,"FREE");
    }
    @Test void channel再照合がreceipt改変とsize超過を拒否してもactiveLockを保持する() throws Exception {
        Fixture fixture=new Fixture();Path receipt=fixture.file.resolveSibling("lease.lock");LeaseSnapshot snapshot=LeaseFile.inspect(fixture.base,fixture.file,fixture.trust);byte[] original=Files.readAllBytes(receipt);
        try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);var held=channel.lock(64,1,false)) {
            channel.write(ByteBuffer.wrap(new byte[]{(byte)(original[0]=='a'?'b':'a')}),0);
            assertThrows(IOException.class,()->snapshot.verifyUnchanged(channel));assertThrows(IOException.class,()->LeaseSnapshot.read(fixture.base,fixture.file,fixture.trust,channel));assertReceiptLock(receipt,"BUSY");
            channel.write(ByteBuffer.wrap(original),0);channel.write(ByteBuffer.wrap(new byte[]{0}),64);
            assertThrows(IOException.class,()->LeaseSnapshot.read(fixture.base,fixture.file,fixture.trust,channel));assertReceiptLock(receipt,"BUSY");channel.truncate(64);snapshot.verifyUnchanged(channel);
        }
        assertReceiptLock(receipt,"FREE");
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
    void Macでは別FDcloseでactiveLockが外れることを別JVMから検出する() throws Exception {
        Fixture fixture=new Fixture();Path receipt=fixture.file.resolveSibling("lease.lock");
        try(var channel=FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);var held=channel.lock(64,1,false)) {
            assertReceiptLock(receipt,"BUSY");try(var other=FileChannel.open(receipt,StandardOpenOption.READ)){other.read(ByteBuffer.allocate(1),0);}assertReceiptLock(receipt,"FREE");
        }
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
    void bootstrap終端検証のreceipt再照合中も別JVMへprobeLockを渡さない() throws Exception {
        Fixture fixture=new Fixture();ObjectNode lease=(ObjectNode)HelperProtocol.read(Files.readAllBytes(fixture.file));lease.put("state","released");Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(lease));
        boolean[] armed={false},observed={false};Path receipt=fixture.file.resolveSibling("lease.lock"),descriptor=fixture.file.resolveSibling("descriptor.json");
        LeaseTrust trust=LeaseTrust.verified(fixture.trustSet,path->{
            if(armed[0]&&path.equals(descriptor)) {
                try{assertReceiptLock(receipt,"BUSY");observed[0]=true;}catch(Exception failure){throw new IOException("別JVMからのprobeに失敗しました",failure);}
            }
            return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey().toString();
        });
        LeaseSnapshot snapshot=LeaseSnapshot.read(fixture.base,fixture.file,trust);armed[0]=true;
        RuntimeMaintenanceVerifier.requireTerminal(snapshot);assertTrue(observed[0]);assertReceiptLock(receipt,"FREE");observed[0]=false;
        RuntimeMaintenanceVerifier.requireReleased(snapshot);assertTrue(observed[0]);assertReceiptLock(receipt,"FREE");
    }
    private static void assertReceiptLock(Path receipt,String expected) throws Exception {
        String classpath=Path.of(ReceiptProbeProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,ReceiptProbeProcess.class.getName(),receipt.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {assertTrue(child.waitFor(5,TimeUnit.SECONDS),"別JVMのlock照合期限");assertEquals(0,child.exitValue());assertEquals(expected,new String(child.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).trim());}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}child.getInputStream().close();child.getErrorStream().close();child.getOutputStream().close();}
    }
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
    @Test void keeperHasFixedInternalArgumentsWithoutChangingExistingControl() throws Exception {
        String[] args={"--protocol","1","--root",root.toString(),"--lease",root.resolve("lease.json").toString(),"keep","--controller","42","23","--attempt","00000000-0000-0000-0000-000000000001","--deadline","2026-10-21T00:00:00Z","--recovery","false"};
        var parsed=HelperProtocol.parse(args);assertEquals(42,parsed.keeper().pid());assertFalse(parsed.keeper().recovery());assertTrue(parsed.dockerArguments().isEmpty());
        for(int index:List.of(1,7,8,9,10,11,12,13,14,15)){String[] invalid=args.clone();invalid[index]="invalid";assertThrows(IOException.class,()->HelperProtocol.parse(invalid));}
        assertThrows(IOException.class,()->HelperProtocol.parse(Arrays.copyOf(args,args.length-1)));
        for(String operation:List.of("preflight","prepare","release")){String[] old=Arrays.copyOf(args,7);old[6]=operation;assertNull(HelperProtocol.parse(old).keeper());}
        assertEquals(0,HelperProtocol.exitCode("ok"));
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
    @Test void signedReferenceHasNoOperationAuthorityAndDetectsReplacement() throws Exception {
        Fixture fixture=new Fixture();LeaseSnapshot snapshot=LeaseFile.inspect(fixture.base,fixture.file,fixture.trust);
        assertEquals(fixture.executable,snapshot.helperExecutable());
        assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,LeaseTrust.legacy()));
        ObjectNode copy=snapshot.lease();copy.put("ownerId",UUID.randomUUID().toString());assertNotEquals(copy,snapshot.lease());
        Path receipt=fixture.file.resolveSibling("lease.lock");byte[] bytes=Files.readAllBytes(receipt);
        Files.delete(receipt);Files.write(receipt,bytes);assertThrows(IOException.class,snapshot::verifyUnchanged);
    }
    @Test void helperAndSignatureMustRemainPinnedEvenWhenReceiptIsConsistent() throws Exception {
        Fixture fixture=new Fixture();
        Path evidence=LeaseTrust.evidence(fixture.base,fixture.open().read().path("descriptorSha256").asText());
        byte[] original=Files.readAllBytes(evidence);ObjectNode value=(ObjectNode)HelperProtocol.read(original);
        value.put("signature",Base64.getEncoder().encodeToString(new byte[64]));Files.write(evidence,HelperProtocol.JSON.writeValueAsBytes(value));
        assertThrows(IOException.class,fixture::open);Files.write(evidence,original);
        Files.writeString(fixture.executable,"different");assertThrows(IOException.class,fixture::open);
    }
    @Test void releasedReferenceRequiresEmptyResourcesAndOperationOpenRejectsIt() throws Exception {
        Fixture fixture=new Fixture();ObjectNode value=fixture.open().read();value.put("state","released");Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(value));
        assertEquals("released",LeaseFile.inspect(fixture.base,fixture.file,fixture.trust).lease().path("state").asText());assertThrows(IOException.class,fixture::open);
        value.set("resources",fixture.starting());Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(value));assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,fixture.trust));
    }
    @Test void hardlinkによるreceipt別root参照を既知所有へしない() throws Exception {
        Fixture fixture=new Fixture();Path receipt=fixture.file.resolveSibling("lease.lock"),alias=fixture.base.resolve("aliased-receipt");
        Files.createLink(alias,receipt);assertThrows(IOException.class,fixture::open);
    }
    @Test void signatureSidecarの偽trustとfield改変を認証根へしない() throws Exception {
        Fixture fixture=new Fixture();String digest=fixture.open().read().path("descriptorSha256").asText();Path evidence=LeaseTrust.evidence(fixture.base,digest);byte[] original=Files.readAllBytes(evidence);
        var forged=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var recovery=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.write(fixture.base.resolve("logs/trust.bin"),TrustFixtures.bytes(forged.getPublic(),recovery.getPublic()));
        var signer=Signature.getInstance("Ed25519");signer.initSign(forged.getPrivate());signer.update(Files.readAllBytes(fixture.file.resolveSibling("descriptor.json")));
        ObjectNode forgedProof=(ObjectNode)HelperProtocol.read(original);forgedProof.put("signature",Base64.getEncoder().encodeToString(signer.sign()));Files.write(evidence,HelperProtocol.JSON.writeValueAsBytes(forgedProof));assertThrows(IOException.class,fixture::open);
        for(String value:List.of("{","{}","{\"schemaVersion\":1,\"schemaVersion\":1}")){Files.writeString(evidence,value);assertThrows(IOException.class,fixture::open);}
        for(String field:List.of("descriptorSha256","signature","schemaVersion")) {
            ObjectNode value=(ObjectNode)HelperProtocol.read(original);value.remove(field);Files.write(evidence,HelperProtocol.JSON.writeValueAsBytes(value));assertThrows(IOException.class,fixture::open);
        }
        ObjectNode value=(ObjectNode)HelperProtocol.read(original);value.put("signature","A".repeat(200));Files.write(evidence,HelperProtocol.JSON.writeValueAsBytes(value));assertThrows(IOException.class,fixture::open);
        value=(ObjectNode)HelperProtocol.read(original);value.put("descriptorSha256","f".repeat(64));Files.write(evidence,HelperProtocol.JSON.writeValueAsBytes(value));assertThrows(IOException.class,fixture::open);
        Files.write(evidence,original);assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,LeaseTrust.legacy()));assertNotNull(fixture.open());
    }
    @Test void capacity証拠は世代要求を再計算し重複と捏造した不足を拒否する() throws Exception {
        Fixture fixture=new Fixture();ObjectNode candidate=fixture.open().read();candidate.put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        ObjectNode identity=candidate.deepCopy();identity.remove(List.of("state","resources"));Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(candidate));Files.write(fixture.file.resolveSibling("lease.lock"),LeaseFile.receipt(identity));
        Path directory=fixture.file.getParent().getParent().resolve(UUID.randomUUID().toString());Files.createDirectories(directory.resolve("tmp"));Files.createDirectory(directory.resolve("inventory"));
        for(String file:List.of("descriptor.json","inventory/helper.json","inventory/image.json"))Files.copy(fixture.file.getParent().resolve(file),directory.resolve(file));
        ObjectNode other=candidate.deepCopy().put("jobId",directory.getFileName().toString()).put("ownerId",UUID.randomUUID().toString());identity=other.deepCopy();identity.remove(List.of("state","resources"));Files.write(directory.resolve("lease.json"),HelperProtocol.JSON.writeValueAsBytes(other));Files.write(directory.resolve("lease.lock"),LeaseFile.receipt(identity));
        long request=candidate.path("requirements").path("containerMemoryMiB").asLong();String digest=candidate.path("descriptorSha256").asText();
        ObjectNode budget=HelperProtocol.JSON.createObjectNode().put("domain","macos-vm").put("constraint","generation").put("hostMiB",65536).put("requiredMiB",request*2+1024).put("availableMiB",request+1024);
        budget.putArray("generations").addObject().put("descriptorSha256",digest).put("reservedMiB",request+1024).put("requestedMiB",request*2);
        LeaseSnapshot own=LeaseFile.inspect(fixture.base,fixture.file,fixture.trust),reference=LeaseFile.inspect(fixture.base,directory.resolve("lease.json"),fixture.trust);
        try(var channel=FileChannel.open(directory.resolve("lease.lock"),StandardOpenOption.WRITE);var active=channel.lock(64,1,false)) {
            ObjectNode invalid=budget.deepCopy();invalid.put("requiredMiB",request*2+1025);assertThrows(IOException.class,()->AdmissionEvidence.capacity(fixture.base,own,List.of(reference),invalid));
            assertThrows(IOException.class,()->AdmissionEvidence.capacity(fixture.base,own,List.of(reference,reference),budget));
            ObjectNode alone=budget.deepCopy();((ObjectNode)alone.path("generations").get(0)).put("reservedMiB",request);alone.put("availableMiB",request);
            IOException insufficient=assertThrows(IOException.class,()->AdmissionEvidence.capacity(fixture.base,own,List.of(reference),alone));assertFalse(insufficient instanceof AdmissionEvidence.Busy);assertTrue(insufficient.getMessage().contains("候補単独でもVM容量"));
            assertFalse(Files.exists(fixture.file.getParent().resolve("tmp/admission-v1.json")));
            assertThrows(AdmissionEvidence.Busy.class,()->AdmissionEvidence.capacity(fixture.base,own,List.of(reference),budget));
            JsonNode proof=AdmissionEvidence.jobReference(fixture.base,candidate.path("jobId").asText());
            assertNotNull(AdmissionEvidence.verifyJob(fixture.base,proof,digest,ProcessHandle.current().pid(),candidate.path("processStartedAt").asText(),fixture.trust,false));
            Path savedEvidence=fixture.file.getParent().resolve("tmp/admission-v1.json");byte[] originalEvidence=Files.readAllBytes(savedEvidence);ObjectNode insufficientProof=(ObjectNode)HelperProtocol.read(originalEvidence);((ObjectNode)insufficientProof.path("budget")).put("availableMiB",request);((ObjectNode)insufficientProof.path("budget").path("generations").get(0)).put("reservedMiB",request);Files.write(savedEvidence,HelperProtocol.JSON.writeValueAsBytes(insufficientProof));JsonNode insufficientClaim=AdmissionEvidence.jobReference(fixture.base,candidate.path("jobId").asText());
            IOException refused=assertThrows(IOException.class,()->AdmissionEvidence.verifyJob(fixture.base,insufficientClaim,digest,ProcessHandle.current().pid(),candidate.path("processStartedAt").asText(),fixture.trust,false));assertTrue(refused.getMessage().contains("候補単独でもVM容量"));Files.write(savedEvidence,originalEvidence);

            Path evidence=fixture.file.getParent().resolve("tmp/admission-v1.json");ObjectNode value=(ObjectNode)HelperProtocol.read(Files.readAllBytes(evidence));((ObjectNode)value.path("budget").path("generations").get(0)).put("requestedMiB",1);Files.write(evidence,HelperProtocol.JSON.writeValueAsBytes(value));
            JsonNode changed=AdmissionEvidence.jobReference(fixture.base,candidate.path("jobId").asText());assertThrows(IOException.class,()->AdmissionEvidence.verifyJob(fixture.base,changed,digest,ProcessHandle.current().pid(),candidate.path("processStartedAt").asText(),fixture.trust,false));
        }
    }
    @Test void releasedでもMacjournal未終端を回収済みへしない() throws Exception {
        Fixture fixture=new Fixture();ObjectNode lease=fixture.open().read().put("state","released");Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(lease));
        Path journal=Files.createDirectories(fixture.file.getParent().resolve("tmp/macos-operations"));ObjectNode identity=HelperProtocol.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,lease.get(key));Files.write(journal.resolve("released.json"),HelperProtocol.JSON.writeValueAsBytes(identity));
        Path operation=Files.createDirectory(journal.resolve(UUID.randomUUID().toString()));ObjectNode intent=HelperProtocol.JSON.createObjectNode().put("operationId",operation.getFileName().toString()).put("kind","prepare");intent.set("identity",identity);byte[] bytes=HelperProtocol.JSON.writeValueAsBytes(intent);Files.write(operation.resolve("intent.json"),bytes);
        LeaseSnapshot snapshot=LeaseFile.inspect(fixture.base,fixture.file,fixture.trust);assertThrows(IOException.class,()->RuntimeMaintenanceVerifier.requireReleased(snapshot));
        Files.writeString(operation.resolve("client-complete"),hash(bytes)+"\n");ObjectNode settled=HelperProtocol.JSON.createObjectNode().put("intentSha256",hash(bytes));settled.putObject("evidence").put("owned",true);Files.write(operation.resolve("settled.json"),HelperProtocol.JSON.writeValueAsBytes(settled));assertDoesNotThrow(()->RuntimeMaintenanceVerifier.requireReleased(snapshot));
        Files.writeString(operation.resolve("client-complete.pending"),"");assertThrows(IOException.class,()->RuntimeMaintenanceVerifier.requireReleased(snapshot));
    }
    @Test void 署名が正しくても固定helperや保守pinに結合しない世代は拒否する() throws Exception {
        Fixture fixture=new Fixture();byte[] original=Files.readAllBytes(fixture.file.resolveSibling("descriptor.json"));
        LeaseTrust bound=IdentityFixtures.trust(fixture.trustSet);bound.bindReader(fixture.base,original,Files.readAllBytes(fixture.file.resolveSibling("inventory/helper.json")),fixture.executable);
        assertNotNull(LeaseFile.inspect(fixture.base,fixture.file,bound));
        assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,LeaseTrust.pinned(fixture.trustSet,"f".repeat(64),null)));
        ObjectNode descriptor=(ObjectNode)HelperProtocol.read(original);((ObjectNode)descriptor.path("artifacts").get(0)).put("sha256","2".repeat(64));
        byte[] changed=HelperProtocol.JSON.writeValueAsBytes(descriptor);fixture.publish(changed);Files.write(fixture.file.resolveSibling("descriptor.json"),changed);
        ObjectNode lease=(ObjectNode)HelperProtocol.read(Files.readAllBytes(fixture.file));lease.put("descriptorSha256",hash(changed));ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(lease));Files.write(fixture.file.resolveSibling("lease.lock"),LeaseFile.receipt(identity));
        Path other=fixture.base.resolve("helpers/macos-aarch64/"+"2".repeat(64)+"/bin/helper");Files.createDirectories(other.getParent());Files.copy(fixture.executable,other);
        assertNotNull(LeaseFile.inspect(fixture.base,fixture.file,fixture.trust));
        IOException failure=assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,bound));assertTrue(failure.getMessage().contains("既知の署名descriptor"));
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
    void signatureSidecarのlinkを固定証拠として読まない() throws Exception {
        Fixture fixture=new Fixture();Path evidence=LeaseTrust.evidence(fixture.base,fixture.open().read().path("descriptorSha256").asText()),target=fixture.base.resolve("linked-proof");
        Files.move(evidence,target);Files.createSymbolicLink(evidence,target);assertThrows(IOException.class,fixture::open);
    }
    @Test void 未公開証拠の再検証はgenerations排他を飛ばさず有限時間で拒否する() throws Exception {
        Fixture fixture=new Fixture();Path lock=fixture.base.resolve("locks/generations.lock");Files.createDirectory(lock.getParent());Files.write(lock,new byte[0]);
        try(var channel=FileChannel.open(lock,StandardOpenOption.WRITE);var held=channel.lock()) {
            IOException failure=assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->assertThrows(IOException.class,()->AdmissionEvidence.verifyPreAdmission(fixture.base,HelperProtocol.JSON.createObjectNode(),"f".repeat(64),ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toString(),fixture.trust)));
            assertTrue(failure.getMessage().contains("generations lock"));
        }
        assertThrows(IOException.class,()->AdmissionEvidence.verifyPreAdmission(fixture.base,HelperProtocol.JSON.createObjectNode(),"f".repeat(64),ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toString(),fixture.trust));
    }
    @Test void packageの署名検証とhelper結合前に現在hashを認証しない() throws Exception {
        Fixture fixture=new Fixture();Path support=fixture.executable.getParent().getParent().resolve("app/support");Files.createDirectories(support);var recovery=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // 証拠はfixtureの既存署名鍵で検証できるが、まだhelper bindingを実施していない。
        Files.write(support.resolve("lease-trust.bin"),TrustFixtures.bytes(fixture.pair.getPublic(),recovery.getPublic()));
        LeaseTrust unbound=LeaseTrust.packaged(fixture.executable,"macos");assertThrows(IOException.class,()->LeaseFile.inspect(fixture.base,fixture.file,unbound));
        unbound.bindReader(fixture.base,Files.readAllBytes(fixture.file.resolveSibling("descriptor.json")),Files.readAllBytes(fixture.file.resolveSibling("inventory/helper.json")),fixture.executable);
        if(!System.getProperty("os.name").startsWith("Windows"))assertNotNull(LeaseFile.inspect(fixture.base,fixture.file,unbound));
    }
    @Test void 証拠保存後に追加されたjobのdeadactiveとactor未終端を完全走査で拒否する() throws Exception {
        Fixture fixture=new Fixture();ObjectNode descriptor=(ObjectNode)HelperProtocol.read(Files.readAllBytes(fixture.file.resolveSibling("descriptor.json")));((ObjectNode)descriptor.path("host")).put("os","windows").put("arch","x86_64");byte[] definition=HelperProtocol.JSON.writeValueAsBytes(descriptor);fixture.publish(definition);Files.write(fixture.file.resolveSibling("descriptor.json"),definition);
        Path executable=fixture.base.resolve("helpers/windows-x86_64/"+"1".repeat(64)+"/bin/helper");Files.createDirectories(executable.getParent());Files.copy(fixture.executable,executable);
        ObjectNode lease=(ObjectNode)HelperProtocol.read(Files.readAllBytes(fixture.file));lease.put("descriptorSha256",hash(definition)).put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(lease));Files.write(fixture.file.resolveSibling("lease.lock"),LeaseFile.receipt(identity));
        var method=AdmissionEvidence.class.getDeclaredMethod("verifyUnpublishedJobs",Path.class,String.class,long.class,String.class,LeaseTrust.class);method.setAccessible(true);
        Object[] args={fixture.base,"f".repeat(64),ProcessHandle.current().pid(),lease.path("processStartedAt").asText(),fixture.trust};
        try(var channel=FileChannel.open(fixture.file.resolveSibling("lease.lock"),StandardOpenOption.WRITE);var held=channel.lock(64,1,false)) {
            method.invoke(null,args);
            Path proof=fixture.base.resolve("logs/"+UUID.randomUUID()+".pre-admission-v1.json");ObjectNode saved=HelperProtocol.JSON.createObjectNode();saved.putArray("references").add(RuntimeMaintenanceVerifier.proof(LeaseFile.inspect(fixture.base,fixture.file,fixture.trust)));AdmissionEvidence.save(proof,saved,fixture.trust);byte[] original=Files.readAllBytes(proof);
            Path extra=fixture.file.getParent().getParent().resolve(UUID.randomUUID().toString());Files.createDirectories(extra.resolve("tmp"));Files.createDirectory(extra.resolve("inventory"));for(String file:List.of("descriptor.json","inventory/helper.json","inventory/image.json"))Files.copy(fixture.file.getParent().resolve(file),extra.resolve(file));
            ObjectNode unknown=lease.deepCopy().put("jobId",extra.getFileName().toString()).put("ownerId",UUID.randomUUID().toString()).put("processStartedAt",java.time.Instant.EPOCH.toString());identity=unknown.deepCopy();identity.remove(List.of("state","resources"));Files.write(extra.resolve("lease.lock"),LeaseFile.receipt(identity));Files.write(extra.resolve("lease.json"),HelperProtocol.JSON.writeValueAsBytes(unknown));
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,args));assertInstanceOf(IOException.class,failure.getCause());assertArrayEquals(original,Files.readAllBytes(proof));
            unknown.put("state","released");Files.write(extra.resolve("lease.json"),HelperProtocol.JSON.writeValueAsBytes(unknown));method.invoke(null,args);
            Files.writeString(extra.resolve("tmp/native-"+UUID.randomUUID()+".intent.json"),"{}");failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,args));assertInstanceOf(IOException.class,failure.getCause());assertArrayEquals(original,Files.readAllBytes(proof));
        }
    }
    final class Fixture {
        final Path base=Files.createTempDirectory(root,"fixture-").toRealPath(),file,executable,image;final HelperProtocol.Command command;final LeaseTrust trust;final java.security.KeyPair pair;final TrustSet trustSet;
        Fixture() throws Exception {
            ObjectNode identity=((ObjectNode)examples().path("receipt").get("identity")).deepCopy();Files.writeString(base.resolve("host-id"),identity.path("hostId").asText());Path job=base.resolve("jobs").resolve(identity.path("jobId").asText());Files.createDirectories(job.resolve("tmp"));Files.createDirectory(job.resolve("inventory"));
            ObjectNode descriptor=HelperProtocol.JSON.createObjectNode().put("schemaVersion",1).put("runtimeId","fixture").put("toolVersion","1.1.0").put("minimumOsVersion","15");descriptor.putObject("host").put("os","macos").put("arch","aarch64");descriptor.putObject("worker").put("toolVersion","1.1.0").put("protocolVersion",1).put("agentSha256",identity.path("workerSha256").asText()).put("adapterSetSha256","a".repeat(64));descriptor.putObject("helper").put("protocolVersion",1).put("artifactId","helper").put("executable","bin/helper");descriptor.putObject("rendering");var artifacts=descriptor.putArray("artifacts");
            for(String id:List.of("helper","image")) {
                String digest=id.equals("helper")?"1".repeat(64):hash(new byte[]{1,2,3});var artifact=artifacts.addObject().put("id",id).put("role",id.equals("helper")?"host-helper":"renderer-image").put("url","https://fixture.invalid/"+id).put("bytes",3).put("sha256",digest).put("unpackedBytes",3).put("format","zip").put("inventoryPath","inventory/"+id+".json").put("inventorySha256",hash(inventory(id)));artifact.putArray("licenseFiles").add("LICENSE");artifact.putArray("noticeFiles");artifact.putArray("sourceArchives");Files.write(job.resolve("inventory/"+id+".json"),inventory(id));
            }
            pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var recovery=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();trustSet=TrustSet.parse(TrustFixtures.bytes(pair.getPublic(),recovery.getPublic()));trust=IdentityFixtures.trust(trustSet);
            byte[] definition=HelperProtocol.JSON.writeValueAsBytes(descriptor);var signer=Signature.getInstance("Ed25519");signer.initSign(pair.getPrivate());signer.update(definition);LeaseTrust.publish(base,definition,signer.sign(),trustSet);identity.put("descriptorSha256",hash(definition));Files.write(job.resolve("descriptor.json"),definition);file=job.resolve("lease.json");ObjectNode lease=identity.deepCopy().put("state","active");lease.putObject("resources");Files.write(file,HelperProtocol.JSON.writeValueAsBytes(lease));Files.write(job.resolve("lease.lock"),LeaseFile.receipt(identity));executable=base.resolve("helpers/macos-aarch64/"+"1".repeat(64)+"/bin/helper");Files.createDirectories(executable.getParent());Files.writeString(executable,"test-only");image=base.resolve("runtimes/fixture/"+identity.path("descriptorSha256").asText()+"/image.zip");command=new HelperProtocol.Command(base,file,"preflight",List.of());
        }
        void packageTrust() throws Exception {
            Path support=executable.getParent().getParent().resolve("app/support");Files.createDirectories(support);
            var recovery=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.write(support.resolve("lease-trust.bin"),TrustFixtures.bytes(pair.getPublic(),recovery.getPublic()));
        }
        LeaseFile open() throws Exception {return LeaseFile.open(command,executable,trust);}
        void publish(byte[] definition) throws Exception {
            var signer=Signature.getInstance("Ed25519");signer.initSign(pair.getPrivate());signer.update(definition);LeaseTrust.publish(base,definition,signer.sign(),trustSet);
        }
        void rebindInventory(ObjectNode inventory) throws Exception {
            byte[] listing=HelperProtocol.JSON.writeValueAsBytes(inventory);Files.write(file.resolveSibling("inventory/helper.json"),listing);
            ObjectNode descriptor=(ObjectNode)HelperProtocol.read(Files.readAllBytes(file.resolveSibling("descriptor.json")));
            ((ObjectNode)descriptor.path("artifacts").get(0)).put("inventorySha256",hash(listing));byte[] definition=HelperProtocol.JSON.writeValueAsBytes(descriptor);publish(definition);Files.write(file.resolveSibling("descriptor.json"),definition);
            ObjectNode lease=(ObjectNode)HelperProtocol.read(Files.readAllBytes(file));lease.put("descriptorSha256",hash(definition));Files.write(file,HelperProtocol.JSON.writeValueAsBytes(lease));
            ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(file.resolveSibling("lease.lock"),LeaseFile.receipt(identity));
        }
        byte[] inventory(String id) throws Exception {
            ObjectNode value=HelperProtocol.JSON.createObjectNode();
            if(id.equals("helper"))value.putObject("contents").putArray("files").addObject().put("path","bin/helper").put("bytes","test-only".getBytes(StandardCharsets.UTF_8).length).put("sha256",hash("test-only".getBytes(StandardCharsets.UTF_8)));
            return HelperProtocol.JSON.writeValueAsBytes(value);
        }
        ObjectNode starting() {var resources=HelperProtocol.JSON.createObjectNode();resources.putObject("macos").put("phase","starting").put("generationOwner",UUID.randomUUID().toString()).put("instanceDir",base.resolve("instance").toString()).putArray("containers");return resources;}
    }
    static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
