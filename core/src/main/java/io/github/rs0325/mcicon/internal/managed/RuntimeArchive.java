package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.internal.FilesSupport;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** host helperだけを展開する。guest rootfsとrenderer archiveはOSのimport/loadへ元bytesで渡す。 */
final class RuntimeArchive {
    static void extract(Path archive,Path destination,RuntimeDescriptor.Artifact artifact,RuntimeStore store) throws IOException {
        if(!artifact.role().equals("host-helper"))throw new IllegalArgumentException("host helper以外はhostへ展開しません");
        Files.createDirectory(destination);RuntimeStore.requireSpace(destination,artifact.unpackedBytes());
        Set<String> names=new HashSet<>();long[] total={0};
        if(artifact.format().equals("zip")) {
            try(ZipFile zip=ZipFile.builder().setPath(archive).get()) {
                var entries=zip.getEntries();
                while(entries.hasMoreElements()) {
                    store.check();ZipArchiveEntry entry=entries.nextElement();int kind=entry.getUnixMode()&0170000;
                    if(entry.isUnixSymlink()||kind!=0&&kind!=0100000&&kind!=0040000||!zip.canReadEntryData(entry))throw RuntimeDescriptor.invalid("helper ZIPにlinkまたは未対応entryがあります");
                    String rawName=new String(entry.getRawName(),java.nio.charset.StandardCharsets.UTF_8);
                    String rawPath=entry.isDirectory()&&rawName.endsWith("/")?rawName.substring(0,rawName.length()-1):rawName;RuntimeDescriptor.relative(rawPath);
                    Path target=entry(destination,entry.getName(),entry.isDirectory(),names);
                    if(entry.isDirectory())Files.createDirectories(target);
                    else try(InputStream input=zip.getInputStream(entry)){copy(input,target,entry.getSize(),artifact.unpackedBytes(),total,store);executable(target,entry.getUnixMode());}
                }
            }
        }else if(artifact.format().equals("tar.gz")||artifact.format().equals("tar.xz")) {
            try(InputStream file=new BufferedInputStream(Files.newInputStream(archive));
                InputStream decompressed=artifact.format().equals("tar.gz")?new GzipCompressorInputStream(file):new XZCompressorInputStream(file,true,64*1024);
                InputStream limited=limit(decompressed,artifact.unpackedBytes(),store);
                TarArchiveInputStream tar=new TarArchiveInputStream(limited)) {
                TarArchiveEntry entry;
                while((entry=tar.getNextEntry())!=null) {
                    store.check();
                    if(!tar.canReadEntryData(entry)||(!entry.isFile()&&!entry.isDirectory())||entry.isSymbolicLink()||entry.isLink()||entry.isSparse())throw RuntimeDescriptor.invalid("helper TARにlinkまたは特殊entryがあります");
                    Path target=entry(destination,entry.getName(),entry.isDirectory(),names);
                    if(entry.isDirectory())Files.createDirectories(target);else {copy(tar,target,entry.getSize(),artifact.unpackedBytes(),total,store);executable(target,entry.getMode());}
                }
            }
        }else throw RuntimeDescriptor.invalid("helperのarchive形式が未対応です");
    }
    private static void executable(Path target,int mode) throws IOException {
        if((mode&0111)!=0&&!target.toFile().setExecutable(true,true))throw new IOException("helper構成fileの実行権限を設定できません");
    }
    private static Path entry(Path root,String name,boolean directory,Set<String> names) throws IOException {
        if(directory&&name.endsWith("/"))name=name.substring(0,name.length()-1);
        RuntimeDescriptor.relative(name);if(name.length()>512||!names.add(name))throw RuntimeDescriptor.invalid("archive entryが重複または長すぎます");
        return FilesSupport.safeResolve(root,name);
    }
    private static void copy(InputStream input,Path target,long size,long maximum,long[] total,RuntimeStore store) throws IOException {
        if(size<0||size>maximum-total[0])throw RuntimeDescriptor.invalid("archiveの展開上限を超えています");
        Files.createDirectories(target.getParent());long written=0;
        try(OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
            byte[] buffer=new byte[65536];int count;
            while((count=input.read(buffer))!=-1) {
                store.check();if(count>size-written||count>maximum-total[0])throw RuntimeDescriptor.invalid("archiveの展開上限を超えています");
                output.write(buffer,0,count);written+=count;total[0]+=count;
            }
        }
        if(written!=size)throw RuntimeDescriptor.invalid("archive entryのsizeが一致しません");
    }
    private static InputStream limit(InputStream input,long maximum,RuntimeStore store) {
        return new FilterInputStream(input) {
            long remaining=maximum;
            @Override public int read() throws IOException {byte[] one=new byte[1];int count=read(one,0,1);return count<0?-1:one[0]&255;}
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                store.check();int count=super.read(bytes,offset,(int)Math.min(length,Math.min(Integer.MAX_VALUE,remaining==Long.MAX_VALUE?remaining:remaining+1)));
                if(count>remaining)throw RuntimeDescriptor.invalid("TAR streamの展開上限を超えています");if(count>0)remaining-=count;return count;
            }
        };
    }
    private RuntimeArchive() {}
}
