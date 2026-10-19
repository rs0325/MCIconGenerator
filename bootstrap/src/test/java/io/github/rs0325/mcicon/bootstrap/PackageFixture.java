package io.github.rs0325.mcicon.bootstrap;

import java.nio.file.*;
import java.util.*;

/** 明示したhost Java/実CLIから私用packaging fixtureを生成する。製品trustではない。 */
public final class PackageFixture {
    public static void main(String[] args) throws Exception {
        Path destination=Path.of(args[0]),runtime=Path.of(args[1]),libraries=Path.of(args[2]);Fixture f=new Fixture(destination);f.files.clear();
        f.files.put("inventory/tool.json","{\"qaOnly\":true,\"productionTrust\":false}".getBytes());f.files.put("LICENSE",Files.readAllBytes(Path.of("LICENSE")));
        try(var paths=Files.walk(runtime)){for(Path path:paths.filter(Files::isRegularFile).sorted().toList()){
            if(Files.isSymbolicLink(path))throw new IllegalArgumentException("runtime linkをmaterializeしてください");f.files.put("java/"+runtime.relativize(path).toString().replace('\\','/'),Files.readAllBytes(path));
        }}
        try(var paths=Files.list(libraries)){for(Path path:paths.filter(p->p.toString().endsWith(".jar")).sorted().toList())f.files.put("app/lib/"+path.getFileName(),Files.readAllBytes(path));}
        f.files.put("app/worker/worker-agent.jar",Files.readAllBytes(libraries.resolve("../worker/worker-agent.jar")));((com.fasterxml.jackson.databind.node.ObjectNode)f.manifest.path("platforms").get(0).get("payload")).put("inventorySha256",Seed.hash(f.files.get("inventory/tool.json")));f.publish();
        // Java実file群のexecute属性は、source runtimeの実属性へ合わせてZIPを再生成する。
        Path zip=f.installed.resolve("seed/tool.zip");
        try(var output=new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(zip)){
            for(var row:f.files.entrySet()){
                var entry=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(row.getKey());boolean executable=row.getKey().startsWith("java/")&&Files.isExecutable(runtime.resolve(row.getKey().substring(5)));entry.setUnixMode(executable?0100700:0100600);output.putArchiveEntry(entry);output.write(row.getValue());output.closeArchiveEntry();
            }
        }
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)f.manifest.path("platforms").get(0).get("payload");payload.put("bytes",Files.size(zip)).put("sha256",Seed.hash(zip));f.writeManifest();
        Files.writeString(destination.resolve("QA-ONLY.txt"),"固定公開testvector署名。実CLI/JVMだけを用いる私用fixture。runtime descriptorは非配布fixtureで実managed描画を受入しない。\n");
        System.out.println(f.installed);
    }
}
