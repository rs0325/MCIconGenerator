package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.McIconException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class FilesSupport {
    private FilesSupport() {}
    public static String hash(byte[] data) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
        catch(NoSuchAlgorithmException e) {throw new AssertionError(e);}
    }
    public static String hash(Path path, String algorithm) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance(algorithm);
            try(InputStream in=Files.newInputStream(path)) {
                byte[] buffer=new byte[65536]; int count;
                while((count=in.read(buffer))>=0) digest.update(buffer,0,count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static Path safeResolve(Path root,String relative) throws IOException {
        root=root.toAbsolutePath().normalize();
        Path fragment=Path.of(relative);
        Path target=root.resolve(fragment).normalize();
        if(fragment.isAbsolute() || !target.startsWith(root) || relative.contains("\\"))
            throw new McIconException("UNSAFE_PATH","Output path escapes its root",2);
        for(Path current=target;current!=null&&current.startsWith(root);current=current.getParent())
            if(Files.isSymbolicLink(current))throw new McIconException("UNSAFE_PATH","Symlink in output path",2);
        return target;
    }
    public static void atomicWrite(Path target,byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),".mcicon-",".part");
        try {
            Files.write(temp,data);
            try {Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e) {Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
}
