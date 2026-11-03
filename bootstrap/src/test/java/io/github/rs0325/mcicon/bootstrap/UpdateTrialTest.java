package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateTrialTest {
    @TempDir Path directory;
    private Fixture fixture;
    private Bootstrap bootstrap;
    private JsonNode old;
    private long deadline(){return System.nanoTime()+Duration.ofSeconds(10).toNanos();}
    private void pending() throws Exception {
        directory=directory.toRealPath();fixture=new Fixture(directory);bootstrap=fixture.bootstrap(directory.resolve("root"));assertEquals(0,bootstrap.launch(List.of()));old=bootstrap.state().get("active");
        fixture.manifest.put("toolVersion","1.1.1").put("sequence",2);fixture.descriptor.put("toolVersion","1.1.1");for(String name:new ArrayList<>(fixture.files.keySet()))if(name.endsWith("-1.1.0.jar")){byte[] bytes=fixture.files.remove(name);fixture.files.put(name.replace("-1.1.0.jar","-1.1.1.jar"),bytes);}fixture.publish();Seed seed=fixture.seed();bootstrap.copyArchive(seed,deadline());bootstrap.saveManifest(seed);bootstrap.verifySlot(seed,deadline());ObjectNode state=(ObjectNode)bootstrap.state();state.set("pending",seed.reference());state.put("highestAcceptedSequence",2);bootstrap.atomic(bootstrap.root.resolve("active.json"),state);
    }
    @Test void eligibleListReapsOldParserAndActivatesOnlyBeforeOutput() throws Exception {
        pending();Path output=directory.resolve("result");assertEquals(0,bootstrap.launch(List.of("list","output",output.toString())));assertTrue(Files.exists(output));JsonNode state=bootstrap.state();assertEquals("1.1.1",state.path("active").path("toolVersion").asText());assertEquals(old,state.get("previous"));assertTrue(state.path("pending").isNull());assertTrue(state.path("trialInvocation").isNull());
        try(var paths=Files.list(bootstrap.root.resolve("runs"))){List<Path> runs=paths.toList();assertEquals(3,runs.size());for(Path run:runs)assertEquals("finished",Seed.json(Seed.read(run,65536)).path("state").asText());}
    }
    @Test void candidateFailureRecoversOnceAndRejectsLocalAndDownloadedBadPayload() throws Exception {
        pending();JsonNode candidate=bootstrap.state().get("pending");Path output=directory.resolve("recovered");
        assertEquals(0,bootstrap.launch(List.of("list","candidate-fail","output",output.toString())));
        JsonNode recovered=bootstrap.state();assertEquals(old,recovered.get("active"));assertTrue(recovered.path("pending").isNull());assertTrue(recovered.path("trialInvocation").isNull());assertEquals(2,recovered.path("highestAcceptedSequence").longValue());assertTrue(Files.exists(output));
        assertEquals(1,recovered.path("badCandidates").size());JsonNode bad=recovered.path("badCandidates").get(0);assertEquals(candidate,bad.get("slot"));assertEquals("NOT_STARTED",bad.path("initialization").path("cleanup").asText());
        JsonNode receipt=Seed.json(Seed.read(bootstrap.root.resolve("runs/"+bad.path("invocation").asText()+".json"),65536));assertEquals("finished",receipt.path("state").asText());assertEquals("failed",receipt.path("startup").asText());assertEquals(bad.get("initialization"),receipt.get("initialization"));assertEquals(bad.get("receiptKey"),receipt.get("receiptKey"));
        assertThrows(IOException.class,()->UpdateDownload.newer(fixture.seed(),recovered));
        fixture.manifest.put("sequence",3);fixture.writeManifest();assertThrows(IOException.class,()->UpdateDownload.newer(fixture.seed(),recovered));
        ObjectNode replay=(ObjectNode)recovered.deepCopy();replay.set("pending",candidate);bootstrap.atomic(bootstrap.root.resolve("active.json"),replay);
        assertEquals(0,bootstrap.launch(List.of("list")));assertEquals(replay,bootstrap.state());
    }
    @Test void oldJobFailureIsReturnedWithoutRetryingAfterRecovery() throws Exception {
        pending();assertEquals(1,bootstrap.launch(List.of("list","candidate-fail","old-fail")));JsonNode current=bootstrap.state();assertEquals(old,current.get("active"));assertTrue(current.path("pending").isNull());assertTrue(current.path("trialInvocation").isNull());assertEquals(1,current.path("badCandidates").size());
        try(var paths=Files.list(bootstrap.root.resolve("runs"))){assertEquals(4,paths.count());}
    }
    @Test void incompleteCleanupKeepsCandidateAndReceiptUnknown() throws Exception {
        pending();Path output=directory.resolve("must-not-write");assertThrows(IOException.class,()->bootstrap.launch(List.of("list","candidate-fail","cleanup-unknown","output",output.toString())));
        JsonNode current=bootstrap.state();assertEquals(old,current.get("active"));assertFalse(current.path("trialInvocation").isNull());assertFalse(current.path("pending").isNull());assertTrue(current.path("badCandidates").isEmpty());assertFalse(Files.exists(output));
        JsonNode receipt=Seed.json(Seed.read(bootstrap.root.resolve("runs/"+current.path("trialInvocation").asText()+".json"),65536));assertEquals("UNKNOWN",receipt.path("initialization").path("cleanup").asText());assertEquals("finished",receipt.path("state").asText());
    }
    @Test void concurrentRunAndDisabledRecoveryNeverStealTheTrial() throws Exception {
        pending();Path gate=directory.resolve("failure-gate"),output=directory.resolve("must-not-write");var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var job=pool.submit(()->assertThrows(IOException.class,()->bootstrap.launch(List.of("list","candidate-fail","wait-ready",gate.toString(),"output",output.toString()))));
            long end=System.nanoTime()+Duration.ofSeconds(120).toNanos();while(!Files.exists(Path.of(gate+".ready"))&&System.nanoTime()<end)Thread.sleep(5);assertTrue(Files.exists(Path.of(gate+".ready")));
            JsonNode reserved=bootstrap.state();assertEquals(0,bootstrap.launch(List.of("list")));assertEquals(reserved,bootstrap.state());
            new UpdatePreparation(bootstrap).configure(false,deadline());Files.writeString(gate,"release");assertNotNull(job.get(30,java.util.concurrent.TimeUnit.SECONDS));
            JsonNode current=bootstrap.state();assertEquals(old,current.get("active"));assertFalse(current.path("trialInvocation").isNull());assertTrue(current.path("badCandidates").isEmpty());assertFalse(Files.exists(output));
        }finally{Files.writeString(gate,"release");pool.shutdownNow();assertTrue(pool.awaitTermination(6,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void replacedReceiptPreventsRecoveryEvenWithValidFailureNotification() throws Exception {
        pending();ObjectNode expected=(ObjectNode)bootstrap.state();JsonNode candidate=expected.get("pending");String trial=UUID.randomUUID().toString();expected.put("trialInvocation",trial);bootstrap.atomic(bootstrap.root.resolve("active.json"),expected);
        JsonNode child=Bootstrap.identity(ProcessHandle.current());ObjectNode failure=Seed.JSON.createObjectNode().put("protocol",3).put("type","FAILED").put("nonce",UUID.randomUUID().toString()).put("invocation",trial).put("failure","BUNDLED_PINS_INVALID").put("cleanup","NOT_STARTED");failure.set("slot",candidate);failure.set("pid",child.get("pid"));failure.set("startedAt",child.get("startedAt"));failure.putArray("jobs");
        ObjectNode record=Seed.JSON.createObjectNode().put("schemaVersion",3).put("kind","command").put("invocationId",trial).put("state","finished").put("exitCode",1).put("startup","failed").put("argsSha256",Seed.hash(Seed.JSON.writeValueAsBytes(List.of("list"))));record.set("slot",candidate);record.set("bootstrap",child);record.set("child",child);record.set("initialization",failure);
        Path run=bootstrap.root.resolve("runs/"+trial+".json"),replacement=bootstrap.root.resolve("staging/replacement");
        try(var channel=bootstrap.create(run)){record.put("receiptKey",bootstrap.fileIdentity(run,deadline()));Bootstrap.write(channel,record);}
        byte[] bytes=Seed.JSON.writeValueAsBytes(record);bootstrap.bytes(replacement,bytes);Files.move(replacement,run,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var channel=java.nio.channels.FileChannel.open(run,StandardOpenOption.READ,StandardOpenOption.WRITE);var held=channel.lock()) {
            long end=System.nanoTime()+Duration.ofMinutes(2).toNanos();assertTrue(assertThrows(IOException.class,()->bootstrap.recoverTrial(trial,candidate,expected,run,channel,record,end)).getMessage().contains("復旧receiptの所有証拠が変化しました"));
        }
        assertEquals(expected,bootstrap.state());assertArrayEquals(bytes,Seed.read(run,65536));
    }
    @Test void schemaTwoIsRejectedWithoutMigratingTheRoot() throws Exception {
        pending();ObjectNode legacy=(ObjectNode)bootstrap.state();legacy.put("schemaVersion",2);legacy.remove("badCandidates");bootstrap.atomic(bootstrap.root.resolve("active.json"),legacy);
        assertTrue(assertThrows(IOException.class,bootstrap::state).getMessage().contains("schemaVersionが未対応"));assertEquals(legacy,Seed.json(Seed.read(bootstrap.root.resolve("active.json"),65536)));
    }
    @Test void rawAndOfflineKeepPendingAndActiveUnchanged() throws Exception {
        pending();JsonNode expected=bootstrap.state();assertEquals(0,bootstrap.launch(List.of("raw")));assertEquals(expected,bootstrap.state());assertEquals(0,bootstrap.launch(List.of("list","--offline")));assertEquals(expected,bootstrap.state());
    }
    @Test void disabledUpdatesNeverStartThePendingTrial() throws Exception {
        pending();new UpdatePreparation(bootstrap).configure(false,deadline());JsonNode expected=bootstrap.state();assertEquals(0,bootstrap.launch(List.of("list")));assertEquals(expected,bootstrap.state());
    }
    @Test void explicitLocalPinDoesNotConsumeOrPromotePending() throws Exception {
        pending();JsonNode expected=bootstrap.state();String pin="1.1.1@"+expected.path("pending").path("payloadSha256").asText();assertEquals(0,bootstrap.launch(List.of("list"),pin));assertEquals(expected,bootstrap.state());assertEquals(0,bootstrap.launch(List.of("raw"),"1.1.0@"+old.path("payloadSha256").asText()));assertEquals(expected,bootstrap.state());
        assertThrows(IOException.class,()->bootstrap.launch(List.of("list"),"9.9.9@"+"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->bootstrap.launch(List.of("list"),"bad"));assertEquals(expected,bootstrap.state());
    }
    @Test void initializationFailureKeepsOldActiveAndUnknownTrialWithoutOutputOrRetry() throws Exception {
        pending();Path output=directory.resolve("must-not-exist");assertThrows(IOException.class,()->bootstrap.launch(List.of("list","init-fail","output",output.toString())));JsonNode unknown=bootstrap.state();assertEquals(old,unknown.get("active"));assertFalse(unknown.path("pending").isNull());assertFalse(unknown.path("trialInvocation").isNull());assertFalse(Files.exists(output));assertEquals(0,bootstrap.launch(List.of("raw")));assertEquals(unknown,bootstrap.state());
    }
    @Test void malformedPinNeverCreatesTheRootAndMissingPinNeverInstallsTheSeed() throws Exception {
        directory=directory.toRealPath();Fixture f=new Fixture(directory);Bootstrap b=f.bootstrap(directory.resolve("root"));assertThrows(IllegalArgumentException.class,()->b.launch(List.of("--help"),"bad"));assertFalse(Files.exists(b.root));assertThrows(IOException.class,()->b.launch(List.of("--help"),"1.1.0@"+f.seed().hash));assertFalse(Files.exists(b.root.resolve("active.json")));assertFalse(Files.exists(b.root.resolve("tools")));
    }
    @Test void stateChangedBetweenPrepareAndReadyPreventsGoAndOutput() throws Exception {
        pending();Path gate=directory.resolve("gate"),output=directory.resolve("must-not-write");var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {var job=pool.submit(()->assertThrows(IOException.class,()->bootstrap.launch(List.of("list","wait-ready",gate.toString(),"output",output.toString()))));long end=System.nanoTime()+Duration.ofSeconds(120).toNanos();while(!Files.exists(Path.of(gate+".ready"))&&System.nanoTime()<end)Thread.sleep(5);assertTrue(Files.exists(Path.of(gate+".ready")));
            try(var held=bootstrap.lock(deadline())){ObjectNode state=(ObjectNode)bootstrap.state();state.put("highestAcceptedSequence",3);bootstrap.atomic(bootstrap.root.resolve("active.json"),state);}Files.writeString(gate,"GO may be requested");assertNotNull(job.get(5,java.util.concurrent.TimeUnit.SECONDS));assertFalse(Files.exists(output));assertEquals(old,bootstrap.state().get("active"));assertFalse(bootstrap.state().path("trialInvocation").isNull());}
        finally {Files.writeString(gate,"release");pool.shutdownNow();assertTrue(pool.awaitTermination(6,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
