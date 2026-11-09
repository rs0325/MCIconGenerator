package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.internal.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** テスト鍵のschema4/protocol fixture。公開rootの実更新成功とは区別する。 */
class TrialRecoveryTest {
    @TempDir Path temporary;
    @Test void UNKNOWNの旧失敗も全終端を確認した明示resumeだけでtrialを解除する() throws Exception {
        Assumptions.assumeTrue(Main.host().os().equals("macos"));
        try(Context c=new Context(temporary)) {
            JsonNodeCopy before=new JsonNodeCopy(c.bootstrap.state());
            byte[] receipt=Files.readAllBytes(c.run);
            new TrialRecovery(c.bootstrap).resume(c.trial);
            var after=c.bootstrap.state();assertTrue(after.path("trialInvocation").isNull());
            before.assertPreserved(after);assertArrayEquals(receipt,Files.readAllBytes(c.run));
            assertTrue(Files.isRegularFile(c.bootstrap.root.resolve("logs/"+c.trial+".resume-v1.json")));
            assertThrows(IOException.class,()->new TrialRecovery(c.bootstrap).resume(c.trial));
            c.nextTrialAndSequence();
        }
    }
    @Test void journal着手とREADYを終端へ読み替えない() throws Exception {
        Assumptions.assumeTrue(Main.host().os().equals("macos"));
        try(Context c=new Context(temporary)) {
            Path journal=Files.createDirectories(c.job.resolve("tmp/macos-operations"));Path operation=Files.createDirectory(journal.resolve(UUID.randomUUID().toString()));
            assertThrows(IOException.class,()->new TrialRecovery(c.bootstrap).resume(c.trial));assertEquals(c.trial,c.bootstrap.state().path("trialInvocation").asText());Files.delete(operation);Files.delete(journal);
            c.record.put("startup","ready").putNull("initialization");c.writeReceipt();
            assertThrows(IOException.class,()->new TrialRecovery(c.bootstrap).resume(c.trial));assertEquals(c.trial,c.bootstrap.state().path("trialInvocation").asText());
            c.record.put("startup","prepare");c.writeReceipt();assertThrows(IOException.class,()->new TrialRecovery(c.bootstrap).resume(c.trial));assertEquals(c.trial,c.bootstrap.state().path("trialInvocation").asText());
        }
    }
    @Test void 延期証拠保存後CAS前の停止を正式resumeで再開し置換は拒否する() throws Exception {
        Assumptions.assumeTrue(Main.host().os().equals("macos"));
        for(boolean replace:List.of(false,true))try(Context c=new Context(Files.createDirectory(temporary.resolve("case-"+replace)))) {
            Path evidence=c.saveDeferred();TrialRecovery recovery=new TrialRecovery(c.bootstrap);
            if(replace){byte[] original=Files.readAllBytes(evidence);Files.delete(evidence);Files.write(evidence,original);assertThrows(IOException.class,()->recovery.resume(c.trial));assertEquals(c.trial,c.bootstrap.state().path("trialInvocation").asText());}
            else {JsonNodeCopy before=new JsonNodeCopy(c.bootstrap.state());recovery.resume(c.trial);assertTrue(c.bootstrap.state().path("trialInvocation").isNull());before.assertPreserved(c.bootstrap.state());c.nextTrialAndSequence();}
        }
    }
    @Test void 保存済み延期の後でも現在設定の明示有効化と再有効化で正式resumeする() throws Exception {
        Assumptions.assumeTrue(Main.host().os().equals("macos"));
        for(boolean wasConfigured:List.of(false,true))try(Context c=new Context(Files.createDirectory(temporary.resolve("config-"+wasConfigured)))) {
            UpdatePreparation updates=new UpdatePreparation(c.bootstrap);
            if(wasConfigured)updates.configure(true,Long.MAX_VALUE);
            Path evidence=c.saveDeferred();byte[] original=Files.readAllBytes(evidence),receipt=Files.readAllBytes(c.run);
            JsonNodeCopy before=new JsonNodeCopy(c.bootstrap.state());TrialRecovery recovery=new TrialRecovery(c.bootstrap);
            ObjectNode broken=(ObjectNode)Seed.json(original);broken.putObject("settings").put("present",true);
            Files.write(evidence,Seed.JSON.writeValueAsBytes(broken));
            assertThrows(IOException.class,()->recovery.resume(c.trial));assertEquals(c.trial,c.bootstrap.state().path("trialInvocation").asText());
            Files.write(evidence,original);
            if(wasConfigured) {
                updates.configure(false,Long.MAX_VALUE);
                assertThrows(IOException.class,()->recovery.resume(c.trial));
                assertEquals(c.trial,c.bootstrap.state().path("trialInvocation").asText());
            }
            updates.configure(true,Long.MAX_VALUE);
            var configuration=recovery.settings(Long.MAX_VALUE);
            assertNotEquals(Seed.json(original).path("settings"),configuration);
            recovery.resume(c.trial);
            assertTrue(c.bootstrap.state().path("trialInvocation").isNull());before.assertPreserved(c.bootstrap.state());
            assertArrayEquals(original,Files.readAllBytes(evidence));assertArrayEquals(receipt,Files.readAllBytes(c.run));
            var attempt=Seed.json(Files.readAllBytes(c.bootstrap.root.resolve("logs/"+c.trial+".resume-v1.json")));
            assertEquals(configuration,attempt.path("settings"));c.nextTrialAndSequence();
        }
    }
    @Test void 再開証拠保存後の設定競合は旧証拠を保持し新しい試行で正式resumeする() throws Exception {
        Assumptions.assumeTrue(Main.host().os().equals("macos"));
        try(Context c=new Context(temporary)) {
            Path deferred=c.saveDeferred();byte[] savedDeferred=Files.readAllBytes(deferred),receipt=Files.readAllBytes(c.run);
            TrialRecovery recovery=new TrialRecovery(c.bootstrap);var state=c.bootstrap.state();var configuration=recovery.settings(Long.MAX_VALUE);
            var proof=Seed.json(savedDeferred).path("runtimeProof");
            var save=TrialRecovery.class.getDeclaredMethod("saveResume",String.class,JsonNode.class,
                    JsonNode.class,JsonNode.class,
                    JsonNode.class,String.class,long.class);
            save.setAccessible(true);save.invoke(recovery,c.trial,state,configuration,c.record,proof,"prepare",Long.MAX_VALUE);
            Path original=c.bootstrap.root.resolve("logs/"+c.trial+".resume-v1.json");byte[] originalBytes=Files.readAllBytes(original);
            UpdatePreparation updates=new UpdatePreparation(c.bootstrap);updates.configure(false,Long.MAX_VALUE);updates.configure(true,Long.MAX_VALUE);
            var current=recovery.settings(Long.MAX_VALUE);assertNotEquals(configuration,current);
            var clear=TrialRecovery.class.getDeclaredMethod("clear",String.class,JsonNode.class,
                    JsonNode.class,JsonNode.class,Path.class,
                    FileChannel.class,ObjectNode.class,String.class,JsonNode.class,
                    JsonNode.class,long.class);
            clear.setAccessible(true);
            try(FileChannel channel=FileChannel.open(c.run,StandardOpenOption.READ,StandardOpenOption.WRITE);var held=channel.lock()) {
                var refused=assertThrows(java.lang.reflect.InvocationTargetException.class,
                        ()->clear.invoke(recovery,c.trial,c.pending,state,configuration,c.run,channel,c.record,"prepare",proof,Seed.json(savedDeferred).path("message"),Long.MAX_VALUE));
                assertInstanceOf(IOException.class,refused.getCause());assertTrue(refused.getCause().getMessage().contains("state/settings CAS"));
            }
            assertEquals(state,c.bootstrap.state());assertArrayEquals(originalBytes,Files.readAllBytes(original));
            recovery.resume(c.trial);assertTrue(c.bootstrap.state().path("trialInvocation").isNull());new JsonNodeCopy(state).assertPreserved(c.bootstrap.state());
            List<Path> attempts;try(var files=Files.newDirectoryStream(c.bootstrap.root.resolve("logs"),c.trial+".resume-*-v1.json")){attempts=new ArrayList<>();for(Path file:files)attempts.add(file);}
            assertEquals(1,attempts.size());assertEquals(current,Seed.json(Files.readAllBytes(attempts.getFirst())).path("settings"));
            assertArrayEquals(originalBytes,Files.readAllBytes(original));assertArrayEquals(savedDeferred,Files.readAllBytes(deferred));assertArrayEquals(receipt,Files.readAllBytes(c.run));
            c.nextTrialAndSequence();
        }
    }
    private record JsonNodeCopy(com.fasterxml.jackson.databind.JsonNode value) {
        void assertPreserved(com.fasterxml.jackson.databind.JsonNode after){for(String name:List.of("active","pending","previous","highestAcceptedSequence","badCandidates","preparation"))assertEquals(value.path(name),after.path(name),name);}
    }
    static final class Context implements AutoCloseable {
        final String previousHome=System.getProperty("user.home"),trial=UUID.randomUUID().toString(),host=UUID.randomUUID().toString();
        final Fixture fixture;
        final Bootstrap bootstrap;
        final Path runtime,run,job;
        final ObjectNode record,pending;
        final byte[] inventory,definition;
        Process peer;
        Context(Path root) throws Exception {
            root=root.toRealPath();
            try {
            Path home=Files.createDirectory(root.resolve("home"));System.setProperty("user.home",home.toString());runtime=Files.createDirectories(home.resolve("Library/Application Support/MCIconGenerator"));Files.writeString(runtime.resolve("host-id"),host);Files.createDirectory(runtime.resolve("jobs"));
            fixture=new Fixture(root.resolve("fixture"));fixture.files.put("app/lib/core-1.1.0.jar",verifierJar());fixture.publish();
            Bootstrap initial=fixture.bootstrap(root.resolve("bootstrap"));initial.repair();
            ObjectNode descriptor=fixture.descriptor;descriptor.removeAll();descriptor.put("schemaVersion",1).put("toolVersion","1.1.1").put("runtimeId","fixture").put("minimumOsVersion","13");descriptor.set("host",Seed.JSON.valueToTree(fixture.host));descriptor.putObject("helper").put("protocolVersion",1).put("artifactId","helper").put("executable","bin/helper");descriptor.putObject("worker").put("protocolVersion",1).put("toolVersion","1.1.1").put("agentSha256","b".repeat(64)).put("adapterSetSha256","c".repeat(64));descriptor.putObject("rendering");
            ObjectNode listing=Seed.JSON.createObjectNode();listing.putObject("contents").putArray("files").addObject().put("path","bin/helper").put("bytes",7).put("sha256",Seed.hash("fixture".getBytes()));inventory=Seed.JSON.writeValueAsBytes(listing);
            var artifacts=descriptor.putArray("artifacts");for(String id:List.of("helper","image")){var artifact=artifacts.addObject().put("id",id).put("role",id.equals("helper")?"host-helper":"renderer-image").put("url","https://fixture.invalid/"+id).put("bytes",1).put("unpackedBytes",1).put("format","zip").put("sha256","a".repeat(64)).put("inventoryPath","inventory/"+id+".json").put("inventorySha256",Seed.hash(inventory));artifact.putArray("licenseFiles");artifact.putArray("noticeFiles");artifact.putArray("sourceArchives");}
            definition=Seed.JSON.writeValueAsBytes(descriptor);LeaseTrust.publish(runtime,definition,fixture.sign(definition),fixture.trust);
            Path helper=runtime.resolve("helpers/"+fixture.host.os()+"-"+fixture.host.arch()+"/"+"a".repeat(64)+"/bin/helper");Files.createDirectories(helper.getParent());Files.writeString(helper,"fixture");
            fixture.manifest.put("toolVersion","1.1.1").put("sequence",2);
            for(String name:List.of("cli","core","worker"))fixture.files.put("app/lib/"+name+"-1.1.1.jar",fixture.files.remove("app/lib/"+name+"-1.1.0.jar"));
            fixture.publish();ObjectNode info=(ObjectNode)Seed.json(Files.readAllBytes(fixture.installed.resolve("bootstrap-package.json")));info.put("bootstrapVersion","1.1.1");Files.write(fixture.installed.resolve("bootstrap-package.json"),Seed.JSON.writeValueAsBytes(info));bootstrap=fixture.bootstrap(initial.root);Seed candidate=fixture.seed();bootstrap.saveManifest(candidate);bootstrap.copyArchive(candidate,Long.MAX_VALUE);pending=(ObjectNode)candidate.reference();
            ObjectNode state=(ObjectNode)bootstrap.state();state.set("pending",pending);state.put("trialInvocation",trial).put("highestAcceptedSequence",2);bootstrap.atomic(bootstrap.root.resolve("active.json"),state);
            ObjectNode parent=dead(),child=dead();record=Seed.JSON.createObjectNode().put("schemaVersion",3).put("kind","command").put("invocationId",trial).put("state","finished").put("exitCode",1).put("startup","failed").put("argsSha256","f".repeat(64));record.set("slot",pending);record.set("bootstrap",parent);record.set("child",child);
            ObjectNode failure=Seed.JSON.createObjectNode().put("protocol",4).put("type","FAILED").put("nonce",UUID.randomUUID().toString()).put("invocation",trial).put("pid",child.path("pid").asLong()).put("startedAt",child.path("startedAt").asText()).put("failure","UNKNOWN").put("cleanup","UNKNOWN");failure.set("slot",pending);failure.putArray("jobs");record.set("initialization",failure);
            run=bootstrap.root.resolve("runs/"+trial+".json");try(FileChannel channel=bootstrap.create(run)){record.put("receiptKey",bootstrap.fileIdentity(run,Long.MAX_VALUE));Bootstrap.write(channel,record);}
            job=runtime.resolve("jobs/"+UUID.randomUUID());Files.createDirectories(job.resolve("tmp"));ObjectNode lease=Seed.JSON.createObjectNode().put("schemaVersion",1).put("jobId",job.getFileName().toString()).put("ownerId",UUID.randomUUID().toString()).put("hostId",host).put("toolVersion","1.1.1").put("runtimeId","fixture").put("workerSha256","b".repeat(64)).put("descriptorSha256",Seed.hash(definition)).put("pid",child.path("pid").asLong()).put("processStartedAt",child.path("startedAt").asText()).put("createdAt",Instant.now().toString()).put("state","released");lease.putObject("requirements").put("containerMemoryMiB",3072).put("heapMemoryMiB",2048).put("cpus",4).put("preparationDeadline",Instant.now().plusSeconds(60).toString());lease.putObject("resources");writeJob(job,lease);
            }catch(Exception failure){System.setProperty("user.home",previousHome);throw failure;}
        }
        Path saveDeferred() throws Exception {
            var trust=LeaseTrust.verified(fixture.trust);record.put("startup","prepare").putNull("initialization");writeReceipt();
            ObjectNode active=(ObjectNode)Seed.json(Files.readAllBytes(job.resolve("lease.json")));
            active.put("state","active");Files.write(job.resolve("lease.json"),Seed.JSON.writeValueAsBytes(active));
            Path other=runtime.resolve("jobs/"+UUID.randomUUID());
            ObjectNode owner=active.deepCopy().put("jobId",other.getFileName().toString()).put("ownerId",UUID.randomUUID().toString());
            writeJob(other,owner);
            Path source=Path.of("..","core","src/test/java/io/github/rs0325/mcicon/internal/managed/LeaseLockProcess.java").toAbsolutePath().normalize();
            peer=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"--source","21",source.toString(),
                    other.resolve("lease.lock").toString(),"true").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            var ready=CompletableFuture.supplyAsync(()->{try{return new String(peer.getInputStream().readNBytes(5),java.nio.charset.StandardCharsets.US_ASCII);}catch(IOException failure){throw new UncheckedIOException(failure);}});
            assertEquals("HELD\n",ready.get(10,TimeUnit.SECONDS));
            var actor=Bootstrap.identity(peer.toHandle());
            owner.put("pid",actor.path("pid").asLong()).put("processStartedAt",actor.path("startedAt").asText()).put("createdAt",Instant.now().toString());writeJob(other,owner);
            LeaseSnapshot reference=LeaseSnapshot.read(runtime,other.resolve("lease.json"),trust),candidate=LeaseSnapshot.read(runtime,job.resolve("lease.json"),trust);
            var budget=Seed.JSON.createObjectNode().put("domain","macos-vm").put("constraint","generation").put("hostMiB",16384).put("requiredMiB",7168).put("availableMiB",4096);
            budget.putArray("generations").addObject().put("descriptorSha256",Seed.hash(definition)).put("reservedMiB",4096).put("requestedMiB",6144);
            assertThrows(AdmissionEvidence.Busy.class,()->AdmissionEvidence.capacity(runtime,candidate,List.of(reference),budget));
            active.put("state","released");Files.write(job.resolve("lease.json"),Seed.JSON.writeValueAsBytes(active));
            ObjectNode message=Seed.JSON.createObjectNode().put("protocol",4).put("type","DEFERRED").put("reason","MANAGED_BUSY")
                    .put("nonce",UUID.randomUUID().toString()).put("invocation",trial).put("pid",record.path("child").path("pid").asLong()).put("startedAt",record.path("child").path("startedAt").asText());
            message.set("slot",record.path("slot"));message.putArray("jobs").add(AdmissionEvidence.jobReference(runtime,job.getFileName().toString()));
            ObjectNode request=message.deepCopy();request.put("type","REQUEST");request.remove(List.of("reason","jobs"));
            TrialRecovery recovery=new TrialRecovery(bootstrap);JsonNode state=bootstrap.state(),settings=recovery.settings(Long.MAX_VALUE);
            Path evidence=bootstrap.root.resolve("logs/"+trial+".deferred-v1.json");ExecutorService executor=Executors.newSingleThreadExecutor();
            try(FileChannel receipt=FileChannel.open(run,StandardOpenOption.READ,StandardOpenOption.WRITE);
                FileLock receiptLock=receipt.lock();var stateLock=bootstrap.lock(Long.MAX_VALUE)) {
                Future<?> operation=executor.submit(()->{try{recovery.defer(trial,pending,state,run,receipt,record,request,message,settings,System.nanoTime()+Duration.ofSeconds(20).toNanos());}catch(IOException failure){throw new UncheckedIOException(failure);}});
                long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();boolean complete=false;
                while(System.nanoTime()<end&&!operation.isDone()) {
                    if(Files.isRegularFile(evidence)){try{complete=Seed.json(Files.readAllBytes(evidence)).has("evidenceKey");if(complete)break;}catch(IOException partial){}}
                    Thread.sleep(10);
                }
                assertTrue(complete,"durable延期証拠の保存へ到達する");assertTrue(operation.cancel(true));
            }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
            assertEquals(trial,bootstrap.state().path("trialInvocation").asText());return evidence;
        }
        void nextTrialAndSequence() throws Exception {
            Path output=bootstrap.root.resolve("after-resume-output");assertEquals(0,bootstrap.launch(List.of("list","output",output.toString())));assertTrue(Files.isRegularFile(output));
            var state=bootstrap.state();assertEquals(pending,state.path("active"));assertTrue(state.path("trialInvocation").isNull());assertTrue(state.path("pending").isNull());assertEquals(2,state.path("highestAcceptedSequence").asLong());
            try(var files=Files.newDirectoryStream(bootstrap.root.resolve("runs"),"*.json")){boolean found=false;for(Path file:files){var receipt=Seed.json(Files.readAllBytes(file));if(receipt.path("slot").equals(pending)&&receipt.path("startup").asText().equals("go")){assertNotEquals(trial,receipt.path("invocationId").asText());assertEquals("finished",receipt.path("state").asText());found=true;}}assertTrue(found,"pendingを新UUIDで再試行する");}
            fixture.manifest.put("toolVersion","1.1.2").put("sequence",3);fixture.descriptor.put("toolVersion","1.1.2");((ObjectNode)fixture.descriptor.path("worker")).put("toolVersion","1.1.2");
            for(String name:List.of("cli","core","worker"))fixture.files.put("app/lib/"+name+"-1.1.2.jar",fixture.files.remove("app/lib/"+name+"-1.1.1.jar"));fixture.publish();
            var receipt=fixture.preparePending(bootstrap);assertEquals("pending",receipt.path("result").asText());assertEquals(3,bootstrap.state().path("highestAcceptedSequence").asLong());assertEquals("1.1.2",bootstrap.state().path("pending").path("toolVersion").asText());
        }
        void writeJob(Path directory,ObjectNode lease) throws Exception {Files.createDirectories(directory.resolve("inventory"));Files.createDirectories(directory.resolve("tmp"));Files.write(directory.resolve("descriptor.json"),definition);for(String id:List.of("helper","image"))Files.write(directory.resolve("inventory/"+id+".json"),inventory);ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(directory.resolve("lease.lock"),LeaseSnapshot.receipt(identity));Files.write(directory.resolve("lease.json"),Seed.JSON.writeValueAsBytes(lease));}
        void writeReceipt() throws Exception {try(FileChannel channel=FileChannel.open(run,StandardOpenOption.WRITE)){Bootstrap.write(channel,record);}}
        private static ObjectNode dead() throws Exception {Process process=new ProcessBuilder("/bin/sleep","0.2").start();try{ObjectNode identity=(ObjectNode)Bootstrap.identity(process.toHandle());assertTrue(process.waitFor(2,TimeUnit.SECONDS));return identity;}finally{if(process.isAlive())process.destroyForcibly();assertTrue(process.waitFor(2,TimeUnit.SECONDS));}}
        private static byte[] verifierJar() throws Exception {
            Path classes=Path.of(RuntimeMaintenanceVerifier.class.getProtectionDomain().getCodeSource().getLocation().toURI());ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(JarOutputStream jar=new JarOutputStream(bytes);var files=Files.walk(classes.resolve("io/github/rs0325/mcicon/internal"))){for(Path file:files.filter(Files::isRegularFile).toList()){jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));jar.write(Files.readAllBytes(file));jar.closeEntry();}}
            return bytes.toByteArray();
        }
        public void close() throws Exception {
            try {
                if(peer!=null) {
                    peer.getOutputStream().close();
                    if(!peer.waitFor(5,TimeUnit.SECONDS)){peer.destroyForcibly();assertTrue(peer.waitFor(3,TimeUnit.SECONDS));}
                    peer.getInputStream().close();peer.getErrorStream().close();
                }
            }finally{System.setProperty("user.home",previousHome);}
        }
    }
}
