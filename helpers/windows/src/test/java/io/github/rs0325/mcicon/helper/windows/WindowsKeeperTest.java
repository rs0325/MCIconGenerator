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
    @Test void 未公開keeperはnative終了とownerを確認してdoneなしでも回復する() throws Exception {
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
            WindowsFiles.write(tmp.resolve("keeper.json"),changed);var observed=WindowsFiles.json(tmp.resolve("keeper.json"));int[] spawned={0};if(java.util.Set.of("valid","done","client","intent").contains(mutation)){WindowsKeeper.pendingRequired(tmp,changedLease,changedJournal,observed,commands);spawned[0]++;assertEquals(1,spawned[0]);}
            else{assertThrows(IOException.class,()->{WindowsKeeper.pendingRequired(tmp,changedLease,changedJournal,observed,commands);spawned[0]++;},mutation);assertEquals(0,spawned[0]);}
        }
    }
    @Test void releaseNeedsOneCompleteBoundedFrameAndEof() throws Exception {
        assertEquals("release",WindowsKeeper.releaseInput(input("{\"operation\":\"release\"}\n")).path("operation").asText());
        for(String value:new String[]{"","{\"operation\":","{}","{}\nextra","{}\n{}\n","[]\n"," ".repeat(4097)+"\n"})assertThrows(IOException.class,()->WindowsKeeper.releaseInput(input(value)));
    }
    @TempDir Path root;
    @Test void recoveryRequiresPositiveDeathAndRejectsUnknownBeforeGuestAction() throws Exception {
        Instant expected=Instant.parse("2026-10-21T00:00:00Z");assertTrue(WindowsKeeper.ownerDead(java.util.Optional.empty(),expected));
        assertTrue(WindowsKeeper.ownerDead(java.util.Optional.of(process(false,java.util.Optional.empty(),false)),expected));
        assertTrue(WindowsKeeper.ownerDead(java.util.Optional.of(process(true,java.util.Optional.of(expected.plusSeconds(1)),false)),expected));
        assertFalse(WindowsKeeper.ownerDead(java.util.Optional.of(process(true,java.util.Optional.of(expected),false)),expected));
        for(ProcessHandle unknown:new ProcessHandle[]{process(true,java.util.Optional.empty(),false),process(true,java.util.Optional.empty(),true)}){
            int[] guestActions={0};assertThrows(IOException.class,()->{if(WindowsKeeper.ownerDead(java.util.Optional.of(unknown),expected))guestActions[0]++;});assertEquals(0,guestActions[0]);
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
    private static InputStream input(String text){return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
}
