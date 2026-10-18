package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RebootRecoveryTest {
    @TempDir Path temporary;
    MacRuntimeTest.Fixture fixture;
    String generation;ObjectNode ready,registration;Path support;
    static final String OLD="00000000-0000-0000-0000-000000000001",NOW="00000000-0000-0000-0000-000000000002",ID="b".repeat(64);
    @BeforeEach void setup() throws Exception {
        fixture=new MacRuntimeTest.Fixture(temporary.toRealPath());CacheState cache=fixture.cache();registration=cache.register(true);generation=registration.path("generationOwner").asText();Files.createDirectory(cache.instance());Files.writeString(cache.instance().resolve("vz-identifier"),"fixture-machine");Files.writeString(cache.instance().resolve("lima.yaml"),"fixture-config");
        ready=MacFiles.JSON.createObjectNode().put("phase","ready").put("generationOwner",generation).put("instanceDir",cache.instance().toString()).put("machineIdSha256",MacFiles.hash(cache.instance().resolve("vz-identifier"))).put("forwardConfigSha256",MacFiles.hash(cache.instance().resolve("lima.yaml"))).put("guestOwnerMarkerSha256","c".repeat(64));ready.putArray("containers");ready.putObject("hostAgent").put("pid",2147483647).put("processStartedAt","2000-01-01T00:00:00Z").put("bootSessionId",OLD);ready.putObject("dockerSocket").put("path",cache.socket().toString()).put("dev",1).put("ino",1).put("uid",registration.path("uid").asLong());
        ObjectNode vm=MacFiles.JSON.createObjectNode();vm.set("identity",registration);vm.set("resources",ready);vm.put("memoryMiB",9216);MacFiles.write(cache.anchor().resolve("vm.json"),vm);
        support=fixture.executable.getParent().getParent().resolve("app/support");Files.createDirectories(support.resolve("bin"));ObjectNode manifest=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("limaVersion","2.2.1");manifest.putArray("files");manifest.putArray("debs");MacFiles.write(support.resolve("payload.json"),manifest);
        script("process-guard","#!/bin/sh\nc=$2; d=$4; shift 4\nif [ \"$1\" = --owner ]; then shift 3; fi\nshift\n\"$@\"\nr=$?\nprintf '%s\\n' \"$d\" > \"$c\"\nexit $r\n");probe(false);lima("Stopped");
    }
    void script(String name,String text) throws Exception {Path file=support.resolve(name);Files.writeString(file,text);file.toFile().setExecutable(true,true);}
    void probe(boolean matched) throws Exception {
        ObjectNode facts=MacFiles.JSON.createObjectNode().put("uid",registration.path("uid").asLong()).put("hostBindingSha256","a".repeat(64)).put("bootSessionId",NOW).put("physicalMemory",16L*1024*1024*1024).put("cpus",8).put("osVersion","15.7.1").put("arch","aarch64").put("vz",true).put("rosetta","installed");
        script("host-probe","#!/bin/sh\nif [ \"$1\" = --owned-executable ]; then echo '{\"complete\":true,\"matched\":"+matched+"}'; else echo '"+MacFiles.JSON.writeValueAsString(facts)+"'; fi\n");
    }
    void lima(String status) throws Exception {ObjectNode value=MacFiles.JSON.createObjectNode().put("name","v").put("status",status).put("dir",fixture.cache().instance().toString()).put("vmType","vz").put("arch","aarch64");script("bin/limactl","#!/bin/sh\necho '"+MacFiles.JSON.writeValueAsString(value)+"'\n");}
    @AfterEach void cleanup() throws Exception {erase(fixture.cache().anchor());}
    void erase(Path path) throws Exception {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.walkFileTree(path,new SimpleFileVisitor<>(){public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException{Files.delete(p);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path p,IOException e)throws IOException{if(e!=null)throw e;Files.delete(p);return FileVisitResult.CONTINUE;}});}
    MacRuntimeTest.Job oldJob(boolean pendingCreate) throws Exception {
        var job=fixture.job(true);ObjectNode lease=job.lease().read(),mac=ready.deepCopy();mac.withArray("containers").addObject().put("id",ID).put("name","old-worker");lease.withObject("resources").set("macos",mac);MacFiles.write(job.command().lease(),lease);
        try(var journal=OperationJournal.acquire(job.command().lease().getParent().resolve("tmp"),lease,Duration.ofSeconds(1))) {
            var prepare=journal.begin("prepare");Files.writeString(prepare.completion(),prepare.sha256()+"\n");journal.settled(prepare,mac);
            var create=journal.begin("create");if(pendingCreate)return job;Files.writeString(create.completion(),create.sha256()+"\n");journal.settled(create,actual(lease));journal.beginStart(ID,mac);
        }
        return job;
    }
    ObjectNode actual(ObjectNode lease) {ObjectNode actual=MacFiles.JSON.createObjectNode().put("Id",ID).put("Name","/old-worker");ObjectNode labels=actual.putObject("Config").putObject("Labels");for(String key:List.of("jobId","ownerId","descriptorSha256"))labels.set("io.github.rs0325.mcicon."+key,lease.path(key));labels.put("io.github.rs0325.mcicon.generation",generation);return actual;}
    MacRuntime runtime(MacRuntimeTest.Job job) throws Exception {return new MacRuntime(job.command(),fixture.executable,job.lease(),System.nanoTime());}
    void release(MacRuntimeTest.Job job) throws Exception {MacRuntime runtime=runtime(job);try(var journal=runtime.journal(Duration.ofSeconds(1));var lock=runtime.cache.lock(Duration.ofSeconds(1))){assertTrue(new RebootRecovery(runtime).release(journal));}}
    @Test void 旧startだけ未完なら別終了証拠を先に保存し元journalを変えない() throws Exception {
        var job=oldJob(false);ObjectNode original=job.lease().read();Path tmp=job.command().lease().getParent().resolve("tmp");ObjectNode snapshot=OperationJournal.rebootSnapshot(tmp,original,generation);release(job);assertTrue(job.lease().read().path("resources").isEmpty());assertEquals("active",job.lease().read().path("state").asText());assertTrue(snapshot.equals(OperationJournal.rebootSnapshot(tmp,original,generation)));
        Path ended=tmp.resolve("macos-ended-by-reboot.json");byte[] saved=Files.readAllBytes(ended);release(job);assertArrayEquals(saved,Files.readAllBytes(ended));
        try(var journal=OperationJournal.acquire(tmp,original,Duration.ofSeconds(1))){assertTrue(journal.released());assertThrows(IOException.class,journal::requireSettled);assertThrows(IOException.class,()->journal.begin("create"));}
    }
    @Test void 同時recoveryの別jobfence保持を生成者と誤判定しない() throws Exception {
        var first=oldJob(false);var second=oldJob(false);try(var waiting=OperationJournal.acquire(second.command().lease().getParent().resolve("tmp"),second.lease().read(),Duration.ofSeconds(1))){release(first);}assertTrue(first.lease().read().path("resources").isEmpty());assertFalse(second.lease().read().path("resources").isEmpty());release(second);
    }
    @Test void create未確定と現在VMと現在limactlは保持する() throws Exception {
        var job=oldJob(true);ObjectNode original=job.lease().read();assertThrows(IOException.class,()->release(job));assertTrue(original.equals(job.lease().read()));
        erase(job.command().lease().getParent());var known=oldJob(false);lima("Running");assertThrows(IOException.class,()->release(known));lima("Stopped");probe(true);assertThrows(IOException.class,()->release(known));assertFalse(Files.exists(known.command().lease().getParent().resolve("tmp/macos-ended-by-reboot.json")));
    }
    @Test void 欠落anchorでも終了参照解消後だけ同pathfreshownerへ進む() throws Exception {
        var old=oldJob(false);erase(fixture.cache().anchor());release(old);ObjectNode value=old.lease().read();value.put("state","released");MacFiles.write(old.command().lease(),value);var next=fixture.job(false);MacRuntime current=runtime(next);
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))){new RebootRecovery(current).prepare(journal);assertTrue(current.cache.freshIncarnation());assertTrue(current.cache.anchor().equals(fixture.cache().anchor()));assertFalse(current.cache.registered().path("generationOwner").asText().equals(generation));assertTrue(current.cache.history(generation,OLD).equals(registration));}
    }
    @Test void 通常close済み旧bootは終了証拠を捏造せず欠落cacheを新ownerで準備する() throws Exception {
        var old=oldJob(false);Path tmp=old.command().lease().getParent().resolve("tmp");ObjectNode lease=old.lease().read();
        try(var journal=OperationJournal.acquire(tmp,lease,Duration.ofSeconds(1))) {
            try(var entries=Files.list(tmp.resolve("macos-operations"))) {
                for(Path entry:entries.toList())if(MacFiles.json(entry.resolve("intent.json")).path("kind").asText().equals("start")) {
                    var start=new OperationJournal.Operation(entry,MacFiles.hash(entry.resolve("intent.json")));Files.writeString(start.completion(),start.sha256()+"\n");
                    journal.settled(start,MacFiles.JSON.createObjectNode().put("removedFullId",ID).put("absenceVerified",true));
                }
            }
            journal.markReleased();
        }
        lease.put("state","released");lease.withObject("resources").removeAll();MacFiles.write(old.command().lease(),lease);
        ObjectNode snapshot=OperationJournal.rebootSnapshot(tmp,lease,null);byte[] original=Files.readAllBytes(old.command().lease()),receipt=Files.readAllBytes(old.command().lease().resolveSibling("lease.lock"));
        erase(fixture.cache().anchor());MacRuntime current=runtime(fixture.job(false));
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))) {
            new RebootRecovery(current).prepare(journal);assertTrue(current.cache.freshIncarnation());assertTrue(current.cache.history(generation,OLD).equals(registration));assertNotEquals(generation,current.cache.registered().path("generationOwner").asText());
            assertEquals(fixture.cache().anchor(),current.cache.anchor());assertFalse(Files.exists(current.cache.instance()));assertFalse(Files.exists(current.cache.anchor().resolve("vm.json")));
        }
        assertArrayEquals(original,Files.readAllBytes(old.command().lease()));assertArrayEquals(receipt,Files.readAllBytes(old.command().lease().resolveSibling("lease.lock")));assertEquals(snapshot,OperationJournal.rebootSnapshot(tmp,lease,null));assertFalse(Files.exists(tmp.resolve("macos-ended-by-reboot.json")));
    }
    @Test void fresh生成資格を先に消費し同boot欠落で再使用しない() throws Exception {
        var old=oldJob(false);erase(fixture.cache().anchor());release(old);ObjectNode value=old.lease().read();value.put("state","released");MacFiles.write(old.command().lease(),value);MacRuntime current=runtime(fixture.job(false));
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))) {
            new RebootRecovery(current).prepare(journal);Path proof=current.cache.anchor().resolve("boot-recreated.json");byte[] retained=Files.readAllBytes(proof);
            assertTrue(current.cache.freshIncarnation());current.cache.consumeFreshIncarnation();assertFalse(current.cache.freshIncarnation());assertFalse(Files.exists(current.cache.instance()));assertFalse(Files.exists(current.cache.anchor().resolve("vm.json")));
            Files.createDirectory(current.cache.instance());MacFiles.write(current.cache.anchor().resolve("vm.json"),MacFiles.JSON.createObjectNode());
            erase(current.cache.instance());Files.delete(current.cache.anchor().resolve("vm.json"));
            assertFalse(current.cache.freshIncarnation());assertThrows(IOException.class,current.cache::consumeFreshIncarnation);assertArrayEquals(retained,Files.readAllBytes(proof));
            assertEquals(MacFiles.hash(proof),MacFiles.json(current.cache.anchor().resolve("boot-recreated-used.json")).path("bootRecreatedSha256").asText());
        }
    }
    @Test void 新bootの厳格live未着手だけを並行待機として許可する() throws Exception {
        var old=oldJob(false);release(old);ObjectNode previous=old.lease().read();previous.put("state","released");MacFiles.write(old.command().lease(),previous);MacRuntime current=runtime(fixture.job(false));
        var dead=fixture.job(true);
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))) {
            assertThrows(IOException.class,()->new RebootRecovery(current).prepare(journal));erase(dead.command().lease().getParent());
            var waiting=fixture.job(false);assertThrows(IOException.class,()->new RebootRecovery(current).prepare(journal));
            try(var core=FileChannel.open(waiting.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var held=core.lock(64,1,false)) {
                Path tmp=waiting.command().lease().getParent().resolve("tmp");
                try(var pending=OperationJournal.acquire(tmp,waiting.lease().read(),Duration.ofSeconds(1))){pending.begin("prepare");}
                assertThrows(IOException.class,()->new RebootRecovery(current).prepare(journal));erase(tmp.resolve("macos-operations"));
                try(var peer=OperationJournal.acquire(tmp,waiting.lease().read(),Duration.ofSeconds(1))){new RebootRecovery(current).prepare(journal);}
                assertEquals(NOW,current.cache.registered().path("bootSessionId").asText());assertEquals(generation,current.cache.registered().path("generationOwner").asText());
            }
        }
    }
    @Test void Stopped再開callerは未着手peerだけ許可し実資源と生成履歴を拒否する() throws Exception {
        var old=oldJob(false);release(old);ObjectNode previous=old.lease().read();previous.put("state","released");MacFiles.write(old.command().lease(),previous);MacRuntime current=runtime(fixture.job(false));
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))){new RebootRecovery(current).prepare(journal);}
        var waiting=fixture.job(false);Path called=support.resolve("start-called");ObjectNode list=MacFiles.JSON.createObjectNode().put("name","v").put("status","Stopped").put("dir",current.cache.instance().toString()).put("vmType","vz").put("arch","aarch64");
        script("bin/limactl","#!/bin/sh\ncase \"$2\" in list) echo '"+MacFiles.JSON.writeValueAsString(list)+"';; start) echo called > '"+called+"'; exit 99;; *) exit 98;; esac\n");
        var base=new io.github.rs0325.mcicon.helper.LeaseFile.Artifact(support.resolve("base"),620224512L,"1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55");var image=new io.github.rs0325.mcicon.helper.LeaseFile.Artifact(support.resolve("image"),1,"b".repeat(64));
        try(var core=FileChannel.open(waiting.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var held=core.lock(64,1,false)) {
            ObjectNode value=waiting.lease().read();value.withObject("resources").set("macos",ready);MacFiles.write(waiting.command().lease(),value);
            assertThrows(IOException.class,()->current.prepare(base,image));assertFalse(Files.exists(called));value.withObject("resources").removeAll();MacFiles.write(waiting.command().lease(),value);
            Path tmp=waiting.command().lease().getParent().resolve("tmp");try(var pending=OperationJournal.acquire(tmp,value,Duration.ofSeconds(1))){pending.begin("prepare");}
            assertThrows(IOException.class,()->current.prepare(base,image));assertFalse(Files.exists(called));erase(tmp.resolve("macos-operations"));
            try(var peer=OperationJournal.acquire(tmp,value,Duration.ofSeconds(1))) {
                var scan=current.references();assertTrue(scan.active());assertFalse(scan.stopAllowed());assertTrue(scan.restartAllowed());
                IOException failed=assertThrows(IOException.class,()->current.prepare(base,image));assertTrue(failed.getMessage().contains("専用VMの再起動"));assertTrue(Files.exists(called));
            }
        }
    }
    @Test void OwnedStoppedは同owner同diskでbootだけ整合しcoldstartへ渡す() throws Exception {
        var old=oldJob(false);release(old);ObjectNode value=old.lease().read();value.put("state","released");MacFiles.write(old.command().lease(),value);var next=fixture.job(false);MacRuntime current=runtime(next);byte[] machine=Files.readAllBytes(fixture.cache().instance().resolve("vz-identifier"));
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))){new RebootRecovery(current).prepare(journal);assertEquals(generation,current.cache.registered().path("generationOwner").asText());assertEquals(NOW,current.cache.registered().path("bootSessionId").asText());assertTrue(current.cache.history(generation,OLD).equals(registration));assertArrayEquals(machine,Files.readAllBytes(current.cache.instance().resolve("vz-identifier")));assertTrue(MacFiles.json(current.cache.anchor().resolve("vm.json")).path("identity").equals(current.cache.registered()));assertFalse(current.cache.freshIncarnation());}
    }
    @Test void boot切替後の未着手jobは旧diskを操作せず閉じる() throws Exception {
        var old=oldJob(false);release(old);ObjectNode previous=old.lease().read();previous.put("state","released");MacFiles.write(old.command().lease(),previous);var next=fixture.job(true);MacRuntime current=runtime(next);
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))){new RebootRecovery(current).prepare(journal);}
        script("bin/limactl","#!/bin/sh\nexit 99\n");current.release();assertTrue(next.lease().read().path("resources").isEmpty());assertFalse(Files.exists(old.command().lease().getParent().resolve("tmp/macos-reboot-cleanup.json")));
    }
    @Test void 旧disk未回収のまま現在ready資源を成功解放しない() throws Exception {
        var old=oldJob(false);release(old);ObjectNode previous=old.lease().read();previous.put("state","released");MacFiles.write(old.command().lease(),previous);var next=fixture.job(false);MacRuntime current=runtime(next);
        try(var journal=current.journal(Duration.ofSeconds(1));var lock=current.cache.lock(Duration.ofSeconds(1))){new RebootRecovery(current).prepare(journal);}
        assertThrows(IOException.class,()->new RebootRecovery(current).requireDiskCleaned());Path ended=old.command().lease().getParent().resolve("tmp/macos-ended-by-reboot.json");ObjectNode cleaned=MacFiles.JSON.createObjectNode().put("endedProofSha256",MacFiles.hash(ended));MacFiles.write(ended.resolveSibling("macos-reboot-cleanup.json"),cleaned);new RebootRecovery(current).requireDiskCleaned();
    }
    @Test void 元core生存とjournal改変はboot差で洗浄しない() throws Exception {
        var live=fixture.job(false);assertThrows(IOException.class,()->RebootRecovery.requireDead(live.lease().read()));erase(live.command().lease().getParent());var old=oldJob(false);release(old);Path ended=old.command().lease().getParent().resolve("tmp/macos-ended-by-reboot.json");ObjectNode proof=(ObjectNode)MacFiles.json(ended);proof.put("receiptSha256","0".repeat(64));MacFiles.write(ended,proof);assertThrows(IOException.class,()->release(old));assertTrue(MacFiles.json(ended).equals(proof));
    }
    @Test void 元ownerlabelsと完全IDをcurrentjobへ読み替えない() throws Exception {
        var old=oldJob(false);ObjectNode lease=old.lease().read(),container=(ObjectNode)lease.path("resources").path("macos").path("containers").get(0);ObjectNode actual=actual(lease);DockerTransport.verifyRebootContainer(actual,lease,container,generation);actual.withObject("Config").withObject("Labels").put("io.github.rs0325.mcicon.ownerId",UUID.randomUUID().toString());assertThrows(IOException.class,()->DockerTransport.verifyRebootContainer(actual,lease,container,generation));
    }
}
