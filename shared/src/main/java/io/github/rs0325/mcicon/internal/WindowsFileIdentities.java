package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 認証済みnativeのread-only batchを、入力・応答・回収を含む同一絶対期限で読む。 */
public final class WindowsFileIdentities implements LeaseTrust.IdentityReader, LeaseTrust.BatchIdentityReader {
    private final Path executable;
    private final String sha256;

    public WindowsFileIdentities(Path executable,String sha256) {
        this.executable=Objects.requireNonNull(executable);
        if(!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("native pinが不正です");
        this.sha256=sha256;
    }

    @Override public String read(Path file) throws IOException {
        return readAll(List.of(file),Path.of(System.getProperty("java.io.tmpdir")),
                System.nanoTime()+Duration.ofSeconds(2).toNanos()).get(file);
    }

    @Override public Map<Path,String> readAll(List<Path> files,Path scratch,long end) throws IOException {
        check(end);
        if(files.isEmpty()||files.size()>256||new HashSet<>(files).size()!=files.size())throw new IOException("identity batch件数が不正です");
        ObjectNode request=LeaseSnapshot.JSON.createObjectNode().put("schemaVersion",1);
        var items=request.putArray("items");
        List<String> kinds=new ArrayList<>();
        for(Path file:files) {
            check(end);
            if(!file.isAbsolute()||!file.toRealPath().equals(file))throw new IOException("identity batch pathにlinkがあります");
            String kind=Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS)?"directory":"file";
            kinds.add(kind);items.addObject().put("path",file.toString()).put("kind",kind);
        }
        byte[] input=LeaseSnapshot.JSON.writeValueAsBytes(request);
        if(input.length>4*1024*1024)throw new IOException("identity batch入力が上限を超えました");
        verifyReader(end);check(end);
        if(!scratch.toRealPath().equals(scratch))throw new IOException("identity batch作業directoryにlinkがあります");
        Path directory=Files.createTempDirectory(scratch,"identity-batch-");
        Path in=directory.resolve("input.json"),out=directory.resolve("output.json");
        Process process=null;
        IOException failure=null;
        try {
            try(FileChannel channel=FileChannel.open(in,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(input);
                while(buffer.hasRemaining()){check(end);channel.write(buffer);}
            }
            check(end);
            // 回収もendまでに終える。pipeへ書くthreadやdrain threadを作らない。
            long runEnd=end-Math.min(TimeUnit.MILLISECONDS.toNanos(50),(end-System.nanoTime())/4);
            check(runEnd);
            process=new ProcessBuilder(executable.toString(),"file-identities-v1")
                    .redirectInput(in.toFile()).redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            check(runEnd);
            if(!process.waitFor(Math.max(0,runEnd-System.nanoTime()),TimeUnit.NANOSECONDS))throw timeout();
            check(runEnd);
            if(process.exitValue()!=0)throw new IOException("identity batch nativeが失敗しました");
            JsonNode response=LeaseSnapshot.read(LeaseSnapshot.readFile(out,65536));
            LeaseSnapshot.fields(response,"schemaVersion","items");
            if(LeaseSnapshot.integer(response,"schemaVersion",1)!=1||!response.path("items").isArray()||response.path("items").size()!=files.size())throw new IOException("identity batch応答件数が一致しません");
            Map<Path,String> result=new LinkedHashMap<>();
            for(int index=0;index<files.size();index++) {
                JsonNode item=response.path("items").get(index);
                LeaseSnapshot.fields(item,"kind","volumeSerial","fileId");
                String volume=LeaseSnapshot.text(item,"volumeSerial",20),id=LeaseSnapshot.text(item,"fileId",32);
                if(!kinds.get(index).equals(LeaseSnapshot.text(item,"kind",16))||!volume.matches("[0-9]{1,20}")||!id.matches("[0-9a-f]{32}"))throw new IOException("identity batch応答が不正です");
                result.put(files.get(index),"windows-file-id-v1:"+volume+":"+id);
            }
            if(new HashSet<>(result.values()).size()!=result.size())throw new IOException("identity batch応答に同実体があります");
            verifyReader(runEnd);check(runEnd);
            return Collections.unmodifiableMap(result);
        }catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();failure=new InterruptedIOException("identity batch待機を中断しました");throw failure;
        }catch(IOException original) {
            failure=original;throw original;
        }finally {
            boolean interrupted=Thread.interrupted();
            try {
                if(process!=null&&process.isAlive()) {
                    process.destroyForcibly();
                    while(process.isAlive()) {
                        if(System.nanoTime()>=end)throw new IOException("identity batch nativeの終端が不明です");
                        try {process.waitFor(Math.max(0,end-System.nanoTime()),TimeUnit.NANOSECONDS);}
                        catch(InterruptedException again){interrupted=true;}
                    }
                }
                if(process!=null){process.getOutputStream().close();process.getInputStream().close();process.getErrorStream().close();}
            }catch(IOException cleanup) {
                if(failure==null)throw cleanup;
                failure.addSuppressed(cleanup);
            }finally {
                try {
                    delete(in,end);delete(out,end);delete(directory,end);
                    checkTime(end);
                }catch(IOException cleanup) {
                    if(failure==null)throw cleanup;
                    failure.addSuppressed(cleanup);
                }finally {if(interrupted)Thread.currentThread().interrupt();}
            }
        }
    }

    private static void delete(Path file,long end) throws IOException {
        while(true) {
            try {Files.deleteIfExists(file);return;}
            catch(FileSystemException busy) {
                if(System.nanoTime()>=end)throw new IOException("identity batchの一時fileを回収できません",busy);
                java.util.concurrent.locks.LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(1),Math.max(0,end-System.nanoTime())));
            }
        }
    }

    private void verifyReader(long end) throws IOException {
        check(end);
        if(!sha256.equals(LeaseSnapshot.fileDigest(executable,Files.size(executable),end)))throw new IOException("identity batch readerのpinが一致しません");
    }
    static void check(long end) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("identity batchを中断しました");
        checkTime(end);
    }
    private static void checkTime(long end) throws IOException {if(System.nanoTime()>=end)throw timeout();}
    private static IOException timeout(){return new IOException("identity batchの絶対期限を超えました");}
}
