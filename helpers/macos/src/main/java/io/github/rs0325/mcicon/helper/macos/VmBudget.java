package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.helper.LeaseFile;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** descriptor別の独立VM予約と、現世代のcontainer予算を一度ずつ集計する。 */
final class VmBudget {
    private VmBudget() {}
    static int require(MacRuntime runtime,LeaseReferences.Scan references) throws IOException {return require(runtime,references,LeaseFile.trust(runtime.command,runtime.executable));}
    static int require(MacRuntime runtime,LeaseReferences.Scan references,LeaseTrust trust) throws IOException {
        JsonNode current=runtime.lease.read();String digest=current.path("descriptorSha256").asText();long request=current.path("requirements").path("containerMemoryMiB").asLong();
        long largest=request;for(var generation:references.generations())if(generation.descriptorSha256().equals(digest))for(var snapshot:generation.leases())largest=Math.max(largest,snapshot.lease().path("requirements").path("containerMemoryMiB").asLong());
        long desired=Math.max(2048,Math.addExact(Math.multiplyExact(largest,2),1024)),host=runtime.host.memoryBytes()/1024/1024,available=host-2048;
        Path ownRecord=runtime.cache.anchor().resolve("vm.json");
        if(Files.exists(ownRecord,LinkOption.NOFOLLOW_LINKS)){runtime.cache.verify();JsonNode record=MacFiles.json(ownRecord);if(!record.path("identity").equals(runtime.cache.registered())||!record.path("memoryMiB").isIntegralNumber()||record.path("memoryMiB").asLong()<1)throw new IOException("現在VMの予約が不明です");desired=record.path("memoryMiB").asLong();verifyReservation(runtime.cache,record,desired);}
        long reserved=desired;List<LeaseSnapshot> snapshots=new ArrayList<>();var generations=MacFiles.JSON.createArrayNode();
        for(var generation:references.generations()) {
            snapshots.addAll(generation.leases());if(generation.descriptorSha256().equals(digest))continue;
            LeaseSnapshot first=generation.leases().getFirst();JsonNode other=first.lease();
            CacheState cache=new CacheState(runtime.command.root(),other.path("hostId").asText(),generation.descriptorSha256(),runtime.host.uid(),runtime.host.hostBindingSha256(),runtime.host.bootSessionId());
            long memory=0;boolean started=false;
            for(LeaseSnapshot snapshot:generation.leases()) {
                JsonNode value=snapshot.lease();
                if(value.path("resources").isEmpty())RuntimeMaintenanceVerifier.unstarted(snapshot);
                else {
                    started=true;cache.verify();JsonNode resources=value.path("resources").path("macos"),record=MacFiles.json(cache.anchor().resolve("vm.json"));
                    if(!record.path("identity").equals(cache.registered())||!resources.path("generationOwner").equals(cache.registered().path("generationOwner"))||!resources.path("instanceDir").asText().equals(cache.instance().toString()))throw new IOException("他世代VMの所有が不明です");
                    for(String field:List.of("hostAgent","machineIdSha256","forwardConfigSha256","guestOwnerMarkerSha256","dockerSocket"))if(!resources.path(field).equals(record.path("resources").path(field)))throw new IOException("他世代VM recordとleaseが一致しません");
                    JsonNode actor=resources.path("hostAgent");
                    if(!actor.path("bootSessionId").asText().equals(runtime.host.bootSessionId())||!RuntimeMaintenanceVerifier.processMatches(actor.path("pid").asLong(),Instant.parse(actor.path("processStartedAt").asText())))throw new IOException("他世代VM hostAgentが不明です");
                    String executable=snapshot.descriptor().path("helper").path("executable").asText();
                    snapshot.helperFile(Path.of(executable).getParent().getParent().resolve("app/support/process-guard").toString().replace('\\','/'));
                    snapshot.helperFile(Path.of(executable).getParent().getParent().resolve("app/support/bin/limactl").toString().replace('\\','/'));
                    long saved=record.path("memoryMiB").asLong(-1);if(saved<1||memory!=0&&memory!=saved)throw new IOException("他世代VM予約が不明です");memory=saved;verifyReservation(cache,record,saved);
                }
            }
            if(!started){
                Path recordFile=cache.anchor().resolve("vm.json");
                if(Files.exists(recordFile,LinkOption.NOFOLLOW_LINKS)){cache.verify();JsonNode record=MacFiles.json(recordFile);memory=record.path("memoryMiB").asLong(-1);if(memory<1||!record.path("identity").equals(cache.registered()))throw new IOException("予定VMの既存予約が不明です");verifyReservation(cache,record,memory);}
                else {long planned=0;for(LeaseSnapshot snapshot:generation.leases())planned=Math.max(planned,snapshot.lease().path("requirements").path("containerMemoryMiB").asLong());memory=Math.max(2048,Math.addExact(Math.multiplyExact(planned,2),1024));}
            }
            if(Math.addExact(generation.containerMemoryMiB(),1024)>memory)throw new IOException("他世代VMの要求と予約が一致しません");
            reserved=Math.addExact(reserved,memory);generations.addObject().put("descriptorSha256",generation.descriptorSha256()).put("reservedMiB",memory).put("requestedMiB",generation.containerMemoryMiB());
        }
        long unreferenced=registeredMemory(runtime,references,trust);
        long container=Math.addExact(Math.addExact(request,references.memoryMiB()),1024);
        generations.addObject().put("descriptorSha256",digest).put("reservedMiB",desired).put("requestedMiB",container-1024);
        if(Math.addExact(request,1024)>desired)throw new IOException("候補単独でもVM容量が不足しています");
        if(Math.addExact(reserved,unreferenced)>available||container>desired) {
            if(desired>available)throw new IOException("候補単独でもhost容量が不足しています");
            if(reserved<=available&&container<=desired)throw new IOException("live lease外の稼働VM予約によりhost容量を確保できません");
            long required=reserved>available?reserved:container,capacity=reserved>available?available:desired;
            var budget=MacFiles.JSON.createObjectNode().put("domain","macos-vm").put("constraint",reserved>available?"host":"generation").put("hostMiB",host).put("requiredMiB",required).put("availableMiB",Math.max(0,capacity));budget.set("generations",generations);
            LeaseSnapshot candidate=LeaseFile.inspect(runtime.command.root(),runtime.command.lease(),LeaseFile.trust(runtime.command,runtime.executable));
            AdmissionEvidence.capacity(runtime.command.root(),candidate,snapshots,budget);
        }
        return Math.toIntExact(desired);
    }
    private static long registeredMemory(MacRuntime runtime,LeaseReferences.Scan references,LeaseTrust trust) throws IOException {
        Path directory=runtime.command.root().resolve("macos");if(CacheState.absent(directory))return 0;
        if(!directory.toRealPath().equals(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("VM登録一覧が不明です");
        String currentDigest=runtime.lease.read().path("descriptorSha256").asText();Set<String> counted=new HashSet<>();counted.add(currentDigest);for(var generation:references.generations())counted.add(generation.descriptorSha256());
        long reserved=0;
        try(var entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries) {
                String name=entry.getFileName().toString();if(name.equals("history")&&Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)&&entry.toRealPath().equals(entry))continue;
                if(!name.matches("[0-9a-f]{64}\\.json"))throw new IOException("未知のVM登録です");String digest=name.substring(0,64);
                CacheState cache=new CacheState(runtime.command.root(),runtime.lease.read().path("hostId").asText(),digest,runtime.host.uid(),runtime.host.hostBindingSha256(),runtime.host.bootSessionId());cache.verify();
                if(CacheState.absent(cache.anchor().resolve("vm.json"))){
                    if(digest.equals(currentDigest)&&cache.freshIncarnation()){
                        LeaseSnapshot current=LeaseFile.inspect(runtime.command.root(),runtime.command.lease(),trust);RuntimeMaintenanceVerifier.unstarted(current);current.verifyUnchanged();continue;
                    }
                    throw new IOException("登録済みVMの生成/停止状態が不明です");
                }
                if(counted.contains(digest))continue;
                LeaseSnapshot owner=null;
                try(var jobs=Files.newDirectoryStream(runtime.command.root().resolve("jobs"))) {
                    for(Path job:jobs){LeaseSnapshot snapshot=LeaseFile.inspect(runtime.command.root(),job.resolve("lease.json"),trust);if(snapshot.lease().path("descriptorSha256").asText().equals(digest)){if(!snapshot.descriptor().path("host").path("os").asText().equals("macos")||!snapshot.descriptor().path("host").path("arch").asText().equals("aarch64"))throw new IOException("登録元descriptorのhostが不一致です");RuntimeMaintenanceVerifier.requireReleased(snapshot);owner=snapshot;}}
                }
                if(owner==null)throw new IOException("VM登録元の署名leaseがありません");
                JsonNode record=MacFiles.json(cache.anchor().resolve("vm.json"));long memory=record.path("memoryMiB").asLong(-1);if(memory<1||!record.path("identity").equals(cache.registered()))throw new IOException("lease外VMの予約が不明です");verifyReservation(cache,record,memory);
                JsonNode instance=runtime.instance(cache),actor=record.path("resources").path("hostAgent");String status=instance.path("status").asText();
                boolean live=RuntimeMaintenanceVerifier.processMatches(actor.path("pid").asLong(),Instant.parse(actor.path("processStartedAt").asText()));
                if(status.equals("Running")) {
                    if(!live||!actor.path("bootSessionId").asText().equals(runtime.host.bootSessionId())||!MacRuntime.readSmall(cache.instance().resolve("ha.pid"),64).trim().equals(Long.toString(actor.path("pid").asLong()))||!MacRuntime.readSmall(cache.instance().resolve("boot-session.tmp"),256).trim().toLowerCase(Locale.ROOT).equals(runtime.host.bootSessionId()))throw new IOException("lease外VMのhostAgentが不明です");
                    var uid=runtime.commands.capture(List.of("/bin/ps","-p",Long.toString(actor.path("pid").asLong()),"-o","uid="),java.time.Duration.ofSeconds(3));MacRuntime.requireZero(uid,"lease外VMのUID照合");if(!new String(uid.stdout(),java.nio.charset.StandardCharsets.US_ASCII).trim().equals(Long.toString(runtime.host.uid())))throw new IOException("lease外VMのUIDが一致しません");
                    reserved=Math.addExact(reserved,memory);
                }else {
                    if(!status.equals("Stopped")||live)throw new IOException("lease外VMの停止を確認できません");
                    Path pidFile=cache.instance().resolve("ha.pid");if(Files.exists(pidFile,LinkOption.NOFOLLOW_LINKS)){long pid;try{pid=Long.parseLong(MacRuntime.readSmall(pidFile,64).trim());}catch(NumberFormatException failure){throw new IOException("停止VMのPID記録が不明です",failure);}if(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent())throw new IOException("停止VMに生存するhostAgent記録があります");}
                }
                owner.verifyUnchanged();
            }
        }
        return reserved;
    }
    static long memoryMiB(byte[] yaml) throws IOException {
        String quantity=null;
        for(String line:new String(yaml,java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            var match=java.util.regex.Pattern.compile("^memory\\s*:(.*)$").matcher(line);
            if(match.matches()){if(quantity!=null)throw new IOException("VM memoryが重複しています");quantity=match.group(1).replaceFirst("\\s+#.*$","").trim();if(quantity.startsWith("\"")&&quantity.endsWith("\"")||quantity.startsWith("'")&&quantity.endsWith("'"))quantity=quantity.substring(1,quantity.length()-1);}
        }
        if(quantity==null)throw new IOException("実体VM memoryがありません");
        var match=java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(B|KiB|MiB|GiB|TiB)?").matcher(quantity);if(!match.matches())throw new IOException("実体VM memoryの形式が不明です");
        try{int power=match.group(2)==null?0:switch(match.group(2)){case "B"->0;case "KiB"->1;case "MiB"->2;case "GiB"->3;case "TiB"->4;default->throw new IOException("VM memory単位が不明です");};long memory=new java.math.BigDecimal(match.group(1)).multiply(java.math.BigDecimal.valueOf(1024).pow(power)).divide(java.math.BigDecimal.valueOf(1024*1024)).longValueExact();if(memory<1)throw new ArithmeticException();return memory;}catch(ArithmeticException failure){throw new IOException("実体VM memoryが範囲外です",failure);}
    }
    private static void verifyReservation(CacheState cache,JsonNode record,long memory) throws IOException {
        if(!record.path("memoryMiB").isIntegralNumber()||!record.path("memoryMiB").canConvertToLong()||memory<1||record.path("memoryMiB").asLong()!=memory)throw new IOException("VM予約memoryの数値が不明です");
        var resources=MacFiles.JSON.createObjectNode();resources.set("macos",record.path("resources"));LeaseResources.validate(resources,"macos");
        if(!record.path("resources").path("generationOwner").equals(cache.registered().path("generationOwner"))||!record.path("resources").path("instanceDir").asText().equals(cache.instance().toString())||!MacFiles.hash(cache.instance().resolve("vz-identifier")).equals(record.path("resources").path("machineIdSha256").asText()))throw new IOException("VM予約のdisk所有が一致しません");
        Path config=cache.instance().resolve("lima.yaml");if(!Files.isRegularFile(config,LinkOption.NOFOLLOW_LINKS)||Files.size(config)>1024*1024)throw new IOException("VM容量設定のfileが不正です");byte[] bytes=Files.readAllBytes(config);
        if(!config.toRealPath().equals(config)||!MacFiles.hash(bytes).equals(record.path("resources").path("forwardConfigSha256").asText()))throw new IOException("VM容量の設定hashが一致しません");
        Path original=cache.anchor().resolve("instance.yaml");if(!original.toRealPath().equals(original)||!Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS)||Files.size(original)>1024*1024)throw new IOException("生成時VM設定のfileが不正です");
        if(memoryMiB(bytes)!=memory||memoryMiB(Files.readAllBytes(original))!=memory)throw new IOException("VM容量と実体/生成時の設定が一致しません");
    }
}
