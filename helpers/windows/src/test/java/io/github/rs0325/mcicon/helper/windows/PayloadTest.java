package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PayloadTest {
    @TempDir Path temporary;
    ObjectNode manifest() throws Exception {
        ObjectNode manifest=WindowsFiles.JSON.createObjectNode().put("schemaVersion",1).put("guestBridgeAbi",1);var files=manifest.putArray("files");
        for(String name:List.of("guestbridge.py","windows-host.exe")){Path path=temporary.resolve(name);Files.writeString(path,"fixture-only:"+name);files.addObject().put("path",name).put("bytes",Files.size(path)).put("sha256",WindowsFiles.hash(path));}
        ObjectNode base=manifest.putObject("guestBase").put("sha256","a".repeat(64)).put("bytes",512).put("format","tar.xz").put("bridgeSha256",WindowsFiles.hash(temporary.resolve("guestbridge.py"))).put("cpuProbeSha256","b".repeat(64));var engines=base.putObject("engines");for(String name:List.of("docker","dockerd","containerd","runc"))engines.put("/usr/bin/"+name,"c".repeat(64));return manifest;
    }
    @Test void rootfsとbridgeとengineの固定closureを必要とする() throws Exception {
        ObjectNode manifest=manifest();WindowsFiles.write(temporary.resolve("payload.json"),manifest);Payload payload=new Payload(temporary.toRealPath());payload.verify();payload.base(new LeaseFile.Artifact(temporary.resolve("base.tar.xz"),512,"a".repeat(64)));assertThrows(IOException.class,()->payload.base(new LeaseFile.Artifact(temporary.resolve("other"),512,"d".repeat(64))));
        ((ObjectNode)manifest.path("guestBase").path("engines")).remove("/usr/bin/runc");WindowsFiles.write(temporary.resolve("payload.json"),manifest);assertThrows(IOException.class,()->new Payload(temporary.toRealPath()).verify());
    }
    @Test void 未知ABIとpin外実体を成功化しない() throws Exception {
        ObjectNode manifest=manifest();manifest.put("guestBridgeAbi",2);WindowsFiles.write(temporary.resolve("payload.json"),manifest);assertThrows(IOException.class,()->new Payload(temporary.toRealPath()));manifest.put("guestBridgeAbi",1);WindowsFiles.write(temporary.resolve("payload.json"),manifest);Files.writeString(temporary.resolve("windows-host.exe"),"changed");assertThrows(IOException.class,()->new Payload(temporary.toRealPath()).verify());
    }
}
