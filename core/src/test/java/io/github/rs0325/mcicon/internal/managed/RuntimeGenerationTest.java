package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeGenerationTest {
    @TempDir Path root;
    @Test void helperPreflightCanPrecedeGuestDownloadAndCacheIsImmutable() throws Exception {
        byte[] helper=RuntimeArchiveTest.zip("bin/helper",false,false),image=RuntimeArchiveTest.zip("../guest-only-link-fixture",true,false),inventory={5};
        ManagedFixture fixture=new ManagedFixture();
        for(int i=0;i<2;i++) {
            byte[] bytes=i==0?helper:image;ObjectNode artifact=(ObjectNode)fixture.json.get("artifacts").get(i);
            artifact.put("bytes",bytes.length).put("sha256",FilesSupport.hash(bytes)).put("unpackedBytes",16384).put("inventorySha256",FilesSupport.hash(inventory));
        }
        RuntimeDescriptor descriptor=fixture.descriptor();AtomicInteger images=new AtomicInteger();
        RuntimeStore store=new RuntimeStore(root,false,new CancellationToken(),Duration.ofSeconds(10),(uri,offset,remaining)-> {
            byte[] bytes=uri.getPath().endsWith("helper")?helper:image;if(bytes==image)images.incrementAndGet();return new RuntimeStore.Response(200,0,new ByteArrayInputStream(bytes));
        });
        RuntimeGeneration generation=new RuntimeGeneration(store);Path executable=generation.helper(descriptor);assertTrue(Files.isRegularFile(executable));assertEquals(0,images.get());
        Map<String,byte[]> inventories=Map.of("inventory/helper.json",inventory,"inventory/image.json",inventory);
        Path installed=generation.prepare(descriptor,inventories);assertArrayEquals(image,Files.readAllBytes(installed.resolve("image.zip")));assertFalse(Files.exists(root.resolve("guest-only-link-fixture")));
        assertEquals(installed,generation.prepare(descriptor,inventories));assertEquals(1,images.get());
        try(var lease=RuntimeLease.create(store,descriptor)) {
            fixture.json.put("minimumOsVersion","5.16");var next=fixture.descriptor();Path newGeneration=generation.prepare(next,inventories);assertNotEquals(installed,newGeneration);assertTrue(Files.exists(installed));assertFalse(RuntimeLease.idle(store,descriptor));
        }
        Files.writeString(executable,"tampered");RuntimeDescriptorTest.assertInvalid(()->generation.helper(descriptor));
        assertEquals("tampered",Files.readString(executable));
        Files.writeString(installed.resolve("image.zip"),"tampered");RuntimeDescriptorTest.assertInvalid(()->generation.prepare(descriptor,inventories));
    }
}
