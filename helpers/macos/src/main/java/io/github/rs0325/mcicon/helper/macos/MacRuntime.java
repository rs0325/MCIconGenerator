package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;

/** 専用VMを1件の描画へ貸し出し、終了後はcontainerを消して停止する。 */
final class MacRuntime {
    final HelperProtocol.Command command;
    final LeaseFile lease;
    final Path temporary;
    final Payload payload;
    final JsonNode descriptor;
    final HostFacts host;
    final CacheState cache;
    final Commands commands;
    private final long preparationEnd;
    private long cleanupEnd;
    MacRuntime(HelperProtocol.Command command,Path executable,LeaseFile lease,long invocationStarted) throws IOException {
        this.command=command;this.lease=lease;descriptor=lease.descriptor();temporary=command.lease().getParent().resolve("tmp");
        if(!temporary.toRealPath().equals(temporary))throw new IOException("job tmpにlinkがあります: "+temporary);
        cleanupEnd=command.operation().equals("release")||(command.operation().equals("docker")&&!command.dockerArguments().isEmpty()&&command.dockerArguments().getFirst().equals("rm"))?invocationStarted+Duration.ofSeconds(30).toNanos():Long.MAX_VALUE;
        payload=new Payload(executable.getParent().getParent().resolve("app/support"));
        Map<String,String> environment=new HashMap<>();for(String key:List.of("TMP","TEMP","TMPDIR"))environment.put(key,temporary.toString());
        JsonNode value=lease.read();Path guard=payload.path("process-guard");Commands bootstrap=new Commands(guard,environment,temporary);bootstrap.cleanupDeadline(cleanupEnd);
        host=HostFacts.read(payload.path("host-probe"),bootstrap,descriptor,value.path("hostId").asText());
        cache=new CacheState(command.root(),value.path("hostId").asText(),value.path("descriptorSha256").asText(),host.uid(),host.bootSessionId());
        environment.put("HOME",cache.home().toString());environment.put("LIMA_HOME",cache.limaHome().toString());environment.put("DOCKER_CONFIG",cache.anchor().resolve("d").toString());environment.put("PATH","/usr/bin:/bin:/usr/sbin:/sbin");environment.put("LC_ALL","C");
        commands=new Commands(guard,environment,temporary);commands.cleanupDeadline(cleanupEnd);
        preparationEnd=System.nanoTime()+Math.max(0,Duration.between(Instant.now(),Instant.parse(value.path("requirements").path("preparationDeadline").asText())).toNanos());
    }
    MacRuntime(HelperProtocol.Command command,LeaseFile lease,Payload payload,HostFacts host,CacheState cache,Commands commands) throws IOException {
        this.command=command;this.lease=lease;this.payload=payload;this.host=host;this.cache=cache;this.commands=commands;
        descriptor=lease.descriptor();temporary=command.lease().getParent().resolve("tmp");
        preparationEnd=System.nanoTime()+Math.max(0,Duration.between(Instant.now(),Instant.parse(lease.read().path("requirements").path("preparationDeadline").asText())).toNanos());cleanupEnd=Long.MAX_VALUE;
    }
    static boolean releaseUnstarted(HelperProtocol.Command command,LeaseFile lease,long started) throws IOException {
        JsonNode value=lease.read();if(!value.path("resources").isEmpty())return false;
        long uid=((Number)Files.getAttribute(command.root(),"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();
        CacheState cache=new CacheState(command.root(),value.path("hostId").asText(),value.path("descriptorSha256").asText(),uid,"00000000-0000-0000-0000-000000000000");
        long left=started+Duration.ofSeconds(30).toNanos()-System.nanoTime();if(left<=0)throw new IOException("解放期限を超えました: "+command.lease()+"。描画を終了して再試行してください");
        try(CacheState.Lock ignored=cache.lock(Duration.ofNanos(left))) {
            ObjectNode state=cache.read();return state==null||!CacheState.ownedBy(state,value);
        }
    }
    void preflight() throws IOException {
        payload.verify();cache.checkSocketLength();
        try(CacheState.Lock ignored=cache.lock(Duration.ofSeconds(5))) {
            ObjectNode state=cache.read();if(state!=null) {
                requireAvailable(state);
                if(!cache.previousBoot(state)&&Files.notExists(cache.anchor(),LinkOption.NOFOLLOW_LINKS))throw failure("同bootの短cacheが失われています");
                if(Files.exists(cache.instance(),LinkOption.NOFOLLOW_LINKS)&&(!state.path("owner").isNull()||cache.previousBoot(state)))stopVm();
            }
            requireMemory(state==null||cache.previousBoot(state)&&Files.notExists(cache.anchor(),LinkOption.NOFOLLOW_LINKS)?desiredMemory():state.path("memoryMiB").asInt(),memoryCapacityMiB(host.memoryBytes()));
            if(Files.getFileStore(command.root()).getUsableSpace()<2L*1024*1024*1024)throw failure("管理rootの空き容量が2GiB未満です");
        }
    }
    void prepare() throws IOException {payload.verify();prepare(lease.verifiedArtifact("guest-base"),lease.verifiedArtifact("renderer-image"));}
    void prepare(LeaseFile.Artifact base,LeaseFile.Artifact image) throws IOException {
        if(base.bytes()!=620224512L||!base.sha256().equals("1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55"))throw failure("固定offline closureとguest baseが一致しません");
        try(CacheState.Lock ignored=cache.lock(timeLeft())) {
            JsonNode current=lease.read();if(!current.path("resources").isEmpty())throw failure("同一jobのprepareを重複実行できません");
            ObjectNode state=cache.read();boolean fresh=state==null;
            if(fresh&&Files.exists(cache.anchor(),LinkOption.NOFOLLOW_LINKS))throw failure("状態ファイルがない短cacheが存在します");
            if(!fresh) {
                requireAvailable(state);
                if(Files.notExists(cache.anchor(),LinkOption.NOFOLLOW_LINKS)) {
                    if(!cache.previousBoot(state))throw failure("同bootの短cacheが失われています");
                    fresh=true;
                }
            }
            if(fresh)state=cache.create(desiredMemory());
            boolean stale=!state.path("owner").isNull()||cache.previousBoot(state);
            int memory=state.path("memoryMiB").asInt();if(current.path("requirements").path("containerMemoryMiB").asLong()+1024>memory)throw failure("既存VMのメモリが今回の要求に不足しています");
            boolean exists=Files.exists(cache.instance(),LinkOption.NOFOLLOW_LINKS);
            cache.directories();CacheState.assign(state,current);cache.currentBoot(state);cache.save(state);
            replace(resources(state,"starting"));
            if(exists&&stale)stopVm();
            else if(exists&&!instance().path("status").asText().equals("Stopped"))throw failure("持ち主がいない専用VMが停止していません");
            if(!exists||!instance().path("status").asText().equals("Running")) {
                requireMemory(memory,memoryCapacityMiB(host.memoryBytes()));
                if(exists)requireZero(commands.generate(lima("start","--tty=false","v"),timeLeft(),owner()),"専用VMの起動");
                else {
                    Path yaml=cache.anchor().resolve("instance.yaml");
                    MacFiles.write(yaml,VmConfiguration.create(base.path(),base.sha256(),image.path().getParent(),staging(),payload.root().resolve("offline"),memory,VmConfiguration.guestMarker(state.path("generationOwner").asText(),current.path("descriptorSha256").asText()),payload.debNames()));
                    requireZero(commands.generate(lima("start","--tty=false","--name=v",yaml.toString()),timeLeft(),owner()),"専用VMの作成");
                }
            }
            verifyGuest(state);new DockerTransport(this).removeAll(state.path("generationOwner").asText());
            replace(resources(state,"ready"));
            requireZero(commands.generate(docker("image","load","--input","/mcicon/runtime/"+image.path().getFileName()),timeLeft(),owner()),"固定rendererのload");
            JsonNode verified=imageInspect();if(!verified.path("Id").asText().equals(imageId())||!verified.path("Os").asText().equals("linux")||!verified.path("Architecture").asText().equals("amd64"))throw failure("rendererのID/platformが一致しません");
            new DockerTransport(this).cpuProbe();
        }
    }
    int docker(List<String> arguments) throws IOException {return new DockerTransport(this).run(arguments);}
    void release() throws IOException {
        try(CacheState.Lock ignored=cache.lock(cleanupBudget(Duration.ofSeconds(30)))) {
            ObjectNode state=cache.read();JsonNode current=lease.read();
            if(state==null||!CacheState.ownedBy(state,current)) {
                if(!current.path("resources").isEmpty())throw failure("解放するjobとVMの持ち主が一致しません");
                return;
            }
            boolean rebooted=cache.previousBoot(state);
            if(Files.exists(cache.instance(),LinkOption.NOFOLLOW_LINKS)) {
                String status=instance().path("status").asText();
                if(rebooted||!CacheState.ownerLive(state)) {
                    stopVm();requireZero(commands.capture(lima("start","--tty=false","v"),cleanupBudget(Duration.ofSeconds(30))),"残ったcontainerを片付けるためのVM起動");
                }else if(!status.equals("Running")&&!status.equals("Stopped"))throw failure("専用VMの状態が不明です: "+status);
                if(instance().path("status").asText().equals("Running")) {
                    verifyGuest(state);new DockerTransport(this).removeAll(state.path("generationOwner").asText());stopVm();
                }
            }else if(!rebooted&&Files.exists(cache.anchor(),LinkOption.NOFOLLOW_LINKS)&&current.path("resources").path("macos").path("phase").asText().equals("ready"))throw failure("解放対象のLima instanceがありません");
            Path stage=command.root().resolve("macos-staging").resolve(current.path("descriptorSha256").asText()).resolve(current.path("jobId").asText());
            if(Files.exists(stage,LinkOption.NOFOLLOW_LINKS))Files.walkFileTree(stage,new SimpleFileVisitor<>() {
                public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
                public FileVisitResult postVisitDirectory(Path directory,IOException error) throws IOException {if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;}
            });
            clearResources();state.putNull("owner");cache.save(state);
        }
    }
    private void requireAvailable(JsonNode state) throws IOException {
        if(!cache.previousBoot(state)&&CacheState.ownerLive(state))throw failure("専用VMを使用中の描画があります（PID "+state.path("owner").path("pid").asLong()+"）。終了後に再試行してください");
    }
    private int desiredMemory() throws IOException {return desiredMemory(lease.read().path("requirements").path("containerMemoryMiB").asLong());}
    static int desiredMemory(long request){return Math.toIntExact(Math.max(2048,Math.addExact(Math.multiplyExact(request,2),1024)));}
    static long memoryCapacityMiB(long memoryBytes){return memoryBytes/1024/1024-2048;}
    static void requireMemory(long requested,long available) throws IOException {if(requested>available)throw new IOException("VMに必要なホストのメモリ容量が不足しています（要求 "+requested+" MiB、容量 "+available+" MiB）。VMの要求メモリを減らして再生成するか、搭載メモリの多いMacで再試行してください");}
    Commands.Owner owner() throws IOException {
        JsonNode value=lease.read();return new Commands.Owner(value.path("pid").asLong(),Instant.parse(value.path("processStartedAt").asText()).toEpochMilli());
    }
    ObjectNode ready() throws IOException {
        ObjectNode state=cache.read();if(state==null||cache.previousBoot(state)||!CacheState.ownedBy(state,lease.read())||!lease.read().path("resources").path("macos").path("phase").asText().equals("ready"))throw failure("このjobのVM準備が完了していません");
        verifyGuest(state);return (ObjectNode)lease.read().path("resources").path("macos");
    }
    private static ObjectNode resources(JsonNode state,String phase) {
        ObjectNode value=MacFiles.JSON.createObjectNode().put("phase",phase).put("generationOwner",state.path("generationOwner").asText()).put("instanceDir",state.path("instanceDir").asText());value.putArray("containers");return value;
    }
    void replace(ObjectNode mac) throws IOException {ObjectNode current=lease.read(),resources=MacFiles.JSON.createObjectNode();resources.set("macos",mac);lease.replaceResources((ObjectNode)current.path("resources"),resources);}
    private void clearResources() throws IOException {ObjectNode current=lease.read();lease.replaceResources((ObjectNode)current.path("resources"),MacFiles.JSON.createObjectNode());}
    private void verifyGuest(JsonNode state) throws IOException {
        if(!instance().path("status").asText().equals("Running"))throw failure("専用VMが稼働していません");
        var marker=commands.capture(lima("shell","--workdir=/","v","--","sudo","--","/bin/cat","/var/lib/mcicon-owner.json"),cleanupBudget(Duration.ofSeconds(10)));requireZero(marker,"guest owner照合");
        if(!VmConfiguration.guestMarker(state.path("generationOwner").asText(),lease.read().path("descriptorSha256").asText()).equals(MacFiles.JSON.readTree(marker.stdout())))throw failure("guest owner markerが一致しません");
        var daemon=captureDocker(List.of("info","--format","{{json .Labels}}"));requireZero(daemon,"daemon owner照合");JsonNode labels=MacFiles.JSON.readTree(daemon.stdout());boolean found=false;
        if(labels!=null&&labels.isArray())for(JsonNode label:labels)if(label.asText().equals("io.github.rs0325.mcicon.generation="+state.path("generationOwner").asText()))found=true;
        if(!found)throw failure("daemonのgeneration labelが一致しません");
    }
    private void stopVm() throws IOException {
        String status=instance().path("status").asText();if(status.equals("Stopped"))return;if(!status.equals("Running"))throw failure("専用VMの状態が不明です: "+status);
        requireZero(commands.capture(lima("stop","v"),cleanupBudget(Duration.ofSeconds(30))),"専用VM停止");
        if(!instance().path("status").asText().equals("Stopped"))throw failure("専用VMの停止を確認できません");
    }
    JsonNode instance() throws IOException {
        var result=commands.capture(lima("list","--json","v"),cleanupBudget(Duration.ofSeconds(5)));requireZero(result,"専用instance照合");JsonNode value=MacFiles.JSON.readTree(result.stdout());
        if(value==null||!value.path("name").asText().equals("v")||!value.path("dir").asText().equals(cache.instance().toString())||!value.path("vmType").asText().equals("vz")||!value.path("arch").asText().equals("aarch64"))throw failure("Lima instanceの場所・種類が一致しません");return value;
    }
    String imageId(){return descriptor.path("rendering").path("imageId").asText();}
    Path staging() throws IOException {Path parent=command.root().resolve("macos-staging");MacFiles.privateDirectory(parent);Path stage=parent.resolve(lease.read().path("descriptorSha256").asText());MacFiles.privateDirectory(stage);return stage;}
    List<String> lima(String... arguments) throws IOException {List<String> argv=new ArrayList<>(List.of(payload.path("bin/limactl").toString(),"--log-level=error"));argv.addAll(List.of(arguments));return argv;}
    List<String> docker(String... arguments) throws IOException {List<String> argv=new ArrayList<>(lima("shell","--workdir=/","v","--","sudo","--","/usr/bin/docker"));argv.addAll(List.of(arguments));return argv;}
    Commands.Result captureDocker(List<String> arguments) throws IOException {List<String> argv=new ArrayList<>(docker());argv.addAll(arguments);return commands.capture(argv,cleanupBudget(Duration.ofSeconds(10)));}
    JsonNode imageInspect() throws IOException {var result=captureDocker(List.of("image","inspect",imageId(),"--format","{{json .}}"));requireZero(result,"renderer inspect");return MacFiles.JSON.readTree(result.stdout());}
    void startCleanup(){cleanupEnd=Math.min(cleanupEnd,System.nanoTime()+Duration.ofSeconds(30).toNanos());commands.cleanupDeadline(cleanupEnd);}
    Duration cleanupBudget(Duration maximum) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("回収が中断されました");if(cleanupEnd==Long.MAX_VALUE)return maximum;
        long left=cleanupEnd-System.nanoTime();if(left<=0)throw failure("回収操作の期限を超えました");return Duration.ofNanos(Math.min(left,maximum.toNanos()));
    }
    private Duration timeLeft() throws IOException {long left=preparationEnd-System.nanoTime();if(left<=0)throw failure("VM準備期限を超えました");return Duration.ofNanos(left);}
    IOException failure(String detail){return new IOException(detail+"。対象: "+cache.instance()+"。LIMA_HOME="+cache.limaHome()+" limactl delete vを実行し、cache "+cache.anchor()+" と状態ファイル "+cache.file()+" を削除して再試行してください");}
    static void requireZero(Commands.Result result,String operation) throws IOException {if(result.exit()!=0)throw new IOException(operation+"が失敗しました（exit "+result.exit()+"）。helperの診断にある専用VMとcacheを確認してください");}
}
