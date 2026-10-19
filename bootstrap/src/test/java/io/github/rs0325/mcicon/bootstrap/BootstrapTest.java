package io.github.rs0325.mcicon.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapTest {
    @TempDir Path directory;
    Fixture fixture() throws Exception {return new Fixture(directory.toRealPath());}
    Path root() throws IOException {return directory.toRealPath().resolve("state");}
    JsonNode state() throws IOException {return Seed.json(Files.readAllBytes(root().resolve("active.json")));}
    Path slot() throws IOException {JsonNode active=state().get("active");return root().resolve("tools/"+active.path("toolVersion").asText()+"/"+active.path("payloadSha256").asText());}
    List<Path> receipts() throws IOException {try(var paths=Files.list(root().resolve("runs"))){return paths.toList();}}
    @Test void actualCliParserRunUsesBundledSlotAndPreservesOfflineDiagnostics() throws Exception {
        Fixture f=fixture();
        actualCli(f);
        f.publish();assertEquals(0,f.bootstrap(root()).launch(List.of("cache","status","--offline","--cache",directory.resolve("cache").toString(),"--json")));
        assertEquals(1,f.bootstrap(root()).launch(List.of("doctor","--version","1.20.6","--offline","--cache",directory.resolve("doctor-cache").toString())));
        assertEquals(2,receipts().size());for(Path receipt:receipts())assertEquals("finished",Seed.json(Files.readAllBytes(receipt)).path("state").asText());
    }
    private void actualCli(Fixture f) throws Exception {
        for(String entry:System.getProperty("bootstrap.testClasspath").split(java.io.File.pathSeparator)){Path path=Path.of(entry);String name=path.getFileName().toString();if(Files.isRegularFile(path)&&name.matches("(?:cli|core|worker|picocli|jackson.*|commons.*|xz)-.*\\.jar"))f.files.put("app/lib/"+name,Files.readAllBytes(path));}
    }
    @Test void actualParserSwitchExitsBeforeManagedPreparationAndIsReaped() throws Exception {
        Fixture f=fixture();actualCli(f);f.publish();f.bootstrap(root()).repair();Path cache=directory.resolve("must-not-prepare");List<String> args=List.of("list","--version","1.20.6","--cache",cache.toString());
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            ObjectNode context=Seed.JSON.createObjectNode().put("protocol",2).put("port",server.getLocalPort()).put("nonce",UUID.randomUUID().toString()).put("invocation",UUID.randomUUID().toString());context.set("slot",state().get("active"));String property="-Dmcicon.bootstrap.control="+Base64.getUrlEncoder().withoutPadding().encodeToString(Seed.JSON.writeValueAsBytes(context));
            List<String> command=new ArrayList<>(List.of(slot().resolve("java/bin/java").toString(),property,"-cp",slot().resolve("app/lib/*").toString(),"io.github.rs0325.mcicon.cli.Main"));command.addAll(args);Process child=new ProcessBuilder(command).redirectOutput(directory.resolve("switch.stdout").toFile()).redirectError(directory.resolve("switch.stderr").toFile()).start();
            try {
                server.setSoTimeout(3000);try(java.net.Socket socket=server.accept()){JsonNode request=StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(3));assertEquals("list",request.path("command").asText());assertTrue(request.path("managed").asBoolean());assertFalse(request.path("offline").asBoolean());assertEquals(Seed.JSON.valueToTree(args),request.get("args"));assertEquals(context.get("slot"),request.get("slot"));StartupControl.write(socket,Seed.JSON.createObjectNode().put("protocol",2).put("type","SWITCH"));}
                assertTrue(child.waitFor(3,TimeUnit.SECONDS));assertEquals(0,child.exitValue());assertFalse(Files.exists(cache));assertEquals("",Files.readString(directory.resolve("switch.stdout")));assertEquals("",Files.readString(directory.resolve("switch.stderr")));
            }finally{if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}}
        }
    }
    @Test void invalidPureArgumentsCannotSendRequestOrBecomeSuccessfulSwitch() throws Exception {
        Fixture f=fixture();actualCli(f);f.publish();f.bootstrap(root()).repair();int index=0;
        for(List<String> invalid:List.of(List.of("render","--version","1.20.6","--id","stone","--size","63"),List.of("render","--version","1.20.6","--id","../stone"),List.of("list","--version","1.20.6","--kind","bad"))) {
            int trial=index++;Path cache=directory.resolve("bad-cache-"+trial),out=directory.resolve("bad-output-"+trial);List<String> args=new ArrayList<>(invalid);args.addAll(List.of("--cache",cache.toString()));if(args.getFirst().equals("render"))args.addAll(List.of("--out",out.toString()));
            try(java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(800);ObjectNode context=Seed.JSON.createObjectNode().put("protocol",2).put("port",server.getLocalPort()).put("nonce",UUID.randomUUID().toString()).put("invocation",UUID.randomUUID().toString());context.set("slot",state().get("active"));String property="-Dmcicon.bootstrap.control="+Base64.getUrlEncoder().withoutPadding().encodeToString(Seed.JSON.writeValueAsBytes(context));
                ExecutorService listener=Executors.newSingleThreadExecutor();var request=listener.submit(()->{try(java.net.Socket socket=server.accept()){StartupControl.read(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));StartupControl.write(socket,Seed.JSON.createObjectNode().put("protocol",2).put("type","SWITCH"));return true;}catch(java.net.SocketTimeoutException absent){return false;}});
                List<String> command=new ArrayList<>(List.of(slot().resolve("java/bin/java").toString(),property,"-cp",slot().resolve("app/lib/*").toString(),"io.github.rs0325.mcicon.cli.Main"));command.addAll(args);Process child=new ProcessBuilder(command).redirectOutput(directory.resolve("bad-"+trial+".stdout").toFile()).redirectError(directory.resolve("bad-"+trial+".stderr").toFile()).start();
                try{assertTrue(child.waitFor(3,TimeUnit.SECONDS));assertEquals(2,child.exitValue());assertFalse(request.get(2,TimeUnit.SECONDS));assertFalse(Files.exists(cache));assertFalse(Files.exists(out));assertTrue(Files.readString(directory.resolve("bad-"+trial+".stderr")).startsWith(args.getFirst().equals("render")?"INVALID_REQUEST:":"INVALID_KIND:"));}
                finally{if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}listener.shutdownNow();assertTrue(listener.awaitTermination(2,TimeUnit.SECONDS));}
            }
        }
    }
    @Test void oldProtocolCannotReachCommandSideEffects() throws Exception {
        Fixture f=fixture();f.manifest.put("minimumBootstrapProtocol",1);f.writeManifest();Path marker=directory.resolve("must-not-exist");assertThrows(IOException.class,()->f.bootstrap(root()).launch(List.of("block",marker.toString())));assertFalse(Files.exists(marker));
    }
    @Test void savedManifestUsesVerifiedBytesAfterPackageReplacement() throws Exception {
        Fixture f=fixture();Seed accepted=f.seed();byte[] original=Files.readAllBytes(f.installed.resolve("seed/stable.json")),signature=Files.readAllBytes(f.installed.resolve("seed/stable.json.sig"));
        f.manifest.put("sequence",2);f.writeManifest();Bootstrap bootstrap=f.bootstrap(root());bootstrap.directory(root());bootstrap.directory(root().resolve("staging"));bootstrap.directory(root().resolve("manifests"));bootstrap.saveManifest(accepted);
        Path stored=root().resolve("manifests/"+accepted.manifestHash);assertArrayEquals(original,Files.readAllBytes(stored.resolve("stable.json")));assertArrayEquals(signature,Files.readAllBytes(stored.resolve("stable.json.sig")));assertEquals(accepted.reference(),new Seed(stored,f.publicKey,f.host).reference());
        Files.write(f.installed.resolve("seed/stable.json.sig"),new byte[64]);bootstrap.saveManifest(accepted);assertEquals(accepted.reference(),new Seed(stored,f.publicKey,f.host).reference());
    }
    @Test void exitedChildWithoutStartIdentityKeepsUnknownButReturnsActualExit() throws Exception {
        for(int code:List.of(0,7)) {
            Process child=new ProcessBuilder("/bin/sh","-c","exit "+code).start();
            try {
                assertTrue(child.waitFor(3,TimeUnit.SECONDS));assertTrue(child.info().startInstant().isEmpty());
                var record=Seed.JSON.createObjectNode().put("state","launching");record.putNull("child").putNull("exitCode");Path path=directory.resolve("short-"+code+".json");
                try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
                    channel.write(java.nio.ByteBuffer.wrap(Seed.JSON.writeValueAsBytes(record)));assertEquals(code,Bootstrap.awaitChild(child,channel,record));
                }
                assertFalse(child.isAlive());assertEquals("launching",Seed.json(Files.readAllBytes(path)).path("state").asText());assertTrue(record.path("child").isNull());assertTrue(record.path("exitCode").isNull());
            }finally{if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}}
        }
    }
    @Test void parallelFreshDirectoriesArePrivateAndExistingPublicDirectoryIsRejected() throws Exception {
        Fixture f=fixture();Bootstrap bootstrap=f.bootstrap(root());ExecutorService pool=Executors.newFixedThreadPool(12);
        try {
            for(int round=0;round<12;round++) {
                Path path=directory.toRealPath().resolve("parallel-"+round+"/nested");CountDownLatch start=new CountDownLatch(1);List<Future<?>> calls=new ArrayList<>();
                for(int i=0;i<12;i++)calls.add(pool.submit(()->{start.await();bootstrap.directory(path);if(Files.getFileStore(path).supportsFileAttributeView("posix"))assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(path));return null;}));
                start.countDown();for(Future<?> call:calls)call.get(5,TimeUnit.SECONDS);
            }
            if(Files.getFileStore(directory).supportsFileAttributeView("posix")){Path existing=directory.toRealPath().resolve("public");Files.createDirectory(existing,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")));assertThrows(IOException.class,()->bootstrap.directory(existing));assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(existing));}
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void initialSeedStartsRealJavaChildAndRecordsItsExit() throws Exception {
        Fixture f=fixture();assertEquals(7,f.bootstrap(root()).launch(List.of("exit7","日本語","space value")));JsonNode state=state();assertEquals(1,state.path("highestAcceptedSequence").asInt());assertTrue(state.path("pending").isNull());
        JsonNode receipt=Seed.json(Files.readAllBytes(receipts().getFirst()));assertEquals("finished",receipt.path("state").asText());assertEquals(7,receipt.path("exitCode").asInt());assertTrue(receipt.path("child").path("pid").asLong()>1);assertEquals(state.get("active"),receipt.get("slot"));
    }
    @Test void missingSlotRepairRequiresSameSeedAndKeepsState() throws Exception {
        Fixture f=fixture();f.bootstrap(root()).launch(List.of("--help"));byte[] state=Files.readAllBytes(root().resolve("active.json"));Path original=slot();Files.move(original,directory.resolve("held-slot"));
        assertThrows(IOException.class,()->f.bootstrap(root()).launch(List.of("--help")));f.bootstrap(root()).repair();assertTrue(Files.isDirectory(original));assertArrayEquals(state,Files.readAllBytes(root().resolve("active.json")));
    }
    @Test void corruptSlotIsPreservedAndNeverOverwrittenByRepair() throws Exception {
        Fixture f=fixture();f.bootstrap(root()).launch(List.of());Path body=slot().resolve("LICENSE");Files.writeString(body,"changed");assertThrows(IOException.class,()->f.bootstrap(root()).repair());assertEquals("changed",Files.readString(body));
    }
    @Test void lostStateAndPartialArchiveNeverBecomeFirstUseAgain() throws Exception {
        Fixture f=fixture();f.bootstrap(root()).launch(List.of());Files.delete(root().resolve("active.json"));assertThrows(IOException.class,()->f.bootstrap(root()).launch(List.of()));assertFalse(Files.exists(root().resolve("active.json")));
        Path other=directory.resolve("partial");byte[] original=Files.readAllBytes(f.installed.resolve("seed/tool.zip"));byte[] bad=original.clone();bad[bad.length/2]^=1;Files.write(f.installed.resolve("seed/tool.zip"),bad);assertThrows(IOException.class,()->f.bootstrap(other).launch(List.of()));Files.write(f.installed.resolve("seed/tool.zip"),original);
        assertThrows(IOException.class,()->f.bootstrap(other).launch(List.of()));assertFalse(Files.exists(other.resolve("active.json")));
    }
    @Test void parallelInitialLaunchesShareOneImmutableSlot() throws Exception {
        Fixture f=fixture();ExecutorService pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->f.bootstrap(root()).launch(List.of("first")));var b=pool.submit(()->f.bootstrap(root()).launch(List.of("second")));assertEquals(0,a.get(15,TimeUnit.SECONDS));assertEquals(0,b.get(15,TimeUnit.SECONDS));assertEquals(2,receipts().size());try(var paths=Files.list(root().resolve("tools/1.1.0"))){assertEquals(1,paths.count());}}
        finally {pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void olderPackageSeedDoesNotDowngradeExistingAcceptedState() throws Exception {
        Fixture f=fixture();f.manifest.put("sequence",2);f.writeManifest();f.bootstrap(root()).launch(List.of());byte[] state=Files.readAllBytes(root().resolve("active.json"));f.manifest.put("sequence",1);f.writeManifest();assertEquals(0,f.bootstrap(root()).launch(List.of()));assertArrayEquals(state,Files.readAllBytes(root().resolve("active.json")));
    }
    @Test void unknownReceiptBlocksMissingSlotRepair() throws Exception {
        Fixture f=fixture();f.bootstrap(root()).launch(List.of());Files.move(slot(),directory.resolve("held-slot"));Files.writeString(root().resolve("runs/partial.json"),"{unfinished");assertThrows(IOException.class,()->f.bootstrap(root()).repair());assertEquals("{unfinished",Files.readString(root().resolve("runs/partial.json")));
    }
    @Test void symlinkRootAndUnknownTrustFailBeforeInstallation() throws Exception {
        Fixture f=fixture();Path target=directory.resolve("outside");Files.createDirectory(target);Path link=directory.resolve("linked");Files.createSymbolicLink(link,target);assertThrows(IOException.class,()->f.bootstrap(link).repair());assertFalse(Files.exists(target.resolve("active.json")));
        Files.write(f.installed.resolve("trust.der"),new byte[]{1});assertThrows(IOException.class,()->f.bootstrap(root()));assertFalse(Files.exists(root()));
    }
    @Test void interruptedWaitStillReapsOwnChildAndPreservesUnknown() throws Exception {
        Fixture f=fixture();Path marker=directory.resolve("interrupt-ready");var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        Thread parent=Thread.ofPlatform().start(()->{try{f.bootstrap(root()).launch(List.of("block",marker.toString()));}catch(Throwable error){failure.set(error);interrupted.set(Thread.currentThread().isInterrupted());}});ProcessHandle child=null;Instant started=null;
        try {
            long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(!Files.exists(marker)&&System.nanoTime()<end)Thread.sleep(10);assertTrue(Files.exists(marker));JsonNode receipt=Seed.json(Files.readAllBytes(receipts().getFirst()));child=ProcessHandle.of(receipt.path("child").path("pid").asLong()).orElseThrow();started=Instant.parse(receipt.path("child").path("startedAt").asText());
            parent.interrupt();parent.join(6000);assertFalse(parent.isAlive());assertInstanceOf(InterruptedIOException.class,failure.get());assertTrue(interrupted.get());assertFalse(child.isAlive());assertEquals("running",Seed.json(Files.readAllBytes(receipts().getFirst())).path("state").asText());
        }finally {
            if(parent.isAlive()){parent.interrupt();parent.join(6000);}
            if(child!=null&&child.isAlive()){assertEquals(started,child.info().startInstant().orElseThrow());child.destroyForcibly();child.onExit().get(5,TimeUnit.SECONDS);}
        }
    }
    @Test void parentDeathLeavesUnknownReceiptAndDoesNotAuthorizeRepair() throws Exception {
        Fixture f=fixture();Path marker=directory.resolve("child-ready");Process parent=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("bootstrap.testClasspath"),BootstrapProcess.class.getName(),f.installed.toString(),root().toString(),"block",marker.toString()).redirectOutput(directory.resolve("parent.stdout").toFile()).redirectError(directory.resolve("parent.stderr").toFile()).start();ProcessHandle child=null;Instant start=null;
        try {
            long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();JsonNode receipt=null;
            while(System.nanoTime()<end){if(Files.isDirectory(root().resolve("runs")))for(Path path:receipts())try {JsonNode value=Seed.json(Files.readAllBytes(path));if(value.path("state").asText().equals("running"))receipt=value;}catch(IOException partial){}if(receipt!=null&&Files.exists(marker))break;Thread.sleep(10);}
            assertNotNull(receipt);assertTrue(Files.exists(marker));child=ProcessHandle.of(receipt.path("child").path("pid").asLong()).orElseThrow();start=Instant.parse(receipt.path("child").path("startedAt").asText());assertEquals(start,child.info().startInstant().orElseThrow());
            parent.destroyForcibly();assertTrue(parent.waitFor(3,TimeUnit.SECONDS));Files.move(slot(),directory.resolve("held-live-slot"));assertThrows(IOException.class,()->f.bootstrap(root()).repair());assertEquals("running",Seed.json(Files.readAllBytes(receipts().getFirst())).path("state").asText());
        }finally {
            if(parent.isAlive()){parent.destroyForcibly();assertTrue(parent.waitFor(3,TimeUnit.SECONDS));}
            if(child!=null&&child.isAlive()){assertEquals(start,child.info().startInstant().orElseThrow());child.destroyForcibly();child.onExit().get(5,TimeUnit.SECONDS);}
        }
    }
}
