package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** SID/root/descriptor/generationとregistry GUID/BasePathが一致する登録だけを使う。 */
final class WslDistribution {
    final Path anchor,basePath;
    private final Path lifecycle;
    final String name;
    private final ObjectNode binding;
    WslDistribution(Path root,JsonNode lease,HostFacts host) throws IOException {
        lifecycle=root.resolve("locks/windows-lifecycle.lock");
        Path parent=root.resolve("windows");WindowsFiles.privateDirectory(parent);anchor=parent.resolve(lease.path("descriptorSha256").asText());WindowsFiles.privateDirectory(anchor);basePath=anchor.resolve("distro");name="MCIcon-"+lease.path("descriptorSha256").asText().substring(0,24);
        binding=WindowsFiles.JSON.createObjectNode().put("sid",host.sid()).put("root",root.toRealPath().toString());for(String key:List.of("hostId","descriptorSha256"))binding.set(key,lease.required(key));
    }
    Lock lock(Duration budget) throws IOException {
        long end=System.nanoTime()+budget.toNanos();
        Files.createDirectories(lifecycle.getParent());if(!lifecycle.getParent().toRealPath().equals(lifecycle.getParent()))throw new IOException("root lockにlinkがあります");
        Lock outer=acquire(lifecycle,end);
        try {Lock generation=acquire(anchor.resolve("generation.lock"),end);return new Lock(generation.channel(),generation.lock(),outer);}catch(IOException|RuntimeException failure){outer.close();throw failure;}
    }
    private static Lock acquire(Path path,long end) throws IOException {
        FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try {while(true){if(System.nanoTime()>=end)throw new IOException("専用distroのlifecycle lockがbusyです");FileLock lock=null;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock!=null)return new Lock(channel,lock,null);if(System.nanoTime()>=end)throw new IOException("専用distroのlifecycle lockがbusyです");try{Thread.sleep(10);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("distro lockが中断されました");}}}catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
    record Lock(FileChannel channel,FileLock lock,Lock outer) implements AutoCloseable {public void close() throws IOException{try{try{lock.release();}finally{channel.close();}}finally{if(outer!=null)outer.close();}}}
    boolean exists(){return Files.exists(anchor.resolve("owner.json"),LinkOption.NOFOLLOW_LINKS);}
    ObjectNode owner() throws IOException {
        ObjectNode value=(ObjectNode)WindowsFiles.json(anchor.resolve("owner.json"));if(!value.path("binding").equals(binding)||!HostFacts.uuid(value.path("generationOwner").asText())||!value.path("name").asText().equals(name)||!value.path("basePath").asText().equals(basePath.toString())||!Set.of("reserved","registered").contains(value.path("state").asText()))throw new IOException("distro所有anchorが一致しません");return value;
    }
    ObjectNode reserve(HostFacts host) throws IOException {
        if(exists()||host.named(name)!=null||Files.exists(basePath,LinkOption.NOFOLLOW_LINKS))throw new IOException("専用distro名/path/所有recordが衝突しています");
        if(host.defaultGuid().isEmpty()||host.distributions().isEmpty())throw new IOException("WSL初回登録はdefaultを変える可能性があります。setup側で明示同意を得て既存defaultを準備してください");
        ObjectNode value=WindowsFiles.JSON.createObjectNode();value.set("binding",binding);value.put("state","reserved").put("generationOwner",UUID.randomUUID().toString()).put("name",name).put("basePath",basePath.toString()).put("importBoot",host.boot()).put("originalDefault",host.defaultGuid());WindowsFiles.write(anchor.resolve("owner.json"),value);return value;
    }
    ObjectNode marker(ObjectNode owner) {ObjectNode marker=WindowsFiles.JSON.createObjectNode().put("abi",1);marker.set("binding",binding);marker.set("generationOwner",owner.required("generationOwner"));return marker;}
    void registered(ObjectNode owner,HostFacts host,String markerHash) throws IOException {
        if(!owner.equals(owner()))throw new IOException("import中に所有recordが変化しました");HostFacts.Distribution row=host.named(name);if(row==null||row.version()!=2||!sameBase(row.basePath(),basePath)||!host.defaultGuid().equals(owner.path("originalDefault").asText()))throw new IOException("import後のGUID/BasePath/defaultが一致しません");
        owner=owner.deepCopy();owner.put("state","registered").put("guid",row.guid()).put("markerSha256",markerHash);WindowsFiles.write(anchor.resolve("owner.json"),owner);
    }
    ObjectNode verify(HostFacts host) throws IOException {
        ObjectNode owner=owner();HostFacts.Distribution row=host.named(name);
        if(!owner.path("state").asText().equals("registered")||row==null||row.version()!=2||!row.guid().equals(owner.path("guid").asText())||!sameBase(row.basePath(),basePath)||!Files.isDirectory(basePath,LinkOption.NOFOLLOW_LINKS)||!basePath.toRealPath().equals(basePath))throw new IOException("専用distroのGUID/BasePath登録が一致しません");return owner;
    }
    ObjectNode claimStop(ObjectNode owner,JsonNode identity,String attempt,String boot) throws IOException {
        ObjectNode current=owner();if(!current.equals(owner))throw new IOException("準備時の所有が変化しました");
        ObjectNode claim=WindowsFiles.JSON.createObjectNode().put("keeperAttempt",attempt);claim.set("identity",identity);claim.set("stop",current.has("lastStop")?current.path("lastStop"):WindowsFiles.JSON.nullNode());
        if(!HostFacts.uuid(attempt)||current.has("lastStop")&&(!current.path("lastStop").path("boot").asText().equals(boot)||!HostFacts.uuid(current.path("lastStop").path("id").asText())))throw new IOException("停止receiptのhost boot/IDが一致しません");
        if(current.has("restartClaim")&&!current.path("restartClaim").equals(claim))throw new IOException("別attemptのdaemon準備が未確定です");
        current.set("restartClaim",claim);WindowsFiles.write(anchor.resolve("owner.json"),current);return current;
    }
    ObjectNode consumeStop(ObjectNode owner,JsonNode proof) throws IOException {
        ObjectNode current=owner();if(!current.equals(owner)||!current.has("restartClaim")||!current.path("restartClaim").equals(proof.path("claim"))||!marker(current).equals(proof.path("marker"))||!proof.path("ready").isBoolean()||!proof.path("ready").asBoolean()||!HostFacts.uuid(proof.path("boot").asText())||!proof.path("daemon").path("generation").equals(current.path("generationOwner"))||!proof.path("daemon").path("boot").equals(proof.path("boot"))||!proof.path("daemon").path("pid").isIntegralNumber()||proof.path("daemon").path("pid").asLong()<1||!proof.path("daemon").path("started").asText().matches("[0-9]+")||!proof.path("labelVerified").isBoolean()||!proof.path("labelVerified").asBoolean())throw new IOException("daemon準備証明/claimが一致しません");
        current.set("preparedDaemon",proof);current.remove(List.of("lastStop","restartClaim"));WindowsFiles.write(anchor.resolve("owner.json"),current);return owner();
    }
    ObjectNode releaseClaim(ObjectNode owner) throws IOException {
        ObjectNode current=owner();if(!current.equals(owner)||!owner.has("restartClaim"))throw new IOException("解除するdaemon準備claimが一致しません");
        current.remove("restartClaim");WindowsFiles.write(anchor.resolve("owner.json"),current);return current;
    }
    void stopped(ObjectNode owner,String boot) throws IOException {ObjectNode current=owner();if(!current.equals(owner))throw new IOException("停止時の所有が変化しました");current.remove(List.of("restartClaim","preparedDaemon"));current.putObject("lastStop").put("boot",boot).put("id",UUID.randomUUID().toString());WindowsFiles.write(anchor.resolve("owner.json"),current);}
    static boolean sameBase(String registered,Path expected) {String value=registered.startsWith("\\\\?\\")?registered.substring(4):registered;return value.replace('/', '\\').equalsIgnoreCase(expected.toString().replace('/', '\\'));}
    ObjectNode resource(ObjectNode owner,String phase,String job) {
        ObjectNode value=WindowsFiles.JSON.createObjectNode().put("phase",phase).put("generationOwner",owner.path("generationOwner").asText());ObjectNode distribution=value.putObject("distribution").put("name",name).put("basePath",basePath.toString());
        if(owner.path("state").asText().equals("registered"))distribution.put("guid",owner.path("guid").asText()).put("markerSha256",owner.path("markerSha256").asText());value.putArray("transports");value.putArray("containers");
        if(phase.equals("ready")){value.put("socketPath","/run/mcicon/docker.sock").put("guestJobPath","/var/lib/mcicon/jobs/"+job);}return value;
    }
}
