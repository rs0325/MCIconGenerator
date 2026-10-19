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

/** 署名slotをpinして起動し、更新の準備・次回trialを管理する。OS helperの停止とGCは行わない。 */
final class Bootstrap {
    final Path installed,root;
    final Seed.Host host;
    final PublicKey key;
    final JsonNode info;
    private final UserPrincipal owner;
    Bootstrap(Path installed,Path root,Seed.Host host) throws IOException {
        this.installed=installed.toAbsolutePath().normalize();this.root=root.toAbsolutePath().normalize();this.host=host;
        canonical(this.installed);info=Seed.json(Seed.read(this.installed.resolve("bootstrap-package.json"),65536));Seed.fields(info,"schemaVersion","bootstrapVersion","bootstrapProtocol","owner","host","stableManifestUrl");if(!info.path("stableManifestUrl").isNull())Seed.https(Seed.text(info,"stableManifestUrl"));Seed.constant(info,"schemaVersion",1);Seed.constant(info,"bootstrapProtocol",2);Seed.version(info,"bootstrapVersion");Seed.choice(info,"owner","homebrew","winget","installer","archive");
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
    void file(Path path) throws IOException {Seed.regular(path);canonical(path);if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("bootstrap fileの所有が不明です");}
    private static void canonical(Path path) throws IOException {for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw Seed.invalid("bootstrap pathにlinkがあります");}
    FileChannel create(Path path) throws IOException {
        if(Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))return FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
    }
    StateLock lock(long deadline) throws IOException {
        directory(root);Path path=root.resolve("state.lock");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))try(FileChannel ignored=create(path)){}catch(FileAlreadyExistsException raced){}
        file(path);FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);boolean acquired=false;
        try {while(true){Seed.check(deadline);FileLock lock=null;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock!=null){acquired=true;return new StateLock(channel,lock);}try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("bootstrap排他を中断しました");}}}
        finally {if(!acquired)channel.close();}
    }
    void atomic(Path path,JsonNode value) throws IOException {
        Path temp=root.resolve("staging/state-"+UUID.randomUUID()+".json");try(FileChannel channel=create(temp)){write(channel,value);}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static void write(FileChannel channel,JsonNode value) throws IOException {byte[] bytes=Seed.JSON.writeValueAsBytes(value);channel.truncate(0);channel.position(0);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
    static byte[] read(FileChannel channel,int maximum) throws IOException {
        if(channel.size()>maximum)throw Seed.invalid("receiptが上限外です");channel.position(0);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(maximum+1);while(buffer.hasRemaining()&&channel.read(buffer)!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("receipt読取を中断しました");}if(buffer.position()>maximum)throw Seed.invalid("receiptが上限外です");return java.util.Arrays.copyOf(buffer.array(),buffer.position());
    }
    private void roots() throws IOException {for(String name:List.of("staging","tools","archives","manifests","runs","logs"))directory(root.resolve(name));}
    JsonNode state() throws IOException {
        Path path=root.resolve("active.json");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;file(path);JsonNode value=Seed.json(Seed.read(path,65536));Seed.fields(value,"schemaVersion","channel","host","active","pending","previous","highestAcceptedSequence","trialInvocation","preparation");Seed.constant(value,"schemaVersion",2);Seed.exact(value,"channel","stable");Seed.positive(value,"highestAcceptedSequence");
        if(!value.path("host").equals(Seed.JSON.valueToTree(host)))throw Seed.invalid("host/更新状態が未対応です");reference(value.get("active"));for(String field:List.of("pending","previous"))if(!value.path(field).isNull())reference(value.get(field));if(!value.path("trialInvocation").isNull())UpdatePreparation.uuid(Seed.text(value,"trialInvocation"));UpdatePreparation.validate(value.get("preparation"));return value;
    }
    static void reference(JsonNode value) throws IOException {Seed.fields(value,"toolVersion","payloadSha256","runtimeDescriptorSha256","manifestSha256");Seed.version(value,"toolVersion");for(String name:List.of("payloadSha256","runtimeDescriptorSha256","manifestSha256"))Seed.digest(value,name);}
    Seed accepted(JsonNode state) throws IOException {return accepted(state.get("active"),state);}
    Path slot(Seed seed) {return root.resolve("tools/"+seed.version+"/"+seed.hash);}
    void copyArchive(Seed seed,long deadline) throws IOException {
        Path archive=root.resolve("archives/"+seed.hash+".zip");if(Files.exists(archive,LinkOption.NOFOLLOW_LINKS)){file(archive);seed.verifyArchive(archive,deadline);return;}
        Path source=seed.source.resolve("tool.zip");Seed.regular(source);Path stage=root.resolve("staging/archive-"+UUID.randomUUID()+".zip");if(Files.size(source)!=Seed.positive(seed.payload,"bytes"))throw Seed.invalid("seed ZIP sizeが不一致です");
        try(InputStream input=Files.newInputStream(source);OutputStream output=Files.newOutputStream(stage,StandardOpenOption.CREATE_NEW)){long total=0;byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){Seed.check(deadline);if(count>Seed.positive(seed.payload,"bytes")-total)throw Seed.invalid("seed ZIPが上限を超えています");output.write(buffer,0,count);total+=count;}}file(stage);seed.verifyArchive(stage,deadline);Files.move(stage,archive,StandardCopyOption.ATOMIC_MOVE);
    }
    void saveManifest(Seed seed) throws IOException {
        Path target=root.resolve("manifests/"+seed.manifestHash);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!new Seed(target,key,host).reference().equals(seed.reference()))throw Seed.invalid("既存manifestが不一致です");return;}
        Path stage=root.resolve("staging/manifest-"+UUID.randomUUID());directory(stage);Files.write(stage.resolve("stable.json"),seed.manifestBytes,StandardOpenOption.CREATE_NEW);Files.write(stage.resolve("stable.json.sig"),seed.signatureBytes,StandardOpenOption.CREATE_NEW);Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);
    }
    Path verifySlot(Seed seed,long deadline) throws IOException {
        Path archive=root.resolve("archives/"+seed.hash+".zip"),slot=slot(seed),stage=root.resolve("staging/slot-"+UUID.randomUUID());if(Files.getFileStore(root).getUsableSpace()<Seed.positive(seed.payload,"unpackedBytes"))throw Seed.invalid("展開空き容量が不足しています");seed.extract(archive,stage,deadline);
        if(Files.exists(slot,LinkOption.NOFOLLOW_LINKS)) {directory(slot);if(!Seed.tree(stage,deadline).equals(Seed.tree(slot,deadline)))throw Seed.invalid("既存slotの完全treeが破損しています");removeOwnStage(stage);}
        else {directory(slot.getParent());Files.move(stage,slot,StandardCopyOption.ATOMIC_MOVE);}
        return slot;
    }
    private void removeOwnStage(Path stage) throws IOException {try(var paths=Files.walk(stage)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(path)||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("staging所有が変化しました");Files.delete(path);}}}
    private Path prepare(boolean repair,long deadline) throws IOException {
        roots();JsonNode existing=state();Seed seed;
        if(existing==null) {
            for(String name:List.of("runs","tools","staging","archives","manifests","logs"))try(var paths=Files.list(root.resolve(name))){if(paths.findAny().isPresent())throw Seed.invalid("部分生成/state消失を初回へ再baselineしません");}
            seed=new Seed(installed.resolve("seed"),key,host);copyArchive(seed,deadline);saveManifest(seed);Path slot=verifySlot(seed,deadline);
            ObjectNode value=Seed.JSON.createObjectNode().put("schemaVersion",2).put("channel","stable");value.set("host",Seed.JSON.valueToTree(host));value.set("active",seed.reference());value.putNull("pending").putNull("previous").putNull("trialInvocation").putNull("preparation").put("highestAcceptedSequence",Seed.positive(seed.manifest,"sequence"));atomic(root.resolve("active.json"),value);return slot;
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
                try(FileLock held=lock){JsonNode value=receipt(channel);if(!value.path("state").asText().equals("finished"))throw Seed.invalid("親死/未完receiptを保持します");}
            }
        }}
    }
    private JsonNode receipt(FileChannel channel) throws IOException {
        JsonNode value=Seed.json(read(channel,65536));if(value.path("kind").asText().equals("preparation"))return UpdatePreparation.receipt(value);Seed.fields(value,"schemaVersion","kind","invocationId","slot","bootstrap","state","child","exitCode");Seed.exact(value,"kind","command");Seed.constant(value,"schemaVersion",1);try{UUID.fromString(Seed.text(value,"invocationId"));}catch(IllegalArgumentException failure){throw new IOException("invocation UUIDが不正です",failure);}reference(value.get("slot"));process(value.get("bootstrap"));String state=Seed.choice(value,"state","launching","running","finished");
        if(state.equals("launching")){if(!value.path("child").isNull()||!value.path("exitCode").isNull())throw Seed.invalid("launching receiptが矛盾しています");}
        else {process(value.get("child"));if(state.equals("running")&&!value.path("exitCode").isNull()||state.equals("finished")&&(!value.path("exitCode").isIntegralNumber()||!value.path("exitCode").canConvertToInt()))throw Seed.invalid("child結果が矛盾しています");}return value;
    }
    static void process(JsonNode value) throws IOException {Seed.fields(value,"pid","startedAt");Seed.positive(value,"pid");try{Instant.parse(Seed.text(value,"startedAt"));}catch(RuntimeException e){throw new IOException("process開始識別が不正です",e);}}
    static JsonNode identity(ProcessHandle process) throws IOException {return Seed.JSON.createObjectNode().put("pid",process.pid()).put("startedAt",process.info().startInstant().orElseThrow(()->Seed.invalid("process開始識別が読めません")).toString());}
    void repair() throws IOException {long end=System.nanoTime()+Duration.ofMinutes(5).toNanos();try(StateLock ignored=lock(end)){prepare(true,end);}}
    static int awaitChild(Process child,FileChannel channel,ObjectNode record) throws IOException {
        JsonNode observed=record.get("child");boolean identified=observed!=null&&!observed.isNull();
        if(identified){process(observed);if(Seed.positive(observed,"pid")!=child.pid())throw Seed.invalid("既観測childのPIDが不一致です");}
        else {var started=child.info().startInstant();if(started.isPresent()){record.put("state","running");record.set("child",Seed.JSON.createObjectNode().put("pid",child.pid()).put("startedAt",started.get().toString()));write(channel,record);identified=true;}}
        int code;try{code=child.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("CLI待機を中断しました");}
        if(identified){record.put("state","finished").put("exitCode",code);write(channel,record);}return code;
    }
    private record LaunchResult(int code,JsonNode request,String trial,JsonNode pending) {}
    int launch(List<String> arguments) throws IOException {return launch(arguments,null);}
    int launch(List<String> arguments,String pin) throws IOException {
        String[] pinned=pin==null?null:parsePin(pin);long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();Path selected;JsonNode reference,pending;
        try(StateLock ignored=lock(deadline)){if(pinned==null){selected=prepare(false,deadline);JsonNode state=state();reference=state.get("active");pending=state.get("pending");}else{if(state()==null)throw Seed.invalid("指定版のlocal stateがありません");Seed seed=pinned(pinned,deadline);reference=seed.reference();selected=slot(seed);pending=null;}}
        LaunchResult first=command(arguments,selected,reference,pending,null,null,pin==null,deadline);
        if(first.trial()==null)return first.code();
        Path candidate;try(StateLock ignored=lock(deadline)){JsonNode current=state();if(!first.trial().equals(current.path("trialInvocation").asText())||!current.path("pending").equals(first.pending()))throw Seed.invalid("trial予約が変化しました");Seed seed=accepted(first.pending(),current);candidate=verifySlot(seed,deadline);}
        return command(arguments,candidate,first.pending(),null,first.trial(),first.request(),false,deadline).code();
    }
    private Seed accepted(JsonNode reference,JsonNode state) throws IOException {
        Seed seed=new Seed(root.resolve("manifests/"+Seed.digest(reference,"manifestSha256")),key,host);if(!seed.reference().equals(reference)||Seed.positive(seed.manifest,"sequence")>Seed.positive(state,"highestAcceptedSequence"))throw Seed.invalid("署名slot参照が不一致です");return seed;
    }
    private static String[] parsePin(String pin) {
String[] parts=pin.split("@",-1);if(parts.length!=2||!parts[0].matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!parts[1].matches("[0-9a-f]{64}"))throw new IllegalArgumentException("--bootstrap-pinはtoolVersion@payloadSHA256で指定してください");return parts;
    }
    private Seed pinned(String[] parts,long deadline) throws IOException {
        try(var manifests=Files.list(root.resolve("manifests"))){for(Path path:manifests.toList()){Seed.check(deadline);directory(path);Seed seed=new Seed(path,key,host);if(seed.version.equals(parts[0])&&seed.hash.equals(parts[1])){if(!Files.isDirectory(slot(seed),LinkOption.NOFOLLOW_LINKS))throw Seed.invalid("指定版のlocal slotがありません");verifySlot(seed,deadline);return seed;}}}throw Seed.invalid("指定版の署名済local slotがありません");
    }
    private static boolean eligible(JsonNode request){return request.path("managed").asBoolean()&&!request.path("offline").asBoolean()&&!request.path("help").asBoolean()&&Set.of("list","render").contains(request.path("command").asText());}
    private static void compareRequest(JsonNode expected,JsonNode actual) throws IOException {for(String field:List.of("args","command","managed","offline","help","preparationMillis","startupMillis","itemMillis"))if(!expected.path(field).equals(actual.path(field)))throw Seed.invalid("候補CLIの判断が元argvと一致しません");}
    private void activate(String trial,JsonNode reference,JsonNode expectedState,long deadline) throws IOException {
        try(StateLock ignored=lock(deadline)){ObjectNode state=(ObjectNode)state();if(!trial.equals(state.path("trialInvocation").asText())||!reference.equals(state.get("pending"))||!state.equals(expectedState)||!new UpdatePreparation(this).enabled())throw Seed.invalid("READY後の適用CAS条件が変化しました");state.set("previous",state.get("active"));state.set("active",reference);state.putNull("pending").putNull("trialInvocation");atomic(root.resolve("active.json"),state);}
    }
    private LaunchResult command(List<String> arguments,Path slot,JsonNode reference,JsonNode pending,String trial,JsonNode expectedRequest,boolean automatic,long deadline) throws IOException {
        Path run;JsonNode transaction;FileChannel channel=null;FileLock held=null;ObjectNode record;Process child=null;StartupControl control=null;IOException original=null;
        try {
            try(StateLock ignored=lock(deadline)) {
                transaction=trial==null?null:state();if(trial!=null&&(!trial.equals(transaction.path("trialInvocation").asText())||!reference.equals(transaction.get("pending"))))throw Seed.invalid("候補開始前の予約が変化しました");String id=trial==null?UUID.randomUUID().toString():trial;run=root.resolve("runs/"+id+".json");channel=create(run);held=channel.lock();record=Seed.JSON.createObjectNode().put("schemaVersion",1).put("kind","command").put("invocationId",id).put("state","launching");record.set("slot",reference);record.set("bootstrap",identity(ProcessHandle.current()));record.putNull("child").putNull("exitCode");write(channel,record);
            }
            if(arguments.equals(List.of("--version"))||arguments.equals(List.of("-V")))System.err.println("Bootstrap "+Seed.text(info,"bootstrapVersion")+" (owner: "+Seed.text(info,"owner")+")");
            control=new StartupControl(reference,record.path("invocationId").asText(),arguments);
            List<String> command=new ArrayList<>(List.of(slot.resolve("java/bin/"+(host.os().equals("windows")?"java.exe":"java")).toString(),control.property(),"-cp",slot.resolve("app/lib/*").toString(),"io.github.rs0325.mcicon.cli.Main"));command.addAll(arguments);
            child=new ProcessBuilder(command).inheritIO().start();
            var started=child.info().startInstant();if(started.isPresent()){record.put("state","running");record.set("child",Seed.JSON.createObjectNode().put("pid",child.pid()).put("startedAt",started.get().toString()));write(channel,record);}
            try(StartupControl.Session session=control.accept(child,Duration.ofSeconds(30))) {
                if(session==null)return new LaunchResult(awaitChild(child,channel,record),null,null,null);
                if(trial!=null) {
                    compareRequest(expectedRequest,session.request);if(!eligible(session.request))throw Seed.invalid("候補が適格なmanaged起動ではありません");
                    long millis;try{millis=Math.addExact(Math.addExact(Seed.positive(session.request,"preparationMillis"),Seed.positive(session.request,"startupMillis")),Seed.positive(session.request,"itemMillis"));}catch(ArithmeticException failure){throw new IOException("trial予算が上限外です",failure);}
                    long end;try{end=Math.addExact(System.nanoTime(),Duration.ofMillis(millis).toNanos());}catch(ArithmeticException failure){throw new IOException("trial予算が上限外です",failure);}
                    session.send("PREPARE");session.ready(end);long goDeadline=Math.min(end,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));activate(trial,reference,transaction,goDeadline);session.go(goDeadline);
                } else {
                    String next=null;
                    if(automatic&&eligible(session.request)) {
                        UpdatePreparation prep=new UpdatePreparation(this);
                        try(StateLock ignored=lock(session.deadline)){ObjectNode current=(ObjectNode)state();if(prep.enabled()&&pending!=null&&!pending.isNull()&&pending.equals(current.get("pending"))&&reference.equals(current.get("active"))&&current.path("trialInvocation").isNull()){next=UUID.randomUUID().toString();current.put("trialInvocation",next);atomic(root.resolve("active.json"),current);}}
                        if(next==null&&pending!=null&&pending.isNull())try{prep.start(session.deadline);}catch(IOException failure){System.err.println("UPDATE_NOT_APPLIED: "+failure.getMessage());}
                    }
                    if(next!=null){session.send("SWITCH");int code=awaitChild(child,channel,record);if(code!=0)throw Seed.invalid("旧parserの正常終了を確認できません");return new LaunchResult(code,session.request,next,pending);}
                    session.send("RUN");
                }
                return new LaunchResult(awaitChild(child,channel,record),session.request,null,null);
            }
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
