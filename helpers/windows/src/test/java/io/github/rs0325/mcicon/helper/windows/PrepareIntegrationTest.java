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

/** native/guest境界だけをJava子プロセスに置き換え、hostの保存と照合を通す。 */
class PrepareIntegrationTest {
    @TempDir Path root;
    Path tmp,leasePath,launcher;
    ObjectNode lease,descriptor,manifest;
    HostFacts host;
    WslDistribution distribution;
    String attempt=UUID.randomUUID().toString();

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
        for(Class<?> type:List.of(PrepareIntegrationTest.class,WindowsFiles.class,com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class))paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        launcher=tmp.resolve(windows?"fixture.cmd":"fixture");String cp=String.join(File.pathSeparator,paths);
        Files.writeString(launcher,windows?"@echo off\r\nset MCICON_FIXTURE_CMD=1\r\n\""+java+"\" -cp \""+cp+"\" "+Guest.class.getName()+" %*\r\nexit /b %errorlevel%\r\n":"#!/bin/sh\nexec '"+java+"' -cp '"+cp+"' '"+Guest.class.getName()+"' \"$@\"\n");if(!windows)assertTrue(launcher.toFile().setExecutable(true,true));
        mode("valid");
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
        WindowsRuntime runtime=allocate(WindowsRuntime.class);set(runtime,"command",new HelperProtocol.Command(root,leasePath,operation,List.of(),operation.equals("keep")?controller:null));set(runtime,"lease",file);set(runtime,"executable",launcher);set(runtime,"temporary",tmp);set(runtime,"wsl",launcher);set(runtime,"payload",new Payload(tmp));set(runtime,"descriptor",descriptor.deepCopy());
        Map<String,String> environment=new HashMap<>();if(System.getenv("SystemRoot")!=null)environment.put("SystemRoot",System.getenv("SystemRoot"));set(runtime,"environment",environment);set(runtime,"commands",new Commands(launcher,tmp,environment,Long.MAX_VALUE));set(runtime,"distribution",new WslDistribution(root,file.read(),host));return runtime;
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
    @Test void 実keeperのEOF後停止照合保存を短い本体期限と予約枠近傍で通す() throws Exception {
        for(int trial=0;trial<4;trial++) {
            PrepareIntegrationTest fixture=new PrepareIntegrationTest();fixture.root=Files.createDirectory(root.resolve("release-"+trial));fixture.setup();fixture.mode("release-tail");assertFalse(Files.exists(fixture.tmp.resolve("fixture-eof-ms")));assertFalse(Files.exists(fixture.tmp.resolve("fixture-terminated")));
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
    public static class Guest {
        private static void save(Path file,String value) throws IOException {
            Path temporary=Files.createTempFile(file.getParent(),"fixture-",".json");try{Files.writeString(temporary,value);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
        }
        public static void main(String[] args) throws Exception {
            Path prefix=Path.of(args[3]),tmp=prefix.getParent();ProcessHandle process=System.getenv("MCICON_FIXTURE_CMD")!=null?ProcessHandle.current().parent().orElseThrow():ProcessHandle.current();
            // 固定receiptはObjectMapperを起動せずに原子的に保存する。
            String state="{\"pid\":"+process.pid()+",\"started\":"+process.info().startInstant().orElseThrow().toEpochMilli()+",\"parent\":"+Long.parseLong(args[1])+",\"parentStarted\":"+Long.parseLong(args[2])+",\"boot\":\""+OwnershipTest.BOOT+"\"}";save(Path.of(prefix+".state.json"),state);
            if(args[0].equals("exec")) {
                List<String> command=Arrays.asList(args).subList(5,args.length);
                if(Files.readString(tmp.resolve("fixture-mode")).equals("release-tail")&&Files.exists(tmp.resolve("fixture-eof-ms"))) {
                    long target=Long.parseLong(Files.readString(tmp.resolve("fixture-eof-ms")))+(command.contains("--terminate")?1600:command.contains("--list")?Files.exists(tmp.resolve("fixture-terminated"))?1900:800:Files.exists(tmp.resolve("fixture-terminated"))?2600:500);Thread.sleep(Math.max(0,target-System.currentTimeMillis()));
                }
                if(command.contains("--list")||command.contains("--terminate")) {
                    Files.write(tmp.resolve("fixture-host-events"),command.subList(1,command.size()),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    if(command.contains("--terminate"))Files.writeString(tmp.resolve("fixture-terminated"),"stopped");
                    else if(!Files.exists(tmp.resolve("fixture-terminated"))||Files.readString(tmp.resolve("fixture-mode")).equals("still-running")) {
                        Path root=tmp.getParent().getParent().getParent();ObjectNode lease=(ObjectNode)WindowsFiles.json(tmp.getParent().resolve("lease.json"));
                        System.out.println("MCIcon-"+lease.path("descriptorSha256").asText().substring(0,24));
                    }
                } else System.out.write(Files.readAllBytes(tmp.resolve("fixture-facts.json")));
                save(Path.of(prefix+".done.json"),state);return;
            }
            ObjectNode client=(ObjectNode)WindowsFiles.JSON.readTree(state);
            ObjectNode request=(ObjectNode)WindowsFiles.JSON.readTree(new BufferedReader(new InputStreamReader(System.in)).readLine());String operation=request.path("operation").asText(),mode=Files.readString(tmp.resolve("fixture-mode"));
            Path root=tmp.getParent().getParent().getParent();ObjectNode owner=(ObjectNode)WindowsFiles.json(root.resolve("windows").resolve(request.path("descriptorSha256").asText()).resolve("owner.json"));
            ObjectNode ack=WindowsFiles.JSON.createObjectNode().put("abi",1).put("boot",OwnershipTest.BOOT).put("memoryBytes",16L*1024*1024*1024);ack.set("marker",request.path("marker"));ack.set("bridgeSha256",request.path("bridgeSha256"));
            int exit=0;
            if(operation.equals("keep")) {
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
            else if(operation.equals("release")) {ack=WindowsFiles.JSON.createObjectNode().put("closed",true).put("actors",0).put("containers",0);ObjectNode identity=WindowsFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256"))identity.set(key,request.path(key));ack.set("identity",identity);ack.set("keeper",WindowsFiles.json(tmp.resolve("keeper.json")).path("guest").path("keeper").deepCopy());}
            ObjectNode event=WindowsFiles.JSON.createObjectNode().put("operation",operation);event.set("request",request);event.set("owner",owner);event.set("ack",ack);Files.writeString(tmp.resolve("fixture-events"),event+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println(ack);System.out.flush();if(operation.equals("keep")){System.in.transferTo(OutputStream.nullOutputStream());Files.writeString(tmp.resolve("fixture-eof-ms"),Long.toString(System.currentTimeMillis()));}
            ObjectNode proof=WindowsFiles.JSON.createObjectNode().put("schema",1).put("phase","drain-empty").put("childKnown",true).put("childExit",exit).put("plannedGuardExit",exit).put("jobEmpty",true);proof.set("client",client.deepCopy());
            if(Set.of("daemon-prepare","daemon-recover").contains(operation)) {
                if(mode.equals("mismatch-completion"))((ObjectNode)proof.path("client")).put("pid",1);
                if(mode.equals("nonzero")){exit=7;proof.put("childExit",7).put("plannedGuardExit",7);}
                if(mode.equals("child-nonzero")){exit=125;proof.put("phase","residual-terminated").put("childExit",7).put("plannedGuardExit",125);}
            }
            if(!Set.of("daemon-prepare","daemon-recover").contains(operation)||!mode.equals("missing-completion"))WindowsFiles.write(Path.of(prefix+".completion.json"),proof);
            if(Set.of("daemon-prepare","daemon-recover").contains(operation)&&mode.equals("mismatch-done"))client.put("pid",1);
            if(!Set.of("daemon-prepare","daemon-recover").contains(operation)||!mode.equals("missing-done"))WindowsFiles.write(Path.of(prefix+".done.json"),client);System.exit(exit);
        }
    }
}
