package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RestartReceiptTest {
    @TempDir Path temporary;
    ObjectNode lease;
    HostFacts host;
    WslDistribution distribution;
    String attempt;
    @BeforeEach void setup() throws Exception {
        temporary=temporary.toRealPath();
        lease=new OwnershipTest().lease();HostFacts before=new OwnershipTest().host(OwnershipTest.DEFAULT,List.of(new HostFacts.Distribution("existing",OwnershipTest.DEFAULT,"C:\\existing",2)));
        distribution=new WslDistribution(temporary.toRealPath(),lease,before);ObjectNode owner=distribution.reserve(before);Files.createDirectory(distribution.basePath);
        host=new OwnershipTest().host(OwnershipTest.DEFAULT,List.of(new HostFacts.Distribution(distribution.name,UUID.randomUUID().toString(),distribution.basePath.toString(),2)));
        distribution.registered(owner,host,"b".repeat(64));distribution.stopped(distribution.owner(),host.boot());attempt=UUID.randomUUID().toString();
    }
    ObjectNode identity(){ObjectNode value=lease.deepCopy();value.remove("hostId");return value;}
    ObjectNode proof(ObjectNode owner) {
        ObjectNode value=WindowsFiles.JSON.createObjectNode().put("ready",true).put("boot",OwnershipTest.BOOT).put("labelVerified",true);value.set("claim",owner.path("restartClaim"));value.set("marker",distribution.marker(owner));
        value.putObject("daemon").put("generation",owner.path("generationOwner").asText()).put("boot",OwnershipTest.BOOT).put("pid",123).put("started","456");return value;
    }
    @Test void 別インスタンスのclaimと消費後再読込で古い所有コピーを拒否する() throws Exception {
        ObjectNode old=distribution.owner();WslDistribution second=new WslDistribution(temporary.toRealPath(),lease,host);
        try(var ignored=second.lock(Duration.ofSeconds(1))) {
            ObjectNode claim=second.claimStop(second.verify(host),identity(),attempt,host.boot());assertEquals(old.path("lastStop"),claim.path("lastStop"));
            assertThrows(IOException.class,()->second.claimStop(second.owner(),identity(),UUID.randomUUID().toString(),host.boot()));
            ObjectNode proof=proof(claim);ObjectNode consumed=second.consumeStop(claim,proof);assertFalse(consumed.has("lastStop"));assertFalse(consumed.has("restartClaim"));assertEquals(proof,distribution.owner().path("preparedDaemon"));
            assertThrows(IOException.class,()->second.consumeStop(claim,proof));assertThrows(IOException.class,()->second.claimStop(old,identity(),attempt,host.boot()));
        }
    }
    @Test void 保存途中相当の再読込でも同じclaimだけ再確認できる() throws Exception {
        ObjectNode claim=distribution.claimStop(distribution.owner(),identity(),attempt,host.boot());
        WslDistribution resumed=new WslDistribution(temporary.toRealPath(),lease,host);assertEquals(claim,resumed.claimStop(resumed.owner(),identity(),attempt,host.boot()));
        for(String field:List.of("ready","labelVerified")) {ObjectNode bad=proof(claim).put(field,false);assertThrows(IOException.class,()->resumed.consumeStop(claim,bad));assertEquals(claim,resumed.owner());}
        ObjectNode foreign=identity().put("ownerId",UUID.randomUUID().toString());assertThrows(IOException.class,()->resumed.claimStop(resumed.owner(),foreign,attempt,host.boot()));
        assertThrows(IOException.class,()->resumed.claimStop(resumed.owner(),identity(),attempt,UUID.randomUUID().toString()));
        ObjectNode consumed=resumed.consumeStop(claim,proof(claim));assertEquals(consumed,new WslDistribution(temporary.toRealPath(),lease,host).verify(host));
    }
    @Test void 消費後journal確定前の中断はreceiptを戻さず同じ証明を保持する() throws Exception {
        Path job=Files.createDirectory(temporary.resolve("job"));ObjectNode evidence;
        try(OperationJournal journal=OperationJournal.acquire(job,lease,Duration.ofSeconds(1));var ignored=distribution.lock(Duration.ofSeconds(1))) {
            journal.begin("daemon-prepare",host.boot());ObjectNode claim=distribution.claimStop(distribution.owner(),identity(),attempt,host.boot());evidence=proof(claim);distribution.consumeStop(claim,evidence);
        }
        try(OperationJournal journal=OperationJournal.acquire(job,lease,Duration.ofSeconds(1));var ignored=distribution.lock(Duration.ofSeconds(1))) {
            assertTrue(journal.pending());assertEquals(evidence,distribution.verify(host).path("preparedDaemon"));assertFalse(distribution.owner().has("lastStop"));journal.settled(evidence);
        }
        try(OperationJournal journal=OperationJournal.acquire(job,lease,Duration.ofSeconds(1))) {assertFalse(journal.pending());assertEquals(evidence,journal.state().path("evidence"));}
    }
    @Test void 世代lock解放と別プロセス終了だけではclaimを奪えない() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();if(!Files.exists(Path.of(executable)))executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        List<String> paths=new ArrayList<>();for(Class<?> type:List.of(RestartReceiptTest.class,WindowsFiles.class,com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class))paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        WindowsFiles.write(temporary.resolve("fixture.json"),lease);String contender=UUID.randomUUID().toString();Process first;
        try(var ignored=distribution.lock(Duration.ofSeconds(1))) {
            first=new ProcessBuilder(executable,"-cp",String.join(java.io.File.pathSeparator,paths),ClaimProcess.class.getName(),temporary.toString(),attempt).redirectErrorStream(true).start();
            var waiting=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return new java.io.BufferedReader(new java.io.InputStreamReader(first.getInputStream())).readLine();}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}});
            assertEquals("LOCK_BUSY",waiting.get(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(first.isAlive());
        }
        assertTrue(first.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,first.exitValue(),new String(first.getInputStream().readAllBytes()));
        Process second=new ProcessBuilder(executable,"-cp",String.join(java.io.File.pathSeparator,paths),ClaimProcess.class.getName(),temporary.toString(),contender).redirectErrorStream(true).start();
        assertTrue(second.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(2,second.exitValue());assertEquals(attempt,distribution.owner().path("restartClaim").path("keeperAttempt").asText());
    }
    public static class ClaimProcess {
        public static void main(String[] args) throws Exception {
            Path root=Path.of(args[0]);ObjectNode lease=(ObjectNode)WindowsFiles.json(root.resolve("fixture.json"));ObjectNode identity=lease.deepCopy();identity.remove("hostId");
            WslDistribution distribution=new WslDistribution(root,lease,new OwnershipTest().host(OwnershipTest.DEFAULT,List.of()));
            try(var channel=java.nio.channels.FileChannel.open(distribution.anchor.resolve("generation.lock"),StandardOpenOption.WRITE)) {
                var lock=channel.tryLock();if(lock==null){System.out.println("LOCK_BUSY");System.out.flush();}else lock.release();
            }
            try(var ignored=distribution.lock(Duration.ofSeconds(5))){distribution.claimStop(distribution.owner(),identity,args[1],OwnershipTest.BOOT);}catch(IOException failure){System.exit(2);}
        }
    }
}
