package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.rs0325.mcicon.helper.*;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 専用WSLの所有、job fence、renderer輸送を固定protocolへ接続する。 */
final class WindowsRuntime {
    final HelperProtocol.Command command;
    final LeaseFile lease;
    final Path executable,temporary,wsl;
    final Payload payload;
    final JsonNode descriptor;
    final Commands commands;
    final Map<String,String> environment;
    final WslDistribution distribution;
    private final boolean cleanup;
    WindowsRuntime(HelperProtocol.Command command,Path executable,LeaseFile lease,long started) throws IOException {this(command,executable,lease,started,Long.MAX_VALUE);}
    WindowsRuntime(HelperProtocol.Command command,Path executable,LeaseFile lease,long started,long endLimit) throws IOException {
        this.command=command;this.executable=executable;this.lease=lease;descriptor=lease.descriptor();temporary=command.lease().getParent().resolve("tmp");
        if(!temporary.toRealPath().equals(temporary))throw new IOException("job tmpの所有位置が不正です");
        payload=new Payload(executable.getParent().resolve("app/support"));payload.verify();
        cleanup=command.operation().equals("release")||command.operation().equals("docker")&&command.dockerArguments().getFirst().equals("rm");
        long end=command.keeper()!=null?started+Math.max(0,Duration.between(Instant.now(),command.keeper().deadline()).toNanos()):cleanup?started+Duration.ofSeconds(9).toNanos():command.operation().equals("docker")?Long.MAX_VALUE:started+Math.max(0,Duration.between(Instant.now(),Instant.parse(lease.read().path("requirements").path("preparationDeadline").asText())).toNanos());
        environment=new HashMap<>();for(String key:List.of("TMP","TEMP","TMPDIR"))environment.put(key,temporary.toString());
        for(String key:List.of("SystemRoot","WINDIR")){String value=System.getenv(key);if(value!=null)environment.put(key,value);}
        String system=environment.get("SystemRoot");if(system==null||!Path.of(system).isAbsolute())throw new IOException("SystemRootが不明です");wsl=Path.of(system).resolve("System32/wsl.exe");if(!Files.isRegularFile(wsl,LinkOption.NOFOLLOW_LINKS)||!wsl.toRealPath().equals(wsl))throw new IOException("固定WSL実行物がありません");
        commands=new Commands(payload.path("windows-host.exe"),temporary,environment,Math.min(end,endLimit));distribution=new WslDistribution(command.root(),lease.read(),facts());
    }
    static boolean releaseUnstarted(HelperProtocol.Command command,LeaseFile lease) throws IOException {
        try(OperationJournal journal=OperationJournal.acquire(command.lease().getParent().resolve("tmp"),lease.read(),Duration.ofSeconds(1))) {
            if(!lease.read().path("resources").isEmpty()||!journal.unstarted()&&!journal.released())return false;
            if(!journal.released())journal.release();return true;
        }
    }
    HostFacts facts() throws IOException {Commands.Result result=commands.capture(List.of(payload.path("windows-host.exe").toString(),"facts"),Duration.ofSeconds(3));Commands.zero(result,"Windows所有情報の読取");return HostFacts.parse(WindowsFiles.JSON.readTree(result.stdout()),descriptor);}
    OperationJournal journal() throws IOException {return OperationJournal.acquire(temporary,lease.read(),commands.left(Duration.ofSeconds(3)));}
    WslDistribution.Lock lock() throws IOException {return distribution.lock(commands.left(Duration.ofSeconds(3)));}
    LeaseReferences.Scan references() throws IOException {return LeaseReferences.inspect(command,executable,lease.read().path("descriptorSha256").asText());}
    void preflight() throws IOException {
        try(OperationJournal journal=journal();var ignored=lock()) {
            journal.settledRequired();HostFacts host=facts();LeaseReferences.Scan others=references();if(others.unknown())throw new IOException("他leaseの所有が不明です");
            long memory=Math.addExact(lease.read().path("requirements").path("containerMemoryMiB").asLong(),others.memoryMiB());if(host.memoryBytes()/1024/1024<memory+2048)throw new IOException("hostの並行job memory予算が不足しています");
            if(Files.getFileStore(command.root()).getUsableSpace()<4L*1024*1024*1024)throw new IOException("WSL準備の空き容量が不足しています");
            Commands.zero(commands.capture(List.of(wsl.toString(),"--status"),Duration.ofSeconds(5)),"導入済みWSL確認");
            if(distribution.exists())distribution.verify(host);
            else if(host.named(distribution.name)!=null||Files.exists(distribution.basePath,LinkOption.NOFOLLOW_LINKS))throw new IOException("専用WSL名/pathが衝突しています");
            else if(host.defaultGuid().isEmpty()||host.distributions().isEmpty())throw new IOException("WSL初回登録のdefault変更についてsetup側の明示同意と既存defaultが必要です");
        }
    }
    void prepare() throws IOException {
        LeaseFile.Artifact image=lease.verifiedArtifact("renderer-image");
        try(OperationJournal journal=journal();var ignored=lock()) {
            journal.settledRequired();ObjectNode owner=ready();JsonNode keeper=keeperRecord();commands.keeper(keeper);
            JsonNode resources=lease.read().path("resources").path("windows");
            if(resources.path("transports").size()!=1||!resources.path("containers").isEmpty()||!resources.path("transports").get(0).path("id").asText().equals(keeper.path("attempt").asText()))throw new IOException("prepare前のkeeper資源が一致しません");
            LeaseReferences.Scan others=references();if(others.unknown())throw new IOException("他leaseが不明です");long guestRequired=GuestBudget.requiredBytes(lease.read().path("requirements").path("containerMemoryMiB").asLong(),others.memoryMiB());
            journal.begin("prepare",facts().boot());
            JsonNode hello=call(request("hello",owner).put("memoryBytes",guestRequired),null);verifyHello(hello,owner);GuestBudget.require(hello.path("memoryBytes").asLong(),guestRequired);
            ObjectNode load=request("load",owner).put("imageId",imageId());JsonNode actual=call(load,image.path());
            if(!actual.path("Id").asText().equals(imageId())||!actual.path("Os").asText().equals("linux")||!actual.path("Architecture").asText().equals("amd64"))throw new IOException("renderer ID/platformが一致しません");
            journal.settled(actual);new DockerTransport(this).cpuProbe(journal,owner);
        }
    }
    ObjectNode keeperOwner(OperationJournal journal,boolean recovery) throws IOException {
        LeaseFile.Artifact base=lease.verifiedArtifact("guest-base");payload.base(base);HostFacts before=facts();
        if(recovery)return distribution.verify(before);
        journal.settledRequired();if(!lease.read().path("resources").isEmpty())throw new IOException("keeper開始前の資源が空ではありません");
        ObjectNode owner;
        if(distribution.exists()){owner=distribution.verify(before);journal.begin("keeper-start",before.boot());}
        else {
            owner=distribution.reserve(before);replace(distribution.resource(owner,"importing",job()));journal.begin("import",before.boot());
            Path rootfs=rootfs(base);Commands.zero(commands.capture(List.of(wsl.toString(),"--import",distribution.name,distribution.basePath.toString(),rootfs.toString(),"--version","2"),commands.left(Duration.ofMinutes(3))),"専用prepared WSL import");
            distribution.registered(owner,facts(),WindowsFiles.hash(WindowsFiles.JSON.writeValueAsBytes(distribution.marker(owner))));owner=distribution.owner();owner.put("needsInit",true);
        }
        distribution.consumeStop(owner);return owner;
    }
    void keeperResource(ObjectNode owner,JsonNode keeper) throws IOException {
        ObjectNode resources=distribution.resource(owner,"ready",job());JsonNode prior=lease.read().path("resources").path("windows");
        if(command.keeper().recovery())for(String field:List.of("transports","containers"))if(prior.has(field))resources.set(field,prior.path(field).deepCopy());
        ((ArrayNode)resources.path("transports")).addObject().put("id",keeper.path("attempt").asText()).put("pid",keeper.path("client").path("pid").asLong()).put("processStartedAt",Instant.ofEpochMilli(keeper.path("client").path("started").asLong()).toString());replace(resources);
    }
    Path keeperPrefix(JsonNode keeper) throws IOException {
        Path prefix=Path.of(keeper.path("prefix").asText());if(!prefix.isAbsolute()||!prefix.getParent().equals(temporary)||!prefix.getFileName().toString().matches("native-[0-9a-f-]{36}"))throw new IOException("keeper native保存位置が不正です");return prefix;
    }
    JsonNode keeperRecord() throws IOException {
        JsonNode keeper=WindowsFiles.json(temporary.resolve("keeper.json"));if(!keeper.path("identity").equals(identity())||!keeper.path("state").asText().equals("ready"))throw new IOException("keeperの準備完了証明がありません");
        WindowsKeeper.requireClientLive(keeper);return keeper;
    }
    private Path rootfs(LeaseFile.Artifact artifact) throws IOException {
        if(payload.manifest.path("guestBase").path("format").asText().equals("tar"))return artifact.path();
        Path target=distribution.anchor.resolve("rootfs.tar");if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("import stagingが既にあります");long limit=0;
        for(JsonNode row:descriptor.path("artifacts"))if(row.path("role").asText().equals("guest-base"))limit=row.path("unpackedBytes").asLong();if(limit<1)throw new IOException("rootfs展開上限がありません");
        try(InputStream source=new XZInputStream(Files.newInputStream(artifact.path()),256*1024);OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {byte[] bytes=new byte[65536];long count=0;int length;while((length=source.read(bytes))!=-1){commands.left(Duration.ofMinutes(3));count=Math.addExact(count,length);if(count>limit)throw new IOException("rootfs展開上限を超えました");output.write(bytes,0,length);}}return target;
    }
    void release() throws IOException {throw new IOException("keeperを通さないguest releaseを拒否しました");}
    void release(WindowsKeeper keeper,boolean recovery) throws IOException {
        commands.keeper(keeper.value());
        try(OperationJournal journal=journal();var ignored=lock()) {
            ObjectNode current=lease.read();if(current.path("resources").isEmpty()&&journal.unstarted()){journal.release();return;}
            ObjectNode owner=distribution.verify(facts());verifyResource(current.path("resources").path("windows"),owner);
            commands.reap(facts().boot(),current.path("resources").path("windows").path("transports"),keeperPrefix(keeper.value()));
            JsonNode proof=bridge(recovery?"release-recover":"release",owner,null,null);if(!proof.path("closed").asBoolean()||proof.path("actors").asInt(-1)!=0||proof.path("containers").asInt(-1)!=0||!proof.path("identity").equals(identity())||!proof.path("keeper").equals(keeper.value().path("guest").path("keeper")))throw new IOException("guest fence終端証明が一致しません");
            keeper.workProof(proof);LeaseReferences.Scan others=references();if(others.unknown())throw new IOException("他leaseが不明なため回収完了を確定しません");
            keeper.closeIntent(others.stopAllowed());keeper.endClient(commands);
            if(others.stopAllowed()) {
                keeper.requireControllerLive();HostFacts fresh=facts();if(!fresh.boot().equals(keeper.value().path("boot").asText())||!fresh.sid().equals(owner.path("binding").path("sid").asText())||!distribution.verify(fresh).equals(owner))throw new IOException("keeper EOF後のhost/ownerが一致しません");
                Commands.Result running=commands.capture(List.of(wsl.toString(),"--list","--running","--quiet"),commands.left(Duration.ofSeconds(2)));Commands.zero(running,"WSL停止前照合");Set<String> names=runningNames(running.stdout()),registered=new HashSet<>();for(var row:fresh.distributions())registered.add(row.name());if(!registered.containsAll(names))throw new IOException("WSL稼働一覧の登録が不明です");
                if(names.contains(distribution.name)) {
                    keeper.requireControllerLive();Commands.zero(commands.capture(List.of(wsl.toString(),"--terminate",distribution.name),commands.left(Duration.ofSeconds(3))),"専用WSL停止");
                    running=commands.capture(List.of(wsl.toString(),"--list","--running","--quiet"),commands.left(Duration.ofSeconds(2)));Commands.zero(running,"WSL停止照合");names=runningNames(running.stdout());
                }
                HostFacts after=facts();registered.clear();for(var row:after.distributions())registered.add(row.name());if(!after.boot().equals(fresh.boot())||!after.sid().equals(fresh.sid())||!registered.containsAll(names)||names.contains(distribution.name)||!distribution.verify(after).equals(owner))throw new IOException("専用WSL停止が未確認です");distribution.stopped(owner,after.boot());keeper.stopped();
            }
            keeper.finish(commands);
            commands.left(Duration.ofSeconds(2));if(!journal.released()){journal.recovered(proof);journal.release();}commands.left(Duration.ofSeconds(2));lease.replaceResources((ObjectNode)current.path("resources"),WindowsFiles.JSON.createObjectNode());commands.left(Duration.ofSeconds(2));
        }
    }
    static Set<String> runningNames(byte[] bytes) throws IOException {
        boolean nul=false;for(byte value:bytes)if(value==0)nul=true;String text;if(bytes.length>=2&&(bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe))text=StandardCharsets.UTF_16LE.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes,2,bytes.length-2)).toString();else if(nul)text=StandardCharsets.UTF_16LE.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();else text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        Set<String> names=new HashSet<>();for(String row:text.replace("\u0000","").split("\\R")){String name=row.trim();if(!name.isEmpty())names.add(name);}return names;
    }
    ObjectNode ready() throws IOException {commands.keeper(keeperRecord());ObjectNode owner=distribution.verify(facts());verifyResource(lease.read().path("resources").path("windows"),owner);return owner;}
    void verifyResource(JsonNode resources,ObjectNode owner) throws IOException {
        ObjectNode expected=distribution.resource(owner,"ready",job());for(String key:List.of("generationOwner","distribution","socketPath","guestJobPath"))if(!expected.path(key).equals(resources.path(key)))throw new IOException("leaseとWSL実所有の記録が不一致です");
    }
    void replace(ObjectNode windows) throws IOException {ObjectNode current=lease.read(),resources=WindowsFiles.JSON.createObjectNode();resources.set("windows",windows);lease.replaceResources((ObjectNode)current.path("resources"),resources);}
    String job() throws IOException{return lease.read().path("jobId").asText();}
    String imageId(){return descriptor.path("rendering").path("imageId").asText();}
    ObjectNode identity() throws IOException {ObjectNode value=WindowsFiles.JSON.createObjectNode();JsonNode current=lease.read();for(String key:List.of("jobId","ownerId","descriptorSha256"))value.set(key,current.required(key));return value;}
    ObjectNode request(String operation,ObjectNode owner) throws IOException {
        ObjectNode value=identity().put("abi",1).put("operation",operation).put("bridgeSha256",payload.manifest.path("guestBase").path("bridgeSha256").asText()).put("memoryBytes",Math.max(8L*1024*1024*1024,(lease.read().path("requirements").path("containerMemoryMiB").asLong()*2+1024)*1024*1024)).put("timeoutSeconds",Math.max(1,commands.left(Duration.ofMinutes(3)).toSeconds()));
        value.put("restartAllowed",owner.has("lastStop")).put("cpuProbeSha256",payload.manifest.path("guestBase").path("cpuProbeSha256").asText());value.set("marker",distribution.marker(owner));value.set("engines",payload.manifest.path("guestBase").required("engines"));return value;
    }
    List<String> guest(){return List.of(wsl.toString(),"--distribution",distribution.name,"--user","root","--exec","/usr/bin/python3","/usr/lib/mcicon/guestbridge.py");}
    JsonNode call(ObjectNode request,Path input) throws IOException {Commands.Result result=commands.request(guest(),WindowsFiles.JSON.writeValueAsBytes(request),input,commands.left(Duration.ofMinutes(3)));Commands.zero(result,"専用guestbridge "+request.path("operation").asText());JsonNode value=WindowsFiles.JSON.readTree(result.stdout());if(value==null||!value.isObject())throw new IOException("guestbridge応答が不正です");return value;}
    JsonNode bridge(String operation,ObjectNode owner,String field,JsonNode value) throws IOException {ObjectNode request=request(operation,owner);if(field!=null)request.set(field,value);return call(request,null);}
    void verifyHello(JsonNode hello,ObjectNode owner) throws IOException {if(hello.path("abi").asInt()!=1||!hello.path("marker").equals(distribution.marker(owner))||!hello.path("bridgeSha256").equals(payload.manifest.path("guestBase").path("bridgeSha256"))||!HostFacts.uuid(hello.path("boot").asText()))throw new IOException("guestbridge ABI/marker/pinが一致しません");}
    int docker(List<String> argv) throws IOException {return new DockerTransport(this).run(argv);}
}
