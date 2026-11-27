package io.github.rs0325.mcicon.helper.macos;

import io.github.rs0325.mcicon.internal.LeaseTrust;
import io.github.rs0325.mcicon.internal.TrustSet;
import io.github.rs0325.mcicon.internal.TrustFixtures;
import java.security.*;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;

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
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"),"Mac lifecycleはPOSIX file systemで検証する");fixture=new Fixture(temporary.toRealPath());
    }
    @AfterEach void cleanup() throws Exception {
        if(fixture==null)return;Path anchor=fixture.cache().anchor();if(Files.exists(anchor,LinkOption.NOFOLLOW_LINKS))Files.walkFileTree(anchor,new SimpleFileVisitor<>(){
            public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path directory,IOException error) throws IOException {if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;}
        });
    }
    private ObjectNode resources(ObjectNode state) {
        ObjectNode resources=MacFiles.JSON.createObjectNode();resources.putObject("macos").put("phase","ready").put("generationOwner",state.path("generationOwner").asText()).put("instanceDir",state.path("instanceDir").asText()).putArray("containers");return resources;
    }
    private MacRuntime runtime(Job job,CacheState cache,FakeCommands commands) throws Exception {
        Path support=fixture.executable.getParent().getParent().resolve("app/support");Files.createDirectories(support.resolve("bin"));Files.writeString(support.resolve("bin/limactl"),"");
        ObjectNode manifest=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("limaVersion","2.2.1");manifest.putArray("files");manifest.putArray("debs");MacFiles.write(support.resolve("payload.json"),manifest);
        return new MacRuntime(job.command(),job.lease(),new Payload(support),new HostFacts(501,"a".repeat(64),"00000000-0000-0000-0000-000000000001",16L*1024*1024*1024,8,"15.8.1"),cache,commands);
    }
    private ObjectNode register(Job job,CacheState cache) throws Exception {
        cache.directories();Files.createDirectory(cache.instance());ObjectNode state=cache.create(3072);CacheState.assign(state,job.lease().read());cache.save(state);job.lease().replaceResources((ObjectNode)job.lease().read().path("resources"),resources(state));return state;
    }
    @Test void 持ち主死亡後は停止して残containerを消してから解放する() throws Exception {
        Job job=fixture.job(true);CacheState cache=fixture.cache();register(job,cache);FakeCommands commands=new FakeCommands(cache,"Running");
        runtime(job,cache,commands).release();assertEquals(List.of("stop","start","rm","stop"),commands.actions);assertTrue(cache.read().path("owner").isNull());assertTrue(job.lease().read().path("resources").isEmpty());
        try(var files=Files.list(cache.file().getParent())){assertEquals(List.of(cache.file()),files.toList());}
    }
    @Test void 再起動後はPIDが同じでも旧持ち主として片付ける() throws Exception {
        Job job=fixture.job(false);CacheState cache=fixture.cache();ObjectNode state=register(job,cache);state.put("bootSessionId","00000000-0000-0000-0000-000000000002");cache.save(state);FakeCommands commands=new FakeCommands(cache,"Stopped");
        runtime(job,cache,commands).release();assertEquals(List.of("start","rm","stop"),commands.actions);assertTrue(cache.read().path("owner").isNull());assertTrue(job.lease().read().path("resources").isEmpty());
    }
    @Test void 通常releaseはcontainerを消して停止し再試行で何も増やさない() throws Exception {
        Job job=fixture.job(false);CacheState cache=fixture.cache();register(job,cache);FakeCommands commands=new FakeCommands(cache,"Running");MacRuntime runtime=runtime(job,cache,commands);
        runtime.release();runtime.release();assertEquals(List.of("rm","stop"),commands.actions);assertTrue(cache.read().path("owner").isNull());
    }
    @Test void 削除に失敗したら状態とresourcesを保持する() throws Exception {
        Job job=fixture.job(false);CacheState cache=fixture.cache();register(job,cache);FakeCommands commands=new FakeCommands(cache,"Running");commands.removeFailure=true;
        assertThrows(IOException.class,()->runtime(job,cache,commands).release());assertFalse(cache.read().path("owner").isNull());assertFalse(job.lease().read().path("resources").isEmpty());assertEquals("Running",commands.status);
    }
    @Test void 再起動で短cacheが消えていても証明ファイルを作らず解放する() throws Exception {
        Job job=fixture.job(false);CacheState cache=fixture.cache();Files.createDirectory(cache.file().getParent());ObjectNode state=cache.create(3072);CacheState.assign(state,job.lease().read());state.put("bootSessionId","00000000-0000-0000-0000-000000000002");cache.save(state);job.lease().replaceResources((ObjectNode)job.lease().read().path("resources"),resources(state));FakeCommands commands=new FakeCommands(cache,"Stopped");
        runtime(job,cache,commands).release();assertTrue(commands.actions.isEmpty());assertTrue(job.lease().read().path("resources").isEmpty());assertFalse(Files.exists(cache.anchor()));
    }
    @Test void 他jobの持ち主を自分のreleaseで消さない() throws Exception {
        Job job=fixture.job(false),other=fixture.job(false);CacheState cache=fixture.cache();ObjectNode state=register(job,cache);CacheState.assign(state,other.lease().read());cache.save(state);FakeCommands commands=new FakeCommands(cache,"Running");
        assertThrows(IOException.class,()->runtime(job,cache,commands).release());assertTrue(commands.actions.isEmpty());assertEquals(state,cache.read());
    }
    @Test void 生成前のreleaseはcacheもjournalも作らない() throws Exception {
        Job job=fixture.job(false);assertTrue(MacRuntime.releaseUnstarted(job.command(),job.lease(),System.nanoTime()));assertFalse(Files.exists(fixture.cache().anchor()));assertFalse(Files.exists(fixture.cache().file()));
        try(var files=Files.list(job.command().lease().getParent().resolve("tmp"))){assertEquals(0,files.count());}
    }
    private static final class FakeCommands extends Commands {
        final CacheState cache;final List<String> actions=new ArrayList<>();String status;boolean removeFailure;
        FakeCommands(CacheState cache,String status){super(Path.of("/guard"),Map.of(),cache.file().getParent());this.cache=cache;this.status=status;}
        @Override Result capture(List<String> arguments,Duration timeout) throws IOException {
            ObjectNode state=cache.read();Object value="";
            if(arguments.contains("list"))value=MacFiles.JSON.createObjectNode().put("name","v").put("dir",cache.instance().toString()).put("vmType","vz").put("arch","aarch64").put("status",status);
            else if(arguments.contains("stop")){actions.add("stop");status="Stopped";}
            else if(arguments.contains("start")){actions.add("start");status="Running";}
            else if(arguments.contains("/bin/cat"))value=VmConfiguration.guestMarker(state.path("generationOwner").asText(),state.path("descriptorSha256").asText());
            else if(arguments.contains("info"))value=MacFiles.JSON.createArrayNode().add("io.github.rs0325.mcicon.generation="+state.path("generationOwner").asText());
            else if(arguments.contains("ls")){assertTrue(arguments.contains("label=io.github.rs0325.mcicon.generation="+state.path("generationOwner").asText()));value="f".repeat(64)+"\n";}
            else if(arguments.contains("rm")){actions.add("rm");if(removeFailure)return new Result(1,new byte[0],new byte[0]);}
            else throw new AssertionError(arguments);
            return new Result(0,value instanceof String text?text.getBytes(java.nio.charset.StandardCharsets.UTF_8):MacFiles.JSON.writeValueAsBytes(value),new byte[0]);
        }
    }
    record Job(HelperProtocol.Command command,LeaseFile lease) {}
    static final class Fixture {
        final Path root,executable;final String host=UUID.randomUUID().toString(),digest;final ObjectNode descriptor;final KeyPair pair;final TrustSet trust;final byte[] inventory;
        Fixture(Path root) throws Exception {
            pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();KeyPair recovery=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] trustBytes=TrustFixtures.bytes(pair.getPublic(),recovery.getPublic());trust=TrustSet.parse(trustBytes);
            ObjectNode listing=MacFiles.JSON.createObjectNode();listing.putObject("contents").putArray("files").addObject().put("path","entry").put("bytes",7).put("sha256",MacFiles.hash("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));inventory=MacFiles.JSON.writeValueAsBytes(listing);
            this.root=root;Files.createDirectory(root.resolve("jobs"));Files.writeString(root.resolve("host-id"),host);descriptor=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("runtimeId","macos-fixture").put("toolVersion","1.1.0").put("minimumOsVersion","13");descriptor.putObject("host").put("os","macos").put("arch","aarch64");descriptor.putObject("helper").put("protocolVersion",1).put("artifactId","host").put("executable","entry");descriptor.putObject("worker").put("toolVersion","1.1.0").put("agentSha256","c".repeat(64)).put("protocolVersion",1).put("adapterSetSha256","d".repeat(64));descriptor.putObject("rendering");
            var artifacts=descriptor.putArray("artifacts");for(String role:List.of("host-helper","renderer-image")) {
                boolean helper=role.equals("host-helper");var artifact=artifacts.addObject().put("id",helper?"host":"renderer").put("role",role).put("url","https://example.invalid/fixture").put("bytes",1).put("sha256",(helper?"a":"b").repeat(64)).put("unpackedBytes",1).put("format",helper?"zip":"tar.gz").put("inventoryPath",helper?"inventory/host.json":"inventory/renderer.json").put("inventorySha256",MacFiles.hash(helper?inventory:"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));artifact.putArray("licenseFiles");artifact.putArray("noticeFiles");artifact.putArray("sourceArchives");
            }
            digest=MacFiles.hash(MacFiles.JSON.writeValueAsBytes(descriptor));executable=root.resolve("helpers/macos-aarch64").resolve("a".repeat(64)).resolve("entry");Files.createDirectories(executable.getParent());Files.writeString(executable,"fixture");Path support=executable.getParent().getParent().resolve("app/support");Files.createDirectories(support);Files.write(support.resolve("lease-trust.bin"),trustBytes);publish(MacFiles.JSON.writeValueAsBytes(descriptor));
        }
        void publish(byte[] definition) throws Exception {Signature signer=Signature.getInstance("Ed25519");signer.initSign(pair.getPrivate());signer.update(definition);LeaseTrust.publish(root,definition,signer.sign(),trust);}
        CacheState cache() throws Exception {return new CacheState(root,host,digest,((Number)Files.getAttribute(root,"unix:uid")).longValue(),"00000000-0000-0000-0000-000000000001");}
        Job job(boolean dead) throws Exception {
            String job=UUID.randomUUID().toString();Path directory=root.resolve("jobs").resolve(job);Files.createDirectory(directory);Files.createDirectory(directory.resolve("tmp"));Files.createDirectory(directory.resolve("inventory"));Files.write(directory.resolve("inventory/host.json"),inventory);Files.writeString(directory.resolve("inventory/renderer.json"),"{}");MacFiles.write(directory.resolve("descriptor.json"),descriptor);
            ObjectNode value=MacFiles.JSON.createObjectNode().put("schemaVersion",1).put("jobId",job).put("ownerId",UUID.randomUUID().toString()).put("toolVersion","1.1.0").put("workerSha256","c".repeat(64)).put("runtimeId","macos-fixture").put("descriptorSha256",digest).put("hostId",host).put("pid",dead?2147483647:ProcessHandle.current().pid()).put("processStartedAt",dead?"2000-01-01T00:00:00Z":ProcessHandle.current().info().startInstant().orElseThrow().toString()).put("createdAt",Instant.now().toString());value.putObject("requirements").put("containerMemoryMiB",1024).put("heapMemoryMiB",512).put("cpus",4).put("preparationDeadline",Instant.now().plusSeconds(60).toString());value.put("state","active");value.putObject("resources");ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));Files.write(directory.resolve("lease.lock"),LeaseFile.receipt(identity));MacFiles.write(directory.resolve("lease.json"),value);
            var command=new HelperProtocol.Command(root,directory.resolve("lease.json"),"release",List.of());return new Job(command,LeaseFile.open(command,executable));
        }
    }
}
