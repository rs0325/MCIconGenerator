package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** package所有seedからtoolを起動する。更新・GC・OS helperの停止は行わない。 */
final class Bootstrap {
    private final Path installed,root;
    private final Seed.Host host;
    private final PublicKey key;
    private final JsonNode info;
    private final UserPrincipal owner;
    Bootstrap(Path installed,Path root,Seed.Host host) throws IOException {
        this.installed=installed.toAbsolutePath().normalize();this.root=root.toAbsolutePath().normalize();this.host=host;
        canonical(this.installed);info=Seed.json(Seed.read(this.installed.resolve("bootstrap-package.json"),65536));Seed.fields(info,"schemaVersion","bootstrapVersion","bootstrapProtocol","owner","host");Seed.constant(info,"schemaVersion",1);Seed.constant(info,"bootstrapProtocol",2);Seed.version(info,"bootstrapVersion");Seed.choice(info,"owner","homebrew","winget","installer","archive");
        if(!info.path("host").equals(Seed.JSON.valueToTree(host)))throw Seed.invalid("package hostが一致しません");
        try {key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Seed.read(this.installed.resolve("trust.der"),1024)));}
        catch(GeneralSecurityException failure){throw new IOException("packageの固定trustが未設定/不正です",failure);}
        owner=FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }
    record StateLock(FileChannel channel,FileLock lock) implements AutoCloseable {public void close() throws IOException {try{lock.close();}finally{channel.close();}}}
    void directory(Path path) throws IOException {
        canonical(path);Path ancestor=path;while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();
        boolean posix=Files.getFileStore(ancestor).supportsFileAttributeView("posix");
        if(posix)Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));else Files.createDirectories(path);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("bootstrap directoryの所有が不明です");
        if(Files.getFileStore(path).supportsFileAttributeView("posix")){var mode=PosixFilePermissions.fromString("rwx------");if(!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(mode))throw Seed.invalid("private directoryのmodeが不一致です");}
    }
    private void file(Path path) throws IOException {Seed.regular(path);canonical(path);if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("bootstrap fileの所有が不明です");}
    private static void canonical(Path path) throws IOException {for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw Seed.invalid("bootstrap pathにlinkがあります");}
    private FileChannel create(Path path) throws IOException {
        if(Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))return FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
    }
    private StateLock lock(long deadline) throws IOException {
        directory(root);Path path=root.resolve("state.lock");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))try(FileChannel ignored=create(path)){}catch(FileAlreadyExistsException raced){}
        file(path);FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);boolean acquired=false;
        try {while(true){Seed.check(deadline);FileLock lock=null;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock!=null){acquired=true;return new StateLock(channel,lock);}try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("bootstrap排他を中断しました");}}}
        finally {if(!acquired)channel.close();}
    }
    private void atomic(Path path,JsonNode value) throws IOException {
        Path temp=root.resolve("staging/state-"+UUID.randomUUID()+".json");try(FileChannel channel=create(temp)){write(channel,value);}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void write(FileChannel channel,JsonNode value) throws IOException {byte[] bytes=Seed.JSON.writeValueAsBytes(value);channel.truncate(0);channel.position(0);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
    private void roots() throws IOException {for(String name:List.of("staging","tools","archives","manifests","runs"))directory(root.resolve(name));}
    private JsonNode state() throws IOException {
        Path path=root.resolve("active.json");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;file(path);JsonNode value=Seed.json(Seed.read(path,65536));Seed.fields(value,"schemaVersion","channel","host","active","pending","previous","highestAcceptedSequence");Seed.constant(value,"schemaVersion",1);Seed.exact(value,"channel","stable");Seed.positive(value,"highestAcceptedSequence");
        if(!value.path("host").equals(Seed.JSON.valueToTree(host))||!value.path("pending").isNull()||!value.path("previous").isNull())throw Seed.invalid("host/更新状態が未対応です");reference(value.get("active"));return value;
    }
    private static void reference(JsonNode value) throws IOException {Seed.fields(value,"toolVersion","payloadSha256","runtimeDescriptorSha256","manifestSha256");Seed.version(value,"toolVersion");for(String name:List.of("payloadSha256","runtimeDescriptorSha256","manifestSha256"))Seed.digest(value,name);}
    private Seed accepted(JsonNode state) throws IOException {
        JsonNode reference=state.get("active");Path manifest=root.resolve("manifests/"+Seed.digest(reference,"manifestSha256"));Seed seed=new Seed(manifest,key,host);
        if(!seed.reference().equals(reference)||Seed.positive(seed.manifest,"sequence")>Seed.positive(state,"highestAcceptedSequence"))throw Seed.invalid("activeの署名参照が一致しません");return seed;
    }
    private Path slot(Seed seed) {return root.resolve("tools/"+seed.version+"/"+seed.hash);}
    private void copyArchive(Seed seed,long deadline) throws IOException {
        Path archive=root.resolve("archives/"+seed.hash+".zip");if(Files.exists(archive,LinkOption.NOFOLLOW_LINKS)){file(archive);seed.verifyArchive(archive,deadline);return;}
        Path source=seed.source.resolve("tool.zip");Seed.regular(source);Path stage=root.resolve("staging/archive-"+UUID.randomUUID()+".zip");if(Files.size(source)!=Seed.positive(seed.payload,"bytes"))throw Seed.invalid("seed ZIP sizeが不一致です");
        try(InputStream input=Files.newInputStream(source);OutputStream output=Files.newOutputStream(stage,StandardOpenOption.CREATE_NEW)){long total=0;byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){Seed.check(deadline);if(count>Seed.positive(seed.payload,"bytes")-total)throw Seed.invalid("seed ZIPが上限を超えています");output.write(buffer,0,count);total+=count;}}file(stage);seed.verifyArchive(stage,deadline);Files.move(stage,archive,StandardCopyOption.ATOMIC_MOVE);
    }
    void saveManifest(Seed seed) throws IOException {
        Path target=root.resolve("manifests/"+seed.manifestHash);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!new Seed(target,key,host).reference().equals(seed.reference()))throw Seed.invalid("既存manifestが不一致です");return;}
        Path stage=root.resolve("staging/manifest-"+UUID.randomUUID());directory(stage);Files.write(stage.resolve("stable.json"),seed.manifestBytes,StandardOpenOption.CREATE_NEW);Files.write(stage.resolve("stable.json.sig"),seed.signatureBytes,StandardOpenOption.CREATE_NEW);Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);
    }
    private Path verifySlot(Seed seed,long deadline) throws IOException {
        Path archive=root.resolve("archives/"+seed.hash+".zip"),slot=slot(seed),stage=root.resolve("staging/slot-"+UUID.randomUUID());if(Files.getFileStore(root).getUsableSpace()<Seed.positive(seed.payload,"unpackedBytes"))throw Seed.invalid("展開空き容量が不足しています");seed.extract(archive,stage,deadline);
        if(Files.exists(slot,LinkOption.NOFOLLOW_LINKS)) {directory(slot);if(!Seed.tree(stage,deadline).equals(Seed.tree(slot,deadline)))throw Seed.invalid("既存slotの完全treeが破損しています");removeOwnStage(stage);}
        else {directory(slot.getParent());Files.move(stage,slot,StandardCopyOption.ATOMIC_MOVE);}
        return slot;
    }
    private void removeOwnStage(Path stage) throws IOException {try(var paths=Files.walk(stage)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(path)||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("staging所有が変化しました");Files.delete(path);}}}
    private Path prepare(boolean repair,long deadline) throws IOException {
        roots();JsonNode existing=state();Seed seed;
        if(existing==null) {
            for(String name:List.of("runs","tools","staging","archives","manifests"))try(var paths=Files.list(root.resolve(name))){if(paths.findAny().isPresent())throw Seed.invalid("部分生成/state消失を初回へ再baselineしません");}
            seed=new Seed(installed.resolve("seed"),key,host);copyArchive(seed,deadline);saveManifest(seed);Path slot=verifySlot(seed,deadline);
            ObjectNode value=Seed.JSON.createObjectNode().put("schemaVersion",1).put("channel","stable");value.set("host",Seed.JSON.valueToTree(host));value.set("active",seed.reference());value.putNull("pending").putNull("previous").put("highestAcceptedSequence",Seed.positive(seed.manifest,"sequence"));atomic(root.resolve("active.json"),value);return slot;
        }
        seed=accepted(existing);
        if(!Files.exists(slot(seed),LinkOption.NOFOLLOW_LINKS)) {
            if(!repair)throw Seed.invalid("active slotがありません。repairが必要です");requireFinishedRuns();Seed packaged=new Seed(installed.resolve("seed"),key,host);if(!packaged.reference().equals(seed.reference()))throw Seed.invalid("同一署名seedでのみmissing slotを修復できます");copyArchive(packaged,deadline);
        }
        return verifySlot(seed,deadline);
    }
    private void requireFinishedRuns() throws IOException {
        try(var paths=Files.list(root.resolve("runs"))){for(Path path:paths.toList()){
            file(path);try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock=null;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock==null)throw Seed.invalid("実行中receiptがあるためrepairしません");
                try(FileLock held=lock){JsonNode value=receipt(path);if(!value.path("state").asText().equals("finished"))throw Seed.invalid("親死/未完receiptを保持します");}
            }
        }}
    }
    private JsonNode receipt(Path path) throws IOException {
        JsonNode value=Seed.json(Seed.read(path,65536));Seed.fields(value,"schemaVersion","invocationId","slot","bootstrap","state","child","exitCode");Seed.constant(value,"schemaVersion",1);try{UUID.fromString(Seed.text(value,"invocationId"));}catch(IllegalArgumentException failure){throw new IOException("invocation UUIDが不正です",failure);}reference(value.get("slot"));process(value.get("bootstrap"));String state=Seed.choice(value,"state","launching","running","finished");
        if(state.equals("launching")){if(!value.path("child").isNull()||!value.path("exitCode").isNull())throw Seed.invalid("launching receiptが矛盾しています");}
        else {process(value.get("child"));if(state.equals("running")&&!value.path("exitCode").isNull()||state.equals("finished")&&(!value.path("exitCode").isIntegralNumber()||!value.path("exitCode").canConvertToInt()))throw Seed.invalid("child結果が矛盾しています");}return value;
    }
    private static void process(JsonNode value) throws IOException {Seed.fields(value,"pid","startedAt");Seed.positive(value,"pid");try{Instant.parse(Seed.text(value,"startedAt"));}catch(RuntimeException e){throw new IOException("process開始識別が不正です",e);}}
    private static JsonNode identity(ProcessHandle process) throws IOException {return Seed.JSON.createObjectNode().put("pid",process.pid()).put("startedAt",process.info().startInstant().orElseThrow(()->Seed.invalid("process開始識別が読めません")).toString());}
    void repair() throws IOException {long end=System.nanoTime()+Duration.ofMinutes(5).toNanos();try(StateLock ignored=lock(end)){prepare(true,end);}}
    static int awaitChild(Process child,FileChannel channel,ObjectNode record) throws IOException {
        var started=child.info().startInstant();
        if(started.isPresent()){record.put("state","running");record.set("child",Seed.JSON.createObjectNode().put("pid",child.pid()).put("startedAt",started.get().toString()));write(channel,record);}
        int code;try{code=child.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("CLI待機を中断しました");}
        if(started.isPresent()){record.put("state","finished").put("exitCode",code);write(channel,record);}return code;
    }
    int launch(List<String> arguments) throws IOException {
        long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();Path slot,run;FileChannel channel=null;FileLock held=null;ObjectNode record;Process child=null;StartupControl control=null;IOException original=null;
        try {
            try(StateLock ignored=lock(deadline)) {
                slot=prepare(false,deadline);JsonNode active=state().get("active");UUID id=UUID.randomUUID();run=root.resolve("runs/"+id+".json");channel=create(run);held=channel.lock();record=Seed.JSON.createObjectNode().put("schemaVersion",1).put("invocationId",id.toString()).put("state","launching");record.set("slot",active);record.set("bootstrap",identity(ProcessHandle.current()));record.putNull("child").putNull("exitCode");write(channel,record);
            }
            if(arguments.equals(List.of("--version"))||arguments.equals(List.of("-V")))System.err.println("Bootstrap "+Seed.text(info,"bootstrapVersion")+" (owner: "+Seed.text(info,"owner")+")");
            control=new StartupControl(record.get("slot"),record.path("invocationId").asText(),arguments);
            List<String> command=new ArrayList<>(List.of(slot.resolve("java/bin/"+(host.os().equals("windows")?"java.exe":"java")).toString(),control.property(),"-cp",slot.resolve("app/lib/*").toString(),"io.github.rs0325.mcicon.cli.Main"));command.addAll(arguments);
            ProcessBuilder builder=new ProcessBuilder(command).inheritIO();child=builder.start();control.request(child);return awaitChild(child,channel,record);
        }catch(IOException failure){original=failure;throw failure;}
        finally {
            IOException cleanup=null;
            try{if(control!=null)control.close();}catch(IOException failure){cleanup=failure;}
            boolean interrupted=Thread.interrupted();
            if(child!=null&&child.isAlive()) {
                for(int phase=0;phase<2&&child.isAlive();phase++) {
                    if(phase==0)child.destroy();else child.destroyForcibly();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(phase==0?3:2);
                    while(child.isAlive()&&System.nanoTime()<end)try{child.waitFor(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(InterruptedException failure){interrupted=true;}
                }
                if(child.isAlive())cleanup=Seed.invalid("自身のCLI childを回収できません");
            }
            if(interrupted)Thread.currentThread().interrupt();
            try {if(held!=null)held.close();}catch(IOException e){if(cleanup==null)cleanup=e;else cleanup.addSuppressed(e);}try{if(channel!=null)channel.close();}catch(IOException e){if(cleanup==null)cleanup=e;else cleanup.addSuppressed(e);}if(cleanup!=null){if(original!=null)original.addSuppressed(cleanup);else throw cleanup;}
        }
    }
}
