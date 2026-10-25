package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** trustで固定されたhelper内のprepared rootfs/guestbridge ABIを照合する。 */
final class Payload {
    final Path root;
    final JsonNode manifest;
    Payload(Path root) throws IOException {
        this.root=root;manifest=WindowsFiles.json(root.resolve("payload.json"));
        if(manifest.path("schemaVersion").asInt()!=1||manifest.path("guestBridgeAbi").asInt()!=1||!manifest.path("files").isArray())throw new IOException("Windows payload/guestbridge ABIが一致しません");
    }
    Path path(String name) throws IOException {
        Path relative=Path.of(name),path=root.resolve(relative).normalize();
        if(relative.isAbsolute()||relative.startsWith("..")||!path.startsWith(root)||!path.toRealPath().equals(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("payload pathが一致しません");return path;
    }
    void verify() throws IOException {
        Set<String> names=new HashSet<>();
        for(JsonNode row:manifest.path("files")) {
            String name=row.path("path").asText();if(!names.add(name))throw new IOException("payload fileが重複しています");Path path=path(name);
            if(Files.size(path)!=row.path("bytes").asLong(-1)||!WindowsFiles.hash(path).equals(row.path("sha256").asText()))throw new IOException("payload size/hashが一致しません");
        }
        if(!names.containsAll(Set.of("windows-host.exe","guestbridge.py")))throw new IOException("Windows native/guestbridge payloadがありません");
        JsonNode base=manifest.path("guestBase");
        if(!base.path("sha256").asText().matches("[0-9a-f]{64}")||base.path("bytes").asLong()<1||!Set.of("tar","tar.xz").contains(base.path("format").asText())||!base.path("bridgeSha256").asText().equals(WindowsFiles.hash(path("guestbridge.py"))))throw new IOException("prepared rootfsのpinがありません");
        JsonNode engines=base.path("engines");Set<String> paths=new HashSet<>();engines.fieldNames().forEachRemaining(paths::add);
        if(!engines.isObject()||!paths.equals(Set.of("/usr/bin/dockerd","/usr/bin/docker","/usr/bin/containerd","/usr/bin/runc")))throw new IOException("prepared engineのpinがありません");
        for(JsonNode pin:engines)if(!pin.isTextual()||!pin.asText().matches("[0-9a-f]{64}"))throw new IOException("engine SHA-256が不正です");
        if(!base.path("cpuProbeSha256").asText().matches("[0-9a-f]{64}"))throw new IOException("guest CPU probeのpinがありません");
    }
    void base(LeaseFile.Artifact artifact) throws IOException {
        JsonNode base=manifest.path("guestBase");if(artifact.bytes()!=base.path("bytes").asLong()||!artifact.sha256().equals(base.path("sha256").asText()))throw new IOException("prepared rootfsとdescriptorが一致しません");
    }
}
