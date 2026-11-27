package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.*;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 一つのleaseのWSL lifetime clientを、coreの実寿命と終了要求へ結ぶ。 */
final class WindowsKeeper {
    private final WindowsRuntime runtime;
    private final HelperProtocol.Keeper controller;
    private Commands.Running child;
    private ObjectNode record;
    private boolean saved;
    WindowsKeeper(WindowsRuntime runtime) throws IOException {
        this.runtime=runtime;controller=runtime.command.keeper();if(controller==null)throw new IOException("keeper controllerがありません");
        requireControllerLive();
        try(FileChannel channel=FileChannel.open(runtime.command.lease().resolveSibling("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock available=channel.tryLock(64,1,false);if(available!=null){available.release();throw new IOException("keeper controllerのactive/recovery lockがありません");}
        }
        JsonNode lease=runtime.lease.read();boolean same=lease.path("pid").asLong()==controller.pid()&&Instant.parse(lease.path("processStartedAt").asText()).toEpochMilli()==controller.started();
        if(controller.recovery()?!ownerDead(lease):!same)throw new IOException("keeperの元owner/controller権限が一致しません");
    }
    void requireControllerLive() throws IOException {
        ProcessHandle parent=ProcessHandle.current().parent().orElseThrow(()->new IOException("keeperの実親が不明です"));
        if(parent.pid()!=controller.pid()||!parent.info().startInstant().map(Instant::toEpochMilli).filter(value->value==controller.started()).isPresent()||!parent.isAlive())throw new IOException("keeper controllerの実親が一致しません");
    }
    void run() throws IOException {

        Throwable failure=null;
        try {
            start();System.out.write(WindowsFiles.JSON.writeValueAsBytes(record));System.out.write('\n');System.out.flush();
            CompletableFuture<JsonNode> input=new CompletableFuture<>();Thread.ofVirtual().start(()->{try{input.complete(releaseInput(System.in));}catch(Throwable error){input.completeExceptionally(error);}});
            ProcessHandle client=ProcessHandle.of(record.path("client").path("clientPid").asLong()).orElseThrow(()->new IOException("keeper clientが消失しました"));
            try {CompletableFuture.anyOf(input,client.onExit()).join();}catch(CompletionException error){throw new IOException("keeper終了入力が失敗しました",error.getCause());}
            if(!input.isDone()||!client.isAlive())throw new IOException("keeperが終了要求前に消失しました");JsonNode request;
            try{request=input.join();}catch(CompletionException error){throw new IOException("keeper終了入力が失敗しました",error.getCause());}

            if(!request.path("operation").asText().equals("release")||!request.path("attempt").asText().equals(controller.attempt()))throw new IOException("keeperの正式releaseではありません");
            Instant deadline;try{deadline=Instant.parse(request.path("deadline").asText());}catch(RuntimeException error){throw new IOException("keeper release期限が不正です",error);}
            long remaining=Math.max(0,Duration.between(Instant.now(),deadline).toNanos());if(remaining<=0||remaining>Duration.ofSeconds(30).toNanos())throw new IOException("keeper release残予算が不正です");
            releaseEnd=System.nanoTime()+remaining;

            HelperProtocol.Command release=new HelperProtocol.Command(runtime.command.root(),runtime.command.lease(),"release",List.of());
            WindowsRuntime cleanup=new WindowsRuntime(release,runtime.executable,runtime.lease,System.nanoTime(),releaseEnd,controller.recovery());
            cleanup.release(this,controller.recovery());
            return;
        }catch(IOException|RuntimeException|Error error){failure=error;throw error;}
        finally {
            if(failure!=null&&child!=null){

                long end=Math.min(releaseEnd,System.nanoTime()+Duration.ofSeconds(2).toNanos());
                Commands cleanup=new Commands(runtime.payload.path("windows-host.exe"),runtime.temporary,runtime.environment,end);
                try{child.process().getOutputStream().close();}catch(IOException error){failure.addSuppressed(error);}
                try{if(child.process().isAlive())cleanup.waitFor(child.process(),Duration.ofNanos(Math.max(0,end-System.nanoTime()-Duration.ofMillis(250).toNanos())));}catch(IOException error){failure.addSuppressed(error);}
                try{if(child.process().isAlive())cleanup.stop(child.process());}catch(IOException error){failure.addSuppressed(error);}
                try{record.put("state","unknown").put("failure",failure.getMessage());save();}catch(IOException error){failure.addSuppressed(error);}

            }
        }
    }
    private long releaseEnd=Long.MAX_VALUE;
    private void start() throws IOException {

        try(OperationJournal journal=runtime.journal();var ignored=runtime.lock()) {
            Path current=runtime.temporary.resolve("keeper.json");
            if(controller.recovery()&&runtime.lease.read().path("resources").isEmpty()||Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                if(!controller.recovery())throw new IOException("keeperの再開始を拒否しました");
                JsonNode prior=WindowsFiles.json(current);Path prefix=runtime.keeperPrefix(prior);if(!runtime.commands.terminal(prefix))throw new IOException("過去keeperのnativeが稼働中です: "+prefix+"。wsl --shutdownを実行して再試行してください");
                if(runtime.lease.read().path("resources").isEmpty())pendingRequired(runtime.temporary,runtime.lease.read(),journal.state(),prior,runtime.commands);
            }
            ObjectNode owner=runtime.keeperOwner(journal,controller.recovery());HostFacts host=runtime.facts();

            record=WindowsFiles.JSON.createObjectNode().put("state","starting").put("attempt",controller.attempt()).put("boot",host.boot()).put("controllerPid",controller.pid()).put("controllerStarted",controller.started()).put("recovery",controller.recovery());
            record.set("identity",runtime.identity());save();
            ObjectNode request=runtime.request(controller.recovery()?"keep-recover":owner.path("needsInit").asBoolean()?"keep-init":"keep",owner).put("keeperAttempt",controller.attempt());owner.remove("needsInit");
            if(controller.recovery()&&owner.has("lastStop"))request.set("stop",owner.path("lastStop"));
            child=runtime.commands.keep(runtime.guest(),controller.pid(),controller.started());record.put("prefix",child.prefix().toString());save();
            CompletableFuture<Void> writing=new CompletableFuture<>();Thread.ofVirtual().start(()->{try{OutputStream writer=child.process().getOutputStream();writer.write(WindowsFiles.JSON.writeValueAsBytes(request));writer.write('\n');writer.flush();writing.complete(null);}catch(Throwable error){writing.completeExceptionally(error);}});
            CompletableFuture<JsonNode> output=new CompletableFuture<>();Thread.ofVirtual().start(()->{try{output.complete(line(child.process().getInputStream(),65536));}catch(Throwable error){output.completeExceptionally(error);}});
            while(!output.isDone()||!writing.isDone()){if(writing.isCompletedExceptionally())try{writing.join();}catch(CompletionException error){throw new IOException("keeper要求の書込みが失敗しました",error.getCause());}runtime.commands.left(Duration.ofMinutes(3));if(!child.process().isAlive())throw new IOException("keeper ACK前に終了しました");try{Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("keeper ACKが中断されました");}}
            JsonNode ack;try{ack=output.join();}catch(CompletionException error){throw new IOException("keeper ACKが不正です",error.getCause());}

            runtime.verifyHello(ack,owner);if(!controller.recovery())GuestBudget.require(ack.path("memoryBytes").asLong(),request.path("memoryBytes").asLong());if(!ack.path("identity").equals(runtime.identity())||!ack.path("keeper").path("attempt").asText().equals(controller.attempt()))throw new IOException("keeper ACK所有が一致しません");
            JsonNode client=WindowsFiles.json(Path.of(child.prefix()+".client.json"));
            record.set("client",client);record.set("guest",ack);record.put("state","ready");save();runtime.keeperRecord();runtime.commands.keeper(record);
            runtime.keeperResource(owner,record);if(!controller.recovery())journal.settled(ack);

        }
    }
    static void pendingRequired(Path tmp,JsonNode lease,JsonNode journal,JsonNode prior,Commands commands) throws IOException {
        var identity=WindowsFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))identity.set(key,lease.required(key));
        if(!journal.path("state").asText().equals("pending")||!journal.path("operation").asText().equals("keeper-start")||!journal.path("identity").equals(identity)||!ownerDead(lease))throw new IOException("未公開keeperの回復権限がありません");identity.remove("hostId");
        String attempt=prior.path("attempt").asText();if(!HostFacts.uuid(attempt)||!Set.of("starting","ready").contains(prior.path("state").asText())||!prior.path("identity").equals(identity)||!prior.path("recovery").isBoolean()||prior.path("recovery").asBoolean()||prior.path("controllerPid").asLong()!=lease.path("pid").asLong()||prior.path("controllerStarted").asLong()!=Instant.parse(lease.path("processStartedAt").asText()).toEpochMilli()||!prior.path("boot").equals(journal.path("boot"))||!prior.equals(WindowsFiles.json(tmp.resolve("keeper-"+attempt+".json"))))throw new IOException("未公開keeperの開始証拠が一致しません");
        Path prefix=Path.of(prior.path("prefix").asText());if(!prefix.isAbsolute()||!prefix.getParent().equals(tmp)||!prefix.getFileName().toString().matches("native-[0-9a-f-]{36}"))throw new IOException("keeper native保存位置が不正です");
        if(!commands.terminal(prefix))throw new IOException("未公開keeperのnativeが稼働中です: "+prefix+"。wsl --shutdownを実行して再試行してください");
    }
    void workProof(JsonNode proof) throws IOException {record.set("workProof",proof);save();}
    static void requireClientLive(JsonNode record) throws IOException {
        JsonNode client=record.path("client");if(!ProcessHandle.of(client.path("clientPid").asLong()).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).map(Instant::toEpochMilli).filter(value->value==client.path("clientStarted").asLong()).isPresent())throw new IOException("keeper clientがclose intent前に終了又は不明です");
    }
    void closeIntent(boolean stop) throws IOException {
        if(!child.process().isAlive())throw new IOException("keeper guardがclose intent前に終了しました");requireClientLive(record);record.put("state","closing").put("closeIntent",true).put("stopIntent",stop);save();requireClientLive(record);
    }
    void stopped() throws IOException {if(!record.path("stopIntent").asBoolean())throw new IOException("keeper stop intentがありません");record.put("stopConfirmed",true);save();}
    static void requireCloseProof(JsonNode record) throws IOException {
        if(!record.path("closeIntent").asBoolean()||record.path("stopIntent").asBoolean()&&!record.path("stopConfirmed").asBoolean())throw new IOException("keeperの先行close/stop証明がありません");
    }
    private boolean clientEnded;
    void endClient(Commands commands) throws IOException {
        releaseEnd=commands.end();
        if(!record.path("closeIntent").asBoolean())throw new IOException("keeperのclose intentがありません");requireControllerLive();requireClientLive(record);
        child.process().getOutputStream().close();
        int exit=commands.waitUntil(child.process(),releaseEnd);
        if(exit!=0&&exit!=125)throw new IOException("keeper guard終了値が不正です: "+child.prefix()+" exit="+exit+"。wsl --shutdownを実行して再試行してください");
        record.putObject("termination").put("exit",exit);save();clientEnded=true;
    }
    void finish(Commands commands) throws IOException {
        commands.left(Duration.ofSeconds(2));requireControllerLive();requireCloseProof(record);if(!clientEnded)throw new IOException("keeper EOFの本人終端がありません");
        record.put("state","closed").put("jobEmpty",true).put("guardExit",record.path("termination").path("exit").asInt());save();commands.left(Duration.ofSeconds(2));
    }
    JsonNode value(){return record.deepCopy();}
    private void save() throws IOException {
        Path attempt=runtime.temporary.resolve("keeper-"+controller.attempt()+".json");
        if(!saved){Files.write(attempt,WindowsFiles.JSON.writeValueAsBytes(record),StandardOpenOption.CREATE_NEW);saved=true;}else WindowsFiles.write(attempt,record);WindowsFiles.write(runtime.temporary.resolve("keeper.json"),record);
    }
    static JsonNode releaseInput(InputStream input) throws IOException {JsonNode value=line(input,4096);if(input.read()!=-1)throw new IOException("keeper releaseの後続入力を拒否しました");return value;}
    private static JsonNode line(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;
        while((value=input.read())!=-1){if(bytes.size()>=limit)throw new IOException("keeper frameが上限を超えました");if(value=='\n'){JsonNode result=WindowsFiles.JSON.readTree(bytes.toByteArray());if(result==null||!result.isObject())throw new IOException("keeper frameはobjectが必要です");return result;}bytes.write(value);}
        throw new EOFException("keeper frameが未完了です");
    }
    static boolean ownerDead(JsonNode lease) throws IOException {
        try{return ownerDead(ProcessHandle.of(lease.path("pid").longValue()),Instant.parse(lease.path("processStartedAt").asText()));}
        catch(SecurityException|java.time.DateTimeException failure){throw new IOException("旧coreの死亡identityを確認できません",failure);}
    }
    static boolean ownerDead(Optional<ProcessHandle> process,Instant expected) throws IOException {
        try {
            if(process.isEmpty()||!process.get().isAlive())return true;
            var started=process.get().info().startInstant();if(started.isPresent())return started.get().toEpochMilli()!=expected.toEpochMilli();
            if(!process.get().isAlive())return true;throw new IOException("旧coreの開始identityが不明です");
        }catch(SecurityException failure){throw new IOException("旧coreの生存状態が不明です",failure);}
    }
    static boolean live(JsonNode lease) {
        if(!lease.path("processStartedAt").isTextual())return false;
        try {return ProcessHandle.of(lease.path("pid").longValue()).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).filter(Instant.parse(lease.path("processStartedAt").asText())::equals).isPresent();}
        catch(RuntimeException failure){return false;}
    }
}
