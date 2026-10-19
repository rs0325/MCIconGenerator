package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.TimeUnit;

/** package Javaだけから開始する、期限付きbackground準備の固定entry。 */
public final class PreparationMain {
    private PreparationMain() {}
    public static void main(String[] args) {int code=1;try{if(args.length!=3)throw Seed.invalid("更新entryの引数が不正です");run(Path.of(args[0]),Path.of(args[1]),args[2]);code=0;}catch(Exception failure){System.err.println("UPDATE_NOT_APPLIED: "+failure.getMessage());}System.exit(code);}
    static Thread guard(long deadline) {
        return Thread.ofPlatform().daemon().name("mcicon-update-deadline").start(()->{try{long nanos=deadline-System.nanoTime();if(nanos>0)TimeUnit.NANOSECONDS.sleep(nanos);TimeUnit.SECONDS.sleep(5);Runtime.getRuntime().halt(124);}catch(InterruptedException stopped){}});
    }
    static void run(Path installed,Path root,String id) throws IOException {
        Bootstrap b=new Bootstrap(installed,root,Main.host());UpdatePreparation prep=new UpdatePreparation(b);Path path=prep.path(id);b.file(path);
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.tryLock()) {
            if(held==null)throw Seed.invalid("更新receiptが既に稼働しています");ObjectNode record=(ObjectNode)UpdatePreparation.readReceipt(channel);if(!UpdatePreparation.fileKey(path).equals(record.path("receiptKey").asText())||!id.equals(record.path("invocationId").asText())||!record.path("state").asText().equals("reserved"))throw Seed.invalid("更新intentを再開しません");
            Duration remaining=Duration.between(Instant.now(),UpdatePreparation.instant(record,"expiresAt"));if(remaining.isNegative()||remaining.isZero())throw Seed.invalid("更新intentは期限切れです");long deadline=System.nanoTime()+Math.min(remaining.toNanos(),UpdatePreparation.BUDGET.toNanos());Thread guard=guard(deadline);
            IOException original=null;
            try {
                JsonNode self=Bootstrap.identity(ProcessHandle.current());record.set("child",self);record.put("state","acked");Bootstrap.write(channel,record);if(!record.path("packagePin").equals(prep.packagePin(deadline)))throw Seed.invalid("更新package pinが不一致です");byte[] ack=Seed.JSON.writeValueAsBytes(Seed.JSON.createObjectNode().put("invocation",id).set("worker",self));System.out.write(ByteBuffer.allocate(4).putInt(ack.length).array());System.out.write(ack);System.out.flush();
                if(UpdatePreparation.receive(System.in,1,Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(5)))[0]!=1)throw Seed.invalid("更新所有移譲を受信できません");System.in.close();System.out.close();prep.checkOwner(id,self,deadline);record.put("state","running");Bootstrap.write(channel,record);
                UpdateDownload.prepare(prep,id,self,record.get("packagePin"),UpdateDownload.client(),deadline);record.put("state","finished").put("exitCode",0).put("result","pending");Bootstrap.write(channel,record);
            }catch(IOException failure){original=failure;try{if(!record.path("child").isNull())record.put("state","finished").put("exitCode",1);record.put("result","未適用");Bootstrap.write(channel,record);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
            finally {guard.interrupt();IOException cleanup=null;try{guard.join(1000);if(guard.isAlive())cleanup=Seed.invalid("更新自己監督を回収できません");}catch(InterruptedException failure){Thread.currentThread().interrupt();cleanup=new InterruptedIOException("更新自己監督回収を中断しました");}if(cleanup!=null){if(original!=null)original.addSuppressed(cleanup);else throw cleanup;}}
        }
    }
}
