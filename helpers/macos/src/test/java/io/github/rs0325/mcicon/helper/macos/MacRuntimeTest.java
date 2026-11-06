package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MacRuntimeTest {
    @TempDir Path temporary;
    private Fixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new Fixture(temporary.toRealPath());}
    @AfterEach void cleanup() throws Exception {
        Path anchor=fixture.cache().anchor();if(Files.exists(anchor,LinkOption.NOFOLLOW_LINKS))Files.walkFileTree(anchor,new SimpleFileVisitor<>(){
            public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws java.io.IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path directory,java.io.IOException error) throws java.io.IOException {if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;}
        });
    }
    @Test void live未着手の失敗cleanupはanchorを要求せず自身だけを閉じる() throws Exception {
        var job=fixture.job(false);assertTrue(MacRuntime.releaseUnstarted(job.command(),job.lease(),System.nanoTime()));assertTrue(job.lease().read().path("resources").isEmpty());assertFalse(Files.exists(fixture.cache().anchor()));assertFalse(Files.exists(fixture.root.resolve("macos")));
        try(var journal=OperationJournal.acquire(job.command().lease().getParent().resolve("tmp"),job.lease().read(),Duration.ofSeconds(1))){assertTrue(journal.released());assertThrows(java.io.IOException.class,()->journal.begin("create"));}
    }
    @Test void 初回取得中断後のdead未着手leaseを閉じて次jobを初回登録できる() throws Exception {
        var dead=fixture.job(true);String launcher=System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path",fixture.executable.toString());
            assertEquals(0,Main.run(new String[]{"--protocol","1","--root",fixture.root.toString(),"--lease",dead.command().lease().toString(),"release"}));
        }finally {if(launcher==null)System.clearProperty("jpackage.app-path");else System.setProperty("jpackage.app-path",launcher);}
        assertTrue(MacRuntime.releaseUnstarted(dead.command(),dead.lease(),System.nanoTime()));
        ObjectNode value=dead.lease().read();value.put("state","released");MacFiles.write(dead.command().lease(),value);
        assertTrue(value.path("resources").isEmpty());assertTrue(fixture.cache().firstUse());
        var next=fixture.job(false);CacheState cache=fixture.cache();
        try(var operation=OperationJournal.acquire(next.command().lease().getParent().resolve("tmp"),next.lease().read(),Duration.ofSeconds(1));var lifecycle=cache.lock(Duration.ofSeconds(1))) {
            var scan=LeaseReferences.inspect(next.command(),fixture.executable,fixture.digest);assertTrue(scan.pristine());assertFalse(scan.unknown());cache.register(scan,operation);cache.verify();
        }
    }
    @Test void 生成痕跡は未着手cleanupへ流さない() throws Exception {
        var pending=fixture.job(false);try(var journal=OperationJournal.acquire(pending.command().lease().getParent().resolve("tmp"),pending.lease().read(),Duration.ofSeconds(1))){journal.begin("prepare");}
        assertFalse(MacRuntime.releaseUnstarted(pending.command(),pending.lease(),System.nanoTime()));assertFalse(Files.exists(fixture.cache().anchor()));
    }
    @Test void dead未着手の回収は既存世代と別jobを変更しない() throws Exception {
        var other=fixture.job(false);CacheState cache=fixture.cache();
        try(var operation=OperationJournal.acquire(other.command().lease().getParent().resolve("tmp"),other.lease().read(),Duration.ofSeconds(1));var lifecycle=cache.lock(Duration.ofSeconds(1))) {
            cache.register(LeaseReferences.inspect(other.command(),fixture.executable,fixture.digest),operation);
        }
        Path registration=fixture.root.resolve("macos").resolve(fixture.digest+".json");byte[] registered=Files.readAllBytes(registration),marker=Files.readAllBytes(cache.anchor().resolve("owner.json")),otherLease=Files.readAllBytes(other.command().lease());
        var dead=fixture.job(true);assertTrue(MacRuntime.releaseUnstarted(dead.command(),dead.lease(),System.nanoTime()));
        cache.verify();assertArrayEquals(registered,Files.readAllBytes(registration));assertArrayEquals(marker,Files.readAllBytes(cache.anchor().resolve("owner.json")));assertArrayEquals(otherLease,Files.readAllBytes(other.command().lease()));
    }
    @Test void deadの資源と途中journalを未着手扱いしない() throws Exception {
        var resources=fixture.job(true);ObjectNode value=resources.lease().read();value.withObject("resources").putObject("macos").put("phase","starting").put("generationOwner",UUID.randomUUID().toString()).put("instanceDir",fixture.cache().instance().toString()).putArray("containers");MacFiles.write(resources.command().lease(),value);
        byte[] before=Files.readAllBytes(resources.command().lease());assertFalse(MacRuntime.releaseUnstarted(resources.command(),resources.lease(),System.nanoTime()));assertArrayEquals(before,Files.readAllBytes(resources.command().lease()));
        var pending=fixture.job(true);Path tmp=pending.command().lease().getParent().resolve("tmp");
        try(var journal=OperationJournal.acquire(tmp,pending.lease().read(),Duration.ofSeconds(1))){journal.begin("prepare");}
        assertFalse(MacRuntime.releaseUnstarted(pending.command(),pending.lease(),System.nanoTime()));
        var partial=fixture.job(true);Path operations=partial.command().lease().getParent().resolve("tmp/macos-operations");Files.createDirectory(operations);Files.createDirectory(operations.resolve(UUID.randomUUID().toString()));
        assertFalse(MacRuntime.releaseUnstarted(partial.command(),partial.lease(),System.nanoTime()));
        assertFalse(Files.exists(fixture.cache().anchor()));
    }
    @Test void 同時live未着手jobはlifecycle内で真初回だけ登録できる() throws Exception {
        var first=fixture.job(false);var second=fixture.job(false);CacheState cache=fixture.cache();
        try(FileChannel core=FileChannel.open(second.command().lease().resolveSibling("lease.lock"),StandardOpenOption.WRITE);var coreLock=core.lock(64,1,false);var operation=OperationJournal.acquire(first.command().lease().getParent().resolve("tmp"),first.lease().read(),Duration.ofSeconds(1));var lifecycle=cache.lock(Duration.ofSeconds(1))) {
            var scan=LeaseReferences.inspect(first.command(),fixture.executable,fixture.digest);assertTrue(scan.active());assertFalse(scan.unknown());assertTrue(scan.pristine());cache.register(scan,operation);cache.verify();assertFalse(cache.firstUse());
        }
    }
    @Test void releasedの生成履歴が残れば両登録欠落でも初回にしない() throws Exception {
        var first=fixture.job(false);var previous=fixture.job(false);ObjectNode value=previous.lease().read();value.put("state","released");MacFiles.write(previous.command().lease(),value);Path journal=previous.command().lease().getParent().resolve("tmp/macos-operations");Files.createDirectory(journal);Files.createDirectory(journal.resolve(UUID.randomUUID().toString()));
        CacheState cache=fixture.cache();try(var operation=OperationJournal.acquire(first.command().lease().getParent().resolve("tmp"),first.lease().read(),Duration.ofSeconds(1));var lifecycle=cache.lock(Duration.ofSeconds(1))) {
            var scan=LeaseReferences.inspect(first.command(),fixture.executable,fixture.digest);assertFalse(scan.pristine());assertThrows(java.io.IOException.class,()->cache.register(scan,operation));assertTrue(cache.firstUse());
        }
    }
    @Test void 別descriptorの正常released履歴を保持して新世代だけ初回登録する() throws Exception {
        var current=fixture.job(false);var previous=releasedJob(true);Path prior=previous.command().lease().getParent();Map<Path,byte[]> retained=new HashMap<>();
        try(var files=Files.walk(prior)){for(Path path:files.filter(Files::isRegularFile).toList())retained.put(path,Files.readAllBytes(path));}
        CacheState cache=fixture.cache();
        try(var operation=OperationJournal.acquire(current.command().lease().getParent().resolve("tmp"),current.lease().read(),Duration.ofSeconds(1));var lifecycle=cache.lock(Duration.ofSeconds(1))) {
            var scan=LeaseReferences.inspect(current.command(),fixture.executable,fixture.digest);assertTrue(scan.pristine());assertTrue(scan.stopAllowed());assertFalse(scan.unknown());
            cache.register(scan,operation);cache.verify();assertEquals(fixture.digest,cache.registered().path("descriptorSha256").asText());
        }
        try(var files=Files.walk(prior)){assertEquals(retained.keySet(),new HashSet<>(files.filter(Files::isRegularFile).toList()));}
        for(var entry:retained.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));
    }
    @Test void 同descriptorの正常released生成履歴は初回登録へ読み替えない() throws Exception {
        var current=fixture.job(false);releasedJob(false);CacheState cache=fixture.cache();
        try(var operation=OperationJournal.acquire(current.command().lease().getParent().resolve("tmp"),current.lease().read(),Duration.ofSeconds(1));var lifecycle=cache.lock(Duration.ofSeconds(1))) {
            var scan=LeaseReferences.inspect(current.command(),fixture.executable,fixture.digest);assertFalse(scan.pristine());assertTrue(scan.stopAllowed());assertThrows(java.io.IOException.class,()->cache.register(scan,operation));assertTrue(cache.firstUse());
        }
    }
    @Test void 別descriptorのreleased破損とactiveを新世代の初回資格にしない() throws Exception {
        var current=fixture.job(false);var previous=releasedJob(true);Path lock=previous.command().lease().resolveSibling("lease.lock");byte[] receipt=Files.readAllBytes(lock);Files.writeString(lock,"");
        var broken=LeaseReferences.inspect(current.command(),fixture.executable,fixture.digest);assertTrue(broken.unknown());assertFalse(broken.pristine());assertFalse(broken.stopAllowed());Files.write(lock,receipt);
        ObjectNode value=(ObjectNode)MacFiles.json(previous.command().lease());value.put("state","active").put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        Path tmp=previous.command().lease().getParent().resolve("tmp");Files.delete(tmp.resolve("macos-operations/released.json"));
        try(var files=Files.walk(tmp.resolve("macos-operations"))){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
        ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));Files.write(lock,LeaseFile.receipt(identity));MacFiles.write(previous.command().lease(),value);
        try(FileChannel core=FileChannel.open(lock,StandardOpenOption.WRITE);var held=core.lock(64,1,false)) {
            var active=LeaseReferences.inspect(current.command(),fixture.executable,fixture.digest);assertTrue(active.active());assertFalse(active.unknown());assertFalse(active.pristine());assertFalse(active.stopAllowed());
        }
    }
    private Job releasedJob(boolean otherDescriptor) throws Exception {
        var job=fixture.job(false);ObjectNode value=job.lease().read();
        if(otherDescriptor) {
            ObjectNode descriptor=fixture.descriptor.deepCopy();descriptor.put("runtimeId","macos-previous");byte[] bytes=MacFiles.JSON.writeValueAsBytes(descriptor);Files.write(job.command().lease().resolveSibling("descriptor.json"),bytes);value.put("runtimeId","macos-previous").put("descriptorSha256",MacFiles.hash(bytes));
            ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));Files.write(job.command().lease().resolveSibling("lease.lock"),LeaseFile.receipt(identity));MacFiles.write(job.command().lease(),value);
        }
        try(var journal=OperationJournal.acquire(job.command().lease().getParent().resolve("tmp"),value,Duration.ofSeconds(1))) {
            var operation=journal.begin("prepare");Files.writeString(operation.completion(),operation.sha256()+"\n");journal.settled(operation,MacFiles.JSON.createObjectNode().put("status","Stopped"));journal.markReleased();
        }
        value.put("state","released");MacFiles.write(job.command().lease(),value);return job;
    }
    @Test void cleanupは呼出し元の残期限を超えて開始しない() throws Exception {
        var job=fixture.job(false);assertThrows(java.io.IOException.class,()->MacRuntime.releaseUnstarted(job.command(),job.lease(),System.nanoTime()-Duration.ofSeconds(10).toNanos()));
        try(var journal=OperationJournal.acquire(job.command().lease().getParent().resolve("tmp"),job.lease().read(),Duration.ofSeconds(1))){assertFalse(journal.released());}
    }
    record Job(HelperProtocol.Command command,LeaseFile lease) {}
    static final class Fixture {
        final Path root,executable;final String host=UUID.randomUUID().toString(),digest;final ObjectNode descriptor;
        Fixture(Path root) throws Exception {
            this.root=root;Files.createDirectory(root.resolve("jobs"));Files.writeString(root.resolve("host-id"),host);descriptor=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("runtimeId","macos-fixture").put("toolVersion","1.1.0").put("minimumOsVersion","13");descriptor.putObject("host").put("os","macos").put("arch","aarch64");descriptor.putObject("helper").put("protocolVersion",1).put("artifactId","host").put("executable","entry");descriptor.putObject("worker").put("toolVersion","1.1.0").put("agentSha256","c".repeat(64)).put("protocolVersion",1).put("adapterSetSha256","d".repeat(64));descriptor.putObject("rendering");
            var artifacts=descriptor.putArray("artifacts");for(String role:List.of("host-helper","renderer-image")) {
                boolean helper=role.equals("host-helper");var artifact=artifacts.addObject().put("id",helper?"host":"renderer").put("role",role).put("url","https://example.invalid/fixture").put("bytes",1).put("sha256",(helper?"a":"b").repeat(64)).put("unpackedBytes",1).put("format",helper?"zip":"tar.gz").put("inventoryPath",helper?"inventory/host.json":"inventory/renderer.json").put("inventorySha256",MacFiles.hash("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));artifact.putArray("licenseFiles");artifact.putArray("noticeFiles");artifact.putArray("sourceArchives");
            }
            digest=MacFiles.hash(MacFiles.JSON.writeValueAsBytes(descriptor));executable=root.resolve("helpers/macos-aarch64").resolve("a".repeat(64)).resolve("entry");Files.createDirectories(executable.getParent());Files.writeString(executable,"fixture");
        }
        CacheState cache() throws Exception {return new CacheState(root,host,digest,((Number)Files.getAttribute(root,"unix:uid")).longValue(),"a".repeat(64),"00000000-0000-0000-0000-000000000001");}
        Job job(boolean dead) throws Exception {
            String job=UUID.randomUUID().toString();Path directory=root.resolve("jobs").resolve(job);Files.createDirectory(directory);Files.createDirectory(directory.resolve("tmp"));Files.createDirectory(directory.resolve("inventory"));Files.writeString(directory.resolve("inventory/host.json"),"{}");Files.writeString(directory.resolve("inventory/renderer.json"),"{}");MacFiles.write(directory.resolve("descriptor.json"),descriptor);
            ObjectNode value=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("jobId",job).put("ownerId",UUID.randomUUID().toString()).put("toolVersion","1.1.0").put("workerSha256","c".repeat(64)).put("runtimeId","macos-fixture").put("descriptorSha256",digest).put("hostId",host).put("pid",dead?2147483647:ProcessHandle.current().pid()).put("processStartedAt",dead?"2000-01-01T00:00:00Z":ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("createdAt",Instant.now().toString());value.putObject("requirements").put("containerMemoryMiB",1024).put("heapMemoryMiB",512).put("cpus",4).put("preparationDeadline",Instant.now().plusSeconds(60).toString());value.put("state","active");value.putObject("resources");ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));Files.write(directory.resolve("lease.lock"),LeaseFile.receipt(identity));MacFiles.write(directory.resolve("lease.json"),value);
            var command=new HelperProtocol.Command(root,directory.resolve("lease.json"),"release",List.of());return new Job(command,LeaseFile.open(command,executable));
        }
    }
}
