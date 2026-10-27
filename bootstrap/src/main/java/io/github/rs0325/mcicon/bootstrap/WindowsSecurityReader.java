package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** package 固定の reader から同一 handle の観測を取得する。親の安全判定や所有変更は行わない。 */
final class WindowsSecurityReader {
    static final class ObservationFailure extends IOException {
        final JsonNode observation;
        final int nativeExit;
        ObservationFailure(JsonNode observation,int nativeExit){super("readerの観測がunknownです: "+observation.path("errorStage").asText());this.observation=observation;this.nativeExit=nativeExit;}
    }
    private final Path executable;
    private final LongSupplier clock;
    /** installer側で実体・非reparse・他標準userによる書換え拒否を確認済みのprefixを受ける。ここでは全reparseを証明しない。 */
    WindowsSecurityReader(Path installed) throws IOException {this(installed,System::nanoTime);}
    WindowsSecurityReader(Path installed,LongSupplier clock) throws IOException {
        this.clock=clock;
        Path support=installed.toAbsolutePath().normalize().resolve("support");
        executable=support.resolve("windows-security-reader.exe");
        for(Path path=executable;path!=null;path=path.getParent())if(Files.isSymbolicLink(path))throw Seed.invalid("reader pathにlinkがあります");
        JsonNode definition=Seed.json(Seed.read(support.resolve("windows-security-reader.json"),65536));
        Seed.fields(definition,"schemaVersion","target","sourceSha256","buildSha256","executable","licenses");Seed.constant(definition,"schemaVersion",1);Seed.exact(definition,"target","x86_64-w64-mingw32");Seed.digest(definition,"sourceSha256");Seed.digest(definition,"buildSha256");
        JsonNode artifact=definition.get("executable");Seed.fields(artifact,"bytes","sha256");long bytes=Seed.positive(artifact,"bytes");Seed.regular(executable);
        if(bytes>16777216||Files.size(executable)!=bytes||!Seed.hash(executable).equals(Seed.digest(artifact,"sha256")))throw Seed.invalid("reader package hashが不一致です");
        Path build=support.resolve("windows-security-reader-build.json");Seed.regular(build);if(Files.size(build)>65536||!Seed.hash(build).equals(Seed.digest(definition,"buildSha256")))throw Seed.invalid("reader build記録が不一致です");
        JsonNode licenses=definition.get("licenses");if(!licenses.isArray()||licenses.isEmpty()||licenses.size()>32)throw Seed.invalid("reader license一覧が不正です");
        for(JsonNode row:licenses){Seed.fields(row,"path","bytes","sha256");String name=Seed.text(row,"path");Seed.relative(name);Path path=Seed.resolve(support,name);Seed.regular(path);if(Files.size(path)!=Seed.positive(row,"bytes")||Files.size(path)>4194304||!Seed.hash(path).equals(Seed.digest(row,"sha256")))throw Seed.invalid("reader licenseが不一致です");}
    }
    JsonNode read(List<Path> paths) throws IOException {return read(paths,false,Long.MAX_VALUE);}
    /** drive rootから親directoryを含むchainの最後を通常fileとして観測する。本文は読まない。 */
    JsonNode readFile(List<Path> paths) throws IOException {return read(paths,true,Long.MAX_VALUE);}
    JsonNode read(List<Path> paths,long deadline) throws IOException {return read(paths,false,deadline);}
    JsonNode readFile(List<Path> paths,long deadline) throws IOException {return read(paths,true,deadline);}
    private JsonNode read(List<Path> paths,boolean terminalFile,long deadline) throws IOException {
        if(paths.isEmpty()||paths.size()>64||terminalFile&&paths.size()<2)throw Seed.invalid("reader path数が範囲外です");
        var request=Seed.JSON.createObjectNode().put("schemaVersion",3).put("terminalType",terminalFile?"regular-file":"directory");var names=request.putArray("paths");for(Path path:paths)names.add(path.toString());byte[] input=Seed.JSON.writeValueAsBytes(request);if(input.length>65536)throw Seed.invalid("reader入力が上限外です");
        long end=Math.min(deadline,clock.getAsLong()+TimeUnit.SECONDS.toNanos(5)),cleanup=end+TimeUnit.SECONDS.toNanos(2);checkDeadline(end);
        Process process=new ProcessBuilder(executable.toString()).start();ProcessHandle child=process.toHandle();
        AtomicReference<IOException> failure=new AtomicReference<>();ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        List<Thread> tasks=new ArrayList<>();IOException original=null;
        try {
            checkDeadline(end);
            tasks.add(Thread.ofVirtual().start(()->{try(OutputStream stream=process.getOutputStream()){stream.write(input);stream.flush();}catch(IOException error){failure.compareAndSet(null,error);}}));
            tasks.add(Thread.ofVirtual().start(()->drain(process.getInputStream(),stdout,1048576,failure)));
            tasks.add(Thread.ofVirtual().start(()->drain(process.getErrorStream(),stderr,65536,failure)));
            while(process.isAlive()||tasks.stream().anyMatch(Thread::isAlive)) {
                if(failure.get()!=null)throw failure.get();long left=end-clock.getAsLong();if(left<=0)throw new IOException("readerの送信/待機/読取が期限を超えました");
                process.waitFor(Math.min(left,TimeUnit.MILLISECONDS.toNanos(10)),TimeUnit.NANOSECONDS);
                if(!process.isAlive())for(Thread task:tasks)join(task,Math.min(end,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(10)));
            }
            if(failure.get()!=null)throw failure.get();JsonNode value=Seed.json(stdout.toByteArray());
            if(process.exitValue()!=0||!value.path("status").asText().equals("observed"))throw new ObservationFailure(value,process.exitValue());
            validate(value,paths,terminalFile);checkDeadline(end);return value;
        }catch(InterruptedException error){Thread.currentThread().interrupt();original=new InterruptedIOException("readerを中断しました");original.initCause(error);throw original;}
        catch(IOException error){original=error;throw error;}
        finally {
            boolean interrupted=Thread.interrupted();
            try {
                if(process.isAlive()){child.destroy();process.waitFor(Math.max(0,Math.min(cleanup-System.nanoTime(),TimeUnit.SECONDS.toNanos(1))),TimeUnit.NANOSECONDS);}
                if(process.isAlive()){child.destroyForcibly();process.waitFor(Math.max(0,cleanup-System.nanoTime()),TimeUnit.NANOSECONDS);}
                for(Thread task:tasks)join(task,cleanup);
                if(process.isAlive()||tasks.stream().anyMatch(Thread::isAlive)) {
                    IOException unknown=new IOException("readerまたはpipeの回収がunknownです");if(original!=null)original.addSuppressed(unknown);else throw unknown;
                }
            }catch(InterruptedException error){interrupted=true;IOException unknown=new IOException("readerの回収を確認できません",error);if(original!=null)original.addSuppressed(unknown);else throw unknown;}
            finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private void checkDeadline(long end) throws IOException {if(clock.getAsLong()>=end)throw new IOException("readerの起動/成功確認が期限を超えました");}
    private static void join(Thread task,long end) throws InterruptedException {long left=end-System.nanoTime();if(task.isAlive()&&left>0)task.join(java.time.Duration.ofNanos(left));}
    private static void drain(InputStream input,ByteArrayOutputStream output,int maximum,AtomicReference<IOException> failure) {
        try(input){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(count>maximum-output.size())throw new IOException("readerの出力が上限を超えました");output.write(buffer,0,count);}}
        catch(IOException error){failure.compareAndSet(null,error);}
    }
    private static void validate(JsonNode value,List<Path> paths,boolean terminalFile) throws IOException {
        Seed.fields(value,"schemaVersion","status","tokenUserSid","tokenDefaultOwnerSid","rows");Seed.constant(value,"schemaVersion",3);sid(value,"tokenUserSid");sid(value,"tokenDefaultOwnerSid");JsonNode rows=value.get("rows");if(!rows.isArray()||rows.size()!=paths.size())throw Seed.invalid("reader行数が不一致です");
        for(int i=0;i<rows.size();i++) {
            JsonNode row=rows.get(i);Seed.fields(row,"index","path","parentIndex","relativeName","parentFileId","parentVolumeSerial","volumeSerial","fileId","nodeType","linkCount","fileAttributes","reparseTag","ownerSid","controlFlags","daclPresent","daclNull","aces");
            if(!row.path("index").isIntegralNumber()||!row.path("index").canConvertToInt()||row.path("index").intValue()!=i||!paths.get(i).toString().equals(Seed.text(row,"path")))throw Seed.invalid("reader path対応が不一致です");
            sid(row,"ownerSid");if(!Seed.text(row,"fileId").matches("[0-9a-f]{32}")||!Seed.text(row,"volumeSerial").matches("[0-9]{1,20}"))throw Seed.invalid("reader identityが不正です");
            for(String name:List.of("fileAttributes","reparseTag","controlFlags"))uint(row,name);
            for(String name:List.of("daclPresent","daclNull"))if(!row.path(name).isBoolean())throw Seed.invalid("reader DACL状態が不正です");
            if(i==0){for(String name:List.of("parentIndex","relativeName","parentFileId","parentVolumeSerial"))if(!row.path(name).isNull())throw Seed.invalid("reader root対応が不正です");}
            else if(!row.path("parentIndex").isIntegralNumber()||row.path("parentIndex").intValue()!=i-1||!row.path("parentFileId").equals(rows.get(i-1).path("fileId"))||!row.path("parentVolumeSerial").equals(rows.get(i-1).path("volumeSerial")))throw Seed.invalid("reader parent対応が不正です");
            if(i>0){String previous=paths.get(i-1).toString(),prefix=previous.endsWith("\\")?previous:previous+"\\",child=paths.get(i).toString();if(child.length()<=prefix.length()||!child.regionMatches(true,0,prefix,0,prefix.length())||!Seed.text(row,"relativeName").equals(child.substring(prefix.length())))throw Seed.invalid("reader relative componentが不一致です");}
            boolean file=terminalFile&&i==paths.size()-1;Seed.exact(row,"nodeType",file?"regular-file":"directory");
            if(file)Seed.constant(row,"linkCount",1);else if(!row.path("linkCount").isNull())throw Seed.invalid("directoryにfile linkCountがあります");
            if(((row.path("fileAttributes").longValue()&16)!=0)==file||(row.path("fileAttributes").longValue()&1024)!=0||row.path("reparseTag").longValue()!=0)throw Seed.invalid("readerの型/reparse観測が不正です");
            JsonNode aces=row.get("aces");if(!aces.isArray()||aces.size()>256)throw Seed.invalid("reader ACE数が上限外です");for(JsonNode ace:aces){uint(ace,"type");uint(ace,"flags");uint(ace,"bytes");if(ace.path("type").longValue()>255||ace.path("flags").longValue()>255||ace.path("bytes").longValue()<4||ace.path("bytes").longValue()>65535)throw Seed.invalid("reader ACE headerが不正です");if(ace.path("unknown").isBoolean()&&ace.path("unknown").booleanValue()){Seed.fields(ace,"type","flags","bytes","unknown");if(ace.path("type").intValue()<2)throw Seed.invalid("reader未知ACE型が不正です");}else {Seed.fields(ace,"type","flags","bytes","rawMask","sid");if(ace.path("type").intValue()>1)throw Seed.invalid("reader ACE型が不正です");uint(ace,"rawMask");sid(ace,"sid");}}
        }
    }
    private static void uint(JsonNode value,String name) throws IOException {JsonNode n=value.get(name);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0||n.longValue()>0xffffffffL)throw Seed.invalid("reader uint32が不正です");}
    private static void sid(JsonNode value,String name) throws IOException {if(!Seed.text(value,name).matches("S-1-[0-9]+(?:-[0-9]+){1,15}"))throw Seed.invalid("reader SIDが不正です");}
}
