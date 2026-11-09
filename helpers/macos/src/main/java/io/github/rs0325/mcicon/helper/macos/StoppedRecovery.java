package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.internal.LeaseTrust;
import io.github.rs0325.mcicon.internal.RuntimeMaintenanceVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 同bootで停止済みのVMについて、元stopの成功とは別に終端を確定する。 */
final class StoppedRecovery {
    private StoppedRecovery() {}
    static boolean recover(HelperProtocol.Command command,Path executable,JsonNode lease,CacheState cache,
            HostFacts host,Payload payload,Commands commands,LeaseTrust trust,OperationJournal journal) throws IOException {
        JsonNode mac=lease.path("resources").path("macos");OperationJournal.Stop stop=journal.stoppedSnapshot(mac);
        if(stop==null)return false;
        if(RuntimeMaintenanceVerifier.live(lease))throw new IOException("元coreが生存しているためstop回復できません");
        if(ProcessHandle.of(lease.path("pid").asLong()).filter(ProcessHandle::isAlive).isPresent())throw new IOException("元core PIDが再利用されています");
        if(!mac.path("phase").asText().equals("releasing")||!mac.path("containers").isArray()||!mac.path("containers").isEmpty())throw new IOException("container除去済みのreleasingではありません");
        cache.verify();JsonNode registration=cache.registered(),vm=MacFiles.json(cache.anchor().resolve("vm.json"));
        if(!vm.path("identity").equals(registration)||!mac.path("generationOwner").equals(registration.path("generationOwner"))||!mac.path("instanceDir").asText().equals(cache.instance().toString())||!cache.instance().toRealPath().equals(cache.instance()))throw new IOException("停止VMの登録が一致しません");
        for(String key:List.of("generationOwner","instanceDir","machineIdSha256","forwardConfigSha256","hostAgent","guestOwnerMarkerSha256"))if(!vm.path("resources").path(key).equals(mac.path(key)))throw new IOException("停止VMの元snapshotが一致しません: "+key);
        JsonNode agent=mac.path("hostAgent");
        if(!agent.path("bootSessionId").asText().equals(host.bootSessionId())||!MacRuntime.readSmall(cache.instance().resolve("boot-session.tmp"),256).trim().toLowerCase(java.util.Locale.ROOT).equals(host.bootSessionId()))throw new IOException("停止VMが同bootではありません");
        if(!agent.path("pid").canConvertToLong()||agent.path("pid").asLong()<1||!agent.path("processStartedAt").isTextual())throw new IOException("hostAgent開始identityがありません");
        Instant started;try{started=Instant.parse(agent.path("processStartedAt").asText());}catch(RuntimeException failure){throw new IOException("hostAgent開始identityが不正です",failure);}
        if(RuntimeMaintenanceVerifier.processMatches(agent.path("pid").asLong(),started))throw new IOException("元hostAgentが生存しています");
        if(ProcessHandle.of(agent.path("pid").asLong()).filter(ProcessHandle::isAlive).isPresent())throw new IOException("hostAgent PIDが再利用されています");
        String machine=checkedHash(cache.instance().resolve("vz-identifier"),4096),config=checkedHash(cache.instance().resolve("lima.yaml"),1024*1024);
        if(!machine.equals(mac.path("machineIdSha256").asText())||!config.equals(mac.path("forwardConfigSha256").asText()))throw new IOException("停止VMのmachine/configが一致しません");
        Path temporary=command.lease().getParent().resolve("tmp");ObjectNode readers=readers(temporary);
        if(!LeaseReferences.inspect(command,executable,lease.path("descriptorSha256").asText(),trust).stopAllowed())throw new IOException("他leaseがliveまたは不明です");
        var observed=commands.capture(List.of(payload.path("bin/limactl").toString(),"--log-level=error","list","--json","v"),Duration.ofSeconds(5));MacRuntime.requireZero(observed,"停止VM観測");
        JsonNode instance=MacFiles.JSON.readTree(observed.stdout());
        if(instance==null||!instance.path("status").asText().equals("Stopped")||!instance.path("name").asText().equals("v")||!instance.path("dir").asText().equals(cache.instance().toString())||!instance.path("vmType").asText().equals("vz")||!instance.path("arch").asText().equals("aarch64"))throw new IOException("所有VMのStoppedを観測できません");
        // 観測に使ったread-clientも回収されてから証拠を固定する。
        readers=readers(temporary);OperationJournal.Stop checked=journal.stoppedSnapshot(mac);
        if(!checked.records().equals(stop.records())||!checked.operation().equals(stop.operation()))throw new IOException("停止観測中に元記録が変化しました");
        ObjectNode evidence=MacFiles.JSON.createObjectNode().put("recoveryKind","observed-stopped").put("status","Stopped").put("bootSessionId",host.bootSessionId()).put("hostBindingSha256",host.hostBindingSha256()).put("uid",host.uid()).put("machineIdSha256",machine).put("forwardConfigSha256",config);
        evidence.set("registration",registration);evidence.set("hostAgent",agent);evidence.set("records",stop.records());evidence.set("readClients",readers);evidence.set("resources",mac);
        evidence=(ObjectNode)MacFiles.JSON.readTree(MacFiles.JSON.writeValueAsBytes(evidence));
        if(stop.settled()) {
            JsonNode saved=MacFiles.json(stop.operation().directory().resolve("settled.json")).path("evidence");
            if(saved.has("recoveryKind")) {
                for(String key:List.of("recoveryKind","status","bootSessionId","hostBindingSha256","uid","machineIdSha256","forwardConfigSha256","registration","hostAgent","records","resources"))if(!saved.path(key).equals(evidence.path(key)))throw new IOException("保存済み停止回復証拠が一致しません: "+key);
                for(var fields=saved.path("readClients").fields();fields.hasNext();) {var field=fields.next();if(!readers.path(field.getKey()).equals(field.getValue()))throw new IOException("元read-client証拠が変化しました");}
            }else if(!saved.path("status").asText().equals("Stopped")||!saved.path("machineIdSha256").asText().equals(machine))throw new IOException("元stop終端証拠が一致しません");
        }else journal.settled(stop.operation(),evidence);
        journal.markReleased();return true;
    }
    private static String checkedHash(Path path,int limit) throws IOException {if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path)||Files.size(path)<1||Files.size(path)>limit)throw new IOException("停止VMの所有fileが不正です");return MacFiles.hash(path);}
    static ObjectNode readers(Path temporary) throws IOException {
        ObjectNode result=MacFiles.JSON.createObjectNode();
        try(var entries=Files.newDirectoryStream(temporary,"read-client-*")) {
            for(Path entry:entries) {
                if(!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)||!entry.toRealPath().equals(entry))throw new IOException("read-clientの位置が不明です");
                try(var files=Files.newDirectoryStream(entry)){for(Path file:files)if(!file.getFileName().toString().equals("complete"))throw new IOException("read-clientの未確定記録があります");}
                Path complete=entry.resolve("complete");if(!Files.isRegularFile(complete,LinkOption.NOFOLLOW_LINKS)||Files.size(complete)!=65||!Files.readString(complete,StandardCharsets.US_ASCII).matches("[0-9a-f]{64}\\n"))throw new IOException("read-client終端が不明です");result.put(entry.getFileName().toString(),MacFiles.hash(complete));
            }
        }
        return result;
    }
}
