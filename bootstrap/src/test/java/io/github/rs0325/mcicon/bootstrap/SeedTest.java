package io.github.rs0325.mcicon.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.io.IOException;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SeedTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeEach void canonicalDirectory() throws Exception {directory=directory.toRealPath();}
    Fixture fixture() throws Exception {return new Fixture(directory.toRealPath());}
    long deadline(){return System.nanoTime()+Duration.ofSeconds(10).toNanos();}
    @Test void temporaryFixtureKeysOnlyValidateTheirOwnSignedSeed() throws Exception {
        Fixture first=new Fixture(directory.toRealPath().resolve("first")),second=new Fixture(directory.toRealPath().resolve("second"));
        assertFalse(java.util.Arrays.equals(first.publicKey.getEncoded(),second.publicKey.getEncoded()));first.seed();second.seed();
        assertThrows(IOException.class,()->new Seed(first.installed.resolve("seed"),second.publicKey,first.host));
        first.manifest.put("sequence",2);first.writeManifest();assertEquals(2,first.seed().manifest.path("sequence").asInt());
    }
    @Test void verifiesTemporaryTestSignatureAndExactArchiveTree() throws Exception {
        Fixture f=fixture();Seed seed=f.seed();Path extracted=directory.resolve("extracted");seed.extract(f.bootstrap(directory),f.installed.resolve("seed/tool.zip"),extracted,deadline());assertTrue(Files.isExecutable(extracted.resolve("java/bin/java")));assertEquals("1.1.0",seed.version);
    }
    @Test void posixSlotRetainsExistingDirectoryModes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Fixture f=fixture();Path slot=directory.resolve("mode-slot");Bootstrap bootstrap=f.bootstrap(directory);
        f.seed().extract(bootstrap,f.installed.resolve("seed/tool.zip"),slot,deadline());
        Files.setPosixFilePermissions(slot,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path child=slot.resolve("app");Files.setPosixFilePermissions(child,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        assertEquals("directory",Seed.tree(bootstrap,slot,deadline()).get("app"));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(child));
    }
    @Test void rawSignatureAndUnsignedZipChangesAreRejected() throws Exception {
        Fixture f=fixture();Path manifest=f.installed.resolve("seed/stable.json");Files.writeString(manifest,Files.readString(manifest)+"\n");assertThrows(IOException.class,f::seed);f.writeManifest();
        Seed seed=f.seed();Files.write(f.installed.resolve("seed/tool.zip"),new byte[]{1});assertThrows(IOException.class,()->seed.extract(f.bootstrap(directory),f.installed.resolve("seed/tool.zip"),directory.resolve("bad"),deadline()));
    }
    @Test void unknownFieldDuplicateHostAndProtocolAreRejected() throws Exception {
        Fixture f=fixture();f.manifest.put("extra",true);f.writeManifest();assertThrows(IOException.class,f::seed);f.manifest.remove("extra");
        ((ArrayNode)f.manifest.get("platforms")).add(f.manifest.path("platforms").get(0).deepCopy());f.writeManifest();assertThrows(IOException.class,f::seed);((ArrayNode)f.manifest.get("platforms")).remove(1);
        for(int protocol:new int[]{1,3}){f.manifest.put("minimumBootstrapProtocol",protocol);f.writeManifest();assertThrows(IOException.class,f::seed);}
    }
    @Test void duplicateJsonKeyIsRejectedAfterSignature() throws Exception {
        Fixture f=fixture();byte[] raw=Files.readAllBytes(f.installed.resolve("seed/stable.json"));byte[] duplicate=("{\"schemaVersion\":1,"+new String(raw,java.nio.charset.StandardCharsets.UTF_8).substring(1)).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(f.installed.resolve("seed/stable.json"),duplicate);Files.write(f.installed.resolve("seed/stable.json.sig"),f.sign(duplicate));assertThrows(IOException.class,f::seed);
    }
    @Test void zipTraversalSymlinkAndDuplicateEntryAreRejected() throws Exception {
        Fixture f=fixture();for(String entry:new String[]{"../outside","/absolute","link","LICENSE"}) {
            f.replaceZip(f.zip(entry));Path stage=directory.resolve("bad-"+Math.abs(entry.hashCode()));assertThrows(IOException.class,()->f.seed().extract(f.bootstrap(directory),f.installed.resolve("seed/tool.zip"),stage,deadline()),entry);
        }
        assertFalse(Files.exists(directory.resolve("outside")));
    }
    @Test void declaredUnpackedLimitAndDeadlineAreEnforced() throws Exception {
        Fixture f=fixture();((ObjectNode)f.manifest.path("platforms").get(0).get("payload")).put("unpackedBytes",1);f.writeManifest();assertThrows(IOException.class,()->f.seed().extract(f.bootstrap(directory),f.installed.resolve("seed/tool.zip"),directory.resolve("small"),deadline()));
        f.publish();assertThrows(IOException.class,()->f.seed().extract(f.bootstrap(directory),f.installed.resolve("seed/tool.zip"),directory.resolve("expired"),System.nanoTime()-1));
    }
    @Test void descriptorAndInventoryMismatchCannotBecomeSlot() throws Exception {
        Fixture f=fixture();f.files.put("inventory/tool.json",new byte[]{1});f.publish();assertThrows(IOException.class,()->f.seed().extract(f.bootstrap(directory),f.installed.resolve("seed/tool.zip"),directory.resolve("inventory"),deadline()));
        f.files.put("inventory/tool.json","{\"fixtureOnly\":true}".getBytes());f.descriptor.put("toolVersion","1.2.0");f.publish();assertThrows(IOException.class,()->f.seed().extract(f.bootstrap(directory),f.installed.resolve("seed/tool.zip"),directory.resolve("descriptor"),deadline()));
    }
}
