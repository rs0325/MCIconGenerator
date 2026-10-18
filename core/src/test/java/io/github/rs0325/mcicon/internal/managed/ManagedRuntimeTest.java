package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.*;
import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedRuntimeTest {
    @TempDir Path root;
    static final RuntimeDescriptor.Host HOST=new RuntimeDescriptor.Host("linux","x86_64");
    ManagedOptions options(){return new ManagedOptions(root.resolve("runtime"),Duration.ofSeconds(5),Duration.ofSeconds(2),Duration.ofSeconds(1),3072,2048);}
    @Test void missingTrustConfigurationFailsBeforeNetworkOrRootWrite() throws Exception {
        AtomicInteger downloads=new AtomicInteger();
        var failure=assertThrows(McIconException.class,()->ManagedRuntime.prepare(options(),false,new CancellationToken(),ManagedRuntime.class.getClassLoader(),HOST,(u,o,t)->{downloads.incrementAndGet();throw new AssertionError();}));
        assertEquals("MANAGED_RUNTIME_INVALID",failure.code());assertEquals(0,downloads.get());assertFalse(Files.exists(options().runtimeRoot()));
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void pinnedWorkerHelperGenerationAndOfflineShareOneLease() throws Exception {
        Fixture fixture=new Fixture(false);AtomicInteger downloads=new AtomicInteger();Path lease;
        try(var loader=fixture.loader();var managed=ManagedRuntime.prepare(options(),false,new CancellationToken(),loader,HOST,fixture.transport(downloads))) {
            assertEquals(2,downloads.get());assertEquals("/opt/java/java21",managed.worker(21).javaHome());
            assertEquals(fixture.worker.sha256(),managed.launch().expectedAgentSha256());assertTrue(Files.isRegularFile(managed.worker(21).agentJar()));
            List<String> prefix=managed.launch().prefix();lease=Path.of(prefix.get(prefix.indexOf("--lease")+1));
            assertArrayEquals(fixture.descriptor,Files.readAllBytes(lease.getParent().resolve("descriptor.json")));
            var observed=RuntimeDescriptor.read(Files.readAllBytes(lease.getParent().resolve("fixture-preflight-lease.json"))).path("requirements");
            assertEquals(options().containerMemoryMiB(),observed.path("containerMemoryMiB").intValue());assertEquals(options().heapMemoryMiB(),observed.path("heapMemoryMiB").intValue());assertEquals(4,observed.path("cpus").intValue());
            assertTrue(java.time.Instant.parse(observed.path("preparationDeadline").asText()).isAfter(java.time.Instant.now()));
            assertArrayEquals(fixture.inventory,Files.readAllBytes(lease.getParent().resolve("inventory/helper.json")));
            assertEquals(List.of("preflight","prepare"),Files.readAllLines(lease.getParent().resolve("fixture-operations")));
            assertEquals(List.of("docker","--"),prefix.subList(prefix.size()-2,prefix.size()));
        }
        assertEquals("released",RuntimeDescriptor.read(Files.readAllBytes(lease)).path("state").asText());
        assertEquals(List.of("preflight","prepare","release"),Files.readAllLines(lease.getParent().resolve("fixture-operations")));
        try(var loader=fixture.loader();var offline=ManagedRuntime.prepare(options(),true,new CancellationToken(),loader,HOST,(u,o,t)->{throw new AssertionError("準備済みofflineで取得しました");})) {assertNotNull(offline.worker(8));}
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void preflightSetupStopsBeforeLargeArtifactsAndReleasesOwnLease() throws Exception {
        Fixture fixture=new Fixture(true);AtomicInteger downloads=new AtomicInteger();
        try(var loader=fixture.loader()) {
            var failure=assertThrows(McIconException.class,()->ManagedRuntime.prepare(options(),false,new CancellationToken(),loader,HOST,fixture.transport(downloads)));
            assertEquals("MANAGED_SETUP_REQUIRED",failure.code());assertTrue(failure.getMessage().contains("管理者操作"));assertEquals(1,downloads.get());
        }
        try(var jobs=Files.list(options().runtimeRoot().resolve("jobs"))) {
            Path job=jobs.findFirst().orElseThrow();assertEquals(List.of("preflight","release"),Files.readAllLines(job.resolve("fixture-operations")));assertEquals("released",RuntimeDescriptor.read(Files.readAllBytes(job.resolve("lease.json"))).path("state").asText());
        }
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void interruptedCloseStillReleasesOwnedJobAndRestoresInterrupt() throws Exception {
        Fixture fixture=new Fixture(false);Path lease;
        try(var loader=fixture.loader()) {
            ManagedRuntime managed=ManagedRuntime.prepare(options(),false,new CancellationToken(),loader,HOST,fixture.transport(new AtomicInteger()));
            var prefix=managed.launch().prefix();lease=Path.of(prefix.get(prefix.indexOf("--lease")+1));
            try {Thread.currentThread().interrupt();managed.close();assertTrue(Thread.currentThread().isInterrupted());}
            finally {Thread.interrupted();}
        }
        assertEquals("released",RuntimeDescriptor.read(Files.readAllBytes(lease)).path("state").asText());
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void publicDoctorReturnsUnhealthySetupThroughIsolatedFixtureClasspath() throws Exception {
        Fixture fixture=new Fixture(true);RuntimeDescriptor.Host actual=ManagedRuntime.host();
        ((ObjectNode)fixture.definition.json.get("host")).put("os",actual.os()).put("arch",actual.arch());fixture.publish();
        try(var loader=fixture.loader()) {assertThrows(McIconException.class,()->ManagedRuntime.prepare(options(),false,new CancellationToken(),loader,actual,fixture.transport(new AtomicInteger())));}
        Object report=publicDoctor(fixture);assertEquals(false,report.getClass().getMethod("healthy").invoke(report));
        assertTrue(report.toString().contains("MANAGED_SETUP_REQUIRED"));assertFalse(Files.exists(root.resolve("api-inputs/metadata/version_manifest_v2.json")));
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void publicDoctorPreservesSetupAndCleanupFailure() throws Exception {
        Fixture fixture=new Fixture(true,true);RuntimeDescriptor.Host actual=ManagedRuntime.host();
        ((ObjectNode)fixture.definition.json.get("host")).put("os",actual.os()).put("arch",actual.arch());fixture.publish();
        try(var loader=fixture.loader()) {
            var preparation=assertThrows(McIconException.class,()->ManagedRuntime.prepare(options(),false,new CancellationToken(),loader,actual,fixture.transport(new AtomicInteger())));
            assertEquals("MANAGED_SETUP_REQUIRED",preparation.code());assertEquals(1,preparation.getSuppressed().length);
        }
        var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->publicDoctor(fixture));Throwable cause=failure.getCause();
        assertEquals("MANAGED_SETUP_REQUIRED",cause.getClass().getMethod("code").invoke(cause));assertEquals(1,cause.getSuppressed().length);
        assertEquals("IO_FAILED",cause.getSuppressed()[0].getClass().getMethod("code").invoke(cause.getSuppressed()[0]));
        try(var jobs=Files.list(options().runtimeRoot().resolve("jobs"))) {
            for(Path job:jobs.toList())assertEquals("active",RuntimeDescriptor.read(Files.readAllBytes(job.resolve("lease.json"))).path("state").asText());
        }
    }
    private Object publicDoctor(Fixture fixture) throws Exception {
        URL core=ManagedRuntime.class.getProtectionDomain().getCodeSource().getLocation();
        URL resources=fixture.resources.getParent().getParent().getParent().toUri().toURL();
        try(var loader=new URLClassLoader(new URL[]{core,resources},ManagedRuntime.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(!name.startsWith("io.github.rs0325.mcicon."))return super.loadClass(name,resolve);
                Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
            }
        }) {
            Class<?> api=loader.loadClass("io.github.rs0325.mcicon.MCIconGenerator"),progress=loader.loadClass("io.github.rs0325.mcicon.ProgressListener"),cancellation=loader.loadClass("io.github.rs0325.mcicon.CancellationToken"),optionType=loader.loadClass("io.github.rs0325.mcicon.ManagedOptions");
            Object option=optionType.getConstructor(Path.class,Duration.class,Duration.class,Duration.class,int.class,int.class).newInstance(options().runtimeRoot(),Duration.ofSeconds(5),Duration.ofSeconds(2),Duration.ofSeconds(1),3072,2048);
            Object generator=api.getConstructor(Path.class,boolean.class,progress,cancellation).newInstance(root.resolve("api-inputs"),true,progress.getField("NONE").get(null),cancellation.getConstructor().newInstance());
            try {
                var future=(java.util.concurrent.CompletableFuture<?>)api.getMethod("doctorManaged",String.class,optionType).invoke(generator,"1.13",option);
                return future.get(5,java.util.concurrent.TimeUnit.SECONDS);
            }finally {((AutoCloseable)generator).close();}
        }
    }
    @Test void signatureAndInventoryCollisionFailBeforeArtifactDownload() throws Exception {
        Fixture fixture=new Fixture(false);AtomicInteger downloads=new AtomicInteger();byte[] signature=Files.readAllBytes(fixture.resources.resolve("linux-x86_64/descriptor.json.sig"));signature[0]^=1;Files.write(fixture.resources.resolve("linux-x86_64/descriptor.json.sig"),signature);
        try(var loader=fixture.loader()) {Fixture current=fixture;RuntimeDescriptorTest.assertInvalid(()->ManagedRuntime.prepare(options(),false,new CancellationToken(),loader,HOST,current.transport(downloads)));}assertEquals(0,downloads.get());
        fixture=new Fixture(false);((ObjectNode)fixture.definition.json.get("artifacts").get(0)).put("inventoryPath","lease.json");fixture.publish();
        try(var loader=fixture.loader()) {Fixture current=fixture;RuntimeDescriptorTest.assertInvalid(()->ManagedRuntime.prepare(options(),false,new CancellationToken(),loader,HOST,current.transport(downloads)));}assertEquals(0,downloads.get());
    }
    final class Fixture {
        final ManagedFixture definition=new ManagedFixture();
        final WorkerArtifact.Resolved worker=WorkerArtifact.resolve(root.resolve("worker-fixture"),new CancellationToken());
        final Path resources=Files.createTempDirectory(root,"resources-").resolve("META-INF/mcicon/runtime");
        final byte[] helper,image={1,2,3},inventory="test-only inventory".getBytes(StandardCharsets.UTF_8);
        byte[] descriptor;
        Fixture(boolean setup) throws Exception {this(setup,false);}
        Fixture(boolean setup,boolean cleanupFails) throws Exception {
            String ok="{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}";
            String missing="{\"protocolVersion\":1,\"status\":\"setup-required\",\"diagnostics\":[{\"code\":\"FIXTURE_SETUP\",\"detail\":\"テスト専用の前提不足\",\"requiresPrivilege\":true,\"requiresRestart\":false}]}";
            String script="#!/bin/sh\nif [ \"$7\" = preflight ]; then /bin/cp \"$6\" \"${6%/*}/fixture-preflight-lease.json\" || exit 1; fi\n[ -f \"${6%/*}/descriptor.json\" ] || exit 1\nprintf '%s\\n' \"$7\" >> \"${6%/*}/fixture-operations\"\n"+(setup?"if [ \"$7\" = preflight ]; then printf '%s' '"+missing+"'; exit 2; fi\n":"")+(cleanupFails?"if [ \"$7\" = release ]; then printf '%s' '{\"protocolVersion\":1,\"status\":\"failed\",\"diagnostics\":[]}'; exit 1; fi\n":"")+"printf '%s' '"+ok+"'\n";
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("bin/helper"));zip.write(script.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}helper=bytes.toByteArray();
            definition.json.put("toolVersion",ToolVersion.VERSION);((ObjectNode)definition.json.get("worker")).put("toolVersion",ToolVersion.VERSION).put("agentSha256",worker.sha256()).put("adapterSetSha256",ManagedRuntime.adapterDigest(ManagedRuntime.class.getClassLoader()));
            ArrayNode java=(ArrayNode)definition.json.get("rendering").get("javaExecutables");java.removeAll();for(int major:List.of(8,17,21,25))java.addObject().put("major",major).put("executable","/opt/java/java"+major+"/bin/java").put("sha256",ManagedFixture.HASH);
            for(int i=0;i<2;i++){byte[] artifact=i==0?helper:image;((ObjectNode)definition.json.get("artifacts").get(i)).put("bytes",artifact.length).put("sha256",FilesSupport.hash(artifact)).put("unpackedBytes",16384).put("inventorySha256",FilesSupport.hash(inventory));}
            publish();
        }
        void publish() throws Exception {
            descriptor=definition.bytes();write("linux-x86_64/descriptor.json",descriptor);write("linux-x86_64/descriptor.json.sig",definition.sign(descriptor));write("trust/fixture.der",definition.key.getPublic().getEncoded());write("inventory/helper.json",inventory);write("inventory/image.json",inventory);
            ObjectNode pins=RuntimeDescriptor.JSON.createObjectNode().put("schemaVersion",1);ObjectNode entry=pins.putArray("runtimes").addObject();entry.set("host",definition.json.get("host").deepCopy());entry.put("descriptorPath","linux-x86_64/descriptor.json").put("descriptorSha256",FilesSupport.hash(descriptor)).put("trustKeyPath","trust/fixture.der");write("pins.json",RuntimeDescriptor.JSON.writeValueAsBytes(pins));
        }
        void write(String path,byte[] bytes) throws IOException {Path target=resources.resolve(path);Files.createDirectories(target.getParent());Files.write(target,bytes);}
        URLClassLoader loader() throws Exception {return new URLClassLoader(new URL[]{resources.getParent().getParent().getParent().toUri().toURL()},ManagedRuntime.class.getClassLoader());}
        RuntimeStore.Transport transport(AtomicInteger downloads) {return (uri,offset,remaining)->{downloads.incrementAndGet();return new RuntimeStore.Response(200,0,new ByteArrayInputStream(uri.getPath().endsWith("helper")?helper:image));};}
    }
}
