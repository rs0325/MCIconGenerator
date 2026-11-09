package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.internal.WindowsDataHome;
import io.github.rs0325.mcicon.internal.TrustSet;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 署名slotをpinして起動し、更新の準備・次回trialを管理する。OS helperの停止とGCは行わない。 */
final class Bootstrap {
    final Path installed,root;
    final Seed.Host host;
    final TrustSet key;
    private final String trustHash;
    final JsonNode info;
    private final UserPrincipal owner;
    private final WindowsPrivateFiles windows;
    private final Path dataRoot;
    Bootstrap(Path installed,Path root,Seed.Host host) throws IOException {
        this.installed=installed.toAbsolutePath().normalize();this.root=root.toAbsolutePath().normalize();this.host=host;
        dataRoot=host.os().equals("windows")?WindowsDataHome.configuredRoot():null;
        canonical(this.installed);info=Seed.json(Seed.read(this.installed.resolve("bootstrap-package.json"),65536));Seed.fields(info,"schemaVersion","bootstrapVersion","bootstrapProtocol","owner","host","stableManifestUrl");if(!info.path("stableManifestUrl").isNull())Seed.https(Seed.text(info,"stableManifestUrl"));Seed.constant(info,"schemaVersion",1);Seed.constant(info,"bootstrapProtocol",4);Seed.version(info,"bootstrapVersion");Seed.choice(info,"owner","homebrew","winget","installer","archive");
        if(!info.path("host").equals(Seed.JSON.valueToTree(host)))throw Seed.invalid("package hostが一致しません");
        byte[] trust=Seed.read(this.installed.resolve("trust-set.bin"),TrustSet.SIZE);key=TrustSet.parse(trust);trustHash=Seed.hash(trust);
        owner=host.os().equals("windows")?null:FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        windows=host.os().equals("windows")?new WindowsPrivateFiles(this.installed,this.root):null;
    }
    ProcessBuilder configureChild(ProcessBuilder builder,long deadline) throws IOException {
        if(dataRoot!=null) {
            Path temp=root.resolve("tmp");directory(temp,deadline);
            builder.command().add(1,"-Djava.io.tmpdir="+temp);
            builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("MCICON_DATA_HOME")||name.equalsIgnoreCase("TMP")||name.equalsIgnoreCase("TEMP"));
            builder.environment().put("MCICON_DATA_HOME",dataRoot.toString());
            builder.environment().put("TMP",temp.toString());builder.environment().put("TEMP",temp.toString());
        }
        return builder;
    }
    void directory(Path path) throws IOException {directory(path,Long.MAX_VALUE);}
    void file(Path path) throws IOException {file(path,Long.MAX_VALUE);}
    FileChannel create(Path path) throws IOException {return create(path,Long.MAX_VALUE);}
    void newDirectory(Path path) throws IOException {newDirectory(path,Long.MAX_VALUE);}
    void bytes(Path path,byte[] value) throws IOException {bytes(path,value,Long.MAX_VALUE);}
    void atomic(Path path,JsonNode value) throws IOException {atomic(path,value,Long.MAX_VALUE);}
    JsonNode state() throws IOException {return state(Long.MAX_VALUE);}
    Seed accepted(JsonNode state) throws IOException {return accepted(state,Long.MAX_VALUE);}
    void saveManifest(Seed seed) throws IOException {saveManifest(seed,Long.MAX_VALUE);}
    record StateLock(FileChannel channel,FileLock lock) implements AutoCloseable {public void close() throws IOException {try{lock.close();}finally{channel.close();}}}
    void directory(Path path,long deadline) throws IOException {Seed.check(deadline);
        if(windows!=null){windows.directory(path.toAbsolutePath().normalize(),deadline);return;}
        canonical(path);Path ancestor=path;while(Files.notExists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();
        boolean posix=Files.getFileStore(ancestor).supportsFileAttributeView("posix");
        if(posix)Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));else Files.createDirectories(path);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("bootstrap directoryの所有が不明です");
        if(Files.getFileStore(path).supportsFileAttributeView("posix")){var mode=PosixFilePermissions.fromString("rwx------");if(!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(mode))throw Seed.invalid("private directoryのmodeが不一致です");}
    Seed.check(deadline);}
    void file(Path path,long deadline) throws IOException {Seed.check(deadline);if(windows!=null){windows.verify(path.toAbsolutePath().normalize(),true,deadline);return;}Seed.regular(path);canonical(path);if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("bootstrap fileの所有が不明です");Seed.check(deadline);}
    /** 更新receiptの資格を確認し、同じ物理fileとの照合に使うOS固有keyを返す。 */
    String fileIdentity(Path path,long deadline) throws IOException {Seed.check(deadline);
        if(windows!=null)return windows.fileIdentity(path.toAbsolutePath().normalize(),deadline);
        file(path,deadline);Object key=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        if(key==null||key.toString().isEmpty()||key.toString().length()>512)throw Seed.invalid("receipt fileKey取得不可のためunknown保持します");
        Seed.check(deadline);return key.toString();
    }
    private static void canonical(Path path) throws IOException {for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw Seed.invalid("bootstrap pathにlinkがあります");}
    FileChannel create(Path path,long deadline) throws IOException {Seed.check(deadline);
        if(windows!=null)return windows.create(path.toAbsolutePath().normalize(),deadline);
        FileChannel channel=Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")?FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))):FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
        try{Seed.check(deadline);return channel;}catch(IOException failure){try{channel.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
    }
    void newDirectory(Path path,long deadline) throws IOException {Seed.check(deadline);
        if(windows!=null){windows.createDirectory(path.toAbsolutePath().normalize(),deadline);return;}
        if(Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));else Files.createDirectory(path);
    Seed.check(deadline);}
    /** installerが信頼確認済みのpackage読取。利用者stateのowner判定には使わない。 */
    void packageFile(Path path) throws IOException {
        if(!path.toAbsolutePath().normalize().getParent().equals(installed))throw Seed.invalid("package直下ではありません");Seed.regular(path);canonical(path);
    }
    void bytes(Path path,byte[] value,long deadline) throws IOException {Seed.check(deadline);try(FileChannel channel=create(path,deadline)){java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(value);while(buffer.hasRemaining()){Seed.check(deadline);channel.write(buffer);}}Seed.check(deadline);}
    StateLock lock(long deadline) throws IOException {
        directory(root,deadline);Path path=root.resolve("state.lock");if(Files.notExists(path,LinkOption.NOFOLLOW_LINKS))try(FileChannel ignored=create(path,deadline)){}catch(FileAlreadyExistsException raced){}
        file(path,deadline);FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);boolean acquired=false;
        try {while(true){Seed.check(deadline);FileLock lock=null;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock!=null){try{Seed.check(deadline);}catch(IOException failure){try{lock.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}acquired=true;return new StateLock(channel,lock);}try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("bootstrap排他を中断しました");}}}
        finally {if(!acquired)channel.close();}
    }
    void atomic(Path path,JsonNode value,long deadline) throws IOException {Seed.check(deadline);
        if(!Files.notExists(path,LinkOption.NOFOLLOW_LINKS))file(path,deadline);
        Path temp=root.resolve("staging/state-"+UUID.randomUUID()+".json");try(FileChannel channel=create(temp,deadline)){Seed.check(deadline);write(channel,value);Seed.check(deadline);}Seed.check(deadline);Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    Seed.check(deadline);}
    static void write(FileChannel channel,JsonNode value) throws IOException {byte[] bytes=Seed.JSON.writeValueAsBytes(value);channel.truncate(0);channel.position(0);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
    static byte[] read(FileChannel channel,int maximum) throws IOException {
        if(channel.size()>maximum)throw Seed.invalid("receiptが上限外です");channel.position(0);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(maximum+1);while(buffer.hasRemaining()&&channel.read(buffer)!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("receipt読取を中断しました");}if(buffer.position()>maximum)throw Seed.invalid("receiptが上限外です");return java.util.Arrays.copyOf(buffer.array(),buffer.position());
    }
    private void roots(long deadline) throws IOException {Seed.check(deadline);for(String name:List.of("staging","tools","archives","manifests","runs","logs"))directory(root.resolve(name),deadline);Seed.check(deadline);}
    JsonNode state(long deadline) throws IOException {Seed.check(deadline);
        Path path=root.resolve("active.json");if(Files.notExists(path,LinkOption.NOFOLLOW_LINKS)){Seed.check(deadline);return null;}file(path,deadline);JsonNode value=Seed.json(Seed.read(path,65536));Seed.constant(value,"schemaVersion",4);Seed.fields(value,"schemaVersion","trustSetSha256","channel","host","active","pending","previous","highestAcceptedSequence","trialInvocation","preparation","badCandidates");Seed.exact(value,"trustSetSha256",trustHash);Seed.exact(value,"channel","stable");Seed.positive(value,"highestAcceptedSequence");
        if(!value.path("host").equals(Seed.JSON.valueToTree(host)))throw Seed.invalid("host/更新状態が未対応です");reference(value.get("active"));for(String field:List.of("pending","previous"))if(!value.path(field).isNull())reference(value.get(field));if(!value.path("trialInvocation").isNull())UpdatePreparation.uuid(Seed.text(value,"trialInvocation"));UpdatePreparation.validate(value.get("preparation"));
        for(JsonNode bad:Seed.array(value,"badCandidates",0,32)) {
            Seed.fields(bad,"slot","previousActive","argsSha256","sequence","invocation","child","receiptKey","initialization");reference(bad.get("slot"));reference(bad.get("previousActive"));Seed.digest(bad,"argsSha256");Seed.positive(bad,"sequence");UpdatePreparation.uuid(Seed.text(bad,"invocation"));process(bad.get("child"));Seed.text(bad,"receiptKey");StartupControl.failure(bad.get("initialization"));
            JsonNode failure=bad.get("initialization");if(!bad.path("slot").equals(failure.path("slot"))||!bad.path("invocation").equals(failure.path("invocation"))||!bad.path("child").path("pid").equals(failure.path("pid"))||!bad.path("child").path("startedAt").equals(failure.path("startedAt"))||!recoverable(failure))throw Seed.invalid("不良候補の証拠が矛盾しています");
            if(Seed.positive(bad,"sequence")>Seed.positive(value,"highestAcceptedSequence"))throw Seed.invalid("不良候補が高水位を超えています");
        }
        Seed.check(deadline);return value;
    }
    static void reference(JsonNode value) throws IOException {Seed.fields(value,"toolVersion","payloadSha256","runtimeDescriptorSha256","manifestSha256");Seed.version(value,"toolVersion");for(String name:List.of("payloadSha256","runtimeDescriptorSha256","manifestSha256"))Seed.digest(value,name);}
    Seed accepted(JsonNode state,long deadline) throws IOException {Seed.check(deadline);return accepted(state.get("active"),state,deadline);}
    Path slot(Seed seed) {return root.resolve("tools/"+seed.version+"/"+seed.hash);}
    void copyArchive(Seed seed,long deadline) throws IOException {
        Path archive=root.resolve("archives/"+seed.hash+".zip");if(!Files.notExists(archive,LinkOption.NOFOLLOW_LINKS)){file(archive,deadline);seed.verifyArchive(archive,deadline);return;}
        Path source=seed.source.resolve("tool.zip");Seed.regular(source);Path stage=root.resolve("staging/archive-"+UUID.randomUUID()+".zip");if(Files.size(source)!=Seed.positive(seed.payload,"bytes"))throw Seed.invalid("seed ZIP sizeが不一致です");
        try(InputStream input=Files.newInputStream(source);OutputStream output=Channels.newOutputStream(create(stage,deadline))){long total=0;byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){Seed.check(deadline);if(count>Seed.positive(seed.payload,"bytes")-total)throw Seed.invalid("seed ZIPが上限を超えています");output.write(buffer,0,count);total+=count;}}file(stage,deadline);seed.verifyArchive(stage,deadline);Seed.check(deadline);Files.move(stage,archive,StandardCopyOption.ATOMIC_MOVE);Seed.check(deadline);
    }
    void saveManifest(Seed seed,long deadline) throws IOException {Seed.check(deadline);
        Path target=root.resolve("manifests/"+seed.manifestHash);
        if(!Files.notExists(target,LinkOption.NOFOLLOW_LINKS)){if(!managedSeed(target,deadline).reference().equals(seed.reference()))throw Seed.invalid("既存manifestが不一致です");return;}
        Path stage=root.resolve("staging/manifest-"+UUID.randomUUID());directory(stage,deadline);bytes(stage.resolve("stable.json"),seed.manifestBytes,deadline);bytes(stage.resolve("stable.json.sig"),seed.signatureBytes,deadline);Seed.check(deadline);Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);Seed.check(deadline);
    }
    Path verifySlot(Seed seed,long deadline) throws IOException {
        Path archive=root.resolve("archives/"+seed.hash+".zip"),slot=slot(seed),stage=root.resolve("staging/slot-"+UUID.randomUUID());if(Files.getFileStore(root).getUsableSpace()<Seed.positive(seed.payload,"unpackedBytes"))throw Seed.invalid("展開空き容量が不足しています");file(archive,deadline);seed.extract(this,archive,stage,deadline);
        if(!Files.notExists(slot,LinkOption.NOFOLLOW_LINKS)) {directory(slot,deadline);if(!Seed.tree(this,stage,deadline).equals(Seed.tree(this,slot,deadline)))throw Seed.invalid("既存slotの完全treeが破損しています");removeOwnStage(stage,deadline);}
        else {directory(slot.getParent(),deadline);Seed.check(deadline);Files.move(stage,slot,StandardCopyOption.ATOMIC_MOVE);}
        Seed.check(deadline);return slot;
    }
    private void removeOwnStage(Path stage,long deadline) throws IOException {Seed.check(deadline);
        if(Files.isDirectory(stage,LinkOption.NOFOLLOW_LINKS)) {
            if(host.os().equals("windows"))directory(stage,deadline);else {canonical(stage);if(!Files.getOwner(stage,LinkOption.NOFOLLOW_LINKS).equals(owner))throw Seed.invalid("stageの所有が不一致です");}
            try(DirectoryStream<Path> children=Files.newDirectoryStream(stage)){for(Path child:children)removeOwnStage(child,deadline);}
        } else file(stage,deadline);
        Seed.check(deadline);Files.delete(stage);
    Seed.check(deadline);}
    private Path prepare(boolean repair,long deadline) throws IOException {
        roots(deadline);JsonNode existing=state(deadline);Seed seed;
        if(existing==null) {
            for(String name:List.of("runs","tools","staging","archives","manifests","logs"))try(var paths=Files.list(root.resolve(name))){if(paths.findAny().isPresent())throw Seed.invalid("部分生成/state消失を初回へ再baselineしません");}
            seed=new Seed(installed.resolve("seed"),key,host);copyArchive(seed,deadline);saveManifest(seed,deadline);Path slot=verifySlot(seed,deadline);
            ObjectNode value=Seed.JSON.createObjectNode().put("schemaVersion",4).put("trustSetSha256",trustHash).put("channel","stable");value.set("host",Seed.JSON.valueToTree(host));value.set("active",seed.reference());value.putNull("pending").putNull("previous").putNull("trialInvocation").putNull("preparation").put("highestAcceptedSequence",Seed.positive(seed.manifest,"sequence"));value.putArray("badCandidates");atomic(root.resolve("active.json"),value,deadline);Seed.check(deadline);return slot;
        }
        seed=accepted(existing,deadline);
        Seed.check(deadline);if(Files.notExists(slot(seed),LinkOption.NOFOLLOW_LINKS)) {
            if(!repair)throw Seed.invalid("active slotがありません。repairが必要です");requireFinishedRuns(deadline);Seed packaged=new Seed(installed.resolve("seed"),key,host);if(!packaged.reference().equals(seed.reference()))throw Seed.invalid("同一署名seedでのみmissing slotを修復できます");copyArchive(packaged,deadline);
        }
        return verifySlot(seed,deadline);
    }
    private void requireFinishedRuns(long deadline) throws IOException {Seed.check(deadline);
        try(var paths=Files.list(root.resolve("runs"))){for(Path path:paths.toList()){
            file(path,deadline);try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock=null;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock==null)throw Seed.invalid("実行中receiptがあるためrepairしません");
                try(FileLock held=lock){JsonNode value=receipt(channel);if(!value.path("state").asText().equals("finished"))throw Seed.invalid("親死/未完receiptを保持します");}
            }
        }}
    }
    JsonNode receipt(FileChannel channel) throws IOException {
        JsonNode value=Seed.json(read(channel,65536));if(value.path("kind").asText().equals("preparation"))return UpdatePreparation.receipt(value);Seed.constant(value,"schemaVersion",3);Seed.fields(value,"schemaVersion","kind","invocationId","slot","bootstrap","state","child","exitCode","startup","initialization","receiptKey","argsSha256");Seed.exact(value,"kind","command");try{UUID.fromString(Seed.text(value,"invocationId"));}catch(IllegalArgumentException failure){throw new IOException("invocation UUIDが不正です",failure);}reference(value.get("slot"));process(value.get("bootstrap"));String state=Seed.choice(value,"state","launching","running","finished");
        Seed.digest(value,"argsSha256");Seed.choice(value,"startup","request","run","switch","prepare","ready","go","failed");Seed.text(value,"receiptKey");
        if(!value.path("initialization").isNull()){StartupControl.failure(value.get("initialization"));if(!value.path("startup").asText().equals("failed"))throw Seed.invalid("失敗receiptのphaseが不一致です");}
        else if(value.path("startup").asText().equals("failed"))throw Seed.invalid("失敗receiptの証拠が欠落しています");
        if(state.equals("launching")){if(!value.path("child").isNull()||!value.path("exitCode").isNull())throw Seed.invalid("launching receiptが矛盾しています");}
        else {process(value.get("child"));if(state.equals("running")&&!value.path("exitCode").isNull()||state.equals("finished")&&(!value.path("exitCode").isIntegralNumber()||!value.path("exitCode").canConvertToInt()))throw Seed.invalid("child結果が矛盾しています");}return value;
    }
    static void process(JsonNode value) throws IOException {Seed.fields(value,"pid","startedAt");Seed.positive(value,"pid");try{Instant.parse(Seed.text(value,"startedAt"));}catch(RuntimeException e){throw new IOException("process開始識別が不正です",e);}}
    static JsonNode identity(ProcessHandle process) throws IOException {return Seed.JSON.createObjectNode().put("pid",process.pid()).put("startedAt",process.info().startInstant().orElseThrow(()->Seed.invalid("process開始識別が読めません")).toString());}
    void repair() throws IOException {long end=System.nanoTime()+Duration.ofMinutes(5).toNanos();try(StateLock ignored=lock(end)){prepare(true,end);}Seed.check(end);}
    static int awaitChild(Process child,FileChannel channel,ObjectNode record) throws IOException {
        JsonNode observed=record.get("child");boolean identified=observed!=null&&!observed.isNull();
        if(identified){process(observed);if(Seed.positive(observed,"pid")!=child.pid())throw Seed.invalid("既観測childのPIDが不一致です");}
        else {var started=child.info().startInstant();if(started.isPresent()){record.put("state","running");record.set("child",Seed.JSON.createObjectNode().put("pid",child.pid()).put("startedAt",started.get().toString()));write(channel,record);identified=true;}}
        int code;try{code=child.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("CLI待機を中断しました");}
        if(identified){record.put("state","finished").put("exitCode",code);write(channel,record);}return code;
    }
    private record LaunchResult(int code,JsonNode request,String trial,JsonNode pending,boolean recovered) {}
    int launch(List<String> arguments) throws IOException {return launch(arguments,null);}
    int launch(List<String> arguments,String pin) throws IOException {
        String[] pinned=pin==null?null:parsePin(pin);long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();Path selected;JsonNode reference,pending;
        try(StateLock ignored=lock(deadline)){if(pinned==null){selected=prepare(false,deadline);JsonNode state=state(deadline);reference=state.get("active");pending=state.get("pending");}else{if(state(deadline)==null)throw Seed.invalid("指定版のlocal stateがありません");Seed seed=pinned(pinned,deadline);reference=seed.reference();selected=slot(seed);pending=null;}}
        LaunchResult first=command(arguments,selected,reference,pending,null,null,pin==null,deadline);
        if(first.trial()==null)return first.code();
        Path candidate;try(StateLock ignored=lock(deadline)){JsonNode current=state(deadline);if(!first.trial().equals(current.path("trialInvocation").asText())||!current.path("pending").equals(first.pending()))throw Seed.invalid("trial予約が変化しました");Seed seed=accepted(first.pending(),current,deadline);candidate=verifySlot(seed,deadline);}
        LaunchResult result=command(arguments,candidate,first.pending(),null,first.trial(),first.request(),false,deadline);
        if(!result.recovered())return result.code();
        try(StateLock ignored=lock(deadline)){JsonNode current=state(deadline);if(!reference.equals(current.get("active"))||!new UpdatePreparation(this).enabled(deadline))throw Seed.invalid("復旧後の継続条件が変化しました");selected=verifySlot(accepted(current,deadline),deadline);}
        return command(arguments,selected,reference,null,null,null,false,deadline).code();
    }
    private Seed managedSeed(Path path,long deadline) throws IOException {Seed.check(deadline);directory(path,deadline);file(path.resolve("stable.json"),deadline);file(path.resolve("stable.json.sig"),deadline);Seed seed=new Seed(path,key,host);Seed.check(deadline);return seed;}
    Seed accepted(JsonNode reference,JsonNode state,long deadline) throws IOException {Seed.check(deadline);
        Seed seed=managedSeed(root.resolve("manifests/"+Seed.digest(reference,"manifestSha256")),deadline);if(!seed.reference().equals(reference)||Seed.positive(seed.manifest,"sequence")>Seed.positive(state,"highestAcceptedSequence"))throw Seed.invalid("署名slot参照が不一致です");Seed.check(deadline);return seed;
    }
    private static String[] parsePin(String pin) {
String[] parts=pin.split("@",-1);if(parts.length!=2||!parts[0].matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!parts[1].matches("[0-9a-f]{64}"))throw new IllegalArgumentException("--bootstrap-pinはtoolVersion@payloadSHA256で指定してください");return parts;
    }
    private Seed pinned(String[] parts,long deadline) throws IOException {
        try(var manifests=Files.list(root.resolve("manifests"))){for(Path path:manifests.toList()){Seed.check(deadline);directory(path,deadline);Seed seed=managedSeed(path,deadline);if(seed.version.equals(parts[0])&&seed.hash.equals(parts[1])){if(!Files.isDirectory(slot(seed),LinkOption.NOFOLLOW_LINKS))throw Seed.invalid("指定版のlocal slotがありません");verifySlot(seed,deadline);return seed;}}}throw Seed.invalid("指定版の署名済local slotがありません");
    }
    private static boolean eligible(JsonNode request){return request.path("managed").asBoolean()&&!request.path("offline").asBoolean()&&!request.path("help").asBoolean()&&Set.of("list","render").contains(request.path("command").asText());}
    private static void compareRequest(JsonNode expected,JsonNode actual) throws IOException {for(String field:List.of("args","command","managed","offline","help","preparationMillis","startupMillis","itemMillis"))if(!expected.path(field).equals(actual.path(field)))throw Seed.invalid("候補CLIの判断が元argvと一致しません");}
    private void activate(String trial,JsonNode reference,JsonNode expectedState,long deadline) throws IOException {
        try(StateLock ignored=lock(deadline)){ObjectNode state=(ObjectNode)state(deadline);if(!trial.equals(state.path("trialInvocation").asText())||!reference.equals(state.get("pending"))||!state.equals(expectedState)||!new UpdatePreparation(this).enabled(deadline))throw Seed.invalid("READY後の適用CAS条件が変化しました");state.set("previous",state.get("active"));state.set("active",reference);state.putNull("pending").putNull("trialInvocation");atomic(root.resolve("active.json"),state,deadline);}
    }
    static boolean badCandidate(JsonNode reference,JsonNode state) {
        for(JsonNode bad:state.path("badCandidates"))if(bad.path("slot").path("payloadSha256").equals(reference.path("payloadSha256")))return true;
        return false;
    }
    private static boolean recoverable(JsonNode failure) {
        return failure.path("failure").asText().equals("BUNDLED_PINS_INVALID")&&failure.path("cleanup").asText().equals("NOT_STARTED")&&failure.path("jobs").isArray()&&failure.path("jobs").isEmpty();
    }
    void recoverTrial(String trial,JsonNode reference,JsonNode expected,Path run,FileChannel channel,JsonNode record,long deadline) throws IOException {
        try(StateLock ignored=lock(deadline)) {
            ObjectNode current=(ObjectNode)state(deadline);
            if(!current.equals(expected)||!trial.equals(current.path("trialInvocation").asText())||!reference.equals(current.get("pending"))||!new UpdatePreparation(this).enabled(deadline)||badCandidate(reference,current))throw Seed.invalid("復旧CAS条件が変化しました");
            if(current.path("badCandidates").size()>=32)throw Seed.invalid("不良候補記録の上限に達しました");
            JsonNode failure=record.get("initialization");if(!recoverable(failure)||!failure.path("invocation").asText().equals(trial)||!failure.path("slot").equals(reference)||Seed.positive(record.path("child"),"pid")!=Seed.positive(failure,"pid")||!record.path("child").path("startedAt").equals(failure.path("startedAt")))throw Seed.invalid("復旧証拠の試行identityが不一致です");
            Seed candidate=accepted(reference,current,deadline);verifySlot(accepted(current,deadline),deadline);
            if(!fileIdentity(run,deadline).equals(Seed.text(record,"receiptKey"))||!receipt(channel).equals(Seed.json(Seed.JSON.writeValueAsBytes(record)))||!record.path("state").asText().equals("finished")||!record.path("startup").asText().equals("failed")||!record.path("slot").equals(reference)||!record.path("invocationId").asText().equals(trial))throw Seed.invalid("復旧receiptの所有証拠が変化しました");
            ObjectNode bad=Seed.JSON.createObjectNode().put("sequence",Seed.positive(candidate.manifest,"sequence")).put("invocation",trial).put("receiptKey",Seed.text(record,"receiptKey"));bad.set("slot",reference);bad.set("previousActive",current.get("active"));bad.put("argsSha256",Seed.digest(record,"argsSha256"));bad.set("child",record.get("child"));bad.set("initialization",failure);((ArrayNode)current.get("badCandidates")).add(bad);
            current.putNull("pending").putNull("trialInvocation");atomic(root.resolve("active.json"),current,deadline);
        }
    }
    private LaunchResult command(List<String> arguments,Path slot,JsonNode reference,JsonNode pending,String trial,JsonNode expectedRequest,boolean automatic,long deadline) throws IOException {
        Path run;JsonNode transaction,transactionSettings=null;FileChannel channel=null;FileLock held=null;ObjectNode record;Process child=null;StartupControl control=null;IOException original=null;
        try {
            try(StateLock ignored=lock(deadline)) {
                transaction=trial==null?null:state(deadline);transactionSettings=trial==null?null:new TrialRecovery(this).settings(deadline);if(trial!=null&&(!trial.equals(transaction.path("trialInvocation").asText())||!reference.equals(transaction.get("pending"))))throw Seed.invalid("候補開始前の予約が変化しました");String id=trial==null?UUID.randomUUID().toString():trial;run=root.resolve("runs/"+id+".json");channel=create(run,deadline);held=channel.lock();record=Seed.JSON.createObjectNode().put("schemaVersion",3).put("kind","command").put("invocationId",id).put("state","launching");record.set("slot",reference);record.set("bootstrap",identity(ProcessHandle.current()));record.putNull("child").putNull("exitCode").putNull("initialization").put("startup","request").put("receiptKey",fileIdentity(run,deadline)).put("argsSha256",Seed.hash(Seed.JSON.writeValueAsBytes(arguments)));write(channel,record);
            }
            if(arguments.equals(List.of("--version"))||arguments.equals(List.of("-V")))System.err.println("Bootstrap "+Seed.text(info,"bootstrapVersion")+" (owner: "+Seed.text(info,"owner")+")");
            control=new StartupControl(reference,record.path("invocationId").asText(),arguments);
            List<String> command=new ArrayList<>(List.of(slot.resolve("java/bin/"+(host.os().equals("windows")?"java.exe":"java")).toString(),control.property(),control.capability(),"-cp",slot.resolve("app/lib").toString()+File.separator+"*","io.github.rs0325.mcicon.cli.Main"));command.addAll(arguments);
            Seed.check(deadline);child=configureChild(new ProcessBuilder(command),deadline).inheritIO().start();Seed.check(deadline);
            var started=child.info().startInstant();if(started.isPresent()){record.put("state","running");record.set("child",Seed.JSON.createObjectNode().put("pid",child.pid()).put("startedAt",started.get().toString()));write(channel,record);}
            try(StartupControl.Session session=control.accept(child,Duration.ofSeconds(30))) {
                if(session==null)return new LaunchResult(awaitChild(child,channel,record),null,null,null,false);
                if(trial!=null) {
                    compareRequest(expectedRequest,session.request);if(!eligible(session.request))throw Seed.invalid("候補が適格なmanaged起動ではありません");
                    long millis;try{millis=Math.addExact(Math.addExact(Seed.positive(session.request,"preparationMillis"),Seed.positive(session.request,"startupMillis")),Seed.positive(session.request,"itemMillis"));}catch(ArithmeticException failure){throw new IOException("trial予算が上限外です",failure);}
                    long end;try{end=Math.addExact(System.nanoTime(),Duration.ofMillis(millis).toNanos());}catch(ArithmeticException failure){throw new IOException("trial予算が上限外です",failure);}
                    record.put("startup","prepare");write(channel,record);session.send("PREPARE");JsonNode failure=session.ready(end);
                    if(failure!=null&&failure.path("type").asText().equals("DEFERRED")) {
                        try{if(!child.waitFor(StartupControl.remaining(end),TimeUnit.MILLISECONDS))throw Seed.invalid("延期CLIの終端が未確認です");}catch(InterruptedException failureWait){Thread.currentThread().interrupt();throw new InterruptedIOException("延期CLIの終端待機を中断しました");}
                        int code=awaitChild(child,channel,record);new TrialRecovery(this).defer(trial,reference,transaction,run,channel,record,session.request,failure,transactionSettings,end);
                        return new LaunchResult(code,session.request,null,null,true);
                    }
                    if(failure!=null) {
                        record.put("startup","failed");record.set("initialization",failure);write(channel,record);
                        try{if(!child.waitFor(StartupControl.remaining(end),TimeUnit.MILLISECONDS))throw Seed.invalid("失敗CLIの終端が未確認です");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("失敗CLIの終端待機を中断しました");}
                        int code=awaitChild(child,channel,record);
                        if(!recoverable(failure))throw Seed.invalid("候補固有故障・資源回収完了を確定できません");
                        recoverTrial(trial,reference,transaction,run,channel,record,end);
                        return new LaunchResult(code,session.request,null,null,true);
                    }
                    record.put("startup","ready");write(channel,record);long goDeadline=Math.min(end,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));activate(trial,reference,transaction,goDeadline);record.put("startup","go");write(channel,record);session.go(goDeadline);
                } else {
                    String next=null;
                    if(automatic&&eligible(session.request)) {
                        UpdatePreparation prep=new UpdatePreparation(this);
                        try(StateLock ignored=lock(session.deadline)){ObjectNode current=(ObjectNode)state(session.deadline);if(prep.enabled(session.deadline)&&pending!=null&&!pending.isNull()&&pending.equals(current.get("pending"))&&reference.equals(current.get("active"))&&current.path("trialInvocation").isNull()&&!badCandidate(pending,current)&&!new TrialRecovery(this).legacyCandidateBlocked(pending,session.deadline)){next=UUID.randomUUID().toString();current.put("trialInvocation",next);atomic(root.resolve("active.json"),current,session.deadline);}}
                        if(next==null&&pending!=null&&pending.isNull())try{prep.start(session.deadline);}catch(IOException failure){System.err.println("UPDATE_NOT_APPLIED: "+failure.getMessage());}
                    }
                    if(next!=null){record.put("startup","switch");write(channel,record);session.send("SWITCH");int code=awaitChild(child,channel,record);if(code!=0)throw Seed.invalid("旧parserの正常終了を確認できません");return new LaunchResult(code,session.request,next,pending,false);}
                    record.put("startup","run");write(channel,record);session.send("RUN");
                }
                return new LaunchResult(awaitChild(child,channel,record),session.request,null,null,false);
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
