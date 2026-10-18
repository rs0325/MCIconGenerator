package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Payload {
    private final Path root;
    private final JsonNode manifest;
    Payload(Path root) throws IOException {this.root=root;manifest=MacFiles.json(root.resolve("payload.json"));if(manifest.path("schemaVersion").asInt()!=1||!manifest.path("limaVersion").asText().equals("2.2.1")||!manifest.path("files").isArray()||!manifest.path("debs").isArray())throw new IOException("固定Mac payloadの形式が一致しません");}
    Path root(){return root;}
    Path path(String name) throws IOException {
        Path relative=Path.of(name),path=root.resolve(relative).normalize();if(relative.isAbsolute()||relative.startsWith("..")||!path.startsWith(root)||!path.toRealPath().equals(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("payloadのpathが一致しません");return path;
    }
    void verify() throws IOException {
        Set<String> names=new HashSet<>();
        for(JsonNode entry:manifest.path("files")) {
            String name=entry.path("path").asText();if(!names.add(name))throw new IOException("payload fileが重複しています");Path path=path(name);
            if(Files.size(path)!=entry.path("bytes").asLong(-1)||!MacFiles.hash(path).equals(entry.path("sha256").asText()))throw new IOException("payload size/hashが一致しません");
            if(entry.path("executable").asBoolean()&&!name.equals("bin/limactl"))throw new IOException("未知のpayload executableです");
        }
        if(!names.containsAll(Set.of("bin/limactl","share/lima/lima-guestagent.Linux-aarch64.gz")))throw new IOException("Lima/arm64 guestagentがありません");
        Map<String,String> versions=Map.of("docker.io","29.1.3-0ubuntu3~24.04.2","containerd","2.2.1-0ubuntu1~24.04.3","runc","1.3.4-0ubuntu1~24.04.1");Set<String> packages=new HashSet<>();
        for(JsonNode deb:manifest.path("debs")) {
            String name=deb.path("package").asText();if(!packages.add(name)||!versions.getOrDefault(name,"").equals(deb.path("version").asText())||!deb.path("architecture").asText().equals("arm64")||!names.contains(deb.path("path").asText()))throw new IOException("offline debの固定版が一致しません");
        }
        if(!packages.equals(versions.keySet()))throw new IOException("offline debが不足しています");
        if(!Files.isExecutable(path("bin/limactl")))throw new IOException("同梱Limaの実行権限がありません");
    }
    List<String> debNames() throws IOException {
        Map<String,String> paths=new HashMap<>();for(JsonNode deb:manifest.path("debs")){String path=deb.path("path").asText();if(!path.startsWith("offline/debs/")||path.indexOf('/',"offline/debs/".length())>=0)throw new IOException("offline deb pathが一致しません");paths.put(deb.path("package").asText(),path.substring("offline/".length()));}
        return List.of(paths.get("runc"),paths.get("containerd"),paths.get("docker.io"));
    }
}
