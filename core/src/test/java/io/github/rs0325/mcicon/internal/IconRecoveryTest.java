package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class IconRecoveryTest {
    @TempDir Path directory;
    RuntimePreparer.Prepared runtime() throws Exception {
        Path client=directory.resolve("client.jar");Files.write(client,new byte[]{1,2,3});
        return new RuntimePreparer.Prepared(new VersionInfo("1.21.4","2024-12-03","experimental","https://example.invalid/metadata",""),Json.MAPPER.createObjectNode(),client,null,List.of(),directory,directory,21,"synthetic");
    }
    List<CatalogEntry> catalog(int count) {
        List<CatalogEntry> result=new ArrayList<>();
        for(int i=0;i<count;i++)result.add(new CatalogEntry(String.format("minecraft:sample_%02d",i),"item","available",List.of()));
        return result;
    }
    RenderRequest request() {return new RenderRequest("1.21.4",List.of(),true,"all",16,directory.resolve("out"),false,false,true);}
    class FakeWorker implements IconWorker {
        int calls;final int crashAfter;
        FakeWorker(int crashAfter){this.crashAfter=crashAfter;}
        public Path render(String id,int size) throws Exception {
            if(calls++==crashAfter)throw new AssertionError("Simulated process crash");
            Path file=directory.resolve("worker-image.png");BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
            image.setRGB(1,1,0xffabcdef);ImageIO.write(image,"PNG",file.toFile());return file;
        }
        public String renderer(){return "synthetic";}
        public String agentSha256(){return "a".repeat(64);}
        public String imageId(){return "sha256:"+"b".repeat(64);}
    }
    @Test void recoversOwnedFilesWrittenSinceTheLastCheckpointAfterCrash() throws Exception {
        var runtime=runtime();var catalog=catalog(20);var exporter=new IconExporter(new CancellationToken(),ProgressListener.NONE);
        assertThrows(AssertionError.class,()->exporter.export(runtime,new FakeWorker(8),catalog,request()));
        Path manifest=directory.resolve("out/java/1.21.4/icons/manifest.json");
        assertFalse(Json.MAPPER.readTree(manifest.toFile()).path("complete").asBoolean());
        assertTrue(Files.exists(manifest.getParent().resolve("assets/minecraft/textures/item/sample_07.png")));
        var resumed=exporter.export(runtime,new FakeWorker(-1),catalog,request());
        assertTrue(resumed.complete());assertEquals(20,resumed.succeeded());assertEquals(0,resumed.failed());
        assertEquals(Json.MAPPER.valueToTree(List.of(252,252,252)),Json.MAPPER.readTree(manifest.toFile()).path("context").path("lightmapFullBrightRgb"));
        FakeWorker cached=new FakeWorker(-1);assertTrue(exporter.export(runtime,cached,catalog,request()).complete());assertEquals(0,cached.calls);
    }
    @Test void repeatedResumeNeverClaimsUnownedOutput() throws Exception {
        Path file=directory.resolve("out/java/1.21.4/icons/assets/minecraft/textures/item/sample_00.png");
        Files.createDirectories(file.getParent());byte[] original={9,8,7};Files.write(file,original);
        var runtime=runtime();var exporter=new IconExporter(new CancellationToken(),ProgressListener.NONE);
        for(int i=0;i<2;i++) {
            var result=exporter.export(runtime,new FakeWorker(-1),catalog(1),request());
            assertFalse(result.complete());assertEquals("OUTPUT_EXISTS",result.entries().getFirst().code());assertArrayEquals(original,Files.readAllBytes(file));
        }
    }
    @Test void preservesWorkerFailureDetailsAndContinuesHealthyItems() throws Exception {
        FakeWorker worker=new FakeWorker(-1) {
            public Path render(String id,int size) throws Exception {
                if(id.endsWith("00"))throw new McIconException("RENDER_FAILED","IOException: Empty inventory icon",3);
                if(id.endsWith("01"))throw new McIconException("RENDER_FAILED","IllegalStateException: inventory draw failure",3);
                return super.render(id,size);
            }
        };
        var result=new IconExporter(new CancellationToken(),ProgressListener.NONE).export(runtime(),worker,catalog(3),request());
        assertFalse(result.complete());assertEquals(1,result.succeeded());assertEquals(2,result.failed());
        var entries=Json.MAPPER.readTree(Path.of(result.manifest()).toFile()).path("entries");
        assertEquals("RENDER_FAILED",entries.get(0).path("code").asText());
        assertEquals("IOException: Empty inventory icon",entries.get(0).path("message").asText());
        assertEquals("IllegalStateException: inventory draw failure",entries.get(1).path("message").asText());
        assertEquals("success",entries.get(2).path("status").asText());
    }
    @Test void cancellationLeavesPlannedEntriesAndCanResumeWithANewToken() throws Exception {
        var runtime=runtime();CancellationToken token=new CancellationToken();
        ProgressListener listener=event->{if(event.completed()==3)token.cancel();};
        var interrupted=new IconExporter(token,listener).export(runtime,new FakeWorker(-1),catalog(20),request());
        assertFalse(interrupted.complete());assertEquals(3,interrupted.succeeded());
        var resumed=new IconExporter(new CancellationToken(),ProgressListener.NONE).export(runtime,new FakeWorker(-1),catalog(20),request());
        assertTrue(resumed.complete());assertEquals(20,resumed.succeeded());
    }
    @Test void interruptedExplicitOverwriteClaimsPreviouslyUnownedPng() throws Exception {
        Path file=directory.resolve("out/java/1.21.4/icons/assets/minecraft/textures/item/sample_00.png");
        Files.createDirectories(file.getParent());Files.write(file,new byte[]{9,8,7});
        var runtime=runtime();var exporter=new IconExporter(new CancellationToken(),ProgressListener.NONE);
        assertFalse(exporter.export(runtime,new FakeWorker(-1),catalog(2),request()).complete());
        var overwrite=new RenderRequest("1.21.4",List.of(),true,"all",16,directory.resolve("out"),true,false,true);
        // sample_01 was previously successful, so force a render after sample_00.
        Files.delete(file.resolveSibling("sample_01.png"));
        assertThrows(AssertionError.class,()->exporter.export(runtime,new FakeWorker(1),catalog(2),overwrite));
        assertTrue(exporter.export(runtime,new FakeWorker(-1),catalog(2),request()).complete());
    }
}
