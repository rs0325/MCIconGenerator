package io.github.rs0325.mcicon.helper.windows;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class WindowsKeeperTest {
    @Test void releasedPeerReceiptUsesExact64BytesOnTheLockedChannel() throws Exception {
        root=root.toRealPath();String host=java.util.UUID.randomUUID().toString(),id=java.util.UUID.randomUUID().toString();Path jobs=Files.createDirectory(root.resolve("jobs")),job=Files.createDirectory(jobs.resolve(id)),receipt=job.resolve("lease.lock");Files.createDirectory(job.resolve("tmp"));
        Files.writeString(root.resolve("host-id"),host,StandardCharsets.US_ASCII);
        ObjectNode value=WindowsFiles.JSON.createObjectNode().put("schemaVersion",1).put("jobId",id).put("ownerId",java.util.UUID.randomUUID().toString()).put("toolVersion","1.1.0").put("workerSha256","b".repeat(64)).put("runtimeId","qa-windows").put("descriptorSha256","a".repeat(64)).put("hostId",host).put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("createdAt",Instant.EPOCH.toString()).put("state","released");
        value.putObject("requirements").put("containerMemoryMiB",3072).put("heapMemoryMiB",2048).put("cpus",4).put("preparationDeadline",Instant.EPOCH.toString());value.putObject("resources");ObjectNode identity=value.deepCopy();identity.remove(java.util.List.of("state","resources"));byte[] correct=io.github.rs0325.mcicon.helper.LeaseFile.receipt(identity);
        var command=new io.github.rs0325.mcicon.helper.HelperProtocol.Command(root,jobs.resolve("current/lease.json"),"release",java.util.List.of());
        for(String mutation:java.util.List.of("valid","short","long","corrupt","other-owner","interrupted")) {
            ObjectNode changed=value.deepCopy();byte[] bytes=correct.clone();
            switch(mutation){case "short"->bytes=java.util.Arrays.copyOf(correct,63);case "long"->bytes=java.util.Arrays.copyOf(correct,65);case "corrupt"->bytes[0]^=1;case "other-owner"->changed.put("ownerId",java.util.UUID.randomUUID().toString());default->{}}
            WindowsFiles.write(job.resolve("lease.json"),changed);Files.write(receipt,bytes);
            if(mutation.equals("interrupted"))Thread.currentThread().interrupt();
            try {var result=LeaseReferences.inspect(command,root.resolve("helper.exe"),"a".repeat(64));assertEquals(!mutation.equals("valid"),result.unknown(),mutation);if(mutation.equals("valid"))assertTrue(result.stopAllowed());else assertFalse(result.stopAllowed());}
            finally{Thread.interrupted();}
        }
    }
    @Test void releasedPeerWithBusyReceiptStillNeedsClosedWorkAndOwnJob0() throws Exception {
        root=root.toRealPath();String host=java.util.UUID.randomUUID().toString(),id=java.util.UUID.randomUUID().toString();Path jobs=Files.createDirectory(root.resolve("jobs")),job=Files.createDirectory(jobs.resolve(id)),tmp=Files.createDirectory(job.resolve("tmp")),receipt=job.resolve("lease.lock"),exe=root.resolve("helper.exe");
        Files.writeString(root.resolve("host-id"),host,StandardCharsets.US_ASCII);
        ObjectNode lease=WindowsFiles.JSON.createObjectNode().put("schemaVersion",1).put("jobId",id).put("ownerId",java.util.UUID.randomUUID().toString()).put("toolVersion","1.1.0").put("workerSha256","b".repeat(64)).put("runtimeId","qa-windows").put("descriptorSha256","a".repeat(64)).put("hostId",host).put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("createdAt",Instant.EPOCH.toString()).put("state","released");lease.putObject("requirements").put("containerMemoryMiB",3072).put("heapMemoryMiB",2048).put("cpus",4).put("preparationDeadline",Instant.EPOCH.toString());lease.putObject("resources");
        ObjectNode receiptIdentity=lease.deepCopy();receiptIdentity.remove(java.util.List.of("state","resources"));WindowsFiles.write(job.resolve("lease.json"),lease);Files.write(receipt,io.github.rs0325.mcicon.helper.LeaseFile.receipt(receiptIdentity));
        ObjectNode identity=WindowsFiles.JSON.createObjectNode();for(String key:java.util.List.of("jobId","ownerId","descriptorSha256"))identity.set(key,lease.required(key));Path prefix=tmp.resolve("native-"+java.util.UUID.randomUUID());ObjectNode client=WindowsFiles.JSON.createObjectNode().put("pid",11).put("started",22).put("parent",33).put("parentStarted",44).put("boot",OwnershipTest.BOOT);
        ObjectNode keeper=WindowsFiles.JSON.createObjectNode().put("state","closed").put("attempt",OwnershipTest.BOOT).put("prefix",prefix.toString()).put("closeIntent",true).put("jobEmpty",true).put("stopIntent",false);keeper.set("identity",identity);keeper.set("client",client);keeper.putObject("workProof").put("closed",true).put("actors",0).put("containers",0).set("identity",identity);WindowsFiles.write(tmp.resolve("keeper.json"),keeper);
        WindowsFiles.write(Path.of(prefix+".client.json"),client);WindowsFiles.write(Path.of(prefix+".state.json"),client);WindowsFiles.write(Path.of(prefix+".done.json"),client);WindowsFiles.write(Path.of(prefix+".intent.json"),WindowsFiles.JSON.createObjectNode().put("native",exe.getParent().resolve("app/support/windows-host.exe").toString()));
        ObjectNode journalIdentity=identity.deepCopy();journalIdentity.set("hostId",lease.required("hostId"));WindowsFiles.write(tmp.resolve("windows-operation.json"),WindowsFiles.JSON.createObjectNode().put("state","released").set("identity",journalIdentity));
        var command=new io.github.rs0325.mcicon.helper.HelperProtocol.Command(root,jobs.resolve("current/lease.json"),"release",java.util.List.of());
        try(var channel=java.nio.channels.FileChannel.open(receipt,StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.lock(64,1,false)) {
            assertTrue(LeaseReferences.inspect(command,exe,"a".repeat(64)).stopAllowed());Files.delete(Path.of(prefix+".done.json"));assertTrue(LeaseReferences.inspect(command,exe,"a".repeat(64)).unknown());
        }
    }
    @Test void unpublishedKeeperRecoveryRechecksAttemptClientControllerAndJob0BeforeGuest() throws Exception {
        Path tmp=Files.createDirectory(root.resolve("tmp")).toRealPath(),nativeExe=root.resolve("windows-host.exe");String attempt=java.util.UUID.randomUUID().toString(),boot=OwnershipTest.BOOT;Path prefix=tmp.resolve("native-"+java.util.UUID.randomUUID());
        ObjectNode lease=WindowsFiles.JSON.createObjectNode().put("jobId","j").put("ownerId","o").put("descriptorSha256","d").put("hostId","h").put("pid",ProcessHandle.current().pid()).put("processStartedAt",Instant.EPOCH.toString());lease.putObject("resources");
        ObjectNode identity=WindowsFiles.JSON.createObjectNode();for(String field:java.util.List.of("jobId","ownerId","descriptorSha256","hostId"))identity.set(field,lease.path(field));
        ObjectNode journal=WindowsFiles.JSON.createObjectNode().put("state","pending").put("operation","keeper-start").put("boot",boot).put("intent",java.util.UUID.randomUUID().toString());journal.set("identity",identity.deepCopy());identity.remove("hostId");
        ObjectNode prior=WindowsFiles.JSON.createObjectNode().put("state","starting").put("attempt",attempt).put("boot",boot).put("controllerPid",ProcessHandle.current().pid()).put("controllerStarted",0).put("recovery",false).put("prefix",prefix.toString());prior.set("identity",identity);
        ObjectNode state=WindowsFiles.JSON.createObjectNode().put("parent",11).put("parentStarted",22).put("pid",33).put("started",44).put("boot",boot),client=state.deepCopy().put("corePid",ProcessHandle.current().pid()).put("coreStarted",0).put("clientPid",55).put("clientStarted",66);
        java.util.List<String> guest=java.util.List.of("/fixture/wsl.exe","--distribution","MCIcon-fixture");ObjectNode intent=WindowsFiles.JSON.createObjectNode().put("native",nativeExe.toString()).put("parent",11).put("started",22).put("argvSha256",WindowsFiles.hash(WindowsFiles.JSON.writeValueAsBytes(guest)));
        Commands commands=new Commands(nativeExe,tmp,java.util.Map.of(),Long.MAX_VALUE);
        for(String mutation:java.util.List.of("valid","done","client","attempt","identity","controller","boot","intent","operation","recovery","live")) {
            ObjectNode changed=prior.deepCopy(),changedClient=client.deepCopy(),changedIntent=intent.deepCopy(),changedJournal=journal.deepCopy(),changedLease=lease.deepCopy();
            switch(mutation){case "client"->changedClient.put("coreStarted",9);case "identity"->((ObjectNode)changed.path("identity")).put("ownerId","different");case "controller"->changed.put("controllerPid",999);case "boot"->changed.put("boot",java.util.UUID.randomUUID().toString());case "intent"->changedIntent.put("argvSha256","0".repeat(64));case "operation"->changedJournal.put("operation","import");case "recovery"->changed.put("recovery",true);case "live"->changedLease.put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());default->{}}
            WindowsFiles.write(tmp.resolve("keeper-"+attempt+".json"),mutation.equals("attempt")?WindowsFiles.JSON.createObjectNode():changed);WindowsFiles.write(Path.of(prefix+".intent.json"),changedIntent);WindowsFiles.write(Path.of(prefix+".state.json"),state);WindowsFiles.write(Path.of(prefix+".client.json"),changedClient);WindowsFiles.write(Path.of(prefix+".done.json"),state);if(mutation.equals("done"))Files.delete(Path.of(prefix+".done.json"));
            WindowsFiles.write(tmp.resolve("keeper.json"),changed);var observed=WindowsFiles.json(tmp.resolve("keeper.json"));int[] spawned={0};if(mutation.equals("valid")){WindowsKeeper.pendingRequired(tmp,changedLease,changedJournal,observed,commands,guest);spawned[0]++;assertEquals(1,spawned[0]);}
            else{assertThrows(IOException.class,()->{WindowsKeeper.pendingRequired(tmp,changedLease,changedJournal,observed,commands,guest);spawned[0]++;},mutation);assertEquals(0,spawned[0]);}
        }
    }
    @Test void releaseNeedsOneCompleteBoundedFrameAndEof() throws Exception {
        assertEquals("release",WindowsKeeper.releaseInput(input("{\"operation\":\"release\"}\n")).path("operation").asText());
        for(String value:new String[]{"","{\"operation\":","{}","{}\nextra","{}\n{}\n","[]\n"," ".repeat(4097)+"\n"})assertThrows(IOException.class,()->WindowsKeeper.releaseInput(input(value)));
    }
    @TempDir Path root;
    @Test void keeperClassificationRequiresLiveClientOrClosedWorkAndJobReceipt() throws Exception {
        root=root.toRealPath();Path job=root.resolve("job"),tmp=job.resolve("tmp"),exe=root.resolve("helper.exe");Files.createDirectories(tmp);String prefix=tmp.resolve("native-00000000-0000-0000-0000-000000000001").toString();
        ObjectNode lease=WindowsFiles.JSON.createObjectNode().put("jobId","j").put("ownerId","o").put("descriptorSha256","d");lease.putObject("resources");
        ObjectNode identity=lease.deepCopy();identity.remove("resources");
        ObjectNode client=WindowsFiles.JSON.createObjectNode().put("pid",11).put("started",22).put("parent",33).put("parentStarted",44).put("boot",OwnershipTest.BOOT).put("clientPid",ProcessHandle.current().pid()).put("clientStarted",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());
        ObjectNode record=WindowsFiles.JSON.createObjectNode().put("attempt",OwnershipTest.BOOT).put("state","ready").put("prefix",prefix);record.set("identity",identity);record.set("client",client);
        WindowsFiles.write(Path.of(prefix+".client.json"),client);WindowsFiles.write(Path.of(prefix+".state.json"),client);WindowsFiles.write(Path.of(prefix+".intent.json"),WindowsFiles.JSON.createObjectNode().put("native",exe.getParent().resolve("app/support/windows-host.exe").toString()));WindowsFiles.write(tmp.resolve("keeper.json"),record);
        var live=LeaseReferences.class.getDeclaredMethod("keeperLive",Path.class,com.fasterxml.jackson.databind.JsonNode.class,Path.class);live.setAccessible(true);assertDoesNotThrow(()->live.invoke(null,job,lease,exe));
        record.put("state","closing");WindowsFiles.write(tmp.resolve("keeper.json"),record);assertThrows(java.lang.reflect.InvocationTargetException.class,()->live.invoke(null,job,lease,exe));
        record.put("state","closed").put("closeIntent",true).put("jobEmpty",true);var proof=record.putObject("workProof").put("closed",true).put("actors",0).put("containers",0);proof.set("identity",identity);WindowsFiles.write(tmp.resolve("keeper.json"),record);WindowsFiles.write(tmp.resolve("windows-operation.json"),WindowsFiles.JSON.createObjectNode().put("state","released").set("identity",identity));WindowsFiles.write(Path.of(prefix+".done.json"),client);
        var quiet=LeaseReferences.class.getDeclaredMethod("quiescent",Path.class,com.fasterxml.jackson.databind.JsonNode.class,Path.class);quiet.setAccessible(true);assertEquals(true,quiet.invoke(null,job,lease,exe));
        for(String field:new String[]{"closeIntent","jobEmpty","workProof"}){ObjectNode invalid=record.deepCopy();invalid.remove(field);WindowsFiles.write(tmp.resolve("keeper.json"),invalid);assertThrows(java.lang.reflect.InvocationTargetException.class,()->quiet.invoke(null,job,lease,exe));}
        WindowsFiles.write(tmp.resolve("keeper.json"),record);Files.delete(Path.of(prefix+".done.json"));assertThrows(java.lang.reflect.InvocationTargetException.class,()->quiet.invoke(null,job,lease,exe));
    }
    @Test void recoveryRequiresPositiveDeathAndRejectsUnknownBeforeGuestAction() throws Exception {
        Instant expected=Instant.parse("2026-10-21T00:00:00Z");assertTrue(LeaseReferences.ownerDead(java.util.Optional.empty(),expected));
        assertTrue(LeaseReferences.ownerDead(java.util.Optional.of(process(false,java.util.Optional.empty(),false)),expected));
        assertTrue(LeaseReferences.ownerDead(java.util.Optional.of(process(true,java.util.Optional.of(expected.plusSeconds(1)),false)),expected));
        assertFalse(LeaseReferences.ownerDead(java.util.Optional.of(process(true,java.util.Optional.of(expected),false)),expected));
        for(ProcessHandle unknown:new ProcessHandle[]{process(true,java.util.Optional.empty(),false),process(true,java.util.Optional.empty(),true)}){
            int[] guestActions={0};assertThrows(IOException.class,()->{if(LeaseReferences.ownerDead(java.util.Optional.of(unknown),expected))guestActions[0]++;});assertEquals(0,guestActions[0]);
        }
    }
    private static ProcessHandle process(boolean alive,java.util.Optional<Instant> started,boolean denied) {
        ProcessHandle.Info info=(ProcessHandle.Info)java.lang.reflect.Proxy.newProxyInstance(ProcessHandle.Info.class.getClassLoader(),new Class<?>[]{ProcessHandle.Info.class},(proxy,method,args)->{if(method.getName().equals("startInstant")){if(denied)throw new SecurityException("fixture");return started;}throw new UnsupportedOperationException();});
        return (ProcessHandle)java.lang.reflect.Proxy.newProxyInstance(ProcessHandle.class.getClassLoader(),new Class<?>[]{ProcessHandle.class},(proxy,method,args)->switch(method.getName()){case "isAlive"->alive;case "info"->info;default->throw new UnsupportedOperationException();});
    }
    @Test void unexpectedClientExitCannotBecomeCloseIntentAndStopRequiresProof() throws Exception {
        ObjectNode record=WindowsFiles.JSON.createObjectNode();record.putObject("client").put("clientPid",ProcessHandle.current().pid()).put("clientStarted",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());assertDoesNotThrow(()->WindowsKeeper.requireClientLive(record));
        ((ObjectNode)record.path("client")).put("clientStarted",1);assertThrows(IOException.class,()->WindowsKeeper.requireClientLive(record));assertFalse(record.has("closeIntent"));assertThrows(IOException.class,()->WindowsKeeper.requireCloseProof(record));
        record.put("closeIntent",true).put("stopIntent",true);assertThrows(IOException.class,()->WindowsKeeper.requireCloseProof(record));record.put("stopConfirmed",true);assertDoesNotThrow(()->WindowsKeeper.requireCloseProof(record));record.remove("stopConfirmed");record.put("stopIntent",false);assertDoesNotThrow(()->WindowsKeeper.requireCloseProof(record));
    }
    @Test void completionRequiresBoundedOwnTupleAndConsistentKnownChildAndPhase() throws Exception {
        Path prefix=root.resolve("native-test"),file=Path.of(prefix+".completion.json");ObjectNode client=WindowsFiles.JSON.createObjectNode().put("pid",11).put("started",22).put("clientPid",33).put("clientStarted",44).put("corePid",55).put("coreStarted",66).put("boot",OwnershipTest.BOOT);
        for(int exit:new int[]{0,1,125,-1}) {
            ObjectNode value=WindowsFiles.JSON.createObjectNode().put("schema",1).put("phase","drain-empty").put("childKnown",true).put("childExit",Integer.toUnsignedLong(exit)).put("plannedGuardExit",exit).put("jobEmpty",true);value.set("client",client);WindowsFiles.write(file,value);
            assertArrayEquals(WindowsFiles.JSON.writeValueAsBytes(value),WindowsFiles.JSON.writeValueAsBytes(WindowsKeeper.completion(prefix,client,exit)));
        }
        ObjectNode value=WindowsFiles.JSON.createObjectNode().put("schema",1).put("phase","owner-death-cleanup").put("childKnown",false).putNull("childExit").put("plannedGuardExit",125).put("jobEmpty",true);value.set("client",client);WindowsFiles.write(file,value);assertArrayEquals(WindowsFiles.JSON.writeValueAsBytes(value),WindowsFiles.JSON.writeValueAsBytes(WindowsKeeper.completion(prefix,client,125)));
        for(String phase:new String[]{"owner-death-cleanup","residual-terminated"}){value.put("phase",phase).put("childKnown",true).put("childExit",7);WindowsFiles.write(file,value);assertArrayEquals(WindowsFiles.JSON.writeValueAsBytes(value),WindowsFiles.JSON.writeValueAsBytes(WindowsKeeper.completion(prefix,client,125)));}
        for(String mutation:new String[]{"schema","phase","client","jobEmpty","known","unknown-number","known-null","negative","overflow","float","planned","actual","absent","oversize","partial","extra"}){
            ObjectNode invalid=value.deepCopy();int actual=125;
            switch(mutation){case "schema"->invalid.put("schema",4294967297L);case "phase"->invalid.put("phase","terminated");case "client"->((ObjectNode)invalid.path("client")).put("coreStarted",1);case "jobEmpty"->invalid.put("jobEmpty",1);case "known"->invalid.put("childKnown",1);case "unknown-number"->invalid.put("childKnown",false);case "known-null"->invalid.putNull("childExit");case "negative"->invalid.put("childExit",-1);case "overflow"->invalid.put("childExit",4294967296L);case "float"->invalid.put("childExit",7.0);case "planned"->invalid.put("plannedGuardExit",4294967421L);case "actual"->actual=0;case "extra"->invalid.put("extra",true);default->{}}
            WindowsFiles.write(file,invalid);if(mutation.equals("absent"))Files.delete(file);if(mutation.equals("oversize"))Files.writeString(file," ".repeat(4097));if(mutation.equals("partial"))Files.writeString(file,"{\"schema\":");final int observed=actual;
            assertThrows(IOException.class,()->WindowsKeeper.completion(prefix,client,observed),mutation);
        }
        value.put("phase","drain-empty").put("childKnown",false).putNull("childExit");WindowsFiles.write(file,value);assertThrows(IOException.class,()->WindowsKeeper.completion(prefix,client,125));
        value.put("childKnown",true).put("childExit",0);WindowsFiles.write(file,value);assertThrows(IOException.class,()->WindowsKeeper.completion(prefix,client,125));
    }
    private static InputStream input(String text){return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
}
