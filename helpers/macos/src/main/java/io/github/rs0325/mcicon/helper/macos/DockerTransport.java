package io.github.rs0325.mcicon.helper.macos;

import io.github.rs0325.mcicon.helper.DockerRequest;
import io.github.rs0325.mcicon.helper.Snapshot;
import io.github.rs0325.mcicon.helper.WorkerProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 固定Lima argvで専用daemonだけを使い、createと既存full IDのstartを分ける。 */
final class DockerTransport {
    private final MacRuntime runtime;
    DockerTransport(MacRuntime runtime){this.runtime=runtime;}
    int run(List<String> arguments) throws IOException {
        if(arguments.size()==5&&arguments.get(0).equals("image")&&arguments.get(1).equals("inspect")&&arguments.get(2).equals(runtime.imageId())&&arguments.get(3).equals("--format")&&Set.of("{{.Id}}","{{.Os}}/{{.Architecture}} {{.Id}}").contains(arguments.get(4))) {
            runtime.ready();var result=runtime.captureDocker(arguments);System.out.write(result.stdout());System.err.write(result.stderr());return result.exit();
        }
        if(arguments.size()==3&&arguments.getFirst().equals("rm")&&arguments.get(1).equals("-f")) {
            try(OperationJournal journal=runtime.journal(runtime.cleanupBudget(Duration.ofSeconds(20)));CacheState.Lock ignored=runtime.cache.lock(runtime.cleanupBudget(Duration.ofSeconds(20)))) {
                journal.requireActive();journal.awaitRecoverable(runtime.cleanupBudget(Duration.ofSeconds(3)));runtime.ready();JsonNode container=find(arguments.get(2));if(container==null)return 0;remove(journal,container);return 0;
            }
        }
        Snapshot snapshot=null;Process process;String id;
        try(OperationJournal journal=runtime.journal(Duration.ofSeconds(30));CacheState.Lock ignored=runtime.cache.lock(Duration.ofSeconds(30))) {
            journal.requireActive();journal.requireSettled();ObjectNode ready=runtime.ready();JsonNode lease=runtime.lease.read();String job=lease.path("jobId").asText();
            DockerRequest request=DockerRequest.parse(arguments,runtime.descriptor,lease,"/mcicon/jobs/"+job+"/cache",ready.path("generationOwner").asText());
            if(request.worker()) {Path jobStage=runtime.staging().resolve(job);MacFiles.privateDirectory(jobStage);snapshot=Snapshot.create(request.cache(),request.agent(),jobStage.resolve("cache"),request.process(),runtime.descriptor.path("worker").path("agentSha256").asText());}
            id=create(journal,request.name(),request.create());var start=journal.beginStart(id,runtime.ready());
            List<String> argv=new ArrayList<>(runtime.docker("container","start","--attach"));if(request.worker())argv.add("--interactive");argv.add(id);
            process=runtime.commands.generatedPipe(argv,start,runtime.owner(),request.worker());
        }
        IOException transportFailure=null;int exit=1;
        try {
            if(snapshot!=null)exit=WorkerProxy.transfer(process,snapshot,Commands::stop);
            else {try{exit=process.waitFor();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("診断輸送が中断されました");}}
        }catch(IOException failure){transportFailure=failure;}
        finally {if(process.isAlive())try{Commands.stop(process);}catch(IOException failure){if(transportFailure==null)transportFailure=failure;else transportFailure.addSuppressed(failure);}}
        runtime.startCleanup();
        try(OperationJournal journal=runtime.journal(runtime.cleanupBudget(Duration.ofSeconds(20)));CacheState.Lock ignored=runtime.cache.lock(runtime.cleanupBudget(Duration.ofSeconds(20)))) {
            journal.awaitRecoverable(runtime.cleanupBudget(Duration.ofSeconds(3)));JsonNode container=find(id);if(container==null)throw new IOException("輸送対象の所有登録が失われました");remove(journal,container);
        }catch(IOException failure){if(transportFailure==null)transportFailure=failure;else transportFailure.addSuppressed(failure);}
        if(transportFailure!=null)throw transportFailure;return exit;
    }
    void cpuProbe(OperationJournal journal) throws IOException {
        JsonNode lease=runtime.lease.read();String name="mcicon-cpu-"+lease.path("jobId").asText();List<String> argv=new ArrayList<>(List.of("create","--name",name,"--platform","linux/amd64","--network","none","--memory",lease.path("requirements").path("containerMemoryMiB").asLong()+"m","--cpus","4","--entrypoint","/mcicon/cpu-probe","-v","/mcicon/offline/cpu-probe:/mcicon/cpu-probe:ro"));labels(argv,lease,runtime.ready().path("generationOwner").asText());argv.add(runtime.imageId());
        String id=create(journal,name,argv);var start=journal.beginStart(id,runtime.ready());var result=runtime.commands.generate(runtime.docker("container","start","--attach",id),Duration.ofSeconds(30),start,runtime.owner());
        JsonNode container=find(id);if(container==null)throw new IOException("CPU probeの所有登録がありません");remove(journal,container);
        if(result.exit()!=0)throw new IOException("実amd64経路のSSE4.1条件を満たしません（exit "+result.exit()+"）");
    }
    private String create(OperationJournal journal,String name,List<String> arguments) throws IOException {
        ObjectNode resources=runtime.ready().deepCopy();ArrayNode containers=(ArrayNode)resources.path("containers");for(JsonNode existing:containers)if(existing.path("name").asText().equals(name))throw new IOException("container名が既に登録されています");
        containers.addObject().put("name",name);runtime.replace(resources);var intent=journal.begin("create");List<String> argv=new ArrayList<>(runtime.docker());argv.addAll(arguments);
        var result=runtime.commands.generate(argv,Duration.ofSeconds(30),intent,runtime.owner());MacRuntime.requireZero(result,"専用container create");String id=new String(result.stdout(),StandardCharsets.US_ASCII).trim();if(!id.matches("[0-9a-f]{64}"))throw new IOException("createの完全IDが不正です");
        JsonNode actual=inspectOwned(id);if(!actual.path("Name").asText().equals("/"+name))throw new IOException("createのnameが一致しません");
        resources=runtime.ready().deepCopy();for(JsonNode container:resources.path("containers"))if(container.path("name").asText().equals(name))((ObjectNode)container).put("id",id);runtime.replace(resources);journal.settled(intent,actual);return id;
    }
    void removeAll(OperationJournal journal) throws IOException {
        ObjectNode resources=runtime.ready();Map<String,String> registered=new HashMap<>();for(JsonNode container:resources.path("containers")) {if(!container.path("id").isTextual())throw new IOException("完全ID未登録のcontainerを回収できません");registered.put(container.path("id").asText(),container.path("name").asText());}
        for(var start:journal.pendingStarts().values()) {
            JsonNode intent=MacFiles.json(start.directory().resolve("intent.json"));if(!Objects.equals(registered.get(intent.path("containerId").asText()),intent.path("containerName").asText())&&!journal.removed(intent.path("containerId").asText()))throw new IOException("start intentのfull ID/元resourcesが一致しません");
        }
        List<JsonNode> containers=new ArrayList<>();resources.path("containers").forEach(containers::add);for(JsonNode container:containers)remove(journal,container);
    }
    private void remove(OperationJournal journal,JsonNode container) throws IOException {
        String id=container.path("id").asText();if(!id.matches("[0-9a-f]{64}"))throw new IOException("回収には完全container IDが必要です");
        if(!journal.removed(id)) {
            JsonNode actual=inspectOwned(id);if(!actual.path("Name").asText().equals("/"+container.path("name").asText()))throw new IOException("回収container名が一致しません");
            var intent=journal.begin("remove");var result=runtime.commands.recover(runtime.docker("container","rm","--force",id),runtime.cleanupBudget(Duration.ofSeconds(10)),intent);MacRuntime.requireZero(result,"専用container rm");
            if(!new String(result.stdout(),StandardCharsets.US_ASCII).trim().equals(id))throw new IOException("rm応答のfull IDが一致しません");absence(id);ObjectNode evidence=MacFiles.JSON.createObjectNode().put("removedFullId",id).put("absenceVerified",true);journal.settled(intent,evidence);
        }else absence(id);
        ObjectNode proof=MacFiles.JSON.createObjectNode().put("removedFullId",id).put("absenceVerified",true);journal.settleStarts(id,proof);
        ObjectNode resources=runtime.ready().deepCopy();ArrayNode remaining=MacFiles.JSON.createArrayNode();for(JsonNode existing:resources.path("containers"))if(!existing.path("id").asText().equals(id))remaining.add(existing);resources.set("containers",remaining);runtime.replace(resources);
    }
    /** reboot終了した元jobの完全IDだけを、元labelで再照合する。currentjobの所有検査とは分ける。 */
    void removeRebooted(OperationJournal journal,JsonNode previous,JsonNode container) throws IOException {
        runtime.ready();String id=container.path("id").asText();if(!id.matches("[0-9a-f]{64}")||container.path("name").asText().isEmpty())throw new IOException("旧boot containerの完全ID/nameが不正です");
        var inspected=runtime.captureDocker(List.of("container","inspect",id,"--format","{{json .}}"));
        if(inspected.exit()==1&&new String(inspected.stderr(),StandardCharsets.UTF_8).trim().equals("Error response from daemon: No such container: "+id)){absence(id);return;}
        MacRuntime.requireZero(inspected,"旧boot container inspect");JsonNode actual=MacFiles.JSON.readTree(inspected.stdout());verifyRebootContainer(actual,previous,container,runtime.cache.registered().path("generationOwner").asText());
        var intent=journal.begin("remove");var result=runtime.commands.recover(runtime.docker("container","rm","--force",id),runtime.cleanupBudget(Duration.ofSeconds(10)),intent);MacRuntime.requireZero(result,"旧boot container rm");
        if(!new String(result.stdout(),StandardCharsets.US_ASCII).trim().equals(id))throw new IOException("旧boot rmのfull IDが不一致です");absence(id);journal.settled(intent,MacFiles.JSON.createObjectNode().put("removedFullId",id).put("absenceVerified",true));
    }
    static void verifyRebootContainer(JsonNode actual,JsonNode previous,JsonNode container,String generation) throws IOException {
        if(actual==null||!actual.path("Id").equals(container.path("id"))||!actual.path("Name").asText().equals("/"+container.path("name").asText())||!previous.path("resources").path("macos").path("generationOwner").asText().equals(generation))throw new IOException("旧boot containerのID/name/世代が不一致です");
        JsonNode labels=actual.path("Config").path("Labels");for(String key:List.of("jobId","ownerId","descriptorSha256"))if(!labels.path("io.github.rs0325.mcicon."+key).equals(previous.path(key)))throw new IOException("旧boot containerの元identity labelが不一致です");
        if(!labels.path("io.github.rs0325.mcicon.generation").asText().equals(generation))throw new IOException("旧boot containerの元世代labelが不一致です");
    }
    private void absence(String id) throws IOException {
        var missing=runtime.captureDocker(List.of("container","inspect",id,"--format","{{json .}}"));
        String error=new String(missing.stderr(),StandardCharsets.UTF_8).trim();if(missing.exit()!=1||!error.equals("Error response from daemon: No such container: "+id))throw new IOException("正常NotFound応答を確認できません");
        var listed=runtime.captureDocker(List.of("container","ls","--all","--quiet","--no-trunc","--filter","id="+id));MacRuntime.requireZero(listed,"container不存在照合");if(!new String(listed.stdout(),StandardCharsets.US_ASCII).isBlank())throw new IOException("削除後もcontainerが存在します");
    }
    private JsonNode inspectOwned(String id) throws IOException {
        var result=runtime.captureDocker(List.of("container","inspect",id,"--format","{{json .}}"));MacRuntime.requireZero(result,"container所有照合");JsonNode actual=MacFiles.JSON.readTree(result.stdout()),lease=runtime.lease.read();if(actual==null||!actual.path("Id").asText().equals(id))throw new IOException("container full IDが一致しません");JsonNode labels=actual.path("Config").path("Labels");
        for(String key:List.of("jobId","ownerId","descriptorSha256"))if(!labels.path("io.github.rs0325.mcicon."+key).asText().equals(lease.path(key).asText()))throw new IOException("container所有labelが一致しません");
        if(!labels.path("io.github.rs0325.mcicon.generation").asText().equals(runtime.cache.registered().path("generationOwner").asText()))throw new IOException("container世代labelが一致しません");return actual;
    }
    private JsonNode find(String nameOrId) throws IOException {for(JsonNode container:runtime.lease.read().path("resources").path("macos").path("containers"))if(container.path("name").asText().equals(nameOrId)||container.path("id").asText().equals(nameOrId))return container.deepCopy();return null;}
    private static void labels(List<String> arguments,JsonNode lease,String generation) {for(String key:List.of("jobId","ownerId","descriptorSha256"))arguments.addAll(List.of("--label","io.github.rs0325.mcicon."+key+"="+lease.path(key).asText()));arguments.addAll(List.of("--label","io.github.rs0325.mcicon.generation="+generation));}
}
