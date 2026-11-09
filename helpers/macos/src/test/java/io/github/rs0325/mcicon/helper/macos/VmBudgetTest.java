package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.*;
import io.github.rs0325.mcicon.internal.AdmissionEvidence;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 実VMを起動せず、署名leaseとhost-probe代役でadmissionを通す。 */
@EnabledOnOs(OS.MAC)
class VmBudgetTest {
    @TempDir Path temporary;
    MacRuntimeTest.Fixture fixture;
    final List<CacheState> extraCaches=new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        fixture=new MacRuntimeTest.Fixture(temporary.toRealPath());Path support=fixture.executable.getParent().getParent().resolve("app/support");
        var manifest=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("limaVersion","2.2.1");manifest.putArray("files");manifest.putArray("debs");MacFiles.write(support.resolve("payload.json"),manifest);
        script(support.resolve("process-guard"),"#!/bin/sh\nc=$2; d=$4; shift 4\nif [ \"$1\" = --owner ]; then shift 3; fi\nshift\n\"$@\"\nr=$?\nprintf '%s\\n' \"$d\" > \"$c\"\nexit $r\n");
        ObjectNode facts=MacFiles.JSON.createObjectNode().put("uid",((Number)Files.getAttribute(fixture.root,"unix:uid")).longValue()).put("hostBindingSha256","a".repeat(64)).put("bootSessionId",RebootRecoveryTest.OLD).put("physicalMemory",16L*1024*1024*1024).put("cpus",8).put("osVersion","15.7.1").put("arch","aarch64").put("vz",true).put("rosetta","installed");
        script(support.resolve("host-probe"),"#!/bin/sh\necho '"+MacFiles.JSON.writeValueAsString(facts)+"'\n");
    }
    private static void script(Path path,String text) throws Exception {Files.writeString(path,text);assertTrue(path.toFile().setExecutable(true,true));}
    private MacRuntime runtime(MacRuntimeTest.Job job) throws Exception {return new MacRuntime(job.command(),fixture.executable,job.lease(),System.nanoTime());}
    private void memory(MacRuntimeTest.Job job,int mib) throws Exception {
        ObjectNode lease=job.lease().read();((ObjectNode)lease.path("requirements")).put("containerMemoryMiB",mib);save(job,lease);
    }
    private void save(MacRuntimeTest.Job job,ObjectNode lease) throws Exception {
        ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(job.command().lease().resolveSibling("lease.lock"),LeaseFile.receipt(identity));MacFiles.write(job.command().lease(),lease);
    }
    private MacRuntimeTest.Job foreign(String name) throws Exception {
        var job=fixture.job(false);ObjectNode descriptor=fixture.descriptor.deepCopy().put("runtimeId",name);byte[] bytes=MacFiles.JSON.writeValueAsBytes(descriptor);fixture.publish(bytes);
        ObjectNode lease=job.lease().read().put("runtimeId",name).put("descriptorSha256",MacFiles.hash(bytes));Files.write(job.command().lease().resolveSibling("descriptor.json"),bytes);save(job,lease);return job;
    }
    private LeaseReferences.Scan scan(MacRuntimeTest.Job job) throws Exception {var scan=LeaseReferences.inspect(job.command(),fixture.executable,fixture.digest,io.github.rs0325.mcicon.internal.LeaseTrust.verified(fixture.trust));assertFalse(scan.unknown(),scan.diagnostics().toString());return scan;}
    @Test void 同世代の未着手liveの最大要求を新VM予定予約へ含める() throws Exception {
        var current=fixture.job(false);var peer=fixture.job(false);memory(peer,4096);
        try(var channel=FileChannel.open(peer.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var held=channel.lock(64,1,false)){assertEquals(9216,VmBudget.require(runtime(current),scan(current)));}
    }
    @Test void 同じ他世代の二leaseはVM予約を一度だけ数える() throws Exception {
        var current=fixture.job(false);var first=foreign("old");var second=foreign("old");
        try(var one=FileChannel.open(first.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var a=one.lock(64,1,false);var two=FileChannel.open(second.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var b=two.lock(64,1,false)) {
            var references=scan(current);assertEquals(1,references.generations().size());assertEquals(2048,references.generations().getFirst().containerMemoryMiB());assertEquals(3072,VmBudget.require(runtime(current),references));
        }
    }
    @Test void 別世代VMを加算したhost不足だけを容量延期にする() throws Exception {
        var current=fixture.job(false);List<FileChannel> channels=new ArrayList<>();List<FileLock> locks=new ArrayList<>();
        try {
            for(int i=0;i<5;i++){var peer=foreign("old-"+i);var channel=FileChannel.open(peer.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);channels.add(channel);locks.add(channel.lock(64,1,false));}
            var runtime=runtime(current);var references=scan(current);assertEquals(5,references.generations().size());assertThrows(AdmissionEvidence.Busy.class,()->VmBudget.require(runtime,references));
        }finally{for(var lock:locks)lock.release();for(var channel:channels)channel.close();}
    }
    @Test void 候補単独でもhost不足なら他liveを理由に延期しない() throws Exception {
        var current=fixture.job(false);memory(current,8192);current=new MacRuntimeTest.Job(current.command(),LeaseFile.open(current.command(),fixture.executable));var peer=foreign("old");
        try(var channel=FileChannel.open(peer.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var held=channel.lock(64,1,false)) {
            var runtime=runtime(current);var references=scan(current);IOException failure=assertThrows(IOException.class,()->VmBudget.require(runtime,references));assertFalse(failure instanceof AdmissionEvidence.Busy);
        }
    }
    @AfterEach void cleanup() throws Exception {
        var owned=new ArrayList<>(extraCaches);owned.add(fixture.cache());for(CacheState cache:owned){Path anchor=cache.anchor();if(Files.exists(anchor,LinkOption.NOFOLLOW_LINKS)){cache.verify();Files.walkFileTree(anchor,new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attributes)throws IOException{Files.delete(path);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path path,IOException failure)throws IOException{if(failure!=null)throw failure;Files.delete(path);return FileVisitResult.CONTINUE;}
        });}}
    }
    @Test void 保存VM予約の不一致を容量延期へ落とさない() throws Exception {
        var current=fixture.job(false);var cache=fixture.cache();var registration=cache.register(true);Files.createDirectory(cache.instance());
        Files.writeString(cache.instance().resolve("vz-identifier"),"machine");Files.writeString(cache.instance().resolve("lima.yaml"),"memory: 9GiB\n");Files.writeString(cache.anchor().resolve("instance.yaml"),"memory: 9216MiB\n");
        ObjectNode ready=MacFiles.JSON.createObjectNode().put("phase","ready").put("generationOwner",registration.path("generationOwner").asText()).put("instanceDir",cache.instance().toString()).put("machineIdSha256",MacFiles.hash(cache.instance().resolve("vz-identifier"))).put("forwardConfigSha256",MacFiles.hash(cache.instance().resolve("lima.yaml"))).put("guestOwnerMarkerSha256","b".repeat(64));
        ready.putArray("containers");ready.putObject("hostAgent").put("pid",2147483647).put("processStartedAt","2000-01-01T00:00:00Z").put("bootSessionId",RebootRecoveryTest.OLD);ready.putObject("dockerSocket").put("path",cache.socket().toString()).put("dev",1).put("ino",1).put("uid",registration.path("uid").asLong());
        ObjectNode record=MacFiles.JSON.createObjectNode().put("memoryMiB",9216);record.set("identity",registration);record.set("resources",ready);MacFiles.write(cache.anchor().resolve("vm.json"),record);
        var runtime=runtime(current);assertEquals(9216,VmBudget.require(runtime,scan(current)));
        record.put("memoryMiB",3072);MacFiles.write(cache.anchor().resolve("vm.json"),record);
        IOException failure=assertThrows(IOException.class,()->VmBudget.require(runtime,scan(current)));assertFalse(failure instanceof AdmissionEvidence.Busy);
        Files.writeString(cache.anchor().resolve("instance.yaml"),"memory: 3072MiB\n");
        failure=assertThrows(IOException.class,()->VmBudget.require(runtime,scan(current)));assertFalse(failure instanceof AdmissionEvidence.Busy);assertTrue(failure.getMessage().contains("実体/生成時"));
        Files.writeString(cache.anchor().resolve("instance.yaml"),"memory: 9216MiB\n");
        record.put("memoryMiB",9216);((ObjectNode)record.path("resources")).remove("machineIdSha256");MacFiles.write(cache.anchor().resolve("vm.json"),record);
        assertThrows(IOException.class,()->VmBudget.require(runtime,scan(current)));
    }

    private ObjectNode record(CacheState cache,int memory,boolean running) throws Exception {
        ObjectNode registration=cache.register(true);if(!cache.anchor().equals(fixture.cache().anchor()))extraCaches.add(cache);Files.createDirectory(cache.instance());
        Files.writeString(cache.instance().resolve("vz-identifier"),"machine");Files.writeString(cache.instance().resolve("lima.yaml"),"memory: "+memory+"MiB\n");Files.writeString(cache.anchor().resolve("instance.yaml"),"memory: "+memory+"MiB\n");
        ObjectNode ready=MacFiles.JSON.createObjectNode().put("phase","ready").put("generationOwner",registration.path("generationOwner").asText()).put("instanceDir",cache.instance().toString()).put("machineIdSha256",MacFiles.hash(cache.instance().resolve("vz-identifier"))).put("forwardConfigSha256",MacFiles.hash(cache.instance().resolve("lima.yaml"))).put("guestOwnerMarkerSha256","b".repeat(64));ready.putArray("containers");
        long pid=running?ProcessHandle.current().pid():2147483647;String started=running?ProcessHandle.current().info().startInstant().orElseThrow().toString():"2000-01-01T00:00:00Z";
        ready.putObject("hostAgent").put("pid",pid).put("processStartedAt",started).put("bootSessionId",RebootRecoveryTest.OLD);ready.putObject("dockerSocket").put("path",cache.socket().toString()).put("dev",1).put("ino",1).put("uid",registration.path("uid").asLong());
        if(running){Files.writeString(cache.instance().resolve("ha.pid"),Long.toString(pid));Files.writeString(cache.instance().resolve("boot-session.tmp"),RebootRecoveryTest.OLD);}
        ObjectNode record=MacFiles.JSON.createObjectNode().put("memoryMiB",memory);record.set("identity",registration);record.set("resources",ready);MacFiles.write(cache.anchor().resolve("vm.json"),record);return record;
    }
    @Test void 最後のleaseがreleasedでもRunningなら予約を落とさず停止不明も保全する() throws Exception {
        var current=fixture.job(false);var released=foreign("idle");ObjectNode value=(ObjectNode)MacFiles.json(released.command().lease());String digest=value.path("descriptorSha256").asText();value.put("state","released");MacFiles.write(released.command().lease(),value);
        CacheState cache=new CacheState(fixture.root,fixture.host,digest,((Number)Files.getAttribute(fixture.root,"unix:uid")).longValue(),"a".repeat(64),RebootRecoveryTest.OLD);ObjectNode record=record(cache,12288,true);
        Path support=fixture.executable.getParent().getParent().resolve("app/support");Files.createDirectories(support.resolve("bin"));script(support.resolve("bin/limactl"),"#!/bin/sh\nprintf '{\"name\":\"v\",\"dir\":\"%s/v\",\"vmType\":\"vz\",\"arch\":\"aarch64\",\"status\":\"%s\"}\n' \"$LIMA_HOME\" \"$(/bin/cat \"$LIMA_HOME/status\")\"\n");Files.writeString(cache.limaHome().resolve("status"),"Running");
        var runtime=runtime(current);var references=scan(current);assertTrue(references.generations().isEmpty());var trust=io.github.rs0325.mcicon.internal.LeaseTrust.verified(fixture.trust);
        IOException failure=assertThrows(IOException.class,()->VmBudget.require(runtime,references,trust));assertFalse(failure instanceof AdmissionEvidence.Busy);assertTrue(failure.getMessage().contains("live lease外"));assertFalse(Files.exists(current.command().lease().getParent().resolve("tmp/admission-v1.json")));
        Files.writeString(cache.limaHome().resolve("status"),"Unknown");assertThrows(IOException.class,()->VmBudget.require(runtime,references,trust));
        Files.writeString(cache.limaHome().resolve("status"),"Stopped");assertThrows(IOException.class,()->VmBudget.require(runtime,references,trust));
        ((ObjectNode)record.path("resources").path("hostAgent")).put("pid",2147483647).put("processStartedAt","2000-01-01T00:00:00Z");MacFiles.write(cache.anchor().resolve("vm.json"),record);assertThrows(IOException.class,()->VmBudget.require(runtime,references,trust));
        Files.delete(cache.instance().resolve("ha.pid"));assertEquals(3072,VmBudget.require(runtime,references,trust));
    }
    @Test void 候補単独で既存VMを超える場合は別世代liveがあっても延期しない() throws Exception {
        var current=fixture.job(false);memory(current,4096);current=new MacRuntimeTest.Job(current.command(),LeaseFile.open(current.command(),fixture.executable));record(fixture.cache(),3072,false);var peer=foreign("other");
        try(var channel=FileChannel.open(peer.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var held=channel.lock(64,1,false)) {
            var runtime=runtime(current);var references=scan(current);IOException failure=assertThrows(IOException.class,()->VmBudget.require(runtime,references));assertFalse(failure instanceof AdmissionEvidence.Busy);assertTrue(failure.getMessage().contains("候補単独でもVM容量"));
        }
    }

}
