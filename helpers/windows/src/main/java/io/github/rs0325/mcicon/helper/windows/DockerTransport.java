package io.github.rs0325.mcicon.helper.windows;

import io.github.rs0325.mcicon.helper.DockerRequest;
import io.github.rs0325.mcicon.helper.Snapshot;
import io.github.rs0325.mcicon.helper.WorkerProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.apache.commons.compress.archivers.tar.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** host bindをsnapshotへ変換し、生成意図を保存してから固定guestbridgeへ接続する。 */
final class DockerTransport {
    private final WindowsRuntime runtime;
    DockerTransport(WindowsRuntime runtime){this.runtime=runtime;}
    void cpuProbe(OperationJournal journal,ObjectNode owner) throws IOException {
        JsonNode lease=runtime.lease.read();String transport=UUID.randomUUID().toString(),name="mcicon-cpu-"+runtime.job();
        List<String> args=new ArrayList<>(List.of("create","--name",name,"--platform","linux/amd64","--network","none","--memory",lease.path("requirements").path("containerMemoryMiB").asLong()+"m","--cpus","4","--entrypoint","/mcicon/cpu-probe","-v","/usr/lib/mcicon/cpu-probe:/mcicon/cpu-probe:ro"));
        for(String key:List.of("jobId","ownerId","descriptorSha256"))args.addAll(List.of("--label","io.github.rs0325.mcicon."+key+"="+lease.path(key).asText()));args.addAll(List.of("--label","io.github.rs0325.mcicon.generation="+owner.path("generationOwner").asText(),runtime.imageId()));
        ObjectNode resources=(ObjectNode)lease.path("resources").path("windows").deepCopy();((ArrayNode)resources.path("transports")).addObject().put("id",transport);((ArrayNode)resources.path("containers")).addObject().put("name",name).put("transportId",transport);runtime.replace(resources);journal.begin("cpu-create",runtime.facts().boot());
        JsonNode created=runtime.bridge("create",owner,"args",WindowsFiles.JSON.valueToTree(args));String id=created.path("id").asText();if(!id.matches("[0-9a-f]{64}"))throw new IOException("CPU probe full IDが不正です");
        resources=(ObjectNode)runtime.lease.read().path("resources").path("windows").deepCopy();for(JsonNode container:resources.path("containers"))if(container.path("transportId").asText().equals(transport))((ObjectNode)container).put("id",id);runtime.replace(resources);journal.settled(created);journal.begin("cpu-start",runtime.facts().boot());
        Commands.Result result=runtime.commands.request(runtime.guest(),WindowsFiles.JSON.writeValueAsBytes(runtime.request("start",owner).put("id",id).put("interactive",false)),null,Duration.ofSeconds(15));Commands.zero(result,"実renderer経路SSE4.1 probe");
        JsonNode proof=runtime.call(runtime.request("remove",owner).put("id",id),null);if(!proof.path("removed").asText().equals(id))throw new IOException("CPU probeの削除証明が不一致です");removeResource(id);journal.settled(proof);
    }
    int run(List<String> argv) throws IOException {
        if(argv.size()==5&&argv.subList(0,2).equals(List.of("image","inspect"))&&argv.get(2).equals(runtime.imageId())&&argv.get(3).equals("--format")&&Set.of("{{.Id}}","{{.Os}}/{{.Architecture}} {{.Id}}").contains(argv.get(4))) {
            ObjectNode owner=runtime.ready();JsonNode result=runtime.bridge("inspect",owner,"args",WindowsFiles.JSON.valueToTree(argv));System.out.println(result.path("stdout").asText());return 0;
        }
        if(argv.size()==3&&argv.subList(0,2).equals(List.of("rm","-f"))) {
            try(OperationJournal journal=runtime.journal();var ignored=runtime.lock()) {
                ObjectNode owner=runtime.ready();JsonNode registered=find(argv.get(2));if(registered==null)return 0;
                ObjectNode request=runtime.request("remove",owner).put("id",registered.path("id").asText());JsonNode proof=runtime.call(request,null);if(!proof.path("removed").asText().equals(registered.path("id").asText()))throw new IOException("container削除証明が不一致です");removeResource(registered.path("id").asText());journal.recovered(proof);return 0;
            }
        }
        Snapshot snapshot=null;Process process=null;Commands.Running running=null;long processStarted=-1;String id=null;IOException failure=null;int exit=1;
        try {
            try(OperationJournal journal=runtime.journal();var ignored=runtime.lock()) {
                journal.settledRequired();ObjectNode owner=runtime.ready();JsonNode lease=runtime.lease.read();String guestCache="/var/lib/mcicon/jobs/"+runtime.job()+"/cache";
                DockerRequest request=DockerRequest.parse(argv,runtime.descriptor,lease,guestCache,owner.path("generationOwner").asText());
                if(request.worker()) {
                    Path stage=runtime.temporary.resolve("snapshot");snapshot=Snapshot.create(request.cache(),request.agent(),stage,request.process(),runtime.descriptor.path("worker").path("agentSha256").asText());
                    journal.begin("snapshot",runtime.facts().boot());Path archive=runtime.temporary.resolve("snapshot.tar");tar(snapshot.cache(),archive);runtime.call(runtime.request("snapshot",owner),archive);journal.settled(WindowsFiles.JSON.createObjectNode().put("snapshot",true));
                    Snapshot selected=snapshot;selected.fetcher((relative,target)->fetch(owner,relative,target));
                }
                String transport=UUID.randomUUID().toString();ObjectNode resources=(ObjectNode)runtime.lease.read().path("resources").path("windows").deepCopy();((ArrayNode)resources.path("transports")).addObject().put("id",transport);((ArrayNode)resources.path("containers")).addObject().put("name",request.name()).put("transportId",transport);runtime.replace(resources);
                journal.begin("create",runtime.facts().boot());JsonNode created=runtime.bridge("create",owner,"args",WindowsFiles.JSON.valueToTree(request.create()));id=created.path("id").asText();if(!id.matches("[0-9a-f]{64}"))throw new IOException("createのfull IDが不正です");
                resources=(ObjectNode)runtime.lease.read().path("resources").path("windows").deepCopy();for(JsonNode container:resources.path("containers"))if(container.path("transportId").asText().equals(transport))((ObjectNode)container).put("id",id);runtime.replace(resources);journal.settled(created);
                journal.begin("start",runtime.facts().boot());ObjectNode start=runtime.request("start",owner).put("id",id).put("interactive",request.worker());running=runtime.commands.attached(runtime.guest());process=running.process();processStarted=process.info().startInstant().orElseThrow(()->new IOException("native輸送開始identity不明")).toEpochMilli();
                resources=(ObjectNode)runtime.lease.read().path("resources").path("windows").deepCopy();for(JsonNode record:resources.path("transports"))if(record.path("id").asText().equals(transport))((ObjectNode)record).put("pid",process.pid()).put("processStartedAt",java.time.Instant.ofEpochMilli(processStarted).toString());runtime.replace(resources);
                process.getOutputStream().write(WindowsFiles.JSON.writeValueAsBytes(start));process.getOutputStream().write('\n');process.getOutputStream().flush();if(!request.worker())process.getOutputStream().close();
            }
            if(snapshot!=null)exit=WorkerProxy.transfer(process,snapshot,child->{runtime.commands.cleanup();runtime.commands.stop(child);});
            else {
                Process attached=process;var relayFailure=new java.util.concurrent.atomic.AtomicReference<IOException>();Thread relay=Thread.ofVirtual().start(()->{try{attached.getInputStream().transferTo(System.out);}catch(IOException error){relayFailure.set(error);}});
                IOException diagnosticFailure=null;try{exit=runtime.commands.waitFor(process,Duration.ofSeconds(30));}catch(IOException error){diagnosticFailure=error;}finally{try{runtime.commands.join(relay,Duration.ofMillis(500));}catch(IOException error){diagnosticFailure=Commands.merge(diagnosticFailure,error);}}
                if(relayFailure.get()!=null)diagnosticFailure=Commands.merge(diagnosticFailure,relayFailure.get());if(diagnosticFailure!=null)throw diagnosticFailure;
            }
        }catch(IOException error){failure=error;}
        finally {runtime.commands.cleanup();if(process!=null&&process.isAlive())try{runtime.commands.stop(process);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
        if(process!=null&&!process.isAlive()&&id!=null) {
            try(OperationJournal journal=runtime.journal();var ignored=runtime.lock()) {
                if(!runtime.commands.terminal(running,processStarted))throw new IOException("native輸送の肯定終端がありません");
                if(!LeaseReferences.live(runtime.lease.read()))throw new IOException("旧coreの生存を確認できないため追加guest清算を拒否しました");WindowsKeeper.requireClientLive(runtime.keeperRecord());
                ObjectNode owner=runtime.ready();JsonNode proof=runtime.call(runtime.request("remove",owner).put("id",id),null);if(!proof.path("removed").asText().equals(id))throw new IOException("container削除証明が不一致です");removeResource(id);journal.recovered(proof);
            }catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        if(failure!=null)throw failure;return exit;
    }
    private void fetch(ObjectNode owner,Path relative,Path target) throws IOException {
        if(Files.isSymbolicLink(target)||!target.getParent().toRealPath().equals(target.getParent()))throw new IOException("PNG取得先が変化しました");Path part=Files.createTempFile(target.getParent(),"png-",".part");
        try {runtime.commands.fetch(runtime.guest(),WindowsFiles.JSON.writeValueAsBytes(runtime.request("fetch",owner).put("path",relative.toString().replace('\\','/'))),part,Duration.ofSeconds(15));Files.move(part,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(part);}
    }
    private JsonNode find(String nameOrId) throws IOException {for(JsonNode container:runtime.lease.read().path("resources").path("windows").path("containers"))if(container.path("name").asText().equals(nameOrId)||container.path("id").asText().equals(nameOrId)){if(!container.has("id"))throw new IOException("未確定createはjob releaseが必要です");return container;}return null;}
    private void removeResource(String id) throws IOException {ObjectNode resources=(ObjectNode)runtime.lease.read().path("resources").path("windows").deepCopy();ArrayNode remaining=WindowsFiles.JSON.createArrayNode();for(JsonNode container:resources.path("containers"))if(!container.path("id").asText().equals(id))remaining.add(container);resources.set("containers",remaining);runtime.replace(resources);}
    static void tar(Path directory,Path archive) throws IOException {
        try(TarArchiveOutputStream output=new TarArchiveOutputStream(Files.newOutputStream(archive,StandardOpenOption.CREATE_NEW))) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);output.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            try(var paths=Files.walk(directory)){for(Path path:paths.toList()){if(Files.isSymbolicLink(path))throw new IOException("snapshot link拒否");if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;TarArchiveEntry entry=new TarArchiveEntry(directory.relativize(path).toString().replace('\\','/'));entry.setSize(Files.size(path));entry.setMode(0600);output.putArchiveEntry(entry);try(InputStream input=Files.newInputStream(path)){input.transferTo(output);}output.closeArchiveEntry();}}
        }
    }
}
