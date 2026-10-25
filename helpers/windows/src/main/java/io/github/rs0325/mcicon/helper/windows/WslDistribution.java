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
    final String name;
    private final ObjectNode binding;
    WslDistribution(Path root,JsonNode lease,HostFacts host) throws IOException {
        Path parent=root.resolve("windows");WindowsFiles.privateDirectory(parent);anchor=parent.resolve(lease.path("descriptorSha256").asText());WindowsFiles.privateDirectory(anchor);basePath=anchor.resolve("distro");name="MCIcon-"+lease.path("descriptorSha256").asText().substring(0,24);
        binding=WindowsFiles.JSON.createObjectNode().put("sid",host.sid()).put("root",root.toRealPath().toString());for(String key:List.of("hostId","descriptorSha256"))binding.set(key,lease.required(key));
    }
    Lock lock(Duration budget) throws IOException {
        FileChannel channel=FileChannel.open(anchor.resolve("generation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);long end=System.nanoTime()+budget.toNanos();
        try {while(true){FileLock lock=null;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock!=null)return new Lock(channel,lock);if(System.nanoTime()>=end)throw new IOException("専用distroのlifecycle lockがbusyです");try{Thread.sleep(10);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("distro lockが中断されました");}}}catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
    record Lock(FileChannel channel,FileLock lock) implements AutoCloseable {public void close() throws IOException{try{lock.release();}finally{channel.close();}}}
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
    void consumeStop(ObjectNode owner) throws IOException {if(owner.has("lastStop")){ObjectNode current=owner();if(!current.equals(owner))throw new IOException("停止receiptが変化しました");current.remove("lastStop");WindowsFiles.write(anchor.resolve("owner.json"),current);}}
    void stopped(ObjectNode owner,String boot) throws IOException {ObjectNode current=owner();if(!current.path("guid").equals(owner.path("guid"))||!current.path("generationOwner").equals(owner.path("generationOwner")))throw new IOException("停止時の所有が変化しました");current.putObject("lastStop").put("boot",boot).put("id",UUID.randomUUID().toString());WindowsFiles.write(anchor.resolve("owner.json"),current);}
    static boolean sameBase(String registered,Path expected) {String value=registered.startsWith("\\\\?\\")?registered.substring(4):registered;return value.replace('/', '\\').equalsIgnoreCase(expected.toString().replace('/', '\\'));}
    ObjectNode resource(ObjectNode owner,String phase,String job) {
        ObjectNode value=WindowsFiles.JSON.createObjectNode().put("phase",phase).put("generationOwner",owner.path("generationOwner").asText());ObjectNode distribution=value.putObject("distribution").put("name",name).put("basePath",basePath.toString());
        if(owner.path("state").asText().equals("registered"))distribution.put("guid",owner.path("guid").asText()).put("markerSha256",owner.path("markerSha256").asText());value.putArray("transports");value.putArray("containers");
        if(phase.equals("ready")){value.put("socketPath","/run/mcicon/docker.sock").put("guestJobPath","/var/lib/mcicon/jobs/"+job);}return value;
    }
}
