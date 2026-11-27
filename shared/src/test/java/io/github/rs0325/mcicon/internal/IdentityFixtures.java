package io.github.rs0325.mcicon.internal;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Windowsの置換負例も実native file IDで検査する。製品の信頼入力へは接続しない。 */
public final class IdentityFixtures {
    private IdentityFixtures() {}
    public static LeaseTrust trust(TrustSet trust) throws IOException {
        if(!System.getProperty("os.name").startsWith("Windows"))return LeaseTrust.verified(trust);
        String executable=System.getProperty("mcicon.testIdentityReader"),expected=System.getProperty("mcicon.testIdentityReaderSha256");
        if(executable==null||expected==null)throw new IOException("Windows試験には明示したbuild済みidentity readerが必要です");
        Path reader=Path.of(executable);if(!LeaseSnapshot.fileDigest(reader).equals(expected))throw new IOException("試験identity readerのpinが一致しません");
        return LeaseTrust.verified(trust,path->{
            Process process=new ProcessBuilder(reader.toString(),"file-identity",path.toString()).start();
            try {
                if(!process.waitFor(2,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("試験identity readerが失敗しました");
                var value=LeaseSnapshot.read(process.getInputStream().readNBytes(4096));LeaseSnapshot.fields(value,"schemaVersion","volumeSerial","fileId");
                if(value.path("schemaVersion").asInt()!=1||!value.path("volumeSerial").asText().matches("[0-9]{1,20}")||!value.path("fileId").asText().matches("[0-9a-f]{32}"))throw new IOException("試験identity応答が不正です");
                return "windows-file-id-v1:"+value.path("volumeSerial").asText()+":"+value.path("fileId").asText();
            }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("試験identity reader待機を中断しました");}
            finally{if(process.isAlive()){process.destroyForcibly();try{if(!process.waitFor(1,TimeUnit.SECONDS))throw new IOException("試験identity readerを回収できません");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("試験identity回収を中断しました");}}process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();}
        });
    }
}
