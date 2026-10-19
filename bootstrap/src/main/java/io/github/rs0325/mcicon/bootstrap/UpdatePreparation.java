package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** stateの予約とreceipt lockを結び、別JVMへ有限の更新準備だけを渡す。 */
final class UpdatePreparation {
    static final Duration BUDGET=Duration.ofMinutes(5);
    final Bootstrap bootstrap;
    UpdatePreparation(Bootstrap bootstrap){this.bootstrap=bootstrap;}
    static void uuid(String value) throws IOException {try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("更新invocationが不正です",failure);}}
    static void validate(JsonNode value) throws IOException {
        if(value==null||value.isNull())return;
        Seed.fields(value,"invocation","phase","expiresAt","worker","receiptKey");key(value,"receiptKey");uuid(Seed.text(value,"invocation"));String phase=Seed.choice(value,"phase","reserved","owned");instant(value,"expiresAt");
        if(phase.equals("reserved")){if(!value.path("worker").isNull())throw Seed.invalid("予約workerが矛盾しています");}else Bootstrap.process(value.get("worker"));
    }
    static Instant instant(JsonNode value,String field) throws IOException {try{return Instant.parse(Seed.text(value,field));}catch(RuntimeException failure){throw new IOException("更新時刻が不正です",failure);}}
    static JsonNode receipt(JsonNode value) throws IOException {
        Seed.fields(value,"schemaVersion","kind","invocationId","slot","bootstrap","state","child","exitCode","issuedAt","expiresAt","packagePin","result","receiptKey");key(value,"receiptKey");Seed.constant(value,"schemaVersion",1);Seed.exact(value,"kind","preparation");uuid(Seed.text(value,"invocationId"));Bootstrap.reference(value.get("slot"));Bootstrap.process(value.get("bootstrap"));
        Instant issued=instant(value,"issuedAt"),expiry=instant(value,"expiresAt");if(!expiry.isAfter(issued)||Duration.between(issued,expiry).compareTo(BUDGET)>0)throw Seed.invalid("更新期限が不正です");
        String phase=Seed.choice(value,"state","reserved","acked","running","finished");if(phase.equals("reserved")){if(!value.path("child").isNull())throw Seed.invalid("更新予約childが矛盾しています");}else Bootstrap.process(value.get("child"));
        if(phase.equals("finished")){if(!value.path("exitCode").isIntegralNumber()||!value.path("exitCode").canConvertToInt())throw Seed.invalid("更新終端結果が不正です");}else if(!value.path("exitCode").isNull())throw Seed.invalid("未終端の更新結果が不正です");
        Seed.fields(value.get("packagePin"),"java","jars","metadata");Seed.digest(value.get("packagePin"),"java");Seed.digest(value.get("packagePin"),"metadata");if(!value.path("packagePin").path("jars").isObject()||value.path("packagePin").path("jars").isEmpty())throw Seed.invalid("package JAR pinが不正です");for(JsonNode hash:value.path("packagePin").path("jars"))if(!hash.isTextual()||!hash.asText().matches("[0-9a-f]{64}"))throw Seed.invalid("package JAR hashが不正です");
        if(!value.path("result").isTextual())throw Seed.invalid("更新診断が不正です");return value;
    }
    static String key(JsonNode value,String field) throws IOException {String key=Seed.text(value,field);if(key.length()>512)throw Seed.invalid("receipt fileKeyが上限外です");return key;}
    static String fileKey(Path path) throws IOException {Object key=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();if(key==null||key.toString().isEmpty()||key.toString().length()>512)throw Seed.invalid("receipt fileKey取得不可のためunknown保持します");return key.toString();}
    static JsonNode readReceipt(FileChannel channel) throws IOException {return receipt(Seed.json(Bootstrap.read(channel,65536)));}
    Path path(String id) throws IOException {uuid(id);return bootstrap.root.resolve("runs/"+id+".json");}
    boolean enabled() throws IOException {
        Path path=bootstrap.root.resolve("config.json");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return true;
        bootstrap.file(path);JsonNode value=Seed.json(Seed.read(path,65536));Seed.fields(value,"schemaVersion","autoUpdate");Seed.constant(value,"schemaVersion",1);if(!value.path("autoUpdate").isBoolean())throw Seed.invalid("autoUpdateはbooleanが必要です");return value.path("autoUpdate").booleanValue();
    }
    void configure(boolean enabled,long deadline) throws IOException {
        try(var ignored=bootstrap.lock(deadline)){bootstrap.directory(bootstrap.root.resolve("staging"));bootstrap.atomic(bootstrap.root.resolve("config.json"),Seed.JSON.createObjectNode().put("schemaVersion",1).put("autoUpdate",enabled));}
    }
    JsonNode packagePin(long deadline) throws IOException {
        Path home=Path.of(System.getProperty("java.home")).toAbsolutePath().normalize(),java=home.resolve("bin/"+(bootstrap.host.os().equals("windows")?"java.exe":"java"));
        Path expected=bootstrap.host.os().equals("macos")?bootstrap.installed.getParent().resolve("runtime/Contents/Home"):bootstrap.installed.getParent().resolve("runtime");
        if(!home.equals(expected.toAbsolutePath().normalize()))throw Seed.invalid("更新準備にはpackage所有のJavaが必要です");
        Seed.regular(java);ObjectNode jars=Seed.JSON.createObjectNode();try(var paths=Files.list(bootstrap.installed)){for(Path jar:paths.filter(p->p.getFileName().toString().endsWith(".jar")).sorted().toList()){bootstrap.file(jar);jars.put(jar.getFileName().toString(),Seed.hash(jar,deadline));}}
        if(jars.isEmpty())throw Seed.invalid("packageの固定JARがありません");ObjectNode value=Seed.JSON.createObjectNode().put("java",Seed.hash(java,deadline)).put("metadata",Seed.hash(bootstrap.installed.resolve("bootstrap-package.json"),deadline));value.set("jars",jars);return value;
    }
    /** 期限切れだけでは回復しない。同じinodeを排他取得できた場合だけ旧UUIDをfenceする。 */
    String reserve(JsonNode expected,JsonNode pin,Instant now,long deadline) throws IOException {
        JsonNode old=expected.get("preparation");
        if(old!=null&&!old.isNull()) {
            if(!now.isAfter(instant(old,"expiresAt")))return null;
            Path path=path(Seed.text(old,"invocation"));bootstrap.file(path);String inode=fileKey(path);if(!inode.equals(key(old,"receiptKey")))throw Seed.invalid("予約時receipt inodeと一致しません");
            try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                FileLock held;try{held=channel.tryLock();}catch(OverlappingFileLockException busy){return null;}if(held==null)return null;
                try(held){JsonNode record=readReceipt(channel);if(!record.path("invocationId").equals(old.get("invocation"))||!record.path("expiresAt").equals(old.get("expiresAt"))||!inode.equals(key(record,"receiptKey"))||!inode.equals(fileKey(path)))throw Seed.invalid("旧更新receiptが変化しました");return reserveLocked(expected,pin,now,deadline);}
            }
        }
        return reserveLocked(expected,pin,now,deadline);
    }
    private String reserveLocked(JsonNode expected,JsonNode pin,Instant now,long deadline) throws IOException {
        try(var ignored=bootstrap.lock(deadline)) {
            ObjectNode state=(ObjectNode)bootstrap.state();if(!state.equals(expected)||!enabled()||!state.path("pending").isNull()||!state.path("trialInvocation").isNull())return null;
            String id=UUID.randomUUID().toString();Instant expiry=now.plus(BUDGET);ObjectNode record=Seed.JSON.createObjectNode().put("schemaVersion",1).put("kind","preparation").put("invocationId",id).put("state","reserved").put("issuedAt",now.toString()).put("expiresAt",expiry.toString()).put("result","");record.set("slot",state.get("active"));record.set("bootstrap",Bootstrap.identity(ProcessHandle.current()));record.putNull("child").putNull("exitCode");record.set("packagePin",pin);
            String inode;try(FileChannel channel=bootstrap.create(path(id))){inode=fileKey(path(id));record.put("receiptKey",inode);Bootstrap.write(channel,record);}ObjectNode preparation=Seed.JSON.createObjectNode().put("invocation",id).put("phase","reserved").put("expiresAt",expiry.toString()).put("receiptKey",inode);preparation.putNull("worker");state.set("preparation",preparation);bootstrap.atomic(bootstrap.root.resolve("active.json"),state);return id;
        }
    }
    static boolean sameProcess(JsonNode first,JsonNode second) throws IOException {Bootstrap.process(first);Bootstrap.process(second);return Seed.positive(first,"pid")==Seed.positive(second,"pid")&&Seed.text(first,"startedAt").equals(Seed.text(second,"startedAt"));}
    static byte[] receive(InputStream input,int size,long deadline) throws IOException {
        byte[] bytes=new byte[size];int offset=0;
        while(offset<size){Seed.check(deadline);int available=input.available();if(available==0){try{TimeUnit.MILLISECONDS.sleep(5);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("更新handoffを中断しました");}continue;}int count=input.read(bytes,offset,Math.min(size-offset,available));if(count<0)throw Seed.invalid("更新handoffが途中です");offset+=count;}Seed.check(deadline);return bytes;
    }
    void start(long requestDeadline) throws IOException {
        if(requestDeadline-System.nanoTime()<TimeUnit.SECONDS.toNanos(11)){System.err.println("UPDATE_NOT_APPLIED: 開始予約時間が不足しています");return;}
        if(!enabled())return;if(bootstrap.info.path("stableManifestUrl").isNull()){System.err.println("UPDATE_NOT_APPLIED: packageの更新URLが未構成です");return;}
        long ackDeadline=Math.min(requestDeadline-TimeUnit.SECONDS.toNanos(6),System.nanoTime()+TimeUnit.SECONDS.toNanos(5));JsonNode pin=packagePin(ackDeadline),expected=bootstrap.state();String id=reserve(expected,pin,Instant.now(),ackDeadline);if(id==null)return;
        Process child=null;boolean transferred=false;IOException original=null;
        try {
            Path executable=Path.of(System.getProperty("java.home"),"bin",bootstrap.host.os().equals("windows")?"java.exe":"java"),error=bootstrap.root.resolve("logs/"+id+".stderr");try(var ignored=bootstrap.create(error)){}
            child=new ProcessBuilder(executable.toString(),"-cp",bootstrap.installed.resolve("*").toString(),PreparationMain.class.getName(),bootstrap.installed.toString(),bootstrap.root.toString(),id).redirectError(error.toFile()).start();
            int size=java.nio.ByteBuffer.wrap(receive(child.getInputStream(),4,ackDeadline)).getInt();if(size<1||size>65536)throw Seed.invalid("更新ACKが上限外です");JsonNode ack=Seed.json(receive(child.getInputStream(),size,ackDeadline));
            Seed.fields(ack,"invocation","worker");if(!id.equals(Seed.text(ack,"invocation"))||!sameProcess(Bootstrap.identity(child.toHandle()),ack.get("worker")))throw Seed.invalid("更新ACK所有が不一致です");
            try(var ignored=bootstrap.lock(ackDeadline)){ObjectNode state=(ObjectNode)bootstrap.state();if(!id.equals(state.path("preparation").path("invocation").asText())||!state.path("preparation").path("phase").asText().equals("reserved")||!enabled())throw Seed.invalid("更新予約が変化しました");ObjectNode prep=(ObjectNode)state.get("preparation");prep.put("phase","owned");prep.set("worker",ack.get("worker"));bootstrap.atomic(bootstrap.root.resolve("active.json"),state);}
            child.getOutputStream().write(1);child.getOutputStream().flush();child.getOutputStream().close();transferred=true;child.getInputStream().close();System.err.println("UPDATE_PREPARING: 裏で準備し、次の適格な起動から適用します");
        }catch(IOException failure){original=failure;throw failure;}
        finally {if(child!=null&&!transferred){try{child.getOutputStream().close();}catch(IOException failure){if(original!=null)original.addSuppressed(failure);}boolean interrupted=Thread.interrupted();for(int phase=0;phase<2&&child.isAlive();phase++){if(phase==0)child.destroy();else child.destroyForcibly();try{child.waitFor(phase==0?3:2,TimeUnit.SECONDS);}catch(InterruptedException failure){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();if(child.isAlive()){IOException failure=Seed.invalid("更新準備子を回収できません");if(original!=null)original.addSuppressed(failure);else throw failure;}}}
    }
    void checkOwner(String id,JsonNode worker,long deadline) throws IOException {
        try(var ignored=bootstrap.lock(deadline)){JsonNode prep=bootstrap.state().path("preparation");if(!id.equals(prep.path("invocation").asText())||!prep.path("phase").asText().equals("owned")||!sameProcess(worker,prep.get("worker"))||!Instant.now().isBefore(instant(prep,"expiresAt"))||!enabled())throw Seed.invalid("更新作業の許可を失いました");}
    }
}
