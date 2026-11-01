package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReceiptIdentityTest {
    @TempDir Path directory;
    @BeforeEach void requirePython() {assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")));}
    private static final String USER="S-1-5-21-1";
    // Mac上の固定reader応答用。Windows実機のpath/共有動作を証明しない。
    private final Path root=Path.of("C:\\"),receipt=root.resolve("receipt");
    private long deadline(){return System.nanoTime()+Duration.ofSeconds(5).toNanos();}
    private ObjectNode observation() {
        ObjectNode value=Seed.JSON.createObjectNode().put("schemaVersion",3).put("status","observed").put("tokenUserSid",USER).put("tokenDefaultOwnerSid",USER);ArrayNode rows=value.putArray("rows");
        for(int i=0;i<2;i++) {
            ObjectNode row=rows.addObject().put("index",i).put("path",(i==0?root:receipt).toString()).put("volumeSerial","18446744073709551615").put("fileId",(i==0?"a":"b").repeat(32)).put("nodeType",i==0?"directory":"regular-file").put("fileAttributes",i==0?16:0).put("reparseTag",0).put("ownerSid",USER).put("controlFlags",4).put("daclPresent",true).put("daclNull",false);
            if(i==0){row.putNull("parentIndex").putNull("relativeName").putNull("parentFileId").putNull("parentVolumeSerial").putNull("linkCount");}
            else row.put("parentIndex",0).put("relativeName",receipt.toString().substring(root.toString().length())).put("parentFileId","a".repeat(32)).put("parentVolumeSerial","18446744073709551615").put("linkCount",1);
            row.putArray("aces");
        }
        return value;
    }
    private Path fixture(String body) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")));directory=directory.toRealPath();Path support=directory.resolve("support");Files.createDirectories(support);Path exe=support.resolve("windows-security-reader.exe");Files.writeString(exe,"#!/usr/bin/python3\nimport sys,json,time\n"+body+"\n");assertTrue(exe.toFile().setExecutable(true,true));
        ObjectNode info=Seed.JSON.createObjectNode().put("schemaVersion",1).put("target","x86_64-w64-mingw32").put("sourceSha256","a".repeat(64));info.putObject("executable").put("bytes",Files.size(exe)).put("sha256",Seed.hash(exe));Path build=support.resolve("windows-security-reader-build.json");Files.writeString(build,"fixture build");info.put("buildSha256",Seed.hash(build));Path license=support.resolve("LICENSE");Files.writeString(license,"fixture license");info.putArray("licenses").addObject().put("path","LICENSE").put("bytes",Files.size(license)).put("sha256",Seed.hash(license));Files.write(support.resolve("windows-security-reader.json"),Seed.JSON.writeValueAsBytes(info));return directory;
    }
    private String emit(ObjectNode value) throws Exception {return "request=json.load(sys.stdin)\nassert request['terminalType']=='regular-file' and len(request['paths'])==2\nsys.stdout.write("+Seed.JSON.writeValueAsString(Seed.JSON.writeValueAsString(value))+")";}
    private WindowsPrivateFiles files(WindowsSecurityReader reader) throws Exception {return new WindowsPrivateFiles(root,reader,USER,Files.getOwner(directory));}
    private String identity(ObjectNode value) throws Exception {fixture(emit(value));return files(new WindowsSecurityReader(directory)).fileIdentity(receipt,deadline());}
    @Test void qualifiedTerminalIdentityIncludesVolumeAndFileId() throws Exception {
        ObjectNode value=observation();String key=identity(value);assertEquals("windows-file-id-v1:18446744073709551615:"+"b".repeat(32),key);assertEquals(72,key.length());assertEquals(key,identity(value));
        ((ObjectNode)value.path("rows").get(1)).put("fileId","c".repeat(32));assertNotEquals(key,identity(value));
        for(var row:value.path("rows"))((ObjectNode)row).put("volumeSerial","1");((ObjectNode)value.path("rows").get(1)).put("parentVolumeSerial","1");assertNotEquals(key,identity(value));
    }
    @Test void qualificationCannotBeSkippedForAValidIdentity() throws Exception {
        ObjectNode owner=observation();((ObjectNode)owner.path("rows").get(1)).put("ownerSid","S-1-5-21-2");assertThrows(IOException.class,()->identity(owner));
        ObjectNode acl=observation();((ArrayNode)acl.path("rows").get(1).get("aces")).addObject().put("type",0).put("flags",0).put("bytes",24).put("rawMask",0x40000).put("sid","S-1-5-21-2");assertThrows(IOException.class,()->identity(acl));
        ObjectNode dacl=observation();((ObjectNode)dacl.path("rows").get(1)).put("daclNull",true);assertThrows(IOException.class,()->identity(dacl));
    }
    @Test void nativeIdentityTypeAndChainAreRequired() throws Exception {
        for(String field:new String[]{"fileId","volumeSerial"}){ObjectNode missing=observation();((ObjectNode)missing.path("rows").get(1)).remove(field);assertThrows(IOException.class,()->identity(missing));}
        for(String field:new String[]{"parentFileId","fileId"}){ObjectNode changed=observation();((ObjectNode)changed.path("rows").get(1)).put(field,"invalid");assertThrows(IOException.class,()->identity(changed));}
        ObjectNode type=observation();((ObjectNode)type.path("rows").get(1)).put("fileAttributes",16);assertThrows(IOException.class,()->identity(type));
        ObjectNode link=observation();((ObjectNode)link.path("rows").get(1)).put("linkCount",2);assertThrows(IOException.class,()->identity(link));
    }
    @Test void outsideRootAndExpiredBudgetNeverStartReader() throws Exception {
        Path marker=directory.resolve("started");fixture("open("+Seed.JSON.writeValueAsString(marker.toString())+",'w').write('started')");WindowsPrivateFiles files=files(new WindowsSecurityReader(directory));
        assertThrows(IOException.class,()->files.fileIdentity(Path.of("other/receipt"),deadline()));assertThrows(IOException.class,()->files.fileIdentity(receipt,System.nanoTime()-1));assertFalse(Files.exists(marker));
    }
    @Test void readerUnknownRetainsOriginalObservation() throws Exception {
        ObjectNode unknown=Seed.JSON.createObjectNode().put("schemaVersion",3).put("status","unknown").put("errorStage","fixture");fixture(emit(unknown)+"\nsys.exit(1)");
        var error=assertThrows(WindowsSecurityReader.ObservationFailure.class,()->files(new WindowsSecurityReader(directory)).fileIdentity(receipt,deadline()));assertEquals(unknown,error.observation);
    }
    @Test void completedIdentityCannotReturnAfterDeadline() throws Exception {
        Path marker=directory.resolve("completed");fixture(emit(observation())+"\nsys.stdout.flush()\nopen("+Seed.JSON.writeValueAsString(marker.toString())+",'w').write('completed')");
        WindowsSecurityReader reader=new WindowsSecurityReader(directory,()->System.nanoTime()+(Files.exists(marker)?Duration.ofSeconds(6).toNanos():0));assertThrows(IOException.class,()->files(reader).fileIdentity(receipt,deadline()));assertTrue(Files.exists(marker));
    }
    @Test void nonWindowsKeepsQualificationAndExistingJdkKey() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));directory=directory.toRealPath();Bootstrap b=new Fixture(directory).bootstrap(directory.resolve("root"));Path file=directory.resolve("receipt");Files.writeString(file,"same");
        Object key=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();assertNotNull(key);assertEquals(key.toString(),b.fileIdentity(file,deadline()));
        assertThrows(IOException.class,()->b.fileIdentity(directory,deadline()));Path link=directory.resolve("linked");Files.createSymbolicLink(link,file);assertThrows(IOException.class,()->b.fileIdentity(link,deadline()));Path parentLink=directory.resolve("parent-link");Files.createSymbolicLink(parentLink,directory);assertThrows(IOException.class,()->b.fileIdentity(parentLink.resolve("receipt"),deadline()));
        assertThrows(IOException.class,()->b.fileIdentity(file,System.nanoTime()-1));
        var owner=Bootstrap.class.getDeclaredField("owner");owner.setAccessible(true);owner.set(b,(java.nio.file.attribute.UserPrincipal)()->"fixture-other-owner");assertThrows(IOException.class,()->b.fileIdentity(file,deadline()));
    }
}
