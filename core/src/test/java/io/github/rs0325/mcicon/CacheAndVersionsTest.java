package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CacheAndVersionsTest {
    @TempDir Path root;
    @Test void tamperedOfflineCacheIsRejectedAndOnlineCacheCanBeRepaired() throws Exception {
        byte[] expected="verified input".getBytes();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(expected));
        AtomicInteger downloads=new AtomicInteger();
        AssetStore store=new AssetStore(root,false,new CancellationToken(),uri->{downloads.incrementAndGet();return new ByteArrayInputStream(expected);});
        URI url=URI.create("https://example.test/input");Path file=store.obtain("input.dat",url,hash,expected.length,false);
        store.obtain("input.dat",url,hash,expected.length,false);assertEquals(1,downloads.get());
        Files.writeString(file,"tampered input");
        AssetStore offline=new AssetStore(root,true,new CancellationToken(),uri->{fail("Offline mode accessed network");return null;});
        assertEquals("OFFLINE_CACHE_MISS",assertThrows(McIconException.class,()->offline.obtain("input.dat",url,hash,expected.length,false)).code());
        store.obtain("input.dat",url,hash,expected.length,false);assertEquals(2,downloads.get());assertArrayEquals(expected,Files.readAllBytes(file));
    }
    @Test void failedHashNeverInstallsInputAndRetryIsBounded() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        AssetStore store=new AssetStore(root,false,new CancellationToken(),uri->{attempts.incrementAndGet();return new ByteArrayInputStream(new byte[]{1,2,3});});
        assertThrows(IOException.class,()->store.obtain("file",URI.create("https://example.test/input"),"0000000000000000000000000000000000000000",3,false));
        assertEquals(3,attempts.get());assertFalse(Files.exists(root.resolve("file")));
        try(var files=Files.list(root)){assertFalse(files.anyMatch(p->p.toString().endsWith(".part")));}
    }
    @Test void offlineMetadataRequiresItsRecordedSha256() throws Exception {
        URI url=URI.create("https://example.test/metadata");
        AssetStore online=new AssetStore(root,false,new CancellationToken(),uri->new ByteArrayInputStream("{}".getBytes()));
        online.obtain("metadata.json",url,null,-1,true);
        AssetStore offline=new AssetStore(root,true,new CancellationToken(),uri->{fail("Unexpected network");return null;});
        offline.obtain("metadata.json",url,null,-1,true);
        Files.writeString(root.resolve("metadata.json"),"{\"changed\":true}");
        assertEquals("OFFLINE_CACHE_MISS",assertThrows(McIconException.class,()->offline.obtain("metadata.json",url,null,-1,true)).code());
    }
    @Test void releaseDatesSelectModernVersionNamesAndRejectSnapshots() throws Exception {
        String manifest="""
                {"latest":{"release":"26.3"},"versions":[
                {"id":"1.12.2","type":"release","releaseTime":"2017-09-18T00:00:00Z","url":"https://example.test/a","sha1":"a"},
                {"id":"1.13","type":"release","releaseTime":"2018-07-18T00:00:00Z","url":"https://example.test/b","sha1":"b"},
                {"id":"26.3","type":"release","releaseTime":"2026-09-15T00:00:00Z","url":"https://example.test/c","sha1":"c"},
                {"id":"26.4-snapshot-3","type":"snapshot","releaseTime":"2026-10-06T00:00:00Z","url":"https://example.test/d","sha1":"d"}]}
                """;
        AssetStore store=new AssetStore(root,false,new CancellationToken(),uri->new ByteArrayInputStream(manifest.getBytes()));
        VersionService service=new VersionService(store);
        assertEquals(List.of("26.3","1.13"),service.list().stream().map(VersionInfo::id).toList());
        assertEquals("26.3",service.resolve("latest").id());
        assertThrows(McIconException.class,()->service.resolve("26.4-snapshot-3"));
        assertThrows(McIconException.class,()->service.resolve("1.12.2"));
    }
}
