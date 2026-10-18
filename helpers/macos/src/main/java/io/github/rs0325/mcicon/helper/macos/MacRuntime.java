package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 固定Mac VMの所有照合と、jobごとの準備・輸送・回収を受け持つ。 */
final class MacRuntime {
    final HelperProtocol.Command command;
    final LeaseFile lease;
    final Path executable,temporary;
    final Payload payload;
    final JsonNode descriptor;
    final HostFacts host;
    final CacheState cache;
    final Commands commands;
    private final long preparationEnd;
    private long cleanupEnd;
    MacRuntime(HelperProtocol.Command command,Path executable,LeaseFile lease,long invocationStarted) throws IOException {
        this.command=command;this.executable=executable;this.lease=lease;cleanupEnd=command.operation().equals("release")||(command.operation().equals("docker")&&!command.dockerArguments().isEmpty()&&command.dockerArguments().getFirst().equals("rm"))?invocationStarted+Duration.ofSeconds(9).toNanos():Long.MAX_VALUE;descriptor=lease.descriptor();temporary=command.lease().getParent().resolve("tmp");
        if(!temporary.toRealPath().equals(temporary))throw new IOException("job tmpにlinkがあります");
        Path support=executable.getParent().getParent().resolve("app/support");payload=new Payload(support);
        Map<String,String> environment=new HashMap<>();for(String key:List.of("TMP","TEMP","TMPDIR"))environment.put(key,temporary.toString());
        JsonNode value=lease.read();Path guard=payload.path("process-guard");Commands bootstrap=new Commands(guard,environment,temporary);bootstrap.cleanupDeadline(cleanupEnd);host=HostFacts.read(payload.path("host-probe"),bootstrap,descriptor,value.path("hostId").asText());
        cache=new CacheState(command.root(),value.path("hostId").asText(),value.path("descriptorSha256").asText(),host.uid(),host.hostBindingSha256(),host.bootSessionId());
        environment.put("HOME",cache.home().toString());environment.put("LIMA_HOME",cache.limaHome().toString());environment.put("DOCKER_CONFIG",cache.anchor().resolve("d").toString());environment.put("PATH","/usr/bin:/bin:/usr/sbin:/sbin");environment.put("LC_ALL","C");
        commands=new Commands(guard,environment,temporary);commands.cleanupDeadline(cleanupEnd);
        Duration remaining=Duration.between(Instant.now(),Instant.parse(value.path("requirements").path("preparationDeadline").asText()));
        preparationEnd=System.nanoTime()+Math.max(0,remaining.toNanos());
    }
    static boolean releaseUnstarted(HelperProtocol.Command command,LeaseFile lease,long invocationStarted) throws IOException {
        long deadline=invocationStarted+Duration.ofSeconds(9).toNanos();
        JsonNode initial=lease.read();Path temporary=command.lease().getParent().resolve("tmp");long uid=((Number)Files.getAttribute(command.root(),"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();CacheState cache=new CacheState(command.root(),initial.path("hostId").asText(),initial.path("descriptorSha256").asText(),uid);
        try(OperationJournal journal=OperationJournal.acquire(temporary,initial,bounded(deadline,Duration.ofSeconds(5)));CacheState.Lock ignored=cache.lock(bounded(deadline,Duration.ofSeconds(5)))) {
            JsonNode current=lease.read();if(!current.path("resources").isEmpty()||!journal.unstarted())return false;
            try{owner(lease);}catch(IOException unknownOwner){return false;}journal.markReleased();lease.replaceResources((ObjectNode)current.path("resources"),MacFiles.JSON.createObjectNode());return true;
        }
    }
    void preflight() throws IOException {
        payload.verify();cache.checkSocketLength();
        try(OperationJournal operation=journal(Duration.ofSeconds(5));CacheState.Lock ignored=cache.lock(Duration.ofSeconds(5))) {
            operation.requireActive();new RebootRecovery(this).prepare(operation);LeaseReferences.Scan references=references();if(references.unknown())throw new IOException("他leaseの所有が不明です: "+references.diagnostics());
            memoryMiB(references);if(Files.exists(cache.anchor(),LinkOption.NOFOLLOW_LINKS))cache.verify();
            if(Files.getFileStore(command.root()).getUsableSpace()<2L*1024*1024*1024)throw new IOException("VM準備の空き容量が不足しています");
        }
    }
    void prepare() throws IOException {
        payload.verify();prepare(lease.verifiedArtifact("guest-base"),lease.verifiedArtifact("renderer-image"));
    }
    void prepare(LeaseFile.Artifact base,LeaseFile.Artifact image) throws IOException {
        if(base.bytes()!=620224512L||!base.sha256().equals("1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55"))throw new IOException("固定offline closureとguest baseが一致しません");
        try(OperationJournal operation=journal(timeLeft());CacheState.Lock ignored=cache.lock(timeLeft())) {
            operation.requireActive();operation.requireSettled();new RebootRecovery(this).prepare(operation);LeaseReferences.Scan references=references();if(references.unknown())throw new IOException("他leaseの所有が不明です: "+references.diagnostics());
            ObjectNode current=lease.read();if(!current.path("resources").isEmpty())throw new IOException("同一jobのprepareを重複実行できません");boolean fresh=cache.freshIncarnation();boolean recreate=(cache.firstUse()&&references.pristine()||fresh)&&operation.unstarted();ObjectNode registration=cache.register(references,operation);String owner=registration.path("generationOwner").asText();
            Path staging=staging();Path vmRecord=cache.anchor().resolve("vm.json");ObjectNode ready;
            if(Files.exists(cache.instance(),LinkOption.NOFOLLOW_LINKS)) {
                JsonNode record=MacFiles.json(vmRecord);checkRegistration(record,registration);ObjectNode previous=(ObjectNode)record.required("resources");checkDisk(previous);
                JsonNode instance=instance();String status=instance.path("status").asText();
                if(status.equals("Stopped")) {
                    if(!references.restartAllowed())throw new IOException("生成着手済み他leaseがあるためVMを再起動できません");
                    ObjectNode starting=previous.deepCopy();starting.put("phase","starting");starting.putArray("containers");replace(starting);
                    var intent=operation.begin("prepare");Commands.Result start=commands.generate(lima("start","--tty=false","v"),timeLeft(),intent,owner());requireZero(start,"専用VMの再起動");
                    ready=inspect(owner,previous.path("machineIdSha256").asText(),previous.path("forwardConfigSha256").asText());saveVm(registration,ready,record.path("memoryMiB").asInt());operation.settled(intent,ready);
                }else if(status.equals("Running")) {ready=previous.deepCopy();verifyReady(ready);}
                else throw new IOException("専用VMの状態が不明です");
                long required=current.path("requirements").path("containerMemoryMiB").asLong()+references.memoryMiB()+1024;
                if(required>record.path("memoryMiB").asLong())throw new IOException("既存VMの並行job予算を超えています");
            }else {
                if(Files.exists(vmRecord,LinkOption.NOFOLLOW_LINKS)||!recreate)throw new IOException("VM実体が失われたためactiveを保持します");
                if(fresh)cache.consumeFreshIncarnation();
                int memory=memoryMiB(references);ObjectNode starting=MacFiles.JSON.createObjectNode().put("phase","starting").put("generationOwner",owner).put("instanceDir",cache.instance().toString());starting.putArray("containers");replace(starting);
                Path yaml=cache.anchor().resolve("instance.yaml");MacFiles.write(yaml,VmConfiguration.create(base.path(),base.sha256(),image.path().getParent(),staging,payload.root().resolve("offline"),memory,VmConfiguration.guestMarker(owner,current.path("descriptorSha256").asText()),payload.debNames()));
                var intent=operation.begin("prepare");Commands.Result start=commands.generate(lima("start","--tty=false","--name=v",yaml.toString()),timeLeft(),intent,owner());requireZero(start,"専用VMの初回準備");
                ready=inspect(owner,null,null);saveVm(registration,ready,memory);operation.settled(intent,ready);
            }
            ready.putArray("containers");replace(ready);new RebootRecovery(this).cleanupDisk(operation);
            var load=operation.begin("prepare");requireZero(commands.generate(docker("image","load","--input","/mcicon/runtime/"+image.path().getFileName()),timeLeft(),load,owner()),"固定rendererのload");
            JsonNode verified=imageInspect();if(!verified.path("Id").asText().equals(imageId())||!verified.path("Os").asText().equals("linux")||!verified.path("Architecture").asText().equals("amd64"))throw new IOException("rendererのID/platformが一致しません");operation.settled(load,verified);
            new DockerTransport(this).cpuProbe(operation);
        }
    }
    int docker(List<String> arguments) throws IOException {return new DockerTransport(this).run(arguments);}
    void release() throws IOException {
        try(OperationJournal operation=journal(cleanupBudget(Duration.ofSeconds(30)));CacheState.Lock ignored=cache.lock(cleanupBudget(Duration.ofSeconds(30)))) {
            if(new RebootRecovery(this).release(operation))return;cache.verify();ObjectNode current=lease.read();
            if(current.path("resources").isEmpty()&&operation.unstarted()){operation.markReleased();return;}
            new RebootRecovery(this).requireDiskCleaned();
            if(operation.released()) {if(!current.path("resources").isEmpty())lease.replaceResources((ObjectNode)current.get("resources"),MacFiles.JSON.createObjectNode());return;}
            operation.awaitRecoverable(cleanupBudget(Duration.ofSeconds(3)));
            if(!current.path("resources").isEmpty()) {
                ObjectNode resources=(ObjectNode)current.path("resources").path("macos");verifyReady(resources);resources=resources.deepCopy();resources.put("phase","releasing");replace(resources);
                new DockerTransport(this).removeAll(operation);
                LeaseReferences.Scan others=references();
                if(others.stopAllowed()) {
                    verifyReady((ObjectNode)lease.read().path("resources").path("macos"));var stop=operation.begin("stop");
                    requireZero(commands.recover(lima("stop","v"),cleanupBudget(Duration.ofSeconds(15)),stop),"専用VM停止");
                    if(!instance().path("status").asText().equals("Stopped"))throw new IOException("専用VM停止を確認できません");
                    ObjectNode evidence=MacFiles.JSON.createObjectNode().put("status","Stopped").put("machineIdSha256",resources.path("machineIdSha256").asText());operation.settled(stop,evidence);
                }
            }
            operation.markReleased();current=lease.read();lease.replaceResources((ObjectNode)current.path("resources"),MacFiles.JSON.createObjectNode());
        }
    }
    OperationJournal journal(Duration budget) throws IOException {return OperationJournal.acquire(temporary,lease.read(),budget);}
    LeaseReferences.Scan references() throws IOException {return LeaseReferences.inspect(command,executable,lease.read().path("descriptorSha256").asText());}
    Commands.Owner owner() throws IOException {return owner(lease);}
    private static Commands.Owner owner(LeaseFile lease) throws IOException {
        JsonNode value=lease.read();if(!value.path("processStartedAt").isTextual())throw new IOException("元core開始identityが不明です");
        long pid=value.path("pid").asLong();Instant started=Instant.parse(value.path("processStartedAt").asText());
        if(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).filter(started::equals).isEmpty())throw new IOException("元coreの生存/開始identityを確認できません");return new Commands.Owner(pid,started.toEpochMilli());
    }
    String imageId(){return descriptor.path("rendering").path("imageId").asText();}
    Path staging() throws IOException {Path parent=command.root().resolve("macos-staging");MacFiles.privateDirectory(parent);Path stage=parent.resolve(lease.read().path("descriptorSha256").asText());MacFiles.privateDirectory(stage);return stage;}
    List<String> lima(String... arguments) throws IOException {List<String> argv=new ArrayList<>(List.of(payload.path("bin/limactl").toString(),"--log-level=error"));argv.addAll(List.of(arguments));return argv;}
    List<String> docker(String... arguments) throws IOException {List<String> argv=new ArrayList<>(lima("shell","--workdir=/","v","--","sudo","--","/usr/bin/docker"));argv.addAll(List.of(arguments));return argv;}
    Commands.Result captureDocker(List<String> arguments) throws IOException {List<String> argv=new ArrayList<>(docker());argv.addAll(arguments);return commands.capture(argv,cleanupBudget(Duration.ofSeconds(10)));}
    JsonNode imageInspect() throws IOException {var result=captureDocker(List.of("image","inspect",imageId(),"--format","{{json .}}"));requireZero(result,"renderer inspect");return MacFiles.JSON.readTree(result.stdout());}
    ObjectNode ready() throws IOException {ObjectNode value=lease.read();if(!value.path("resources").path("macos").isObject())throw new IOException("VM準備が完了していません");ObjectNode ready=(ObjectNode)value.path("resources").path("macos");verifyReady(ready);return ready;}
    void replace(ObjectNode mac) throws IOException {ObjectNode current=lease.read(),resources=MacFiles.JSON.createObjectNode();resources.set("macos",mac);lease.replaceResources((ObjectNode)current.path("resources"),resources);}
    void verifyReady(ObjectNode expected) throws IOException {
        cache.verify();if(!expected.path("generationOwner").asText().equals(cache.registered().path("generationOwner").asText())||!expected.path("instanceDir").asText().equals(cache.instance().toString()))throw new IOException("VM登録とleaseが一致しません");
        ObjectNode actual=inspect(expected.path("generationOwner").asText(),expected.path("machineIdSha256").asText(),expected.path("forwardConfigSha256").asText());
        for(String key:List.of("hostAgent","dockerSocket","guestOwnerMarkerSha256"))if(!actual.get(key).equals(expected.get(key)))throw new IOException("VM実所有が変化しました: "+key);
    }
    private ObjectNode inspect(String owner,String machineHash,String configurationHash) throws IOException {
        cache.verify();if(!cache.instance().toRealPath().equals(cache.instance()))throw new IOException("VM instanceにlinkがあります");
        String machine=checkedHash(cache.instance().resolve("vz-identifier"),4096),configuration=checkedHash(cache.instance().resolve("lima.yaml"),1024*1024);
        if(machineHash!=null&&!machine.equals(machineHash)||configurationHash!=null&&!configuration.equals(configurationHash))throw new IOException("VM machine/config hashが一致しません");
        Path pidFile=cache.instance().resolve("ha.pid");String pidText=readSmall(pidFile,64).trim();long pid;try{pid=Long.parseLong(pidText);}catch(NumberFormatException failure){throw new IOException("hostAgent PIDが不正です",failure);}
        ProcessHandle process=ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow(()->new IOException("hostAgentが存在しません"));Instant started=process.info().startInstant().orElseThrow(()->new IOException("hostAgent開始identityが不明です"));
        if(!readSmall(cache.instance().resolve("boot-session.tmp"),256).trim().toLowerCase(Locale.ROOT).equals(host.bootSessionId()))throw new IOException("VM boot sessionが一致しません");
        var uid=commands.capture(List.of("/bin/ps","-p",Long.toString(pid),"-o","uid="),cleanupBudget(Duration.ofSeconds(3)));requireZero(uid,"hostAgent UID照合");if(!new String(uid.stdout(),StandardCharsets.US_ASCII).trim().equals(Long.toString(host.uid())))throw new IOException("hostAgent UIDが一致しません");
        var marker=commands.capture(lima("shell","--workdir=/","v","--","sudo","--","/bin/cat","/var/lib/mcicon-owner.json"),cleanupBudget(Duration.ofSeconds(10)));requireZero(marker,"guest owner照合");
        ObjectNode guest=VmConfiguration.guestMarker(owner,lease.read().path("descriptorSha256").asText());JsonNode actualMarker=MacFiles.JSON.readTree(marker.stdout());if(actualMarker==null||!actualMarker.equals(guest))throw new IOException("guest owner markerが一致しません");
        var daemon=captureDocker(List.of("info","--format","{{json .Labels}}"));requireZero(daemon,"daemon owner照合");JsonNode labels=MacFiles.JSON.readTree(daemon.stdout());if(labels==null||!labels.isArray())throw new IOException("daemon labelsが不正です");boolean found=false;for(JsonNode label:labels)if(label.asText().equals("io.github.rs0325.mcicon.generation="+owner))found=true;if(!found)throw new IOException("daemon owner labelが一致しません");
        Path socket=cache.socket();if(!socket.toRealPath().equals(socket)||(((Number)Files.getAttribute(socket,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue()&0170000)!=0140000)throw new IOException("Docker輸送が専用socketではありません");
        ObjectNode result=MacFiles.JSON.createObjectNode().put("phase","ready").put("generationOwner",owner).put("instanceDir",cache.instance().toString()).put("machineIdSha256",machine).put("forwardConfigSha256",configuration).put("guestOwnerMarkerSha256",MacFiles.hash(marker.stdout()));result.putArray("containers");
        result.putObject("hostAgent").put("pid",pid).put("processStartedAt",started.toString()).put("bootSessionId",host.bootSessionId());ObjectNode transport=result.putObject("dockerSocket").put("path",socket.toString());for(String key:List.of("dev","ino","uid"))transport.put(key,((Number)Files.getAttribute(socket,"unix:"+key,LinkOption.NOFOLLOW_LINKS)).longValue());
        if(transport.path("uid").asLong()!=host.uid())throw new IOException("Docker socket UIDが一致しません");return (ObjectNode)MacFiles.JSON.readTree(MacFiles.JSON.writeValueAsBytes(result));
    }
    void checkDisk(ObjectNode previous) throws IOException {if(!previous.path("generationOwner").asText().equals(cache.registered().path("generationOwner").asText())||!previous.path("instanceDir").asText().equals(cache.instance().toString())||!checkedHash(cache.instance().resolve("vz-identifier"),4096).equals(previous.path("machineIdSha256").asText())||!checkedHash(cache.instance().resolve("lima.yaml"),1024*1024).equals(previous.path("forwardConfigSha256").asText()))throw new IOException("既存VMのdisk所有が一致しません");}
    JsonNode instance() throws IOException {var result=commands.capture(lima("list","--json","v"),cleanupBudget(Duration.ofSeconds(5)));requireZero(result,"専用instance照合");JsonNode value=MacFiles.JSON.readTree(result.stdout());if(value==null||!value.path("name").asText().equals("v")||!value.path("dir").asText().equals(cache.instance().toString())||!value.path("vmType").asText().equals("vz")||!value.path("arch").asText().equals("aarch64"))throw new IOException("Lima instance照合が一致しません");return value;}
    private void checkRegistration(JsonNode record,JsonNode registration) throws IOException {if(!record.path("identity").equals(registration)||!record.path("resources").isObject()||record.path("memoryMiB").asInt()<1)throw new IOException("VM登録snapshotが一致しません");}
    private void saveVm(ObjectNode identity,ObjectNode ready,int memory) throws IOException {ObjectNode record=MacFiles.JSON.createObjectNode();record.set("identity",identity);record.set("resources",ready);record.put("memoryMiB",memory);MacFiles.write(cache.anchor().resolve("vm.json"),record);}
    private int memoryMiB(LeaseReferences.Scan references) throws IOException {long current=lease.read().path("requirements").path("containerMemoryMiB").asLong(),available=host.memoryBytes()/1024/1024-2048,desired=Math.max(2048,Math.addExact(Math.multiplyExact(current,2),1024));if(desired>available||current+references.memoryMiB()+1024>desired)throw new IOException("VMと並行jobのmemory予算を確保できません");return Math.toIntExact(desired);}
    void startCleanup(){cleanupEnd=Math.min(cleanupEnd,System.nanoTime()+Duration.ofSeconds(9).toNanos());commands.cleanupDeadline(cleanupEnd);}
    Duration cleanupBudget(Duration maximum) throws IOException {return bounded(cleanupEnd,maximum);}
    private static Duration bounded(long deadline,Duration maximum) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("回収が中断されました");
        if(deadline==Long.MAX_VALUE)return maximum;long left=deadline-System.nanoTime();if(left<=0)throw new IOException("回収操作の期限を超えました");return Duration.ofNanos(Math.min(left,maximum.toNanos()));
    }
    private Duration timeLeft() throws IOException {long left=preparationEnd-System.nanoTime();if(left<=0)throw new IOException("VM準備期限を超えました");return Duration.ofNanos(left);}
    private static String checkedHash(Path path,int limit) throws IOException {if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path)||(Files.size(path)<1||Files.size(path)>limit))throw new IOException("VM所有fileを読めません: "+path.getFileName());return MacFiles.hash(path);}
    private static String readSmall(Path path,int limit) throws IOException {if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path)||(Files.size(path)<1||Files.size(path)>limit))throw new IOException("VM所有fileを読めません: "+path.getFileName());return Files.readString(path,StandardCharsets.UTF_8);}
    static void requireZero(Commands.Result result,String operation) throws IOException {if(result.exit()!=0)throw new IOException(operation+"が失敗しました（exit "+result.exit()+"）");}
}
