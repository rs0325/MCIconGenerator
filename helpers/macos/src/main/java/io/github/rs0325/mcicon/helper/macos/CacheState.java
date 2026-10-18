package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/** 長い管理rootと、再生成可能な短いVM cacheを完全な所有記録で結ぶ。 */
final class CacheState {
    private final Path root,anchor,registration;
    private final String hostId,digest,hostBindingSha256,bootSessionId;
    private final long uid;
    CacheState(Path root,String hostId,String digest,long uid) {
        this(root,hostId,digest,uid,null,null);
    }
    CacheState(Path root,String hostId,String digest,long uid,String hostBindingSha256,String bootSessionId) {
        this.root=root;this.hostId=hostId;this.digest=digest;this.uid=uid;this.hostBindingSha256=hostBindingSha256;this.bootSessionId=bootSessionId;
        String token=MacFiles.hash((root+"\0"+hostId+"\0"+digest+"\0"+uid).getBytes(StandardCharsets.UTF_8)).substring(0,20);
        anchor=Path.of("/private/tmp/mcicon-"+uid+"-"+token);registration=root.resolve("macos").resolve(digest+".json");
    }
    Path anchor(){return anchor;}
    boolean firstUse() throws IOException {return absent(registration)&&absent(anchor);}
    static boolean absent(Path path) throws IOException {try{Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false;}catch(NoSuchFileException absent){return true;}}
    Path limaHome(){return anchor.resolve("l");}
    Path home(){return anchor.resolve("h");}
    Path instance(){return limaHome().resolve("v");}
    Path socket(){return instance().resolve("sock/docker.sock");}
    String instanceName(){return "v";}
    Lock lock(Duration timeout) throws IOException {
        Path directory=root.resolve("locks");Files.createDirectories(directory);if(!directory.toRealPath().equals(directory))throw new IOException("lifecycle lock directoryにlinkがあります");
        Path path=directory.resolve("macos-lifecycle.lock");if(Files.isSymbolicLink(path))throw new IOException("lifecycle lockにlinkがあります");
        FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);long end=System.nanoTime()+timeout.toNanos();
        try {
            while(true) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("lifecycle lock待機が中断されました");
                FileLock held=null;try {held=channel.tryLock();}catch(OverlappingFileLockException busy){}
                if(held!=null)return new Lock(channel,held);
                if(System.nanoTime()>=end)throw new IOException("lifecycle lockの期限を超えました");LockSupport.parkNanos(10_000_000);
            }
        }catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
    /** 不明anchorを引き取らず、作成途中の失敗も上書きで隠さない。呼出し元はlifecycle lockを保持する。 */
    ObjectNode register(LeaseReferences.Scan references,OperationJournal operation) throws IOException {return register(firstUse()&&references.pristine()&&operation.unstarted());}
    ObjectNode register(boolean recreateAllowed) throws IOException {
        if(((Number)Files.getAttribute(root,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue()!=uid)throw new IOException("管理rootのUIDが一致しません");
        MacFiles.privateDirectory(registration.getParent());ObjectNode identity;
        if(Files.exists(registration,LinkOption.NOFOLLOW_LINKS)) {identity=validate(MacFiles.json(registration));if(!identity.path("bootSessionId").asText().equals(bootSessionId))throw new IOException("以前のbootの登録を再生成しません");if(!Files.exists(anchor,LinkOption.NOFOLLOW_LINKS))throw new IOException("登録済み短cacheが失われたため再生成しません");}
        else {
            if(!recreateAllowed)throw new IOException("過去生成の未着手を証明できないため初回登録できません");
            if(Files.exists(anchor,LinkOption.NOFOLLOW_LINKS))throw new IOException("未登録の短cacheが存在します");
            identity=identity();identity.put("generationOwner",UUID.randomUUID().toString());MacFiles.write(registration,identity);force(registration);
        }
        if(Files.exists(anchor,LinkOption.NOFOLLOW_LINKS)) {
            checkPrivate(anchor);ObjectNode marker=validate(MacFiles.json(anchor.resolve("owner.json")));if(!identity.equals(marker))throw new IOException("短cacheの所有markerが一致しません");
        }else {
            if(!recreateAllowed)throw new IOException("過去leaseが残るため短cacheを再生成できません");
            MacFiles.privateDirectory(anchor);MacFiles.write(anchor.resolve("owner.json"),identity);force(anchor.resolve("owner.json"));
        }
        for(Path directory:List.of(limaHome(),home(),anchor.resolve("d"))) {MacFiles.privateDirectory(directory);checkPrivate(directory);}
        checkSocketLength();return identity;
    }
    ObjectNode registered() throws IOException {return validate(MacFiles.json(registration));}
    boolean registrationMissing() throws IOException {return absent(registration);}
    boolean previousBoot() throws IOException {return !registered().path("bootSessionId").asText().equals(bootSessionId);}
    void verify() throws IOException {
        if(previousBoot())throw new IOException("登録が以前のbootに属するため通常操作を続行できません");verifyPrevious();
    }
    void verifyPrevious() throws IOException {
        ObjectNode identity=registered();checkPrivate(anchor);if(!identity.equals(validate(MacFiles.json(anchor.resolve("owner.json")))))throw new IOException("短cache所有者が変化しました");
        for(Path directory:List.of(limaHome(),home(),anchor.resolve("d")))checkPrivate(directory);checkSocketLength();
    }
    /** 全旧参照を解消したlifecycle内でだけ呼ぶ。途中破損を推測修復しない。 */
    ObjectNode transitionBoot(boolean missing) throws IOException {
        ObjectNode before=registered();if(!previousBoot())return before;
        Path history=root.resolve("macos/history");MacFiles.privateDirectory(history);Path generations=history.resolve(digest);MacFiles.privateDirectory(generations);Path file=generations.resolve(before.path("generationOwner").asText()+".json");
        ObjectNode records=Files.exists(file,LinkOption.NOFOLLOW_LINKS)?(ObjectNode)MacFiles.json(file):MacFiles.JSON.createObjectNode();String oldBoot=before.path("bootSessionId").asText();
        for(var fields=records.fields();fields.hasNext();) {var field=fields.next();if(!validate(field.getValue()).path("bootSessionId").asText().equals(field.getKey()))throw new IOException("boot履歴の対応が不明です");}
        if(records.has(oldBoot)&&!records.get(oldBoot).equals(before))throw new IOException("旧boot履歴が一致しません");records.set(oldBoot,before);MacFiles.write(file,records);force(file);
        ObjectNode after=before.deepCopy();after.put("bootSessionId",bootSessionId);if(missing)after.put("generationOwner",UUID.randomUUID().toString());
        if(missing&&!absent(anchor))throw new IOException("欠落cacheのpathが再出現しました");if(!missing)verifyPrevious();
        MacFiles.write(registration,after);force(registration);
        if(missing) {
            MacFiles.privateDirectory(anchor);MacFiles.write(anchor.resolve("owner.json"),after);force(anchor.resolve("owner.json"));
            for(Path directory:List.of(limaHome(),home(),anchor.resolve("d")))MacFiles.privateDirectory(directory);
            ObjectNode proof=MacFiles.JSON.createObjectNode();proof.set("identity",after);proof.set("previous",before);MacFiles.write(anchor.resolve("boot-recreated.json"),proof);force(anchor.resolve("boot-recreated.json"));
        }else {
            MacFiles.write(anchor.resolve("owner.json"),after);force(anchor.resolve("owner.json"));ObjectNode vm=(ObjectNode)MacFiles.json(anchor.resolve("vm.json"));
            if(!vm.path("identity").equals(before))throw new IOException("旧VM snapshotが不一致です");vm.set("identity",after);MacFiles.write(anchor.resolve("vm.json"),vm);force(anchor.resolve("vm.json"));
        }
        return after;
    }
    boolean anchorMissing() throws IOException {return absent(anchor);}
    ObjectNode history(String owner,String boot) throws IOException {
        try{if(!UUID.fromString(owner).toString().equals(owner)||!UUID.fromString(boot).toString().equals(boot))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("boot履歴参照が不正です");}
        JsonNode value=MacFiles.json(root.resolve("macos/history").resolve(digest).resolve(owner+".json")).required(boot);ObjectNode record=validate(value);if(!record.path("generationOwner").asText().equals(owner)||!record.path("bootSessionId").asText().equals(boot))throw new IOException("boot履歴所有が一致しません");return record;
    }
    boolean freshIncarnation() throws IOException {
        Path file=anchor.resolve("boot-recreated.json");if(!absent(anchor.resolve("boot-recreated-used.json"))||absent(file))return false;verify();JsonNode proof=MacFiles.json(file),previous=validate(proof.path("previous"));
        if(!proof.path("identity").equals(registered())||previous.path("generationOwner").equals(proof.path("identity").path("generationOwner"))||previous.path("bootSessionId").asText().equals(bootSessionId))throw new IOException("fresh VMcacheの元世代が不正です");
        JsonNode history=MacFiles.json(root.resolve("macos/history").resolve(digest).resolve(previous.path("generationOwner").asText()+".json"));
        if(!history.path(previous.path("bootSessionId").asText()).equals(previous))throw new IOException("fresh VMcacheの旧履歴がありません");return true;
    }
    void consumeFreshIncarnation() throws IOException {
        if(!freshIncarnation())throw new IOException("fresh VM生成資格は既に使用済みか不明です");
        ObjectNode used=MacFiles.JSON.createObjectNode().put("bootRecreatedSha256",MacFiles.hash(anchor.resolve("boot-recreated.json")));
        OperationJournal.durable(anchor.resolve("boot-recreated-used.json"),MacFiles.JSON.writeValueAsBytes(used));
    }
    void checkSocketLength() throws IOException {
        for(Path path:List.of(instance().resolve("ssh.sock.1234567890123456"),socket(),instance().resolve("user-v2_fd.sock")))
            if(path.toString().getBytes(StandardCharsets.UTF_8).length>=104)throw new IOException("固定短cacheのsocket pathがmacOS上限を超えています");
    }
    private ObjectNode identity() throws IOException {
        if(hostBindingSha256==null||!hostBindingSha256.matches("[0-9a-f]{64}")||bootSessionId==null)throw new IOException("VM登録のhost/boot識別を読み取っていません");
        try {if(!UUID.fromString(bootSessionId).toString().equals(bootSessionId))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("VM登録のboot識別が不正です");}
        ObjectNode value=MacFiles.JSON.createObjectNode();value.put("schemaVersion",2);value.put("hostBindingSha256",hostBindingSha256);value.put("bootSessionId",bootSessionId);value.put("root",root.toString());value.put("hostId",hostId);value.put("descriptorSha256",digest);value.put("uid",uid);value.put("cachePath",anchor.toString());value.put("instance","v");return (ObjectNode)MacFiles.JSON.readTree(MacFiles.JSON.writeValueAsBytes(value));
    }
    private ObjectNode validate(JsonNode value) throws IOException {
        ObjectNode expected=identity();Set<String> names=new HashSet<>();value.fieldNames().forEachRemaining(names::add);
        if(!names.equals(Set.of("schemaVersion","root","hostId","descriptorSha256","uid","cachePath","instance","generationOwner","hostBindingSha256","bootSessionId")))throw new IOException("VM登録のfieldが一致しません");
        for(var fields=expected.fields();fields.hasNext();) {var field=fields.next();if(!field.getKey().equals("bootSessionId")&&!field.getValue().equals(value.get(field.getKey())))throw new IOException("VM登録の完全識別が一致しません: "+field.getKey());}
        try {String boot=value.path("bootSessionId").textValue();if(!UUID.fromString(boot).toString().equals(boot))throw new IllegalArgumentException();}catch(RuntimeException failure){throw new IOException("VM登録bootが不正です");}
        try {String owner=value.path("generationOwner").textValue();if(!UUID.fromString(owner).toString().equals(owner))throw new IllegalArgumentException();}catch(RuntimeException failure){throw new IOException("VM登録ownerが不正です",failure);}
        return (ObjectNode)value;
    }
    private void checkPrivate(Path directory) throws IOException {
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toRealPath().equals(directory)||((Number)Files.getAttribute(directory,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue()!=uid||!Files.getPosixFilePermissions(directory,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------")))throw new IOException("短cacheのpath/UID/0700が一致しません");
    }
    private static void force(Path path) throws IOException {try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ)){file.force(true);}try(FileChannel parent=FileChannel.open(path.getParent(),StandardOpenOption.READ)){parent.force(true);}}
    record Lock(FileChannel channel,FileLock held) implements AutoCloseable {public void close() throws IOException {try {held.release();}finally {channel.close();}}}
}
