package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsSecurityReaderTest {
    @TempDir Path directory;
    private Path fixture(String body) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")));directory=directory.toRealPath();Path support=directory.resolve("support");Files.createDirectory(support);Path exe=support.resolve("windows-security-reader.exe");Files.writeString(exe,"#!/usr/bin/python3\nimport sys,os,time,json\n"+body+"\n");assertTrue(exe.toFile().setExecutable(true,true));
        ObjectNode info=Seed.JSON.createObjectNode().put("schemaVersion",1).put("target","x86_64-w64-mingw32").put("sourceSha256","a".repeat(64)).put("buildSha256","b".repeat(64));info.putObject("executable").put("bytes",Files.size(exe)).put("sha256",Seed.hash(exe));Path build=support.resolve("windows-security-reader-build.json");Files.writeString(build,"fixture build");info.put("buildSha256",Seed.hash(build));Path license=support.resolve("LICENSE");Files.writeString(license,"fixture license");info.putArray("licenses").addObject().put("path","LICENSE").put("bytes",Files.size(license)).put("sha256",Seed.hash(license));Files.write(support.resolve("windows-security-reader.json"),Seed.JSON.writeValueAsBytes(info));return exe;
    }
    private ObjectNode observation() {
        ObjectNode value=Seed.JSON.createObjectNode().put("schemaVersion",3).put("status","observed").put("tokenUserSid","S-1-5-21-1").put("tokenDefaultOwnerSid","S-1-5-32-544");ObjectNode row=value.putArray("rows").addObject().put("index",0).put("path","C:\\").put("volumeSerial","1").put("fileId","a".repeat(32)).put("nodeType","directory").putNull("linkCount").put("fileAttributes",16).put("reparseTag",0).put("ownerSid","S-1-5-21-1").put("controlFlags",4).put("daclPresent",true).put("daclNull",false);for(String name:List.of("parentIndex","relativeName","parentFileId","parentVolumeSerial"))row.putNull(name);row.putArray("aces");return value;
    }
    private ObjectNode fileObservation() {
        ObjectNode value=observation(),row=((ObjectNode)value.path("rows").get(0)).deepCopy();row.put("index",1).put("path","C:\\file").put("fileId","b".repeat(32)).put("nodeType","regular-file").put("linkCount",1).put("fileAttributes",0).put("parentIndex",0).put("relativeName","file").put("parentFileId","a".repeat(32)).put("parentVolumeSerial","1");((com.fasterxml.jackson.databind.node.ArrayNode)value.get("rows")).add(row);return value;
    }
    private String emit(ObjectNode value) throws Exception {return "sys.stdin.read()\nsys.stdout.write("+Seed.JSON.writeValueAsString(Seed.JSON.writeValueAsString(value))+")";}
    @Test void eofAndRawMaskUnknownAceArePreserved() throws Exception {
        ObjectNode value=observation();var aces=(com.fasterxml.jackson.databind.node.ArrayNode)value.path("rows").get(0).get("aces");aces.addObject().put("type",0).put("flags",0).put("bytes",24).put("rawMask",0xc0020000L).put("sid","S-1-1-0");aces.addObject().put("type",42).put("flags",0).put("bytes",8).put("unknown",true);fixture(emit(value));assertEquals(value,new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\"))));
    }
    @Test void defaultOwnerMustBePresentAndWellFormed() throws Exception {
        ObjectNode value=observation();value.remove("tokenDefaultOwnerSid");fixture(emit(value));assertThrows(IOException.class,()->new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\"))));
    }
    @Test void packageHashMismatchNeverStartsReader() throws Exception {Path exe=fixture("raise RuntimeError('must-not-start')");Files.writeString(exe,"changed");assertThrows(IOException.class,()->new WindowsSecurityReader(directory));}
    @Test void wrongParentNeverBecomesObservation() throws Exception {ObjectNode value=observation();((ObjectNode)value.path("rows").get(0)).put("parentIndex",0);fixture(emit(value));assertThrows(IOException.class,()->new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\"))));}
    @Test void stalledStdinIsBoundedAndOwnedProcessIsReaped() throws Exception {
        Path marker=directory.resolve("reader.pid");fixture("open("+Seed.JSON.writeValueAsString(marker.toString())+",'w').write(str(os.getpid()))\ntime.sleep(30)");
        assertTimeout(Duration.ofSeconds(9),()->assertThrows(IOException.class,()->new WindowsSecurityReader(directory).read(java.util.Collections.nCopies(64,Path.of("C:\\"+"x".repeat(900))))));
        assertTrue(Files.exists(marker));long pid=Long.parseLong(Files.readString(marker));assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void outputOverflowFailsWithoutRetry() throws Exception {fixture("sys.stdout.write('x'*1048577)\nsys.stdout.flush()\ntime.sleep(30)");assertTimeout(Duration.ofSeconds(8),()->assertThrows(IOException.class,()->new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\")))));}
    @Test void rawMaskOverflowIsRejected() throws Exception {ObjectNode value=observation();((com.fasterxml.jackson.databind.node.ArrayNode)value.path("rows").get(0).get("aces")).addObject().put("type",0).put("flags",0).put("bytes",24).put("rawMask",0x100000000L).put("sid","S-1-1-0");fixture(emit(value));IOException error=assertThrows(IOException.class,()->new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\"))));assertTrue(error.getMessage().contains("uint32"));}
    @Test void earlyExitCannotBecomeEmptySuccessfulObservation() throws Exception {fixture("sys.exit(3)");assertThrows(IOException.class,()->new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\"))));}
    @Test void nativeUnknownKeepsItsRawCause() throws Exception {ObjectNode value=Seed.JSON.createObjectNode().put("schemaVersion",3).put("status","unknown").put("errorStage","reparse-or-type").put("fileAttributes",1040).put("reparseTag",2684354563L);fixture(emit(value)+"\nsys.exit(1)");var error=assertThrows(WindowsSecurityReader.ObservationFailure.class,()->new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\"))));assertEquals(value,error.observation);}
    @Test void parentBudgetPreventsStartAndBoundsStalledReader() throws Exception {
        Path marker=directory.resolve("reader.pid");fixture("open("+Seed.JSON.writeValueAsString(marker.toString())+",'w').write(str(os.getpid()))\ntime.sleep(30)");
        WindowsSecurityReader reader=new WindowsSecurityReader(directory);assertThrows(IOException.class,()->reader.read(List.of(Path.of("C:\\")),System.nanoTime()-1));assertFalse(Files.exists(marker));
        assertTimeout(Duration.ofSeconds(4),()->assertThrows(IOException.class,()->reader.read(List.of(Path.of("C:\\")),System.nanoTime()+Duration.ofSeconds(2).toNanos())));
        assertTrue(Files.exists(marker));assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(marker))).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void expiredStartDoesNotSendRequest() throws Exception {
        Path marker=directory.resolve("received");fixture("data=sys.stdin.buffer.read()\nif data: open("+Seed.JSON.writeValueAsString(marker.toString())+",'w').write('received')");
        var calls=new java.util.concurrent.atomic.AtomicInteger();var reader=new WindowsSecurityReader(directory,()->System.nanoTime()+(calls.incrementAndGet()>=2?java.util.concurrent.TimeUnit.SECONDS.toNanos(6):0));
        IOException error=assertThrows(IOException.class,()->reader.read(List.of(Path.of("C:\\"))));assertTrue(error.getMessage().contains("期限"));assertFalse(Files.exists(marker));
    }
    @Test void completedReplyCannotReturnAfterDeadline() throws Exception {
        Path marker=directory.resolve("completed");fixture(emit(observation())+"\nsys.stdout.flush()\nopen("+Seed.JSON.writeValueAsString(marker.toString())+",'w').write('completed')");
        var reader=new WindowsSecurityReader(directory,()->System.nanoTime()+(Files.exists(marker)?java.util.concurrent.TimeUnit.SECONDS.toNanos(6):0));
        IOException error=assertThrows(IOException.class,()->reader.read(List.of(Path.of("C:\\"))));assertTrue(Files.exists(marker));assertTrue(error.getMessage().contains("期限"));
    }
    @Test void fileRequestAndMetadataObservationAreBound() throws Exception {
        ObjectNode value=fileObservation();fixture(emit(value).replace("sys.stdin.read()","request=json.load(sys.stdin)\nassert request['schemaVersion']==3 and request['terminalType']=='regular-file'"));assertEquals(value,new WindowsSecurityReader(directory).readFile(List.of(Path.of("C:\\"),Path.of("C:\\file"))));
    }
    @Test void fileRequiresParentBeforeProcessStart() throws Exception {
        Path marker=directory.resolve("started");fixture("open("+Seed.JSON.writeValueAsString(marker.toString())+",'w').write('started')");assertThrows(IOException.class,()->new WindowsSecurityReader(directory).readFile(List.of(Path.of("C:\\"))));assertFalse(Files.exists(marker));
    }
    @Test void directoryRequestRejectsFileReply() throws Exception {fixture(emit(fileObservation()));assertThrows(IOException.class,()->new WindowsSecurityReader(directory).read(List.of(Path.of("C:\\"),Path.of("C:\\file"))));}
    @Test void fileReplyRejectsMultipleLinks() throws Exception {ObjectNode value=fileObservation();((ObjectNode)value.path("rows").get(1)).put("linkCount",2);fixture(emit(value));assertThrows(IOException.class,()->new WindowsSecurityReader(directory).readFile(List.of(Path.of("C:\\"),Path.of("C:\\file"))));}
    @Test void fileReplyRejectsDirectoryAttributes() throws Exception {ObjectNode value=fileObservation();((ObjectNode)value.path("rows").get(1)).put("fileAttributes",16);fixture(emit(value));assertThrows(IOException.class,()->new WindowsSecurityReader(directory).readFile(List.of(Path.of("C:\\"),Path.of("C:\\file"))));}
    @Test void oldProtocolCannotBecomeFileObservation() throws Exception {ObjectNode value=fileObservation().put("schemaVersion",1);fixture(emit(value));assertThrows(IOException.class,()->new WindowsSecurityReader(directory).readFile(List.of(Path.of("C:\\"),Path.of("C:\\file"))));}
}
