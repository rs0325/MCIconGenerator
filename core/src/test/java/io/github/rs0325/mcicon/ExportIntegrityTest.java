package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ExportIntegrityTest {
    @TempDir Path directory;
    byte[] png() throws Exception {
        BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0x80224466);image.setRGB(1,1,0xffaabbcc);
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
    Path jar(Map<String,byte[]> assets) throws Exception {
        Path jar=directory.resolve("client.jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            for(var entry:assets.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        return jar;
    }
    RawExporter exporter(){return new RawExporter(new CancellationToken(),ProgressListener.NONE);}
    ExtractRequest request(boolean all,List<ResourceId> ids){return new ExtractRequest("1.21.4",ids,all,directory.resolve("out"),false,false);}
    @Test void preservesBytesTransparencyAndAnimationAndRepairsOwnedOutput() throws Exception {
        String name="assets/minecraft/textures/item/test.png";
        byte[] original=png();byte[] metadata="{\"animation\":{\"frametime\":3}}".getBytes();
        Path jar=jar(Map.of(name,original,name+".mcmeta",metadata,"assets/minecraft/textures/entity/ignored.png",png()));
        ExportResult first=exporter().export("1.21.4",jar,request(true,List.of()));
        assertTrue(first.complete());assertEquals(1,first.succeeded());
        Path root=directory.resolve("out/java/1.21.4/raw");Path target=root.resolve(name);
        assertArrayEquals(original,Files.readAllBytes(target));assertArrayEquals(metadata,Files.readAllBytes(root.resolve(name+".mcmeta")));
        assertEquals(0x80224466,ImageIO.read(target.toFile()).getRGB(0,0));
        Files.write(target,new byte[]{1,2,3});Files.writeString(root.resolve(name+".mcmeta"),"{}");
        ExportResult resumed=exporter().export("1.21.4",jar,request(true,List.of()));
        assertTrue(resumed.complete());assertArrayEquals(original,Files.readAllBytes(target));
        assertArrayEquals(metadata,Files.readAllBytes(root.resolve(name+".mcmeta")));
    }
    @Test void failedTextureDoesNotBecomeCompleteOrPreventOtherOutputs() throws Exception {
        Path jar=jar(Map.of("assets/minecraft/textures/item/good.png",png(),"assets/minecraft/textures/item/broken.png",new byte[]{1}));
        ExportResult result=exporter().export("1.21.4",jar,request(true,List.of()));
        assertFalse(result.complete());assertEquals(3,result.exitCode());assertEquals(1,result.succeeded());assertEquals(1,result.failed());
        assertFalse(Json.MAPPER.readTree(Path.of(result.manifest()).toFile()).path("complete").asBoolean());
    }
    @Test void unownedSidecarCannotPartiallyReplaceOutput() throws Exception {
        String name="assets/minecraft/textures/item/test.png";
        Path jar=jar(Map.of(name,png(),name+".mcmeta","{}".getBytes()));
        Path root=directory.resolve("out/java/1.21.4/raw");Path sidecar=root.resolve(name+".mcmeta");
        Files.createDirectories(sidecar.getParent());Files.writeString(sidecar,"owned by another tool");
        for(int i=0;i<2;i++) {
            ExportResult result=exporter().export("1.21.4",jar,request(true,List.of()));
            assertFalse(result.complete());assertEquals("OUTPUT_EXISTS",result.entries().getFirst().code());
            assertEquals("owned by another tool",Files.readString(sidecar));assertFalse(Files.exists(root.resolve(name)));
        }
    }
    @Test void repeatedResumeNeverClaimsUnownedPng() throws Exception {
        String name="assets/minecraft/textures/item/test.png";
        Path jar=jar(Map.of(name,png()));Path target=directory.resolve("out/java/1.21.4/raw").resolve(name);
        Files.createDirectories(target.getParent());byte[] original={9,8,7};Files.write(target,original);
        for(int i=0;i<2;i++) {
            ExportResult result=exporter().export("1.21.4",jar,request(true,List.of()));
            assertFalse(result.complete());assertEquals("OUTPUT_EXISTS",result.entries().getFirst().code());
            assertArrayEquals(original,Files.readAllBytes(target));
        }
    }
    @Test void crashBeforeFirstCheckpointCanResumeReservedPngAndMetadata() throws Exception {
        String name="assets/minecraft/textures/item/test.png";byte[] original=png(),metadata="{\"animation\":{}}".getBytes();
        Path jar=jar(Map.of(name,original,name+".mcmeta",metadata));
        RawExporter interrupted=new RawExporter(new CancellationToken(),event->{throw new AssertionError("Process stopped before checkpoint");});
        assertThrows(AssertionError.class,()->interrupted.export("1.21.4",jar,request(true,List.of())));
        Path root=directory.resolve("out/java/1.21.4/raw");
        assertFalse(Json.MAPPER.readTree(root.resolve("manifest.json").toFile()).path("complete").asBoolean());
        var checkpoint=Json.MAPPER.readTree(root.resolve("manifest.json").toFile());
        assertEquals(name+".mcmeta",checkpoint.path("reservedAuxiliary").path("minecraft:item/test").asText());
        assertTrue(checkpoint.path("entries").get(0).path("auxiliary").isEmpty());
        ExportResult resumed=exporter().export("1.21.4",jar,request(true,List.of()));
        assertTrue(resumed.complete());assertArrayEquals(original,Files.readAllBytes(root.resolve(name)));
        assertArrayEquals(metadata,Files.readAllBytes(root.resolve(name+".mcmeta")));
        assertTrue(Json.MAPPER.readTree(root.resolve("manifest.json").toFile()).path("reservedAuxiliary").isEmpty());
    }
    @Test void cancelRecordsEveryPlannedTextureAndCanResume() throws Exception {
        Path jar=jar(Map.of("assets/minecraft/textures/item/a.png",png(),"assets/minecraft/textures/item/b.png",png(),"assets/minecraft/textures/item/c.png",png()));
        CancellationToken token=new CancellationToken();
        RawExporter interrupted=new RawExporter(token,event->{if(event.completed()==1)token.cancel();});
        ExportResult first=interrupted.export("1.21.4",jar,request(true,List.of()));
        assertFalse(first.complete());assertEquals(1,first.succeeded());assertEquals(2,first.failed());
        assertTrue(exporter().export("1.21.4",jar,request(true,List.of())).complete());
    }
    @Test void explicitOverwriteCanReplacePreviouslyRejectedPngAndSidecar() throws Exception {
        String name="assets/minecraft/textures/item/test.png";byte[] original=png(),metadata="{}".getBytes();
        Path jar=jar(Map.of(name,original,name+".mcmeta",metadata));
        Path root=directory.resolve("out/java/1.21.4/raw");Files.createDirectories(root.resolve(name).getParent());
        Files.writeString(root.resolve(name),"unowned PNG");Files.writeString(root.resolve(name+".mcmeta"),"unowned metadata");
        assertFalse(exporter().export("1.21.4",jar,request(true,List.of())).complete());
        var overwrite=new ExtractRequest("1.21.4",List.of(),true,directory.resolve("out"),true,false);
        assertTrue(exporter().export("1.21.4",jar,overwrite).complete());
        assertArrayEquals(original,Files.readAllBytes(root.resolve(name)));assertArrayEquals(metadata,Files.readAllBytes(root.resolve(name+".mcmeta")));
    }
    @Test void failFastRetainsUnprocessedIdsForRecovery() throws Exception {
        Path jar=jar(Map.of("assets/minecraft/textures/item/a.png",new byte[]{1},"assets/minecraft/textures/item/b.png",png()));
        var failFast=new ExtractRequest("1.21.4",List.of(),true,directory.resolve("out"),false,true);
        ExportResult first=exporter().export("1.21.4",jar,failFast);
        assertFalse(first.complete());assertEquals(2,first.failed());assertEquals("NOT_EXTRACTED",first.entries().get(1).code());
        ExportResult continued=exporter().export("1.21.4",jar,request(true,List.of()));
        assertEquals(1,continued.succeeded());assertEquals(1,continued.failed());
    }
    @Test void interruptedExplicitOverwriteOwnsThePairOnResume() throws Exception {
        String name="assets/minecraft/textures/item/test.png";Path jar=jar(Map.of(name,png(),name+".mcmeta","{}".getBytes()));
        Path root=directory.resolve("out/java/1.21.4/raw");Files.createDirectories(root.resolve(name).getParent());
        Files.writeString(root.resolve(name),"unowned");Files.writeString(root.resolve(name+".mcmeta"),"unowned");
        assertFalse(exporter().export("1.21.4",jar,request(true,List.of())).complete());
        RawExporter interrupted=new RawExporter(new CancellationToken(),event->{throw new AssertionError("Stopped before checkpoint");});
        var overwrite=new ExtractRequest("1.21.4",List.of(),true,directory.resolve("out"),true,false);
        assertThrows(AssertionError.class,()->interrupted.export("1.21.4",jar,overwrite));
        assertTrue(exporter().export("1.21.4",jar,request(true,List.of())).complete());
    }
    @Test void symlinkAndTraversalCannotEscapeOutput() throws Exception {
        assertThrows(IllegalArgumentException.class,()->ResourceId.parse("../outside"));
        assertThrows(IllegalArgumentException.class,()->ResourceId.parse("..:outside"));
        Path outside=directory.resolve("outside");Files.createDirectory(outside);
        Path root=directory.resolve("output");Files.createDirectory(root);Files.createSymbolicLink(root.resolve("assets"),outside);
        assertThrows(McIconException.class,()->FilesSupport.safeResolve(root,"assets/minecraft/textures/item/test.png"));
        assertThrows(McIconException.class,()->FilesSupport.safeResolve(root,"../outside/file"));
    }
    @Test void changedInputIsRejectedWithoutExplicitOverwrite() throws Exception {
        Path jar=jar(Map.of("assets/minecraft/textures/item/test.png",png()));
        exporter().export("1.21.4",jar,request(true,List.of()));
        jar=jar(Map.of("assets/minecraft/textures/item/other.png",png()));
        Path changed=jar;
        McIconException error=assertThrows(McIconException.class,()->exporter().export("1.21.4",changed,request(true,List.of())));
        assertEquals("OUTPUT_MISMATCH",error.code());
    }
    @Test void validSignatureWithCorruptChunksIsNotExported() throws Exception {
        byte[] broken=png();broken[broken.length-1]^=1;
        Path jar=jar(Map.of("assets/minecraft/textures/item/test.png",broken));
        ExportResult result=exporter().export("1.21.4",jar,request(true,List.of()));
        assertEquals(1,result.failed());assertFalse(result.complete());
        assertFalse(Files.exists(directory.resolve("out/java/1.21.4/raw/assets/minecraft/textures/item/test.png")));
    }
    @Test void explicitEntityTextureAndMissingOptionalMetadataWork() throws Exception {
        byte[] original=png();Path jar=jar(Map.of("assets/minecraft/textures/entity/chest/normal.png",original));
        ExportResult result=exporter().export("1.21.4",jar,request(false,List.of(ResourceId.parse("entity/chest/normal"))));
        assertEquals(1,result.succeeded());assertTrue(result.entries().getFirst().auxiliary().isEmpty());
        McIconException missing=assertThrows(McIconException.class,()->exporter().export("1.21.4",jar,request(false,List.of(ResourceId.parse("item/missing")))));
        assertEquals("TEXTURE_NOT_FOUND",missing.code());
    }
    @Test void brokenJarAndBrokenMetadataAreRejectedWithoutNormalPng() throws Exception {
        Path broken=directory.resolve("broken.jar");Files.writeString(broken,"not a ZIP");
        assertThrows(java.util.zip.ZipException.class,()->exporter().export("1.21.4",broken,request(true,List.of())));
        String name="assets/minecraft/textures/item/test.png";
        Path jar=jar(Map.of(name,png(),name+".mcmeta","{bad".getBytes()));
        ExportResult result=exporter().export("1.21.4",jar,request(true,List.of()));
        assertFalse(result.complete());assertEquals(1,result.failed());
        assertFalse(Files.exists(directory.resolve("out/java/1.21.4/raw").resolve(name)));
    }
}
