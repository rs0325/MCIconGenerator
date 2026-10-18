package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

final class MacFiles {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private MacFiles() {}
    static JsonNode json(Path path) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>1024*1024)throw new IOException("所有記録を安全に読めません: "+path.getFileName());
        JsonNode value=JSON.readTree(Files.readAllBytes(path));if(value==null||!value.isObject())throw new IOException("所有記録はobjectが必要です");return value;
    }
    static String hash(Path path) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(path)){byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("検証が中断されました");digest.update(bytes,0,count);}}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException failure){throw new AssertionError(failure);}
    }
    static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException failure){throw new AssertionError(failure);}}
    static void write(Path file,JsonNode value) throws IOException {write(file,JSON.writeValueAsBytes(value));}
    static void write(Path file,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(file.getParent(),"state-",".json");
        try {Files.write(temporary,bytes);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temporary);}
    }
    static void privateDirectory(Path path) throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw new IOException("専用directoryの所有位置が一致しません");
    }
    static String quoted(String value) throws IOException {return JSON.writeValueAsString(value);}
}
