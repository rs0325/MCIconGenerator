package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 実TLS配信ではなく、package Javaのbackground所有と親終了を確認するMac限定QA。 */
@EnabledOnOs(OS.MAC)
class BackgroundPackageTest {
    @TempDir Path directory;
    private void execute(List<String> command,Path output,long seconds) throws Exception {
        Process process=new ProcessBuilder(command).redirectOutput(output.toFile()).redirectErrorStream(true).start();
        try {assertTrue(process.waitFor(seconds,TimeUnit.SECONDS),()->"期限: "+output);assertEquals(0,process.exitValue(),()->"ログ: "+output);}
        finally {if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
    }
    @Test void packageWorkerOutlivesNormalParentWithoutKeepingItsStdioAndRecordsTlsFailure() throws Exception {
        directory=directory.toRealPath();Fixture fixture=new Fixture(directory);Process parent=null;ProcessHandle worker=null;Instant started=null;
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(15000);String url="https://127.0.0.1:"+server.getLocalPort()+"/stable.json";Path packaged=directory.resolve("app"),distribution=Path.of(System.getProperty("bootstrap.distribution"));
            execute(List.of("python3",Path.of("scripts/package-bootstrap.py").toAbsolutePath().toString(),"--jdk",System.getProperty("java.home"),"--input",distribution.resolve("lib").toString(),"--seed",fixture.installed.resolve("seed").toString(),"--trust",fixture.installed.resolve("trust.der").toString(),"--version","1.1.0","--owner","archive","--stable-manifest-url",url,"--output",packaged.toString()),directory.resolve("packaging.log"),180);
            Path home=directory.resolve("home");Files.createDirectory(home);Path app=packaged.resolve("image/MCIconBootstrap.app/Contents/MacOS/MCIconBootstrap");ProcessBuilder command=new ProcessBuilder(app.toString(),"list").redirectOutput(directory.resolve("parent.stdout").toFile()).redirectError(directory.resolve("parent.stderr").toFile());command.environment().put("JAVA_TOOL_OPTIONS","-Duser.home="+home);long parentStarted=System.nanoTime();parent=command.start();
            try(Socket peer=server.accept()) {
                peer.setSoTimeout(10000);assertEquals(22,peer.getInputStream().read(),"TLS ClientHelloのrecordが到着してから親終了を観測する");assertTrue(parent.waitFor(10,TimeUnit.SECONDS));assertEquals(0,parent.exitValue());
                Path root=home.resolve("Library/Application Support/MCIconBootstrap");JsonNode state=Seed.json(Files.readAllBytes(root.resolve("active.json")));String id=state.path("preparation").path("invocation").asText();UpdatePreparation.uuid(id);Path receipt=root.resolve("runs/"+id+".json");JsonNode running=Seed.json(Files.readAllBytes(receipt));assertEquals("running",running.path("state").asText());started=Instant.parse(running.path("child").path("startedAt").asText());worker=ProcessHandle.of(running.path("child").path("pid").asLong()).orElseThrow();assertEquals(started,worker.info().startInstant().orElseThrow());assertTrue(worker.isAlive());assertNotEquals(parent.pid(),worker.pid());assertTrue(Files.readString(directory.resolve("parent.stdout")).contains("fixture-cli:list"));
                peer.close();worker.onExit().get(15,TimeUnit.SECONDS);assertFalse(worker.isAlive());JsonNode terminal=Seed.json(Files.readAllBytes(receipt));assertEquals("finished",terminal.path("state").asText());assertEquals(1,terminal.path("exitCode").asInt());JsonNode after=Seed.json(Files.readAllBytes(root.resolve("active.json")));assertTrue(after.path("pending").isNull());assertEquals(1,after.path("highestAcceptedSequence").asInt());
                ObjectNode result=Seed.JSON.createObjectNode().put("normalParentExited",true).put("workerWasAliveAfterParent",true).put("tlsClientHelloReceived",true).put("workerTerminationObserved",true).put("receiptFailurePreserved",true).put("downloadSucceeded",false).put("externalConnections",0);result.put("parentElapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-parentStarted));Path evidence=Path.of(System.getProperty("bootstrap.qaEvidenceRoot")).resolve(UUID.randomUUID().toString());Files.createDirectories(evidence);Files.writeString(evidence.resolve("result.json"),Seed.JSON.writeValueAsString(result));Files.copy(receipt,evidence.resolve("worker-receipt.json"));Files.write(evidence.resolve("before.json"),Seed.JSON.writeValueAsBytes(state));Files.write(evidence.resolve("after.json"),Seed.JSON.writeValueAsBytes(after));for(String name:List.of("parent.stdout","parent.stderr","packaging.log"))Files.copy(directory.resolve(name),evidence.resolve(name));Files.copy(packaged.resolve("packaging.json"),evidence.resolve("packaging.json"));System.out.println("backgroundQA="+evidence);
                fixture.manifest.put("toolVersion","1.1.1").put("sequence",2);fixture.descriptor.put("toolVersion","1.1.1");for(String name:new ArrayList<>(fixture.files.keySet()))if(name.endsWith("-1.1.0.jar")){byte[] bytes=fixture.files.remove(name);fixture.files.put(name.replace("-1.1.0.jar","-1.1.1.jar"),bytes);}fixture.publish();
                Path contents=app.getParent().getParent(),packageJava=contents.resolve("runtime/Contents/Home/bin/java"),installed=contents.resolve("app");String classpath=installed.resolve("*")+File.pathSeparator+System.getProperty("bootstrap.testClasspath");
                for(String mode:List.of("success","disable","signature"))execute(List.of(packageJava.toString(),"-cp",classpath,DownloadClosureProcess.class.getName(),installed.toString(),directory.resolve("closure-"+mode).toString(),fixture.installed.resolve("seed").toString(),mode,evidence.resolve("closure-"+mode).toString()),evidence.resolve("closure-"+mode+".log"),30);

            }
        } finally {
            if(parent!=null&&parent.isAlive()){parent.destroyForcibly();assertTrue(parent.waitFor(5,TimeUnit.SECONDS));}
            if(worker!=null&&worker.isAlive()){assertEquals(started,worker.info().startInstant().orElseThrow());worker.destroy();try{worker.onExit().get(3,TimeUnit.SECONDS);}catch(TimeoutException timeout){assertEquals(started,worker.info().startInstant().orElseThrow());worker.destroyForcibly();worker.onExit().get(3,TimeUnit.SECONDS);}}
        }
    }
}
