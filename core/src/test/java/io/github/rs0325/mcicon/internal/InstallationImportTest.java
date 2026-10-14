package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InstallationImportTest {
    @TempDir Path root;
    private final byte[] expected="公式入力のfixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final URI uri=URI.create("https://example.test/input");
    private String sha1(){return HexFormat.of().formatHex(digest(expected));}
    private byte[] digest(byte[] bytes){try{return java.security.MessageDigest.getInstance("SHA-1").digest(bytes);}catch(Exception e){throw new AssertionError(e);}}
    private Path source(String relative,byte[] bytes) throws IOException {
        Path source=root.resolve("installation").resolve(relative);Files.createDirectories(source.getParent());Files.write(source,bytes);return source;
    }
    @Test void officialInputsAreCopiedAndSourceRemainsUntouched() throws Exception {
        var paths=Map.of("versions/1.21.4/client.jar","versions/1.21.4/1.21.4.jar",
            "versions/1.21.4/version.json","versions/1.21.4/1.21.4.json","libraries/example/a.jar","libraries/example/a.jar",
            "assets/indexes/19.json","assets/indexes/19.json","assets/objects/ab/abcdef","assets/objects/ab/abcdef");
        for(String relative:paths.values())source(relative,expected);
        Path installation=root.resolve("installation"),cache=root.resolve("cache");
        var store=new AssetStore(cache,true,new CancellationToken(),u->{throw new AssertionError("ネットワークアクセス");},installation);
        for(var entry:paths.entrySet()) {
            Path result=store.obtain(entry.getKey(),uri,sha1(),expected.length,false);
            assertArrayEquals(expected,Files.readAllBytes(result));
            assertEquals(FilesSupport.hash(result,"SHA-256"),Files.readString(Path.of(result+".sha256")));
            assertArrayEquals(expected,Files.readAllBytes(installation.resolve(entry.getValue())));
            assertFalse(Files.exists(Path.of(installation.resolve(entry.getValue())+".sha256")));
        }
    }
    @Test void invalidLocalInputIsNotTrustedOrInstalled() throws Exception {
        Path original=source("libraries/a.jar",new byte[]{1,2,3});AtomicInteger downloads=new AtomicInteger();
        var offline=new AssetStore(root.resolve("offline"),true,new CancellationToken(),u->{throw new AssertionError();},root.resolve("installation"));
        assertEquals("OFFLINE_CACHE_MISS",assertThrows(McIconException.class,()->offline.obtain("libraries/a.jar",uri,sha1(),expected.length,false)).code());
        assertFalse(Files.exists(root.resolve("offline/libraries/a.jar")));
        var online=new AssetStore(root.resolve("online"),false,new CancellationToken(),u->{downloads.incrementAndGet();return new ByteArrayInputStream(expected);},root.resolve("installation"));
        assertArrayEquals(expected,Files.readAllBytes(online.obtain("libraries/a.jar",uri,sha1(),expected.length,false)));
        assertEquals(1,downloads.get());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(original));
        try(var walk=Files.walk(root)){assertFalse(walk.anyMatch(p->p.toString().endsWith(".part")));}
    }
    @Test void unverifiedMetadataAndUnrelatedInstallationFilesAreNotRead() throws Exception {
        source("metadata/version_manifest_v2.json",expected);source("launcher_accounts.json",expected);
        Files.createSymbolicLink(root.resolve("installation/saves"),root.resolve("does-not-exist"));
        var store=new AssetStore(root.resolve("cache"),true,new CancellationToken(),u->{throw new AssertionError();},root.resolve("installation"));
        assertEquals("OFFLINE_CACHE_MISS",assertThrows(McIconException.class,()->store.obtain("metadata/version_manifest_v2.json",uri,null,-1,true)).code());
    }
    @Test void symbolicLinksAreNotFollowedWhenImportingInputs() throws Exception {
        Path original=source("original",expected);Files.createDirectories(root.resolve("installation/libraries"));
        Files.createSymbolicLink(root.resolve("installation/libraries/a.jar"),original);
        var store=new AssetStore(root.resolve("cache"),true,new CancellationToken(),u->{throw new AssertionError();},root.resolve("installation"));
        assertEquals("UNSAFE_PATH",assertThrows(McIconException.class,()->store.obtain("libraries/a.jar",uri,sha1(),expected.length,false)).code());
        assertFalse(Files.exists(root.resolve("cache/libraries/a.jar")));
    }
    @Test void officialSubtreesCannotEscapeIntoLauncherOrWorldFiles() throws Exception {
        source("launcher_accounts.json",expected);source("saves/world/data",expected);
        var store=new AssetStore(root.resolve("cache"),true,new CancellationToken(),u->{throw new AssertionError();},root.resolve("installation"));
        for(String relative:List.of("libraries/../launcher_accounts.json","assets/indexes/../../saves/world/data"))
            assertEquals("UNSAFE_PATH",assertThrows(McIconException.class,()->store.obtain(relative,uri,sha1(),expected.length,false)).code());
        assertFalse(Files.exists(root.resolve("cache/launcher_accounts.json")));assertFalse(Files.exists(root.resolve("cache/saves/world/data")));
    }
    @Test void cacheCannotWriteWithinOrContainTheInstallation() throws Exception {
        Path installation=source("versions/1.21.4/1.21.4.jar",expected).getParent().getParent().getParent();
        for(Path cache:List.of(installation,installation.resolve("cache"),root))
            assertThrows(IllegalArgumentException.class,()->new AssetStore(cache,true,new CancellationToken(),installation));
        Path alias=root.resolve("alias");Files.createSymbolicLink(alias,installation);
        assertThrows(IllegalArgumentException.class,()->new AssetStore(alias.resolve("nested/cache"),true,new CancellationToken(),installation));
        try(var files=Files.walk(installation)){assertEquals(1,files.filter(Files::isRegularFile).count());}
    }
}
