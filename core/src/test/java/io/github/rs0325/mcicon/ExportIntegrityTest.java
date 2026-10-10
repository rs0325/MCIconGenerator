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
        ExportResult result=exporter().export("1.21.4",jar,request(true,List.of()));
        assertFalse(result.complete());assertEquals("owned by another tool",Files.readString(sidecar));assertFalse(Files.exists(root.resolve(name)));
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
