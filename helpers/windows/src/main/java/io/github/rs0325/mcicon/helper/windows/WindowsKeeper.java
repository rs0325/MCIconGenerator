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
        if(controller.recovery()?!LeaseReferences.ownerDead(lease):!same)throw new IOException("keeperの元owner/controller権限が一致しません");
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
            long remaining=Math.max(0,Duration.between(Instant.now(),deadline).toNanos());if(remaining<=0||remaining>Duration.ofSeconds(9).toNanos())throw new IOException("keeper release残予算が不正です");
            releaseEnd=System.nanoTime()+remaining;
            HelperProtocol.Command release=new HelperProtocol.Command(runtime.command.root(),runtime.command.lease(),"release",List.of());
            WindowsRuntime cleanup=new WindowsRuntime(release,runtime.executable,runtime.lease,System.nanoTime(),releaseEnd);
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
                JsonNode prior=WindowsFiles.json(current);Path prefix=runtime.keeperPrefix(prior);if(!runtime.commands.terminal(prefix))throw new IOException("過去keeper Jobの終端が不明です");
                if(runtime.lease.read().path("resources").isEmpty())pendingRequired(runtime.temporary,runtime.lease.read(),journal.state(),prior,runtime.commands,runtime.guest());
            }
            ObjectNode owner=runtime.keeperOwner(journal,controller.recovery());HostFacts host=runtime.facts();
            record=WindowsFiles.JSON.createObjectNode().put("state","starting").put("attempt",controller.attempt()).put("boot",host.boot()).put("controllerPid",controller.pid()).put("controllerStarted",controller.started()).put("recovery",controller.recovery());
            record.set("identity",runtime.identity());save();
            ObjectNode request=runtime.request(controller.recovery()?"keep-recover":owner.path("needsInit").asBoolean()?"keep-init":"keep",owner).put("keeperAttempt",controller.attempt());owner.remove("needsInit");
            child=runtime.commands.keep(runtime.guest(),controller.pid(),controller.started());record.put("prefix",child.prefix().toString());save();
            CompletableFuture<Void> writing=new CompletableFuture<>();Thread.ofVirtual().start(()->{try{OutputStream writer=child.process().getOutputStream();writer.write(WindowsFiles.JSON.writeValueAsBytes(request));writer.write('\n');writer.flush();writing.complete(null);}catch(Throwable error){writing.completeExceptionally(error);}});
            CompletableFuture<JsonNode> output=new CompletableFuture<>();Thread.ofVirtual().start(()->{try{output.complete(line(child.process().getInputStream(),65536));}catch(Throwable error){output.completeExceptionally(error);}});
            while(!output.isDone()||!writing.isDone()){if(writing.isCompletedExceptionally())try{writing.join();}catch(CompletionException error){throw new IOException("keeper要求の書込みが失敗しました",error.getCause());}runtime.commands.left(Duration.ofMinutes(3));if(!child.process().isAlive())throw new IOException("keeper ACK前に終了しました");try{Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("keeper ACKが中断されました");}}
            JsonNode ack;try{ack=output.join();}catch(CompletionException error){throw new IOException("keeper ACKが不正です",error.getCause());}
            runtime.verifyHello(ack,owner);if(!controller.recovery())GuestBudget.require(ack.path("memoryBytes").asLong(),request.path("memoryBytes").asLong());if(!ack.path("identity").equals(runtime.identity())||!ack.path("keeper").path("attempt").asText().equals(controller.attempt()))throw new IOException("keeper ACK所有が一致しません");
            JsonNode client=WindowsFiles.json(Path.of(child.prefix()+".client.json"));
            if(client.path("pid").asLong()!=child.process().pid()||client.path("parent").asLong()!=ProcessHandle.current().pid()||client.path("started").asLong()!=child.process().info().startInstant().orElseThrow(()->new IOException("keeper guard開始時刻不明")).toEpochMilli()||client.path("parentStarted").asLong()!=ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("keeper helper開始時刻不明")).toEpochMilli()||client.path("corePid").asLong()!=controller.pid()||client.path("coreStarted").asLong()!=controller.started()||!client.path("boot").asText().equals(host.boot()))throw new IOException("keeper client所有が一致しません");
            record.set("client",client);record.set("guest",ack);record.put("state","ready");save();runtime.keeperRecord();runtime.commands.keeper(record);
            runtime.keeperResource(owner,record);if(!controller.recovery())journal.settled(ack);
        }
    }
    static void pendingRequired(Path tmp,JsonNode lease,JsonNode journal,JsonNode prior,Commands commands,List<String> guest) throws IOException {
        var identity=WindowsFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))identity.set(key,lease.required(key));
        if(!journal.path("state").asText().equals("pending")||!journal.path("operation").asText().equals("keeper-start")||!journal.path("identity").equals(identity)||!LeaseReferences.ownerDead(lease))throw new IOException("未公開keeperの回復権限がありません");identity.remove("hostId");
        String attempt=prior.path("attempt").asText();if(!HostFacts.uuid(attempt)||!Set.of("starting","ready").contains(prior.path("state").asText())||!prior.path("identity").equals(identity)||!prior.path("recovery").isBoolean()||prior.path("recovery").asBoolean()||prior.path("controllerPid").asLong()!=lease.path("pid").asLong()||prior.path("controllerStarted").asLong()!=Instant.parse(lease.path("processStartedAt").asText()).toEpochMilli()||!prior.path("boot").equals(journal.path("boot"))||!prior.equals(WindowsFiles.json(tmp.resolve("keeper-"+attempt+".json"))))throw new IOException("未公開keeperの開始証拠が一致しません");
        Path prefix=Path.of(prior.path("prefix").asText());if(!prefix.isAbsolute()||!prefix.getParent().equals(tmp)||!prefix.getFileName().toString().matches("native-[0-9a-f-]{36}"))throw new IOException("keeper native保存位置が不正です");
        if(!commands.terminal(prefix))throw new IOException("未公開keeperのJob0証拠がありません");
        JsonNode state=WindowsFiles.json(Path.of(prefix+".state.json")),client=WindowsFiles.json(Path.of(prefix+".client.json")),intent=WindowsFiles.json(Path.of(prefix+".intent.json"));
        if(!state.path("boot").equals(prior.path("boot"))||!intent.path("argvSha256").asText().equals(WindowsFiles.hash(WindowsFiles.JSON.writeValueAsBytes(guest))))throw new IOException("keeper nativeのboot/argvが一致しません");
        for(String field:List.of("parent","parentStarted","pid","started","boot"))if(!client.path(field).equals(state.path(field)))throw new IOException("keeper clientのnative所有が一致しません");
        if(client.path("corePid").asLong()!=prior.path("controllerPid").asLong()||client.path("coreStarted").asLong()!=prior.path("controllerStarted").asLong()||client.path("clientPid").asLong()<1||client.path("clientStarted").asLong()<1||(prior.has("client")||prior.path("state").asText().equals("ready"))&&!prior.path("client").equals(client))throw new IOException("keeper clientのcontrollerが一致しません");
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
        releaseEnd=commands.end();long end=Math.min(releaseEnd-Duration.ofSeconds(3).toNanos(),System.nanoTime()+Duration.ofSeconds(2).toNanos());
        if(System.nanoTime()>=end)throw new IOException("keeper EOF後の停止/保存枠を残せません");
        if(!record.path("closeIntent").asBoolean()||!child.process().isAlive())throw new IOException("keeperの先行close intent/guardがありません");requireControllerLive();requireClientLive(record);
        if(System.nanoTime()>=end)throw new IOException("keeper EOF期限を超えました");child.process().getOutputStream().close();
        int exit=commands.waitFor(child.process(),Duration.ofNanos(Math.max(0,end-System.nanoTime())));
        ObjectNode termination=WindowsFiles.JSON.createObjectNode().put("exit",exit).put("unsignedExit",Integer.toUnsignedLong(exit)).put("hexExit",String.format(Locale.ROOT,"%08x",exit)).put("closeIntent",record.path("closeIntent").asBoolean()).put("stopIntent",record.path("stopIntent").asBoolean()).put("stopConfirmed",record.path("stopConfirmed").asBoolean());termination.set("client",record.path("client").deepCopy());
        IOException failure=exit!=0&&exit!=125?new IOException("keeper guard終了値が不正です（exit "+exit+" / unsigned "+Integer.toUnsignedLong(exit)+"）"):null;
        try {boolean empty=commands.terminal(child.prefix());termination.put("jobEmpty",empty);if(!empty)failure=Commands.merge(failure,new IOException("keeper Job0終端がありません"));}
        catch(IOException error){termination.put("jobEmptyUnknown",true).put("error",error.getClass().getSimpleName());failure=Commands.merge(failure,error);}
        try{if(System.nanoTime()>=end)throw new IOException("keeper EOF期限を超えました");JsonNode proof=completion(child.prefix(),record.path("client"),exit);termination.set("completion",proof);
            if(!proof.path("childKnown").asBoolean()||proof.path("childExit").asLong()!=0||!(exit==0&&proof.path("phase").asText().equals("drain-empty")||exit==125&&proof.path("phase").asText().equals("residual-terminated")))throw new IOException("keeper EOFの正常な子0終端がありません");
            if(System.nanoTime()>=end)throw new IOException("keeper EOF期限を超えました");}
        catch(IOException error){termination.put("completionUnknown",true);failure=Commands.merge(failure,error);}
        record.set("termination",termination);try{save();}catch(IOException error){failure=Commands.merge(failure,error);}if(System.nanoTime()>=end)failure=Commands.merge(failure,new IOException("keeper EOF保存後の期限を超えました"));if(failure!=null)throw failure;
        clientEnded=true;
    }
    void finish(Commands commands) throws IOException {
        commands.left(Duration.ofSeconds(2));requireControllerLive();requireCloseProof(record);if(!clientEnded)throw new IOException("keeper EOFの本人終端がありません");
        record.put("state","closed").put("jobEmpty",true).put("guardExit",record.path("termination").path("exit").asInt());save();commands.left(Duration.ofSeconds(2));
    }
    static JsonNode completion(Path prefix,JsonNode client,int exit) throws IOException {
        Path file=Path.of(prefix+".completion.json");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("keeper completionがありません");
        byte[] bytes;try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(4097);}if(bytes.length>4096)throw new IOException("keeper completionが上限を超えました");
        JsonNode value=WindowsFiles.JSON.readTree(bytes);if(value==null||!value.isObject()||value.size()!=7||!value.path("schema").isIntegralNumber()||!value.path("schema").canConvertToInt()||value.path("schema").asLong()!=1||!value.path("client").equals(client)||!value.path("jobEmpty").isBoolean()||!value.path("jobEmpty").asBoolean()||!value.path("childKnown").isBoolean())throw new IOException("keeper completionの本人/形式が一致しません");
        JsonNode planned=value.path("plannedGuardExit"),child=value.path("childExit");boolean known=value.path("childKnown").asBoolean();String phase=value.path("phase").asText();
        if(!planned.isIntegralNumber()||!planned.canConvertToInt()||planned.intValue()!=exit||!Set.of("drain-empty","owner-death-cleanup","residual-terminated").contains(phase))throw new IOException("keeper completionの実終了値/phaseが一致しません");
        if(known? !child.isIntegralNumber()||!child.canConvertToLong()||child.longValue()<0||child.longValue()>0xffffffffL : !child.isNull())throw new IOException("keeper child終了値が不明又は不正です");
        if(phase.equals("drain-empty")? !known||Integer.toUnsignedLong(exit)!=child.longValue() : exit!=125||!known&&!phase.equals("owner-death-cleanup"))throw new IOException("keeper completionの終端条件が矛盾しています");
        return value;
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
}
