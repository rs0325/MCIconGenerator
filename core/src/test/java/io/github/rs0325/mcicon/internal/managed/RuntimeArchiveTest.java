package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeArchiveTest {
    @TempDir Path root;
    RuntimeStore store() throws Exception {return new RuntimeStore(root,false,new CancellationToken(),Duration.ofSeconds(10),(u,o,t)->{throw new AssertionError();});}
    static byte[] zip(String name,boolean link,boolean duplicate) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if(!link&&!duplicate) {
            try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));zip.write(new byte[]{1,2,3});zip.closeEntry();
            }
            return bytes.toByteArray();
        }
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(bytes)) {
            for(int i=0;i<(duplicate?2:1);i++) {
                ZipArchiveEntry entry=new ZipArchiveEntry(name);entry.setUnixMode(link?0120777:0100755);zip.putArchiveEntry(entry);zip.write(new byte[]{1,2,3});zip.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
    static byte[] tar(String name,byte kind,String format) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(OutputStream compressed=format.equals("tar.gz")?new GZIPOutputStream(bytes):new XZCompressorOutputStream(bytes);
            TarArchiveOutputStream tar=new TarArchiveOutputStream(compressed)) {
            TarArchiveEntry entry=new TarArchiveEntry(name,kind);entry.setMode(0755);
            if(kind==TarConstants.LF_NORMAL)entry.setSize(3);else entry.setLinkName("outside");
            tar.putArchiveEntry(entry);if(kind==TarConstants.LF_NORMAL)tar.write(new byte[]{1,2,3});tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
    RuntimeDescriptor.Artifact artifact(byte[] bytes,String format,long limit) {
        var a=RuntimeStoreTest.artifact(bytes);return new RuntimeDescriptor.Artifact(a.id(),a.role(),a.url(),a.bytes(),a.sha256(),limit,format,a.inventoryPath(),a.inventorySha256(),a.licenseFiles(),a.noticeFiles(),a.sourceArchives());
    }
    void extract(byte[] bytes,String format,long limit) throws Exception {
        Path archive=Files.createTempFile(root,"archive-",".bin");Files.write(archive,bytes);
        Path target=root.resolve("extract-"+UUID.randomUUID());RuntimeArchive.extract(archive,target,artifact(bytes,format,limit),store());
    }
    @Test void supportedHelperFormatsExtract() throws Exception {
        extract(zip("bin/helper",false,false),"zip",3);
        extract(tar("bin/helper",TarConstants.LF_NORMAL,"tar.gz"),"tar.gz",16384);
        extract(tar("bin/helper",TarConstants.LF_NORMAL,"tar.xz"),"tar.xz",16384);
    }
    @Test void rejectsTraversalLinksDuplicatesAndExpandedLimit() throws Exception {
        for(String name:List.of("../escape","/absolute","C:/drive","bin\\helper","bin//helper","bin/./helper"))RuntimeDescriptorTest.assertInvalid(()->extract(zip(name,false,false),"zip",100));
        RuntimeDescriptorTest.assertInvalid(()->extract(zip("bin/helper",true,false),"zip",100));
        RuntimeDescriptorTest.assertInvalid(()->extract(zip("bin/helper",false,true),"zip",100));
        RuntimeDescriptorTest.assertInvalid(()->extract(zip("bin/helper",false,false),"zip",2));
        for(String format:List.of("tar.gz","tar.xz")) {
            RuntimeDescriptorTest.assertInvalid(()->extract(tar("bin/helper",TarConstants.LF_SYMLINK,format),format,16384));
            RuntimeDescriptorTest.assertInvalid(()->extract(tar("bin/helper",TarConstants.LF_LINK,format),format,16384));
            RuntimeDescriptorTest.assertInvalid(()->extract(tar("../escape",TarConstants.LF_NORMAL,format),format,16384));
            RuntimeDescriptorTest.assertInvalid(()->extract(tar("bin/helper",TarConstants.LF_NORMAL,format),format,2));
        }
    }
}
