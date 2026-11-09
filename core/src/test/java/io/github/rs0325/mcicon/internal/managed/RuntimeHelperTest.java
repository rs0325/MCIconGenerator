package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.CancellationToken;
import java.nio.file.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeHelperTest {
    @TempDir Path root;
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void pendingKeeperRoutesThroughRealControlOnlyWithCompleteOwnedJob0() throws Exception {
        for(String mutation:List.of("valid","done","client","attempt","identity","controller","boot","intent","operation","recovery")) {
            Path directory=Files.createDirectory(root.resolve(mutation)).toRealPath();var fixture=new ManagedFixture();byte[] inventory="fixture inventory".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for(var artifact:fixture.json.withArray("artifacts"))((com.fasterxml.jackson.databind.node.ObjectNode)artifact).put("inventorySha256",io.github.rs0325.mcicon.internal.FilesSupport.hash(inventory));
            var descriptor=fixture.descriptor();var store=new RuntimeStore(directory,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
            var original=ManagedFixture.lease(store,descriptor);Path file=original.file(),job=file.getParent(),tmp=Files.createDirectory(job.resolve("tmp"));original.close();
            ManagedFixture.owner(file,new ManagedFixture.Owner(ProcessHandle.current().pid(),java.time.Instant.EPOCH));Files.write(job.resolve("descriptor.json"),fixture.bytes());Files.createDirectory(job.resolve("inventory"));for(var artifact:descriptor.artifacts())Files.write(job.resolve(artifact.inventoryPath()),inventory);
            var owner=RuntimeDescriptor.read(Files.readAllBytes(file));var identity=RuntimeDescriptor.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))identity.set(key,owner.required(key));
            String boot=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();Path prefix=tmp.resolve("native-"+UUID.randomUUID()),exe=directory.resolve("helper"),called=directory.resolve("called");
            var journal=RuntimeDescriptor.JSON.createObjectNode().put("state","pending").put("operation","keeper-start").put("boot",boot).put("intent",UUID.randomUUID().toString());journal.set("identity",identity.deepCopy());identity.remove("hostId");
            var prior=RuntimeDescriptor.JSON.createObjectNode().put("state","starting").put("attempt",attempt).put("boot",boot).put("controllerPid",owner.path("pid").asLong()).put("controllerStarted",0).put("recovery",false).put("prefix",prefix.toString());prior.set("identity",identity);
            var state=RuntimeDescriptor.JSON.createObjectNode().put("parent",11).put("parentStarted",22).put("pid",33).put("started",44).put("boot",boot);var client=state.deepCopy().put("corePid",owner.path("pid").asLong()).put("coreStarted",0).put("clientPid",55).put("clientStarted",66);
            List<String> guest=List.of(directory.resolve("System32/wsl.exe").toString(),"--distribution","MCIcon-"+descriptor.digest().substring(0,24),"--user","root","--exec","/usr/bin/python3","/usr/lib/mcicon/guestbridge.py");
            var intent=RuntimeDescriptor.JSON.createObjectNode().put("parent",11).put("started",22).put("native",directory.resolve("app/support/windows-host.exe").toString()).put("argvSha256",io.github.rs0325.mcicon.internal.FilesSupport.hash(RuntimeDescriptor.JSON.writeValueAsBytes(guest)));
            Files.writeString(exe,"#!/usr/bin/python3\nimport sys,json,pathlib,time,datetime\na=sys.argv\ntime.sleep(1.2)\nassert a[7]=='keep' and a[a.index('--recovery')+1]=='true'\npathlib.Path('"+called+"').write_text('keep-recover')\nprint(json.dumps({'state':'ready','attempt':a[a.index('--attempt')+1],'controllerPid':int(a[a.index('--controller')+1]),'controllerStarted':int(a[a.index('--controller')+2])}),flush=True)\nr=json.loads(sys.stdin.readline());assert r['operation']=='release';assert sys.stdin.read()==''\ntime.sleep(max(0,(datetime.datetime.strptime(r['deadline'][:19],'%Y-%m-%dT%H:%M:%S').replace(tzinfo=datetime.timezone.utc).timestamp()+float('0'+r['deadline'][19:-1]))-time.time()-0.1))\nprint('{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}',flush=True)\n");assertTrue(exe.toFile().setExecutable(true));
            switch(mutation) {case "client"->client.put("coreStarted",9);case "identity"->identity.put("ownerId",UUID.randomUUID().toString());case "controller"->prior.put("controllerPid",999);case "boot"->prior.put("boot",UUID.randomUUID().toString());case "intent"->intent.put("argvSha256","0".repeat(64));case "operation"->journal.put("operation","import");case "recovery"->prior.put("recovery",true);default->{}}
            Files.write(tmp.resolve("windows-operation.json"),RuntimeDescriptor.JSON.writeValueAsBytes(journal));Files.write(tmp.resolve("keeper.json"),RuntimeDescriptor.JSON.writeValueAsBytes(prior));Files.write(tmp.resolve("keeper-"+attempt+".json"),RuntimeDescriptor.JSON.writeValueAsBytes(mutation.equals("attempt")?RuntimeDescriptor.JSON.createObjectNode():prior));
            for(var entry:Map.of("intent",intent,"state",state,"client",client,"done",state).entrySet())if(!mutation.equals("done")||!entry.getKey().equals("done"))Files.write(Path.of(prefix+"."+entry.getKey()+".json"),RuntimeDescriptor.JSON.writeValueAsBytes(entry.getValue()));
            try(var lease=RuntimeLease.tryRecovery(store,descriptor,job)) {
                assertNotNull(lease);RuntimeHelper helper=new RuntimeHelper(exe,store.root(),lease,Map.of("SystemRoot",directory.toString()),true);
                if(mutation.equals("valid")){assertTrue(helper.control("release",Duration.ofSeconds(18),new CancellationToken()).ok());assertEquals("keep-recover",Files.readString(called));}
                else {assertThrows(IOException.class,()->helper.control("release",Duration.ofSeconds(5),new CancellationToken()),mutation);assertFalse(Files.exists(called),mutation);assertEquals("active",lease.read().path("state").asText());}
            }
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void expiredKeeperBudgetRejectsBeforeActualHelperSpawn() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});Path exe=root.resolve("must-not-start"),called=root.resolve("called");
        Files.writeString(exe,"#!/bin/sh\ntouch '"+called+"'\n");assertTrue(exe.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())){RuntimeHelper helper=new RuntimeHelper(exe,store.root(),lease,Map.of(),true);assertThrows(IOException.class,()->helper.keep(Duration.ZERO,new CancellationToken(),true));assertFalse(Files.exists(called));assertEquals("active",lease.read().path("state").asText());}
    }
    @Test void keeperの本体期限直前の正常終了を受け取り真の超過だけ失敗する() throws Exception {
        for(String mode:List.of("normal","late","cancel","empty")) {
            Path directory=Files.createDirectory(root.resolve(mode)).toRealPath();var store=new RuntimeStore(directory,true,new CancellationToken(),Duration.ofSeconds(20),(u,o,t)->{throw new AssertionError();});
            try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
                Path phaseDirectory=Files.createDirectory(lease.file().getParent().resolve("tmp"));
                Path output=directory.resolve("keeper.stdout");Files.writeString(output,"{}\n");
                List<String> paths=new ArrayList<>();for(Class<?> type:List.of(BudgetKeeper.class,RuntimeDescriptor.class,com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class))paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
                Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",String.join(java.io.File.pathSeparator,paths),BudgetKeeper.class.getName(),mode).redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile())).redirectError(directory.resolve("keeper.stderr").toFile()).start();
                RuntimeHelper helper=new RuntimeHelper(directory.resolve("unused"),directory,lease,Map.of(),true);
                for(var entry:Map.<String,Object>of("keeper",child,"keeperReady",true,"keeperAttempt","fixture","keeperOutput",output).entrySet()){var field=RuntimeHelper.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(helper,entry.getValue());}
                CancellationToken token=new CancellationToken();if(mode.equals("cancel"))token.cancel();long started=System.nanoTime();
                try {
                    if(mode.equals("normal")){assertTrue(helper.control("release",Duration.ofSeconds(9),token).ok());assertEquals("released",RuntimeDescriptor.read(Files.readAllBytes(lease.file())).path("state").asText());}
                    else {assertThrows(Exception.class,()->helper.control("release",mode.equals("empty")?Duration.ZERO:Duration.ofSeconds(9),token));assertEquals("active",lease.read().path("state").asText());}
                    assertFalse(child.isAlive());assertTrue(System.nanoTime()-started<Duration.ofSeconds(10).toNanos());
                    String phases;try(var files=Files.list(phaseDirectory)){phases=Files.readString(files.filter(path->path.getFileName().toString().startsWith("phase-")).findFirst().orElseThrow());}
                    assertTrue(phases.contains("\"phase\":\""+(mode.equals("normal")?"release-ok":"release-failed")+"\""));assertFalse(phases.contains(directory.toString()));
                }finally{if(child.isAlive())child.destroyForcibly();assertTrue(child.waitFor(2,java.util.concurrent.TimeUnit.SECONDS));}
            }
        }
    }
    public static class BudgetKeeper {
        public static void main(String[] arguments) throws Exception {
            var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));String line=input.readLine();if(line==null)return;
            var request=RuntimeDescriptor.read(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));assert input.readLine()==null;
            java.time.Instant deadline=java.time.Instant.parse(request.path("deadline").asText());long left=Duration.between(java.time.Instant.now(),deadline).toMillis();
            if(arguments[0].equals("normal"))Thread.sleep(Math.max(0,left-100));else Thread.sleep(30000);
            System.out.println("{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}");
        }
    }
    @Test void 回復開始で余った18秒枠を本体へ渡し上限を延長しない() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(20),(u,o,t)->{throw new AssertionError();});
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            var value=lease.read();value.withObject("resources").put("fixture",true);Files.write(lease.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));
            RuntimeHelper helper=new RuntimeHelper(controlFixture("recovery"),store.root(),lease,fixtureEnvironment(),true);
            long began=System.nanoTime();assertTrue(helper.control("release",Duration.ofSeconds(18),new CancellationToken()).ok());
            long budget=Long.parseLong(Files.readString(store.root().resolve("release-budget-ms")));assertTrue(budget>9000&&budget<=15000);assertTrue(System.nanoTime()-began<Duration.ofSeconds(18).toNanos());
        }
    }
    @Test void 子孫が標準出力を保持しても正常応答を失敗にせず一時ログを増やさない() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(20),(u,o,t)->{throw new AssertionError();});
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(controlFixture("retain"),store.root(),lease,fixtureEnvironment(),true);
            for(int i=0;i<3;i++) {
                ProcessHandle child=null;
                try {
                    assertTrue(helper.control("preflight",Duration.ofSeconds(4),new CancellationToken()).ok());
                    var record=RuntimeDescriptor.read(Files.readAllBytes(store.root().resolve("retainer.json")));child=ProcessHandle.of(record.path("pid").asLong()).orElseThrow();
                    assertTrue(child.isAlive());assertEquals(record.path("started").asLong(),child.info().startInstant().orElseThrow().toEpochMilli());
                    Files.writeString(store.root().resolve("write-"+child.pid()),"write");Path closed=store.root().resolve("closed-"+child.pid());long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(!Files.exists(closed)&&System.nanoTime()<end)Thread.sleep(10);assertTrue(Files.exists(closed));assertEquals("true:true",Files.readString(closed));
                    try(var files=Files.list(lease.file().getParent())){assertEquals(0,files.filter(f->f.getFileName().toString().startsWith("helper-")).count());}
                }finally{
                    if(child==null&&Files.exists(store.root().resolve("retainer.json"))){long pid=RuntimeDescriptor.read(Files.readAllBytes(store.root().resolve("retainer.json"))).path("pid").asLong();child=ProcessHandle.of(pid).orElse(null);}
                    if(child!=null){Files.writeString(store.root().resolve("release-"+child.pid()),"release");try{child.onExit().get(3,java.util.concurrent.TimeUnit.SECONDS);}finally{if(child.isAlive())child.destroyForcibly();}}
                }
            }
        }
    }
    @Test void stdout上限超過でもstderr診断と元例外を保持する() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(20),(u,o,t)->{throw new AssertionError();});
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(controlFixture("overflow"),store.root(),lease,fixtureEnvironment(),true);
            io.github.rs0325.mcicon.McIconException failure=assertThrows(io.github.rs0325.mcicon.McIconException.class,()->helper.control("preflight",Duration.ofSeconds(5),new CancellationToken()));assertTrue(failure.getMessage().contains("上限"));
            try(var files=Files.list(lease.file().getParent())) {
                List<Path> logs=files.filter(p->p.getFileName().toString().endsWith(".stdout.json")).toList();assertEquals(1,logs.size());Path log=logs.getFirst();
                assertTrue(Files.size(log)<=1024*1024);Path error=log.resolveSibling(log.getFileName()+".stderr.log");assertTrue(Files.size(error)<=1024*1024);assertTrue(Files.readString(error).contains("fixture-stderr-before-overflow"));
                var result=RuntimeDescriptor.read(Files.readAllBytes(log.resolveSibling(log.getFileName()+".result.json")));assertEquals(failure.getClass().getSimpleName(),result.path("failureType").asText());assertEquals(failure.getMessage(),result.path("failureDetail").asText());
            }
        }
    }
    private Map<String,String> fixtureEnvironment(){return System.getenv("SystemRoot")==null?Map.of():Map.of("SystemRoot",System.getenv("SystemRoot"));}
    private Path controlFixture(String mode) throws Exception {
        boolean windows=System.getProperty("os.name").startsWith("Windows");String java=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString();List<String> paths=new ArrayList<>();
        for(Class<?> type:List.of(ControlFixture.class,RuntimeDescriptor.class,com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class))paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        String cp=String.join(System.getProperty("path.separator"),paths);Path exe=root.resolve(windows?"control.cmd":"control");
        Files.writeString(exe,windows?"@echo off\r\n\""+java+"\" -cp \""+cp+"\" "+ControlFixture.class.getName()+" "+mode+" %*\r\nexit /b %errorlevel%\r\n":"#!/bin/sh\nexec '"+java+"' -cp '"+cp+"' '"+ControlFixture.class.getName()+"' "+mode+" \"$@\"\n");if(!windows)assertTrue(exe.toFile().setExecutable(true));return exe;
    }
    public static class ControlFixture {
        public static void main(String[] args) throws Exception {
            if(args[0].equals("hold")){Path root=Path.of(args[1]);long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(!Files.exists(root.resolve("write-"+ProcessHandle.current().pid()))&&!Files.exists(root.resolve("release-"+ProcessHandle.current().pid()))&&System.nanoTime()<end)Thread.sleep(10);if(Files.exists(root.resolve("write-"+ProcessHandle.current().pid()))){System.out.write(new byte[65536]);System.out.flush();boolean out=System.out.checkError();System.err.write(new byte[65536]);System.err.flush();io.github.rs0325.mcicon.internal.FilesSupport.atomicWrite(root.resolve("closed-"+ProcessHandle.current().pid()),(out+":"+System.err.checkError()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}while(!Files.exists(root.resolve("release-"+ProcessHandle.current().pid()))&&System.nanoTime()<end)Thread.sleep(10);return;}
            List<String> a=Arrays.asList(args);Path root=Path.of(a.get(a.indexOf("--root")+1));
            if(args[0].equals("overflow")){System.err.println("fixture-stderr-before-overflow");System.err.flush();System.out.write(new byte[1024*1024+1]);System.out.flush();return;}
            if(args[0].equals("retain")){
                Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),ControlFixture.class.getName(),"hold",root.toString()).inheritIO().start();
                var record=RuntimeDescriptor.JSON.createObjectNode().put("pid",child.pid()).put("started",child.info().startInstant().orElseThrow().toEpochMilli());Files.write(root.resolve("retainer.json"),RuntimeDescriptor.JSON.writeValueAsBytes(record));
            } else {
                Thread.sleep(300);System.out.println(RuntimeDescriptor.JSON.createObjectNode().put("state","ready").put("attempt",a.get(a.indexOf("--attempt")+1)).put("controllerPid",Long.parseLong(a.get(a.indexOf("--controller")+1))).put("controllerStarted",Long.parseLong(a.get(a.indexOf("--controller")+2))));System.out.flush();
                var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));var request=RuntimeDescriptor.read(input.readLine().getBytes(java.nio.charset.StandardCharsets.UTF_8));if(input.readLine()!=null)throw new IOException("EOF不足");
                long left=Duration.between(java.time.Instant.now(),java.time.Instant.parse(request.path("deadline").asText())).toMillis();Files.writeString(root.resolve("release-budget-ms"),Long.toString(left));
                Path file=Path.of(a.get(a.indexOf("--lease")+1));var lease=(com.fasterxml.jackson.databind.node.ObjectNode)RuntimeDescriptor.read(Files.readAllBytes(file));lease.putObject("resources");Files.write(file,RuntimeDescriptor.JSON.writeValueAsBytes(lease));
            }
            System.out.println("{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}");System.out.flush();
        }
    }
    @Test void writerFailureAtCompletionCannotCrossTheSuccessGate() throws Exception {
        var failure=new java.util.concurrent.atomic.AtomicReference<IOException>();IOException expected=new IOException("writer fixture");
        Thread writer=Thread.ofVirtual().start(()->{try{Thread.sleep(20);}catch(InterruptedException error){Thread.currentThread().interrupt();}failure.set(expected);});
        assertSame(expected,assertThrows(IOException.class,()->RuntimeHelper.joinWriter(writer,System.nanoTime()+Duration.ofSeconds(1).toNanos(),failure)));assertFalse(writer.isAlive());
    }
    @Test void releaseWriterMustEndWithinOriginalBudget() throws Exception {
        var wait=new java.util.concurrent.CountDownLatch(1);Thread writer=Thread.ofVirtual().start(()->{try{wait.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        long started=System.nanoTime();try{assertThrows(IOException.class,()->RuntimeHelper.joinWriter(writer,started+Duration.ofMillis(20).toNanos(),new java.util.concurrent.atomic.AtomicReference<>()));assertTrue(System.nanoTime()-started<Duration.ofSeconds(1).toNanos());}finally{wait.countDown();writer.join(Duration.ofSeconds(1));}assertFalse(writer.isAlive());assertDoesNotThrow(()->RuntimeHelper.joinWriter(writer,System.nanoTime(),new java.util.concurrent.atomic.AtomicReference<>()));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void keeperAckIsHeldUntilOneReleaseAndDoesNotChangeOtherOsDocker() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        Path executable=root.resolve("keeper");
        Files.writeString(executable,"#!/usr/bin/python3\nimport sys,json\na=sys.argv\nprint(json.dumps({'state':'ready','attempt':a[a.index('--attempt')+1],'controllerPid':int(a[a.index('--controller')+1]),'controllerStarted':int(a[a.index('--controller')+2])}),flush=True)\nr=json.loads(sys.stdin.readline())\nassert r['operation']=='release'\nassert sys.stdin.read()==''\nprint('{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}',flush=True)\n");assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());assertDoesNotThrow(()->helper.docker(List.of("version")));
            helper.keep(Duration.ofSeconds(2),new CancellationToken(),false);assertTrue(helper.control("release",Duration.ofSeconds(10),new CancellationToken()).ok());
            assertEquals("released",RuntimeDescriptor.read(Files.readAllBytes(lease.file())).path("state").asText());assertThrows(IOException.class,()->helper.control("release",Duration.ofSeconds(10),new CancellationToken()));
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void malformedKeeperAckClosesOwnPipeAndRetainsActiveLease() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});Path executable=root.resolve("keeper");
        Files.writeString(executable,"#!/usr/bin/python3\nimport sys\nprint('{}',flush=True)\nsys.stdin.read()\n");assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())){RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());assertThrows(IOException.class,()->helper.keep(Duration.ofSeconds(2),new CancellationToken(),false));assertEquals("active",lease.read().path("state").asText());}
    }
    @Test void commonControlExamplesMatchCoreConsumer() throws Exception {
        var examples=RuntimeDescriptor.read(Files.readAllBytes(Path.of("..","schemas","managed-helper-v1.examples.json")));
        for(var item:examples.get("control")) {
            var response=item.get("response");var result=RuntimeHelper.parse(RuntimeDescriptor.JSON.writeValueAsBytes(response),item.path("exitCode").intValue());
            assertEquals(response.path("status").asText().equals("ok"),result.ok());
        }
    }
    @Test void controlProtocolRejectsStatusExitAndUnknownFields() throws Exception {
        byte[] setup="{\"protocolVersion\":1,\"status\":\"setup-required\",\"diagnostics\":[{\"code\":\"MISSING\",\"detail\":\"前提が不足しています\",\"requiresPrivilege\":true,\"requiresRestart\":false}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(RuntimeHelper.parse(setup,2).ok());RuntimeDescriptorTest.assertInvalid(()->RuntimeHelper.parse(setup,0));
        RuntimeDescriptorTest.assertInvalid(()->RuntimeHelper.parse(new String(setup,java.nio.charset.StandardCharsets.UTF_8).replace("\"protocolVersion\":1","\"protocolVersion\":2").getBytes(java.nio.charset.StandardCharsets.UTF_8),2));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void keeperWriterFailureKeepsLeaseUnknownAndCollectsOnlyItsHelper() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});Path executable=root.resolve("keeper");
        Files.writeString(executable,"#!/usr/bin/python3\nimport sys,json,os,time\na=sys.argv\nopen(a[a.index('--lease')+1]+'.keeper-pid','w').write(str(os.getpid()))\nos.close(0)\nprint(json.dumps({'state':'ready','attempt':a[a.index('--attempt')+1],'controllerPid':int(a[a.index('--controller')+1]),'controllerStarted':int(a[a.index('--controller')+2])}),flush=True)\ntime.sleep(20)\n");assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())){RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());helper.keep(Duration.ofSeconds(2),new CancellationToken(),false);
            long started=System.nanoTime();assertThrows(IOException.class,()->helper.control("release",Duration.ofSeconds(10),new CancellationToken()));assertTrue(System.nanoTime()-started<Duration.ofSeconds(9).toNanos());assertEquals("active",lease.read().path("state").asText());
            long pid=Long.parseLong(Files.readString(Path.of(lease.file()+".keeper-pid")));assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void controlExecutesFixtureAndReleaseMarksEvidence() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        Path executable=root.resolve("helper");
        Files.writeString(executable,"#!/bin/sh\nprintf '%s' '{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}'\n");
        assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());
            assertTrue(helper.control("preflight",Duration.ofSeconds(1),new CancellationToken()).ok());
            assertTrue(helper.control("release",Duration.ofSeconds(1),new CancellationToken()).ok());
            assertEquals("released",RuntimeDescriptor.JSON.readTree(Files.readAllBytes(lease.file())).path("state").asText());
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void timedOutHelperKeepsUncertainLease() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        var descriptor=new ManagedFixture().descriptor();Path executable=root.resolve("helper");
        Files.writeString(executable,"#!/bin/sh\nexec /bin/sleep 5\n");assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,descriptor)) {
            RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());
            assertThrows(java.io.IOException.class,()->helper.control("release",Duration.ofMillis(50),new CancellationToken()));
            assertEquals("active",lease.read().path("state").asText());
            var evidence=evidence(lease);assertTrue(evidence.path("directProcessTerminated").asBoolean());assertTrue(evidence.path("exitCode").isInt());
            assertFalse(ProcessHandle.of(evidence.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));
        }
        assertFalse(RuntimeLease.idle(store,descriptor));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void runningHelperCancellationWaitsAndKeepsRawEvidence() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        Path executable=root.resolve("helper");Files.writeString(executable,"#!/bin/sh\nprintf started > \"$6.started\"\nprintf partial\nexec /bin/sleep 5\n");assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor());var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            CancellationToken token=new CancellationToken();RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());
            var future=executor.submit(()->helper.control("prepare",Duration.ofSeconds(4),token));
            Path marker=Path.of(lease.file()+".started");long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(5);assertTrue(Files.exists(marker));token.cancel();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED",((io.github.rs0325.mcicon.McIconException)failure.getCause()).code());
            var evidence=evidence(lease);assertTrue(evidence.path("directProcessTerminated").asBoolean());
            assertEquals("partial",Files.readString(lease.file().getParent().resolve(evidence.path("stdout").asText())));
            assertFalse(ProcessHandle.of(evidence.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));assertEquals("active",lease.read().path("state").asText());
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void malformedAndInconsistentRepliesKeepStdoutAndExitCode() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        for(String reply:List.of("invalid-json","{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}")) {
            Path executable=root.resolve("helper");Files.writeString(executable,"#!/bin/sh\nprintf '%s' '"+reply+"'\nexit 2\n");assertTrue(executable.toFile().setExecutable(true));
            try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
                RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());assertThrows(Exception.class,()->helper.control("prepare",Duration.ofSeconds(1),new CancellationToken()));
                var evidence=evidence(lease);assertEquals(2,evidence.path("exitCode").intValue());assertTrue(evidence.path("directProcessTerminated").asBoolean());
                assertEquals(reply,Files.readString(lease.file().getParent().resolve(evidence.path("stdout").asText())));assertEquals("active",lease.read().path("state").asText());
            }
        }
    }
    @Test void nonTerminatingDirectProcessIsRecordedAsFailure() throws Exception {
        Process process=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit) throws InterruptedException {Thread.sleep(unit.toMillis(timeout));return false;}
            public int exitValue(){throw new IllegalThreadStateException();}
            public boolean isAlive(){return true;}
            public void destroy(){}
            public Process destroyForcibly(){return this;}
            public long pid(){return ProcessHandle.current().pid();}
            public ProcessHandle.Info info(){return ProcessHandle.current().info();}
        };
        Path evidence=root.resolve("cleanup.json");assertThrows(java.io.IOException.class,()->RuntimeHelper.terminate(process,evidence));assertFalse(RuntimeDescriptor.read(Files.readAllBytes(evidence)).path("terminated").asBoolean());
    }
    private com.fasterxml.jackson.databind.JsonNode evidence(RuntimeLease lease) throws Exception {
        try(var files=Files.list(lease.file().getParent())) {return RuntimeDescriptor.read(Files.readAllBytes(files.filter(p->p.getFileName().toString().endsWith(".result.json")).findFirst().orElseThrow()));}
    }
    @Test void alreadyCancelledJobDoesNotStartHelper() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(root.resolve("missing-helper"),store.root(),lease,Map.of());
            CancellationToken token=new CancellationToken();token.cancel();
            assertEquals("CANCELLED",assertThrows(io.github.rs0325.mcicon.McIconException.class,()->helper.control("prepare",Duration.ofSeconds(1),token)).code());
            assertEquals("active",lease.read().path("state").asText());
        }
    }
    @Test void dockerKeepsAbsolutePrefixAndArgumentsSeparate() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(root.resolve("helper"),root.toRealPath(),lease,Map.of("MCICON_JOB","fixture"));
            ProcessBuilder builder=helper.docker(List.of("run","--name","contains spaces; $(literal)"));
            assertEquals(List.of(root.resolve("helper").toString(),"--protocol","1","--root",root.toRealPath().toString(),"--lease",lease.file().toString(),"docker","--","run","--name","contains spaces; $(literal)"),builder.command());
            assertEquals("fixture",builder.environment().get("MCICON_JOB"));
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void windowsHelperUsesOnlyVerifiedAppPathWhileOtherOsEnvironmentStaysUnchanged() throws Exception {
        for(String mode:List.of("windows","other-empty","other-explicit")) {
            Path directory=Files.createDirectory(root.resolve(mode)).toRealPath();
            var store=new RuntimeStore(directory,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
            Path executable=directory.resolve("helper");
            Files.writeString(executable,"#!/usr/bin/python3\nimport os,sys,json,pathlib\nr=pathlib.Path(sys.argv[sys.argv.index('--root')+1])\n(r/'child-environment.json').write_text(json.dumps({'PATH':os.environ.get('PATH'),'MCICON_JOB':os.environ.get('MCICON_JOB')}))\nprint(json.dumps({'protocolVersion':1,'status':'ok','diagnostics':[]}))\n");assertTrue(executable.toFile().setExecutable(true));
            try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
                Map<String,String> environment=mode.equals("other-empty")?Map.of("MCICON_JOB","fixture"):Map.of("PATH","host-sentinel","MCICON_JOB","fixture");
                RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,environment,mode.equals("windows"));
                assertTrue(helper.control("preflight",Duration.ofSeconds(3),new CancellationToken()).ok());
                var actual=RuntimeDescriptor.read(Files.readAllBytes(directory.resolve("child-environment.json")));
                if(mode.equals("windows"))assertEquals(directory.resolve("app").toString(),actual.path("PATH").asText());
                else if(mode.equals("other-empty"))assertTrue(actual.path("PATH").isNull());
                else assertEquals("host-sentinel",actual.path("PATH").asText());
                assertEquals("fixture",actual.path("MCICON_JOB").asText());
            }
        }
    }

}
