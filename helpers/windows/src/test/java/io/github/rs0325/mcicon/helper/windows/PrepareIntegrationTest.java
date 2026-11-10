package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** native/guest境界をJava/.NETのfixture子プロセスに置き換え、hostの保存と照合を通す。 */
class PrepareIntegrationTest {
    @TempDir Path root;
    Path tmp,leasePath,launcher;
    ObjectNode lease,descriptor,manifest;
    HostFacts host;
    WslDistribution distribution;
    String attempt=UUID.randomUUID().toString(),classpath;

    @BeforeEach void setup() throws Exception {
        root=root.toRealPath();lease=new OwnershipTest().lease().put("schemaVersion",1).put("toolVersion","1.1.0").put("workerSha256","b".repeat(64)).put("runtimeId","fixture").put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("createdAt",Instant.EPOCH.toString()).put("state","active");
        lease.putObject("requirements").put("containerMemoryMiB",3072).put("heapMemoryMiB",2048).put("cpus",4).put("preparationDeadline",Instant.now().plusSeconds(120).toString());lease.putObject("resources");
        Path job=Files.createDirectories(root.resolve("jobs").resolve(lease.path("jobId").asText()));tmp=Files.createDirectory(job.resolve("tmp"));leasePath=job.resolve("lease.json");Files.writeString(root.resolve("host-id"),lease.path("hostId").asText());saveLease();
        Files.write(tmp.resolve("windows-host.exe"),new byte[0]);
        HostFacts before=new OwnershipTest().host(OwnershipTest.DEFAULT,List.of(new HostFacts.Distribution("existing",OwnershipTest.DEFAULT,"C:\\existing",2)));
        distribution=new WslDistribution(root,lease,before);ObjectNode owner=distribution.reserve(before);Files.createDirectory(distribution.basePath);
        host=new OwnershipTest().host(OwnershipTest.DEFAULT,List.of(before.distributions().getFirst(),new HostFacts.Distribution(distribution.name,UUID.randomUUID().toString(),distribution.basePath.toString(),2)));distribution.registered(owner,host,"b".repeat(64));distribution.stopped(distribution.owner(),host.boot());
        ObjectNode facts=WindowsFiles.JSON.valueToTree(host);facts.put("arch","x86_64");WindowsFiles.write(tmp.resolve("fixture-facts.json"),facts);
        descriptor=new OwnershipTest().descriptor();descriptor.putObject("rendering").put("imageId","sha256:"+"c".repeat(64));descriptor.putArray("artifacts");
        Path artifacts=Files.createDirectories(root.resolve("runtimes/fixture").resolve(lease.path("descriptorSha256").asText()));
        for(String role:List.of("renderer-image","guest-base")) {
            Path file=artifacts.resolve(role+".tar.xz");Files.writeString(file,role);((com.fasterxml.jackson.databind.node.ArrayNode)descriptor.path("artifacts")).addObject().put("id",role).put("role",role).put("format","tar.xz").put("bytes",Files.size(file)).put("sha256",WindowsFiles.hash(file));
        }
        manifest=WindowsFiles.JSON.createObjectNode().put("schemaVersion",1).put("guestBridgeAbi",1);manifest.putArray("files");var base=manifest.putObject("guestBase").put("bridgeSha256","d".repeat(64)).put("cpuProbeSha256","e".repeat(64));base.putObject("engines");base.put("bytes",Files.size(artifacts.resolve("guest-base.tar.xz"))).put("sha256",WindowsFiles.hash(artifacts.resolve("guest-base.tar.xz")));WindowsFiles.write(tmp.resolve("payload.json"),manifest);
        boolean windows=System.getProperty("os.name").startsWith("Windows");String java=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString();List<String> paths=new ArrayList<>();
        for(Class<?> type:List.of(PrepareIntegrationTest.class,WindowsFiles.class,LeaseFile.class,com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class,org.tukaani.xz.XZInputStream.class))paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        launcher=tmp.resolve(windows?"fixture.cmd":"fixture");classpath=String.join(File.pathSeparator,paths);String cp=classpath;
        Files.writeString(launcher,windows?"@echo off\r\nset MCICON_FIXTURE_CMD=1\r\nset MCICON_FIXTURE_MAIN="+Guest.class.getName()+"\r\nif \"%~1\"==\"exec\" set MCICON_FIXTURE_MAIN="+NativeExec.class.getName()+"\r\n\""+java+"\" -cp \""+cp+"\" %MCICON_FIXTURE_MAIN% %*\r\nexit /b %errorlevel%\r\n":"#!/bin/sh\nif [ \"$1\" = \"exec\" ]; then exec '"+java+"' -cp '"+cp+"' '"+NativeExec.class.getName()+"' \"$@\"; fi\nexec '"+java+"' -cp '"+cp+"' '"+Guest.class.getName()+"' \"$@\"\n");if(!windows)assertTrue(launcher.toFile().setExecutable(true,true));
        Files.writeString(tmp.resolve("fixture-distribution-name"),distribution.name);mode("valid");
    }
    void saveLease() throws Exception {WindowsFiles.write(leasePath,lease);lease=(ObjectNode)WindowsFiles.json(leasePath);ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(leasePath.resolveSibling("lease.lock"),LeaseFile.receipt(identity));}
    void mode(String value) throws Exception {Files.writeString(tmp.resolve("fixture-mode"),value);}
    // OS探索だけを省き、既存のpackageメソッドとfinal依存フィールドへ差し込む。
    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    static void set(Object target,String name,Object value) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    WindowsRuntime runtime(String operation) throws Exception {
        var constructor=LeaseFile.class.getDeclaredConstructor(Path.class,Path.class,ObjectNode.class,JsonNode.class,String.class);constructor.setAccessible(true);ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));LeaseFile file=constructor.newInstance(root,leasePath,identity,descriptor,"windows");
        var controller=new HelperProtocol.Keeper(ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),attempt,Instant.now().plusSeconds(120),false);
        Path support=Files.isDirectory(tmp.resolve("app/support"))?tmp.resolve("app/support"):tmp;WindowsRuntime runtime=allocate(WindowsRuntime.class);set(runtime,"command",new HelperProtocol.Command(root,leasePath,operation,List.of(),operation.equals("keep")?controller:null));set(runtime,"lease",file);set(runtime,"executable",launcher);set(runtime,"temporary",tmp);set(runtime,"wsl",launcher);set(runtime,"payload",new Payload(support));set(runtime,"descriptor",descriptor.deepCopy());
        Map<String,String> environment=new HashMap<>();if(System.getenv("SystemRoot")!=null)environment.put("SystemRoot",System.getenv("SystemRoot"));set(runtime,"environment",environment);set(runtime,"commands",new Commands(support.equals(tmp)?launcher:support.resolve("windows-host.exe"),tmp,environment,Long.MAX_VALUE));set(runtime,"distribution",new WslDistribution(root,file.read(),host));set(runtime,"trace",new io.github.rs0325.mcicon.internal.RuntimePhaseLog(tmp,"windows",operation,operation.equals("keep")?attempt:null));return runtime;
    }
    void readyKeeper() throws Exception {
        WindowsRuntime runtime=runtime("prepare");var keeper=WindowsFiles.JSON.createObjectNode().put("state","ready").put("attempt",attempt);keeper.set("identity",runtime.identity());keeper.putObject("client").put("clientPid",ProcessHandle.current().pid()).put("clientStarted",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());WindowsFiles.write(tmp.resolve("keeper.json"),keeper);
        var resources=distribution.resource(distribution.owner(),"ready",lease.path("jobId").asText());resources.withArray("transports").addObject().put("id",attempt);lease.withObject("resources").set("windows",resources);saveLease();
    }
    List<JsonNode> events() throws Exception {if(!Files.exists(tmp.resolve("fixture-events")))return List.of();List<JsonNode> values=new ArrayList<>();for(String line:Files.readAllLines(tmp.resolve("fixture-events")))values.add(WindowsFiles.JSON.readTree(line));return values;}
    List<String> operations() throws Exception {return events().stream().map(value->value.path("operation").asText()).toList();}
    void pending() throws Exception {try(var journal=runtime("prepare").journal()){assertTrue(journal.pending());assertEquals("daemon-prepare",journal.state().path("operation").asText());}}

    @Test void 別helperのkeepとhelloを経てもreceiptを保持して肯定時だけ消費後loadする() throws Exception {
        JsonNode receipt=distribution.owner().path("lastStop");WindowsRuntime keep=runtime("keep");WindowsKeeper keeper=allocate(WindowsKeeper.class);set(keeper,"runtime",keep);set(keeper,"controller",keep.command.keeper());Method start=WindowsKeeper.class.getDeclaredMethod("start");start.setAccessible(true);
        Commands.Running child=null;
        try {
            start.invoke(keeper);Field field=WindowsKeeper.class.getDeclaredField("child");field.setAccessible(true);child=(Commands.Running)field.get(keeper);assertEquals(receipt,distribution.owner().path("lastStop"));
            WindowsRuntime hello=runtime("prepare");hello.commands.keeper(hello.keeperRecord());var ack=hello.call(hello.request("hello",hello.ready()),null);hello.verifyHello(ack,distribution.owner());assertEquals(receipt,distribution.owner().path("lastStop"));
            runtime("prepare").prepare();assertFalse(distribution.owner().has("lastStop"));assertTrue(distribution.owner().has("preparedDaemon"));
            assertEquals(List.of("keep","hello","hello","daemon-prepare","load","create","start","remove"),operations());
            for(JsonNode event:events())if(Set.of("keep","hello","daemon-prepare").contains(event.path("operation").asText()))assertEquals(receipt,event.path("owner").path("lastStop"));else {assertFalse(event.path("owner").has("lastStop"));assertTrue(event.path("owner").has("preparedDaemon"));}
            try(var journal=runtime("prepare").journal()){assertFalse(journal.pending());}
        } finally {if(child==null){Field field=WindowsKeeper.class.getDeclaredField("child");field.setAccessible(true);child=(Commands.Running)field.get(keeper);}if(child!=null){child.process().getOutputStream().close();assertTrue(child.process().waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}}
    }
    @Test void 肯定ACKでもnative終端が欠落不一致非zeroなら別helperへreceiptを保持する() throws Exception {
        readyKeeper();JsonNode receipt=distribution.owner().path("lastStop");
        for(String mode:List.of("missing-done","mismatch-done","missing-completion","mismatch-completion","nonzero","child-nonzero")) {
            mode(mode);IOException failure=assertThrows(IOException.class,()->runtime("prepare").prepare(),mode);assertTrue(failure.getMessage().contains("Windows入力/実行失敗"),failure.getMessage());
            assertEquals(receipt,distribution.owner().path("lastStop"));assertFalse(distribution.owner().has("preparedDaemon"));pending();assertFalse(operations().contains("load"));
            JsonNode event=events().getLast();assertEquals("daemon-prepare",event.path("operation").asText());assertTrue(event.path("ack").path("ready").asBoolean());
        }
        mode("valid");runtime("prepare").prepare();assertFalse(distribution.owner().has("lastStop"));assertTrue(operations().contains("load"));
    }
    @Test void 消費直後journal保存失敗から再開し本人不一致ではunknownを保持する() throws Exception {
        readyKeeper();mode("crash");assertThrows(IOException.class,()->runtime("prepare").prepare());assertFalse(distribution.owner().has("lastStop"));JsonNode proof=distribution.owner().path("preparedDaemon");assertTrue(proof.path("ready").asBoolean());assertFalse(operations().contains("load"));
        Files.delete(tmp.resolve("windows-operation.json"));Files.move(tmp.resolve("fixture-pending.json"),tmp.resolve("windows-operation.json"));pending();JsonNode journal=WindowsFiles.json(tmp.resolve("windows-operation.json"));
        for(String mode:List.of("guest-mismatch","guest-missing","guest-attempt")) {
            mode(mode);assertThrows(IOException.class,()->runtime("prepare").prepare());pending();assertEquals(journal,WindowsFiles.json(tmp.resolve("windows-operation.json")));assertEquals(proof,distribution.owner().path("preparedDaemon"));assertFalse(distribution.owner().has("restartClaim"));assertFalse(distribution.owner().has("lastStop"));assertFalse(operations().contains("load"));
        }
        mode("valid");runtime("prepare").prepare();assertEquals(proof,distribution.owner().path("preparedDaemon"));assertTrue(operations().contains("load"));
        for(JsonNode event:events())if(event.path("operation").asText().equals("daemon-prepare")&&!event.path("owner").has("lastStop")){assertEquals(proof.path("claim"),event.path("request").path("claim"));assertFalse(event.path("owner").has("restartClaim"));}
    }

    @Test void 回復keeper開始前に専用distroだけを停止して新しいreceiptを保存する() throws Exception {
        readyKeeper();JsonNode previous=distribution.owner().path("lastStop");
        try(var journal=runtime("prepare").journal()) {
            ObjectNode recovered=runtime("prepare").keeperOwner(journal,true);
            assertNotEquals(previous,recovered.path("lastStop"));
            assertTrue(Files.exists(tmp.resolve("fixture-terminated")));
            assertEquals(List.of("--list","--running","--quiet","--terminate",distribution.name,"--list","--running","--quiet"),Files.readAllLines(tmp.resolve("fixture-host-events")));
        }
    }
    @Test void 回復停止の未確認や他lease不明では停止receiptを更新しない() throws Exception {
        readyKeeper();JsonNode previous=distribution.owner().path("lastStop");
        mode("still-running");
        try(var journal=runtime("prepare").journal()) {
            assertThrows(IOException.class,()->runtime("prepare").keeperOwner(journal,true));
        }
        assertEquals(previous,distribution.owner().path("lastStop"));
        Files.deleteIfExists(tmp.resolve("fixture-terminated"));Files.delete(tmp.resolve("fixture-host-events"));
        mode("valid");Files.createDirectory(root.resolve("jobs/unknown"));
        try(var journal=runtime("prepare").journal()) {
            assertThrows(IOException.class,()->runtime("prepare").keeperOwner(journal,true));
        }
        assertFalse(Files.exists(tmp.resolve("fixture-host-events")));
        assertEquals(previous,distribution.owner().path("lastStop"));
    }

    @Test void 回復時の資源所有不一致は停止より前に拒否する() throws Exception {
        readyKeeper();JsonNode previous=distribution.owner().path("lastStop");
        ((ObjectNode)lease.path("resources").path("windows")).put("generationOwner",UUID.randomUUID().toString());saveLease();
        try(var journal=runtime("prepare").journal()) {
            assertThrows(IOException.class,()->runtime("prepare").keeperOwner(journal,true));
        }
        assertFalse(Files.exists(tmp.resolve("fixture-host-events")));
        assertEquals(previous,distribution.owner().path("lastStop"));
    }

    @Test void preflight後の停止と別keeper開始を挟む空回復でも次のprepareを妨げない() throws Exception {
        WindowsRuntime next=runtime("prepare");next.preflight();
        Path nextTmp=tmp,nextLeasePath=leasePath;ObjectNode nextLease=lease;
        lease=lease.deepCopy().put("jobId",UUID.randomUUID().toString()).put("ownerId",UUID.randomUUID().toString());lease.putObject("resources");
        Path job=Files.createDirectories(root.resolve("jobs").resolve(lease.path("jobId").asText()));tmp=Files.createDirectory(job.resolve("tmp"));leasePath=job.resolve("lease.json");saveLease();
        for(String name:List.of("fixture-facts.json","payload.json","windows-host.exe"))Files.copy(nextTmp.resolve(name),tmp.resolve(name));
        mode("empty");WindowsRuntime recovery=runtime("release");
        tmp=nextTmp;leasePath=nextLeasePath;lease=nextLease;
        distribution.stopped(distribution.owner(),host.boot());JsonNode stop=distribution.owner().path("lastStop");
        WindowsRuntime keep=runtime("keep");WindowsKeeper keeper=allocate(WindowsKeeper.class);set(keeper,"runtime",keep);set(keeper,"controller",keep.command.keeper());
        Method start=WindowsKeeper.class.getDeclaredMethod("start");start.setAccessible(true);Commands.Running child=null;
        try {
            start.invoke(keeper);Field field=WindowsKeeper.class.getDeclaredField("child");field.setAccessible(true);child=(Commands.Running)field.get(keeper);
            recovery.commands.keeper(keeper.value());
            try(var ignored=recovery.lock()) {
                ObjectNode recovered=recovery.recoverDaemon(distribution.owner(),WindowsFiles.JSON.createObjectNode().put("attempt",UUID.randomUUID().toString()));
                assertEquals(stop,recovered.path("lastStop"));assertFalse(recovered.has("preparedDaemon"));
                assertTrue(child.process().isAlive());
            }
            // 回収済みleaseの公開を再現し、別keeper生存中は追加停止しない。
            ObjectNode released=recovery.lease.read();released.put("state","released");WindowsFiles.write(recovery.command.lease(),released);
            runtime("prepare").prepare();assertTrue(child.process().isAlive());assertTrue(distribution.owner().has("preparedDaemon"));assertFalse(distribution.owner().has("restartClaim"));
        } finally {
            if(child==null){Field field=WindowsKeeper.class.getDeclaredField("child");field.setAccessible(true);child=(Commands.Running)field.get(keeper);}
            if(child!=null){child.process().getOutputStream().close();assertTrue(child.process().waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    @Test void 回復ACKだけではnative終端不明と矛盾する空証明を確定しない() throws Exception {
        readyKeeper();WindowsRuntime recovery=runtime("release");recovery.commands.keeper(recovery.keeperRecord());JsonNode stop=distribution.owner().path("lastStop");
        for(String mode:List.of("missing-done","mismatch-done","missing-completion","mismatch-completion","nonzero","child-nonzero","empty-ready","empty-daemon")) {
            mode(mode);assertThrows(IOException.class,()->recovery.recoverDaemon(distribution.owner(),recovery.keeperRecord()),mode);
            assertEquals(stop,distribution.owner().path("lastStop"));assertTrue(distribution.owner().has("restartClaim"));assertFalse(distribution.owner().has("preparedDaemon"));
        }
        mode("valid");ObjectNode recovered=recovery.recoverDaemon(distribution.owner(),recovery.keeperRecord());assertTrue(recovered.has("preparedDaemon"));assertFalse(recovered.has("lastStop"));assertFalse(recovered.has("restartClaim"));
    }
    @Test void createだけに準備残予算とnative回収枠内の操作期限を与える() throws Exception {
        WindowsRuntime runtime=runtime("prepare");ObjectNode owner=distribution.owner();assertEquals(30,runtime.request("create",owner).path("timeoutSeconds").asDouble());
        runtime.commands.end(System.nanoTime()+Duration.ofMillis(4500).toNanos());double remaining=runtime.request("create",owner).path("timeoutSeconds").asDouble();assertTrue(remaining>0&&remaining<=2.5);
        runtime.commands.end(System.nanoTime()+Duration.ofMillis(1500).toNanos());assertThrows(IOException.class,()->runtime.request("create",owner));
        runtime.commands.end(System.nanoTime()-1);assertThrows(IOException.class,()->runtime.request("create",owner));
        assertTrue(events().isEmpty());
    }
    @Test void guest期限をnative回収枠とsubcommand上限の両方へ収める() throws Exception {
        readyKeeper();
        for(long remainingMillis:List.of(6500L,4500L)) {
            WindowsRuntime runtime=runtime("prepare");runtime.commands.keeper(runtime.keeperRecord());
            long end=System.nanoTime()+Duration.ofMillis(remainingMillis).toNanos();runtime.commands.end(end);
            ObjectNode request=runtime.request("load",distribution.owner());byte[] original=WindowsFiles.JSON.writeValueAsBytes(request);
            assertEquals(0,runtime.commands.request(runtime.guest(),original,null,Duration.ofSeconds(3)).exit());
            double seconds=events().getLast().path("request").path("timeoutSeconds").asDouble();
            assertTrue(seconds>0&&seconds<=Math.min(3,(remainingMillis-2000)/1000.0),"guest timeoutSeconds="+seconds);
            assertArrayEquals(original,WindowsFiles.JSON.writeValueAsBytes(request));assertEquals(end,runtime.commands.end());
        }
    }
    @Test void controller絶対期限から初期化済み時間を二重に差し引かない() throws Exception {
        prepareRecoveryPayload();
        lease.withObject("requirements").put("preparationDeadline",Instant.EPOCH.toString());saveLease();
        WindowsRuntime fixture=runtime("keep");long now=System.nanoTime();
        var controller=new HelperProtocol.Keeper(ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),attempt,Instant.now().plusSeconds(9),true);
        WindowsRuntime runtime=new WindowsRuntime(new HelperProtocol.Command(root,leasePath,"keep",List.of(),controller),launcher,fixture.lease,now-Duration.ofSeconds(3).toNanos());
        long remaining=runtime.commands.end()-now;
        assertTrue(remaining>=Duration.ofSeconds(8).toNanos()&&remaining<=Duration.ofSeconds(10).toNanos(),"deadline from now="+Duration.ofNanos(remaining));
    }
    @Test void 期限後のnative正常終端でもTIMEOUTを維持して失敗phaseを記録する() throws Exception {
        readyKeeper();mode("late-native");WindowsRuntime runtime=runtime("prepare");runtime.commands.keeper(runtime.keeperRecord());
        ObjectNode request=runtime.request("hello",distribution.owner()).put("privateValue","C:/Users/private-user/token");
        IOException failure=assertThrows(IOException.class,()->runtime.commands.request(runtime.guest(),WindowsFiles.JSON.writeValueAsBytes(request),null,Duration.ofMillis(600)));
        assertTrue(failure.getMessage().contains("\"executionFailure\":\"TIMEOUT\""));assertTrue(failure.getMessage().contains("\"terminal\":\"done\""));
        assertTrue(failure.getMessage().contains("\"jobEmpty\":true"));assertTrue(failure.getMessage().contains("\"nativeExit\":0"));
        assertTrue(failure.getMessage().contains("\"operation\":\"guest-hello\""));assertTrue(failure.getMessage().contains("\"failedPhase\":\"native-wait\""));
        List<JsonNode> phases=new ArrayList<>();try(var files=Files.list(tmp)){for(Path path:files.filter(p->p.getFileName().toString().startsWith("phase-")).toList())for(String line:Files.readAllLines(path))phases.add(WindowsFiles.JSON.readTree(line));}
        List<String> names=phases.stream().map(v->v.path("phase").asText()).toList();assertTrue(names.containsAll(List.of("native-wait","native-reclaim","completion-read-done","exchange-throw")));
        assertTrue(phases.stream().anyMatch(v->v.has("processPid")&&v.has("processStartedEpochMillis")));assertFalse(phases.toString().contains("private-user"));assertFalse(phases.toString().contains("token"));
        assertFalse(runtime.lease.read().path("resources").isEmpty());
    }
    @Test void 実keeperのEOF後停止照合保存を短い本体期限と予約枠近傍で通す() throws Exception {
        for(int trial=0;trial<4;trial++) {
            PrepareIntegrationTest fixture=new PrepareIntegrationTest();fixture.root=Files.createDirectory(root.resolve("release-"+trial));fixture.setup();fixture.mode("release-tail");fixture.prepareRecoveryPayload();assertFalse(Files.exists(fixture.tmp.resolve("fixture-eof-ms")));assertFalse(Files.exists(fixture.tmp.resolve("fixture-terminated")));
            WindowsRuntime keep=fixture.runtime("keep");WindowsKeeper keeper=allocate(WindowsKeeper.class);set(keeper,"runtime",keep);
            ProcessHandle parent=ProcessHandle.current().parent().orElseThrow();set(keeper,"controller",new HelperProtocol.Keeper(parent.pid(),parent.info().startInstant().orElseThrow().toEpochMilli(),fixture.attempt,Instant.now().plusSeconds(20),false));
            Method start=WindowsKeeper.class.getDeclaredMethod("start");start.setAccessible(true);Commands.Running child=null;long elapsed=0;int budget=trial==3?2500:6000;boolean success=false;
            try {
                start.invoke(keeper);Field field=WindowsKeeper.class.getDeclaredField("child");field.setAccessible(true);child=(Commands.Running)field.get(keeper);
                WindowsRuntime cleanup=fixture.runtime("release");long began=System.nanoTime();cleanup.commands.end(began+Duration.ofMillis(budget).toNanos());
                if(trial==3){assertThrows(IOException.class,()->cleanup.release(keeper,false));assertFalse(cleanup.lease.read().path("resources").isEmpty());}
                else {cleanup.release(keeper,false);assertTrue(cleanup.lease.read().path("resources").isEmpty());assertEquals("closed",keeper.value().path("state").asText());assertTrue(keeper.value().path("stopConfirmed").asBoolean());assertTrue(keeper.value().path("jobEmpty").asBoolean());assertFalse(child.process().isAlive());success=true;}
                elapsed=Duration.ofNanos(System.nanoTime()-began).toMillis();assertTrue(elapsed<budget);
                var timing=WindowsFiles.JSON.createObjectNode().put("budgetMs",budget).put("releaseMs",elapsed).put("success",success);Path eof=fixture.tmp.resolve("fixture-eof-ms");if(Files.exists(eof)){long after=System.currentTimeMillis()-Long.parseLong(Files.readString(eof));timing.put("afterEofMs",after);if(success)assertTrue(after>=2600&&after<3000,"EOF後の予約3秒内で停止・保存を完了する");}System.out.println("RELEASE_TIMING="+timing);
            } finally {
                if(child==null){Field field=WindowsKeeper.class.getDeclaredField("child");field.setAccessible(true);child=(Commands.Running)field.get(keeper);}
                if(child!=null){child.process().getOutputStream().close();assertTrue(child.process().waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
            }
        }
    }
    @Test void 一覧が二秒を超えても共有close期限内だけ停止保存を確定する() throws Exception {
        for(int budget:new int[]{9000,4200,1900}) {
            PrepareIntegrationTest fixture=new PrepareIntegrationTest();fixture.root=Files.createDirectory(root.resolve("list-"+budget));fixture.setup();fixture.mode("release-list");fixture.prepareRecoveryPayload();Files.writeString(fixture.tmp.resolve("fixture-list-ms"),"2100");
            WindowsRuntime keep=fixture.runtime("keep");WindowsKeeper keeper=allocate(WindowsKeeper.class);set(keeper,"runtime",keep);
            ProcessHandle parent=ProcessHandle.current().parent().orElseThrow();set(keeper,"controller",new HelperProtocol.Keeper(parent.pid(),parent.info().startInstant().orElseThrow().toEpochMilli(),fixture.attempt,Instant.now().plusSeconds(20),false));
            Method start=WindowsKeeper.class.getDeclaredMethod("start");start.setAccessible(true);Field childField=WindowsKeeper.class.getDeclaredField("child");childField.setAccessible(true);Commands.Running child=null;
            try {
                start.invoke(keeper);child=(Commands.Running)childField.get(keeper);WindowsRuntime cleanup=fixture.runtime("release");long end=System.nanoTime()+Duration.ofMillis(budget).toNanos();cleanup.commands.end(end);
                byte[] leaseBefore=Files.readAllBytes(fixture.leasePath),journalBefore=Files.readAllBytes(fixture.tmp.resolve("windows-operation.json"));
                List<Path> intentsBefore;try(var paths=Files.list(fixture.tmp)){intentsBefore=paths.filter(p->p.getFileName().toString().endsWith(".intent.json")).sorted().toList();}
                if(budget==9000){cleanup.release(keeper,false);assertTrue(cleanup.lease.read().path("resources").isEmpty());assertEquals("closed",keeper.value().path("state").asText());assertTrue(keeper.value().path("termination").path("jobEmpty").asBoolean());assertTrue(keeper.value().path("workProof").path("closed").asBoolean());assertEquals(0,keeper.value().path("workProof").path("actors").asInt());assertEquals(0,keeper.value().path("workProof").path("containers").asInt());assertTrue(keeper.value().path("stopConfirmed").asBoolean());try(var journal=cleanup.journal()){assertTrue(journal.released());}
                    boolean waited=false;try(var paths=Files.list(fixture.tmp)){for(Path path:paths.filter(p->p.getFileName().toString().startsWith("phase-")).toList()){List<JsonNode> rows=new ArrayList<>();for(String line:Files.readAllLines(path))rows.add(WindowsFiles.JSON.readTree(line));if(rows.isEmpty()||!rows.getFirst().path("operation").asText().equals("wsl-list"))continue;JsonNode before=rows.stream().filter(v->v.path("phase").asText().equals("native-wait")).findFirst().orElseThrow(),after=rows.stream().filter(v->v.path("phase").asText().equals("native-exited")).findFirst().orElseThrow();if(after.path("nanoTime").asLong()-before.path("nanoTime").asLong()>Duration.ofSeconds(2).toNanos()){assertTrue(before.path("remainingNanos").asLong()>Duration.ofMillis(2100).toNanos());waited=true;}}}assertTrue(waited,"2.1秒遅延する一覧が固定2秒を超えて実際に待機し、共有期限内で成功する");}
                else {IOException failure=assertThrows(IOException.class,()->cleanup.release(keeper,false));if(budget==4200)assertTrue(failure.getMessage().contains("TIMEOUT"),failure.getMessage());
                    else {assertNotNull(failure.getCause());assertEquals("native回収枠を残せません",failure.getCause().getMessage());assertTrue(failure.getMessage().contains("\"failedPhase\":\"native-budget\""));try(var paths=Files.list(fixture.tmp)){assertEquals(intentsBefore,paths.filter(p->p.getFileName().toString().endsWith(".intent.json")).sorted().toList(),"回収枠不足では新しいnative intentを作らず起動しない");}}
                    assertFalse(cleanup.lease.read().path("resources").isEmpty());assertArrayEquals(leaseBefore,Files.readAllBytes(fixture.leasePath));assertArrayEquals(journalBefore,Files.readAllBytes(fixture.tmp.resolve("windows-operation.json")));assertFalse(keeper.value().path("stopConfirmed").asBoolean());assertFalse(Files.exists(fixture.tmp.resolve("fixture-terminated")));}
                assertEquals(end,cleanup.commands.end());assertTrue(System.nanoTime()<end,"native回収2秒を含むclose元期限内で終了する");
            } finally {if(child==null)child=(Commands.Running)childField.get(keeper);if(child!=null){child.process().getOutputStream().close();assertTrue(child.process().waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}}
        }
    }
    @Test void 後続必須段へ最小枠を残して現在のnative実行期限を固定する() throws Exception {
        prepareRecoveryPayload();mode("exec-late");WindowsRuntime runtime=runtime("release");long end=System.nanoTime()+Duration.ofSeconds(6).toNanos();runtime.commands.end(end);
        Commands.Result result=runtime.commands.releaseCapture(List.of(tmp.resolve("app/support/windows-host.exe").toString(),"facts"),3);assertEquals(0,result.exit());assertEquals(end,runtime.commands.end());
        boolean required=false,budget=false;try(var paths=Files.list(tmp)){for(Path path:paths.filter(p->p.getFileName().toString().startsWith("phase-")).toList())for(String line:Files.readAllLines(path)){JsonNode value=WindowsFiles.JSON.readTree(line);if(!value.path("operation").asText().equals("facts"))continue;if(value.path("phase").asText().equals("native-required")){assertEquals(Duration.ofSeconds(3).toNanos(),value.path("budgetNanos").asLong());required=true;}if(value.path("phase").asText().equals("native-budget")){assertTrue(value.path("deadlineNanos").asLong()<=end-Duration.ofMillis(2750).toNanos());assertTrue(value.path("budgetNanos").asLong()>Duration.ofMillis(250).toNanos());budget=true;}}}assertTrue(required);assertTrue(budget);
    }
    @Test void 前段消費後の二秒近傍ではnativeを起動せず保存を保持する() throws Exception {
        prepareRecoveryPayload();mode("exec-late");WindowsRuntime runtime=runtime("release");try(var initial=runtime.journal()){initial.release();}
        byte[] before=Files.readAllBytes(leasePath),journal=Files.readAllBytes(tmp.resolve("windows-operation.json"));List<Path> intents;try(var paths=Files.list(tmp)){intents=paths.filter(p->p.getFileName().toString().endsWith(".intent.json")).sorted().toList();}
        long original=System.nanoTime()+Duration.ofSeconds(6).toNanos();runtime.commands.end(original);long reduced=original-Duration.ofMillis(3981).toNanos();runtime.commands.end(reduced);
        IOException failure=assertThrows(IOException.class,runtime::facts);assertNotNull(failure.getCause());assertTrue(failure.getCause().getMessage().contains("最小実行枠"),failure.toString());assertTrue(failure.getMessage().contains("\"failedPhase\":\"native-budget\""));
        try(var paths=Files.list(tmp)){assertEquals(intents,paths.filter(p->p.getFileName().toString().endsWith(".intent.json")).sorted().toList());}
        assertArrayEquals(before,Files.readAllBytes(leasePath));assertArrayEquals(journal,Files.readAllBytes(tmp.resolve("windows-operation.json")));assertEquals(reduced,runtime.commands.end());
    }
    @Test void plainExecのdoneを本人照合してcompletion不在とJob残留を分ける() throws Exception {
        prepareRecoveryPayload();
        for(String mode:List.of("exec-late","exec-late-mismatch","exec-late-missing")) {
            mode(mode);WindowsRuntime runtime=runtime("release");
            IOException failure=assertThrows(IOException.class,()->runtime.commands.capture(List.of(tmp.resolve("app/support/windows-host.exe").toString(),"facts"),Duration.ofMillis(100)));
            assertTrue(failure.getMessage().contains("\"executionFailure\":\"TIMEOUT\""));assertTrue(failure.getMessage().contains("\"nativeExit\":0"));assertTrue(failure.getMessage().contains("\"completionRequired\":false"));assertFalse(failure.getMessage().contains("completionUnknown"));
            assertEquals(mode.equals("exec-late"),failure.getMessage().contains("\"jobEmpty\":true"));assertEquals(mode.equals("exec-late"),failure.getMessage().contains("\"terminal\":\"done\""));
            JsonNode diagnostic=WindowsFiles.JSON.readTree(failure.getMessage().substring(failure.getMessage().indexOf('{')));assertEquals(!mode.equals("exec-late"),diagnostic.path("terminalUnknown").asBoolean());
            List<String> phases=new ArrayList<>();for(String line:Files.readAllLines(tmp.resolve(diagnostic.path("phaseLog").asText())))phases.add(WindowsFiles.JSON.readTree(line).path("phase").asText());assertEquals(!mode.equals("exec-late"),phases.contains("terminal-unknown"));assertEquals(mode.equals("exec-late"),phases.contains("terminal-read-done"));
        }
    }
    @Test void 旧owner終了後の回復releaseがdaemon再準備後もEOF停止保存枠を残す() throws Exception {
        for(int limit:new int[]{6,15}){PrepareIntegrationTest fixture=new PrepareIntegrationTest();fixture.root=Files.createDirectory(root.resolve("recovery-"+limit));fixture.setup();fixture.recoverKilledOwner(limit);}
    }
    @Test void 回復keeper実行が15秒受渡しと9秒超の本体期限を実コンストラクタで保持する() throws Exception {recoverKilledOwner(14);}
    private void recoverKilledOwner(int limit) throws Exception {
        Process owner=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,Guest.class.getName(),"owner").redirectError(tmp.resolve("owner.stderr").toFile()).start();Commands.Running oldChild=null,recoveredChild=null;
        try {
            assertEquals("owner-ready",owner.inputReader().readLine());lease.put("pid",owner.pid()).put("processStartedAt",owner.info().startInstant().orElseThrow().toString());saveLease();
            if(limit==14)prepareRecoveryPayload();
            WindowsRuntime original=runtime("keep");WindowsKeeper old=allocate(WindowsKeeper.class);set(old,"runtime",original);set(old,"controller",new HelperProtocol.Keeper(owner.pid(),owner.info().startInstant().orElseThrow().toEpochMilli(),attempt,Instant.now().plusSeconds(20),false));
            Method start=WindowsKeeper.class.getDeclaredMethod("start");start.setAccessible(true);Field field=WindowsKeeper.class.getDeclaredField("child");field.setAccessible(true);start.invoke(old);oldChild=(Commands.Running)field.get(old);
            lease=(ObjectNode)WindowsFiles.json(leasePath);lease.withObject("resources").withObject("windows").withArray("containers").addObject().put("name","mcicon-worker-"+lease.path("jobId").asText()).put("id","f".repeat(64)).put("transportId",attempt);saveLease();
            owner.destroyForcibly();assertTrue(owner.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));oldChild.process().getOutputStream().close();assertTrue(oldChild.process().waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(LeaseReferences.ownerDead(lease));
            if(limit==14){runRecoveryContract();return;}
            attempt=UUID.randomUUID().toString();mode("recovery-release");long overallEnd=System.nanoTime()+Duration.ofSeconds(18).toNanos();
            WindowsRuntime recovery=runtime("keep");ProcessHandle parent=ProcessHandle.current().parent().orElseThrow();var controller=new HelperProtocol.Keeper(parent.pid(),parent.info().startInstant().orElseThrow().toEpochMilli(),attempt,Instant.now().plusSeconds(9),true);set(recovery,"command",new HelperProtocol.Command(root,leasePath,"keep",List.of(),controller));
            WindowsKeeper keeper=allocate(WindowsKeeper.class);set(keeper,"runtime",recovery);set(keeper,"controller",controller);start.invoke(keeper);recoveredChild=(Commands.Running)field.get(keeper);
            WindowsRuntime cleanup=runtime("release");cleanup.commands.end(limit==6?System.nanoTime()+Duration.ofSeconds(6).toNanos():overallEnd-Duration.ofSeconds(3).toNanos());long began=System.nanoTime();Files.writeString(tmp.resolve("fixture-recovery-began"),Long.toString(System.currentTimeMillis()));
            if(limit==6){IOException failure=assertThrows(IOException.class,()->cleanup.release(keeper,true));assertTrue(failure.getMessage().contains("EOF後の停止/保存枠"));assertFalse(cleanup.lease.read().path("resources").isEmpty());assertTrue(keeper.value().path("workProof").path("closed").asBoolean());assertFalse(keeper.value().path("stopConfirmed").asBoolean());assertFalse(Files.exists(tmp.resolve("fixture-terminated")));}
            else {cleanup.release(keeper,true);assertTrue(cleanup.lease.read().path("resources").isEmpty());assertEquals("closed",keeper.value().path("state").asText());assertTrue(keeper.value().path("stopConfirmed").asBoolean());assertTrue(keeper.value().path("jobEmpty").asBoolean());assertFalse(recoveredChild.process().isAlive());assertTrue(Files.exists(tmp.resolve("fixture-terminated")));assertEquals(2,Files.readAllLines(tmp.resolve("fixture-host-events")).stream().filter("--terminate"::equals).count());}
            long elapsed=Duration.ofNanos(System.nanoTime()-began).toMillis();assertTrue(elapsed>3000);assertTrue(System.nanoTime()<overallEnd);assertTrue(operations().contains("daemon-recover"));assertTrue(operations().contains("release-recover"));
            System.out.println("RECOVERY_RELEASE_TIMING="+WindowsFiles.JSON.createObjectNode().put("bodyLimit",limit).put("releaseMs",elapsed).put("success",limit!=6).put("overallRemainingMs",Duration.ofNanos(overallEnd-System.nanoTime()).toMillis()));
        } finally {
            if(owner.isAlive())owner.destroyForcibly();assertTrue(owner.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));
            for(Commands.Running child:new Commands.Running[]{oldChild,recoveredChild})if(child!=null){child.process().getOutputStream().close();assertTrue(child.process().waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    private void prepareRecoveryPayload() throws Exception {
        Path support=Files.createDirectories(tmp.resolve("app/support")),nativeHost=support.resolve("windows-host.exe");
        boolean windows=System.getProperty("os.name").startsWith("Windows");String javaExe=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString();
        if(windows) {
            String execute="""
                static long Ms(DateTime d){return (d.ToUniversalTime().Ticks-621355968000000000L)/10000;}
                static void Save(string p,string s){string t=p+".tmp";System.IO.File.WriteAllText(t,s);System.IO.File.Move(t,p);}
                static bool E(string[] a){
                    if(a[0]!="exec")return false;
                    string prefix=a[3],tmp=System.IO.Path.GetDirectoryName(prefix),mode=System.IO.File.ReadAllText(System.IO.Path.Combine(tmp,"fixture-mode"));var p=Process.GetCurrentProcess();char q=(char)34;
                    string state="{"+q+"pid"+q+":"+p.Id+","+q+"started"+q+":"+Ms(p.StartTime)+","+q+"parent"+q+":"+a[1]+","+q+"parentStarted"+q+":"+a[2]+","+q+"boot"+q+":"+q+"00000000-0000-0000-0000-000000000001"+q+"}";
                    Save(prefix+".state.json",state);bool list=Array.IndexOf(a,"--list")>=0,terminate=Array.IndexOf(a,"--terminate")>=0;string stopped=System.IO.Path.Combine(tmp,"fixture-terminated"),eof=System.IO.Path.Combine(tmp,"fixture-eof-ms");
                    if(mode=="release-tail"&&System.IO.File.Exists(eof)){long target=long.Parse(System.IO.File.ReadAllText(eof))+(terminate?1600:list?System.IO.File.Exists(stopped)?1900:800:System.IO.File.Exists(stopped)?2600:500);System.Threading.Thread.Sleep((int)Math.Max(0,target-Ms(DateTime.UtcNow)));}
                    if(mode=="release-list"&&list&&!System.IO.File.Exists(stopped)&&System.IO.File.Exists(eof))System.Threading.Thread.Sleep(int.Parse(System.IO.File.ReadAllText(System.IO.Path.Combine(tmp,"fixture-list-ms"))));
                    if(mode.StartsWith("exec-late"))System.Threading.Thread.Sleep(250);
                    if(list||terminate){System.IO.File.AppendAllLines(System.IO.Path.Combine(tmp,"fixture-host-events"),new ArraySegment<string>(a,6,a.Length-6));if(terminate)System.IO.File.WriteAllText(stopped,"stopped");else if(!System.IO.File.Exists(stopped)||mode=="still-running")Console.WriteLine(System.IO.File.ReadAllText(System.IO.Path.Combine(tmp,"fixture-distribution-name")));}
                    else Console.Write(System.IO.File.ReadAllText(System.IO.Path.Combine(tmp,"fixture-facts.json")));
                    if(mode!="exec-late-missing")Save(prefix+".done.json",mode=="exec-late-mismatch"?state.Replace(q+"pid"+q+":"+p.Id+",",q+"pid"+q+":0,"):state);return true;
                }
                """;
            String source="using System;using System.Diagnostics;class Native{"+execute+"static void F(System.IO.Stream a,System.IO.Stream b){var v=new byte[4096];int n;while((n=a.Read(v,0,v.Length))>0){b.Write(v,0,n);b.Flush();}}static string Q(string s){return \"\\\"\"+s.Replace(\"\\\"\",\"\\\\\\\"\")+\"\\\"\";}static int Main(string[] a){if(E(a))return 0;var p=new ProcessStartInfo("+WindowsFiles.JSON.writeValueAsString(javaExe)+");p.UseShellExecute=false;p.CreateNoWindow=true;p.Arguments=Q(\"-cp\")+\" \"+Q("+WindowsFiles.JSON.writeValueAsString(classpath)+")+\" \"+Q("+WindowsFiles.JSON.writeValueAsString(Guest.class.getName())+");foreach(var s in a)p.Arguments+=\" \"+Q(s);Environment.SetEnvironmentVariable(\"MCICON_FIXTURE_CMD\",\"1\");p.RedirectStandardInput=true;p.RedirectStandardOutput=true;p.RedirectStandardError=true;using(var c=Process.Start(p)){System.Threading.Tasks.Task.Factory.StartNew(()=>{F(Console.OpenStandardInput(),c.StandardInput.BaseStream);c.StandardInput.Close();});var o=System.Threading.Tasks.Task.Factory.StartNew(()=>F(c.StandardOutput.BaseStream,Console.OpenStandardOutput()));var e=System.Threading.Tasks.Task.Factory.StartNew(()=>F(c.StandardError.BaseStream,Console.OpenStandardError()));c.WaitForExit();System.Threading.Tasks.Task.WaitAll(o,e);return c.ExitCode;}}}";
            Path cs=tmp.resolve("Native.cs");Files.writeString(cs,source);Path compiler=Path.of(System.getenv("SystemRoot"),"Microsoft.NET","Framework64","v4.0.30319","csc.exe");assertTrue(Files.isRegularFile(compiler));
            Process compile=new ProcessBuilder(compiler.toString(),"/nologo","/out:"+nativeHost,cs.toString()).redirectErrorStream(true).redirectOutput(tmp.resolve("compiler.log").toFile()).start();
            try{assertTrue(compile.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,compile.exitValue(),()->{try{return Files.readString(tmp.resolve("compiler.log"));}catch(IOException e){return e.toString();}});}finally{if(compile.isAlive())compile.destroyForcibly();assertTrue(compile.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));}
        } else {Files.writeString(nativeHost,"#!/bin/sh\nif [ \"$1\" = \"exec\" ]; then exec '"+javaExe+"' -cp '"+classpath+"' '"+NativeExec.class.getName()+"' \"$@\"; fi\nexec '"+javaExe+"' -cp '"+classpath+"' '"+Guest.class.getName()+"' \"$@\"\n");assertTrue(nativeHost.toFile().setExecutable(true));}
        Files.writeString(support.resolve("guestbridge.py"),"fixture");ObjectNode pins=manifest.deepCopy();pins.putArray("files");
        for(String name:List.of("windows-host.exe","guestbridge.py")){Path file=support.resolve(name);pins.withArray("files").addObject().put("path",name).put("bytes",Files.size(file)).put("sha256",WindowsFiles.hash(file));}
        ((ObjectNode)pins.path("guestBase")).put("format","tar.xz").put("bridgeSha256",WindowsFiles.hash(support.resolve("guestbridge.py")));
        for(String name:List.of("docker","dockerd","containerd","runc"))((ObjectNode)pins.path("guestBase").path("engines")).put("/usr/bin/"+name,"c".repeat(64));WindowsFiles.write(support.resolve("payload.json"),pins);
        Path system=Files.createDirectories(tmp.resolve("fixture-system/System32"));Files.writeString(system.resolve("wsl.exe"),"fixture-only");
    }
    private void runRecoveryContract() throws Exception {
        attempt=UUID.randomUUID().toString();mode("recovery-contract");WindowsFiles.write(tmp.resolve("fixture-descriptor.json"),descriptor);
        boolean windows=System.getProperty("os.name").startsWith("Windows");String javaExe=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString();Path system=tmp.resolve("fixture-system/System32");
        ProcessBuilder builder=new ProcessBuilder(javaExe,"-cp",classpath,KeeperProcess.class.getName(),root.toString(),leasePath.toString(),attempt,launcher.toString()).redirectError(tmp.resolve("keeper-run.stderr").toFile());builder.environment().put("SystemRoot",windows?System.getenv("SystemRoot"):system.getParent().toString());
        Process process=null;long began=0;
        try(var channel=java.nio.channels.FileChannel.open(leasePath.resolveSibling("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.lock(64,1,false)) {
            process=builder.start();String line=process.inputReader().readLine();assertNotNull(line,new String(Files.readAllBytes(tmp.resolve("keeper-run.stderr")),java.nio.charset.StandardCharsets.UTF_8));JsonNode ready=WindowsFiles.JSON.readTree(line);assertEquals("ready",ready.path("state").asText());assertEquals(attempt,ready.path("attempt").asText());
            began=System.nanoTime();Files.writeString(tmp.resolve("fixture-recovery-began"),Long.toString(System.currentTimeMillis()));var request=WindowsFiles.JSON.createObjectNode().put("operation","release").put("attempt",attempt).put("deadline",Instant.now().plusSeconds(14).toString());
            try(OutputStream out=process.getOutputStream()){out.write(WindowsFiles.JSON.writeValueAsBytes(request));out.write('\n');}
            assertTrue(process.waitFor(16,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(Files.readAllBytes(tmp.resolve("keeper-run.stderr")),java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(WindowsFiles.json(leasePath).path("resources").isEmpty());JsonNode closed=WindowsFiles.json(tmp.resolve("keeper.json"));assertEquals("closed",closed.path("state").asText());assertTrue(closed.path("stopConfirmed").asBoolean());assertTrue(closed.path("jobEmpty").asBoolean());
            assertEquals(2,Files.readAllLines(tmp.resolve("fixture-host-events")).stream().filter("--terminate"::equals).count());long elapsed=Duration.ofNanos(System.nanoTime()-began).toMillis();assertTrue(elapsed>=7200&&elapsed<14000);System.out.println("RECOVERY_CONTRACT_TIMING="+elapsed);
        } finally {if(process!=null){process.getOutputStream().close();if(!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));process.getInputStream().close();process.getErrorStream().close();}}
    }
    public static class KeeperProcess {
        public static void main(String[] args) throws Exception {
            PrepareIntegrationTest fixture=new PrepareIntegrationTest();fixture.root=Path.of(args[0]);fixture.leasePath=Path.of(args[1]);fixture.tmp=fixture.leasePath.getParent().resolve("tmp");fixture.attempt=args[2];fixture.launcher=Path.of(args[3]);fixture.lease=(ObjectNode)WindowsFiles.json(fixture.leasePath);fixture.descriptor=(ObjectNode)WindowsFiles.json(fixture.tmp.resolve("fixture-descriptor.json"));fixture.host=HostFacts.parse(WindowsFiles.json(fixture.tmp.resolve("fixture-facts.json")),fixture.descriptor);
            LeaseFile file=fixture.runtime("keep").lease;ProcessHandle parent=ProcessHandle.current().parent().orElseThrow();var controller=new HelperProtocol.Keeper(parent.pid(),parent.info().startInstant().orElseThrow().toEpochMilli(),fixture.attempt,Instant.now().plusSeconds(9),true);
            WindowsRuntime runtime=new WindowsRuntime(new HelperProtocol.Command(fixture.root,fixture.leasePath,"keep",List.of(),controller),fixture.launcher,file,System.nanoTime());new WindowsKeeper(runtime).run();return;
        }
    }
    public static class NativeExec {
        private static void save(Path file,String value) throws IOException {
            Path temporary=Files.createTempFile(file.getParent(),"fixture-",".json");try{Files.writeString(temporary,value);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
        }
        public static void main(String[] args) throws Exception {
            Path prefix=Path.of(args[3]),tmp=prefix.getParent();ProcessHandle process=System.getenv("MCICON_FIXTURE_CMD")!=null?ProcessHandle.current().parent().orElseThrow():ProcessHandle.current();
            // 固定receiptはObjectMapperを起動せずに原子的に保存する。
            String state="{\"pid\":"+process.pid()+",\"started\":"+process.info().startInstant().orElseThrow().toEpochMilli()+",\"parent\":"+Long.parseLong(args[1])+",\"parentStarted\":"+Long.parseLong(args[2])+",\"boot\":\""+OwnershipTest.BOOT+"\"}";save(Path.of(prefix+".state.json"),state);
            List<String> command=Arrays.asList(args).subList(5,args.length);
            String mode=Files.readString(tmp.resolve("fixture-mode"));if(mode.equals("release-tail")&&Files.exists(tmp.resolve("fixture-eof-ms"))) {
                long target=Long.parseLong(Files.readString(tmp.resolve("fixture-eof-ms")))+(command.contains("--terminate")?1600:command.contains("--list")?Files.exists(tmp.resolve("fixture-terminated"))?1900:800:Files.exists(tmp.resolve("fixture-terminated"))?2600:500);Thread.sleep(Math.max(0,target-System.currentTimeMillis()));
            }
            if(mode.equals("release-list")&&command.contains("--list")&&!Files.exists(tmp.resolve("fixture-terminated"))&&Files.exists(tmp.resolve("fixture-eof-ms")))Thread.sleep(Long.parseLong(Files.readString(tmp.resolve("fixture-list-ms"))));
            if(mode.startsWith("exec-late"))Thread.sleep(250);
            if(command.contains("--list")||command.contains("--terminate")) {
                Files.write(tmp.resolve("fixture-host-events"),command.subList(1,command.size()),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                if(command.contains("--terminate"))Files.writeString(tmp.resolve("fixture-terminated"),"stopped");
                else if(!Files.exists(tmp.resolve("fixture-terminated"))||Files.readString(tmp.resolve("fixture-mode")).equals("still-running")) {
                    System.out.println(Files.readString(tmp.resolve("fixture-distribution-name")));
                }
            } else System.out.write(Files.readAllBytes(tmp.resolve("fixture-facts.json")));
            if(!mode.equals("exec-late-missing"))save(Path.of(prefix+".done.json"),mode.equals("exec-late-mismatch")?state.replace("\"pid\":"+process.pid()+",","\"pid\":0,"):state);return;
        }
    }
    public static class Guest {
        public static void main(String[] args) throws Exception {
            if(args[0].equals("owner")){System.out.println("owner-ready");System.out.flush();System.in.transferTo(OutputStream.nullOutputStream());return;}
            Path prefix=Path.of(args[3]),tmp=prefix.getParent();ProcessHandle process=System.getenv("MCICON_FIXTURE_CMD")!=null?ProcessHandle.current().parent().orElseThrow():ProcessHandle.current();
            // 固定receiptはObjectMapperを起動せずに原子的に保存する。
            String state="{\"pid\":"+process.pid()+",\"started\":"+process.info().startInstant().orElseThrow().toEpochMilli()+",\"parent\":"+Long.parseLong(args[1])+",\"parentStarted\":"+Long.parseLong(args[2])+",\"boot\":\""+OwnershipTest.BOOT+"\"}";NativeExec.save(Path.of(prefix+".state.json"),state);
            ObjectNode client=(ObjectNode)WindowsFiles.JSON.readTree(state);
            ObjectNode request=(ObjectNode)WindowsFiles.JSON.readTree(new BufferedReader(new InputStreamReader(System.in)).readLine());String operation=request.path("operation").asText(),mode=Files.readString(tmp.resolve("fixture-mode"));
            Path root=tmp.getParent().getParent().getParent();ObjectNode owner=(ObjectNode)WindowsFiles.json(root.resolve("windows").resolve(request.path("descriptorSha256").asText()).resolve("owner.json"));
            ObjectNode ack=WindowsFiles.JSON.createObjectNode().put("abi",1).put("boot",OwnershipTest.BOOT).put("memoryBytes",16L*1024*1024*1024);ack.set("marker",request.path("marker"));ack.set("bridgeSha256",request.path("bridgeSha256"));
            int exit=0;
            if(operation.equals("daemon-recover")&&mode.equals("recovery-release"))Thread.sleep(1000);
            if(operation.equals("release-recover")&&Set.of("recovery-release","recovery-contract").contains(mode))Thread.sleep(Math.max(0,Long.parseLong(Files.readString(tmp.resolve("fixture-recovery-began")))+(mode.equals("recovery-contract")?7200:3200)-System.currentTimeMillis()));
            if(Set.of("keep","keep-recover").contains(operation)) {
                if(operation.equals("keep-recover"))Files.deleteIfExists(tmp.resolve("fixture-terminated"));
                ObjectNode identity=WindowsFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,request.path(key));ack.set("identity",identity);ack.putObject("keeper").put("attempt",request.path("keeperAttempt").asText());
                client.put("corePid",Long.parseLong(args[5])).put("coreStarted",Long.parseLong(args[6])).put("clientPid",ProcessHandle.current().pid()).put("clientStarted",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());WindowsFiles.write(Path.of(prefix+".client.json"),client);WindowsFiles.write(Path.of(prefix+".state.json"),client);
            } else if(Set.of("daemon-prepare","daemon-recover").contains(operation)) {
                ack.put("ready",true).put("labelVerified",true);ack.set("claim",request.path("claim").deepCopy());ack.putObject("daemon").put("generation",owner.path("generationOwner").asText()).put("boot",OwnershipTest.BOOT).put("pid",mode.equals("guest-mismatch")?124:123).put("started","456");
                if(mode.equals("guest-attempt"))((ObjectNode)ack.path("claim")).put("keeperAttempt",UUID.randomUUID().toString());
                if(mode.equals("guest-missing"))exit=23;
                if(mode.equals("crash")){Files.move(tmp.resolve("windows-operation.json"),tmp.resolve("fixture-pending.json"));Files.createDirectory(tmp.resolve("windows-operation.json"));}
                if(mode.startsWith("empty")){ack.put("empty",true);if(!mode.equals("empty-ready"))ack.remove("ready");if(!mode.equals("empty-daemon"))ack.remove("daemon");}
            } else if(operation.equals("load")){System.in.transferTo(OutputStream.nullOutputStream());ack=WindowsFiles.JSON.createObjectNode().put("Id","sha256:"+"c".repeat(64)).put("Os","linux").put("Architecture","amd64");}
            else if(operation.equals("create"))ack=WindowsFiles.JSON.createObjectNode().put("id","f".repeat(64));
            else if(operation.equals("remove"))ack=WindowsFiles.JSON.createObjectNode().put("removed",request.path("id").asText());
            else if(Set.of("release","release-recover").contains(operation)) {ack=WindowsFiles.JSON.createObjectNode().put("closed",true).put("actors",0).put("containers",0);ObjectNode identity=WindowsFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,request.path(key));ack.set("identity",identity);ack.set("keeper",WindowsFiles.json(tmp.resolve("keeper.json")).path("guest").path("keeper").deepCopy());}
            ObjectNode event=WindowsFiles.JSON.createObjectNode().put("operation",operation);event.set("request",request);event.set("owner",owner);event.set("ack",ack);Files.writeString(tmp.resolve("fixture-events"),event+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println(ack);System.out.flush();if(Set.of("keep","keep-recover").contains(operation)){System.in.transferTo(OutputStream.nullOutputStream());Files.writeString(tmp.resolve("fixture-eof-ms"),Long.toString(System.currentTimeMillis()));}
            ObjectNode proof=WindowsFiles.JSON.createObjectNode().put("schema",1).put("phase","drain-empty").put("childKnown",true).put("childExit",exit).put("plannedGuardExit",exit).put("jobEmpty",true);proof.set("client",client.deepCopy());
            if(Set.of("daemon-prepare","daemon-recover").contains(operation)) {
                if(mode.equals("mismatch-completion"))((ObjectNode)proof.path("client")).put("pid",1);
                if(mode.equals("nonzero")){exit=7;proof.put("childExit",7).put("plannedGuardExit",7);}
                if(mode.equals("child-nonzero")){exit=125;proof.put("phase","residual-terminated").put("childExit",7).put("plannedGuardExit",125);}
            }
            if(!Set.of("daemon-prepare","daemon-recover").contains(operation)||!mode.equals("missing-completion"))WindowsFiles.write(Path.of(prefix+".completion.json"),proof);
            if(Set.of("daemon-prepare","daemon-recover").contains(operation)&&mode.equals("mismatch-done"))client.put("pid",1);
            if(!Set.of("daemon-prepare","daemon-recover").contains(operation)||!mode.equals("missing-done"))WindowsFiles.write(Path.of(prefix+".done.json"),client);if(mode.equals("late-native"))Thread.sleep(1200);System.exit(exit);
        }
    }
}
