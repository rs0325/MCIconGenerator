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
        try {var job=pool.submit(()->assertThrows(IOException.class,()->bootstrap.launch(List.of("list","wait-ready",gate.toString(),"output",output.toString()))));long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(!Files.exists(Path.of(gate+".ready"))&&System.nanoTime()<end)Thread.sleep(5);assertTrue(Files.exists(Path.of(gate+".ready")));
            try(var held=bootstrap.lock(deadline())){ObjectNode state=(ObjectNode)bootstrap.state();state.put("highestAcceptedSequence",3);bootstrap.atomic(bootstrap.root.resolve("active.json"),state);}Files.writeString(gate,"GO may be requested");assertNotNull(job.get(5,java.util.concurrent.TimeUnit.SECONDS));assertFalse(Files.exists(output));assertEquals(old,bootstrap.state().get("active"));assertFalse(bootstrap.state().path("trialInvocation").isNull());}
        finally {Files.writeString(gate,"release");pool.shutdownNow();assertTrue(pool.awaitTermination(6,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
