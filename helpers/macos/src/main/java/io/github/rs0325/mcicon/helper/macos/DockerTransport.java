package io.github.rs0325.mcicon.helper.macos;

import io.github.rs0325.mcicon.helper.DockerRequest;
import io.github.rs0325.mcicon.helper.Snapshot;
import io.github.rs0325.mcicon.helper.WorkerProxy;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** 専用daemonのgeneration labelでcontainerを回収する。 */
final class DockerTransport {
    private final MacRuntime runtime;
    DockerTransport(MacRuntime runtime){this.runtime=runtime;}
    int run(List<String> arguments) throws IOException {
        if(arguments.size()==5&&arguments.get(0).equals("image")&&arguments.get(1).equals("inspect")&&arguments.get(2).equals(runtime.imageId())&&arguments.get(3).equals("--format")&&Set.of("{{.Id}}","{{.Os}}/{{.Architecture}} {{.Id}}").contains(arguments.get(4))) {
            runtime.ready();var result=runtime.captureDocker(arguments);System.out.write(result.stdout());System.err.write(result.stderr());return result.exit();
        }
        if(arguments.size()==3&&arguments.getFirst().equals("rm")&&arguments.get(1).equals("-f")) {
            try(CacheState.Lock ignored=runtime.cache.lock(runtime.cleanupBudget(Duration.ofSeconds(30)))) {
                runtime.ready();String target=arguments.get(2);
                var listed=runtime.captureDocker(List.of("container","ls","--all","--quiet","--no-trunc","--filter","label=io.github.rs0325.mcicon.generation="+runtime.ready().path("generationOwner").asText(),"--filter","label=io.github.rs0325.mcicon.jobId="+runtime.lease.read().path("jobId").asText()));
                MacRuntime.requireZero(listed,"回収対象container一覧");
                for(String id:ids(listed.stdout())) {JsonNode actual=inspectOwned(id);if(id.equals(target)||actual.path("Name").asText().equals("/"+target))remove(id);}
                return 0;
            }
        }
        Snapshot snapshot=null;Process process;String id;
        try(CacheState.Lock ignored=runtime.cache.lock(Duration.ofSeconds(30))) {
            JsonNode ready=runtime.ready(),lease=runtime.lease.read();String job=lease.path("jobId").asText();
            DockerRequest request=DockerRequest.parse(arguments,runtime.descriptor,lease,"/mcicon/jobs/"+job+"/cache",ready.path("generationOwner").asText());
            if(request.worker()) {Path jobStage=runtime.staging().resolve(job);MacFiles.privateDirectory(jobStage);snapshot=Snapshot.create(request.cache(),request.agent(),jobStage.resolve("cache"),request.process(),runtime.descriptor.path("worker").path("agentSha256").asText());}
            id=create(request.name(),request.create());
            List<String> argv=new ArrayList<>(runtime.docker("container","start","--attach"));if(request.worker())argv.add("--interactive");argv.add(id);
            process=runtime.commands.generatedPipe(argv,runtime.owner(),request.worker());
        }
        IOException failure=null;int exit=1;
        try {
            if(snapshot!=null)exit=WorkerProxy.transfer(process,snapshot,Commands::stop);
            else try{exit=process.waitFor();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("診断輸送が中断されました");}
        }catch(IOException original){failure=original;}
        finally {
            try{if(process.isAlive())Commands.stop(process);runtime.commands.finish(process);}
            catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        }
        runtime.startCleanup();
        try(CacheState.Lock ignored=runtime.cache.lock(runtime.cleanupBudget(Duration.ofSeconds(30)))) {runtime.ready();inspectOwned(id);remove(id);}
        catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        if(failure!=null)throw failure;return exit;
    }
    void cpuProbe() throws IOException {
        JsonNode lease=runtime.lease.read();String name="mcicon-cpu-"+lease.path("jobId").asText();List<String> argv=new ArrayList<>(List.of("create","--name",name,"--platform","linux/amd64","--network","none","--memory",lease.path("requirements").path("containerMemoryMiB").asLong()+"m","--cpus","4","--entrypoint","/mcicon/cpu-probe","-v","/mcicon/offline/cpu-probe:/mcicon/cpu-probe:ro"));
        labels(argv,lease,runtime.ready().path("generationOwner").asText());argv.add(runtime.imageId());String id=create(name,argv);
        var result=runtime.commands.generate(runtime.docker("container","start","--attach",id),Duration.ofSeconds(30),runtime.owner());remove(id);
        if(result.exit()!=0)throw runtime.failure("実amd64経路のSSE4.1条件を満たしません（exit "+result.exit()+"）");
    }
    private String create(String name,List<String> arguments) throws IOException {
        List<String> argv=new ArrayList<>(runtime.docker());argv.addAll(arguments);var result=runtime.commands.generate(argv,Duration.ofSeconds(30),runtime.owner());MacRuntime.requireZero(result,"専用container create");
        String id=new String(result.stdout(),StandardCharsets.US_ASCII).trim();if(!id.matches("[0-9a-f]{64}"))throw runtime.failure("createの完全IDが不正です");
        if(!inspectOwned(id).path("Name").asText().equals("/"+name))throw runtime.failure("createのnameが一致しません");return id;
    }
    void removeAll(String generation) throws IOException {
        var result=runtime.captureDocker(List.of("container","ls","--all","--quiet","--no-trunc","--filter","label=io.github.rs0325.mcicon.generation="+generation));MacRuntime.requireZero(result,"generationのcontainer一覧");
        for(String id:ids(result.stdout()))remove(id);
    }
    private void remove(String id) throws IOException {
        var result=runtime.commands.capture(runtime.docker("container","rm","--force",id),runtime.cleanupBudget(Duration.ofSeconds(10)));MacRuntime.requireZero(result,"専用container rm "+id);
    }
    static List<String> ids(byte[] output) throws IOException {
        List<String> result=new ArrayList<>();for(String line:new String(output,StandardCharsets.US_ASCII).lines().toList()) {
            if(!line.matches("[0-9a-f]{64}"))throw new IOException("container一覧の完全IDが不正です。専用VMをlimactl delete vで削除して再試行してください");result.add(line);
        }return result;
    }
    private JsonNode inspectOwned(String id) throws IOException {
        var result=runtime.captureDocker(List.of("container","inspect",id,"--format","{{json .}}"));MacRuntime.requireZero(result,"container所有照合 "+id);JsonNode actual=MacFiles.JSON.readTree(result.stdout()),lease=runtime.lease.read();
        if(actual==null||!actual.path("Id").asText().equals(id))throw runtime.failure("container full IDが一致しません");JsonNode labels=actual.path("Config").path("Labels");
        for(String key:List.of("jobId","ownerId","descriptorSha256"))if(!labels.path("io.github.rs0325.mcicon."+key).asText().equals(lease.path(key).asText()))throw runtime.failure("container所有labelが一致しません");
        if(!labels.path("io.github.rs0325.mcicon.generation").asText().equals(runtime.ready().path("generationOwner").asText()))throw runtime.failure("container世代labelが一致しません");return actual;
    }
    private static void labels(List<String> arguments,JsonNode lease,String generation) {
        for(String key:List.of("jobId","ownerId","descriptorSha256"))arguments.addAll(List.of("--label","io.github.rs0325.mcicon."+key+"="+lease.path(key).asText()));arguments.addAll(List.of("--label","io.github.rs0325.mcicon.generation="+generation));
    }
}
