package io.github.rs0325.mcicon.qa;

import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Minecraft・Dockerを起動せず準備結果を検査する。引数はcache、exact版、offline指定（true/false）。 */
public final class RuntimePrepareProbe {
    public static void main(String[] args) throws Exception {
        var token=new CancellationToken();var store=new AssetStore(Path.of(args[0]),Boolean.parseBoolean(args[2]),token);
        var service=new VersionService(store);var prepared=new RuntimePreparer(store,service,token).prepare(service.resolve(args[1]));
        var hashes=new TreeMap<String,String>();
        Files.walkFileTree(prepared.natives(),new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes) throws IOException {
                if(attributes.isRegularFile()&&!temporary(path))
                    hashes.put(prepared.natives().relativize(path).toString(),FilesSupport.hash(path,"SHA-256"));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path,IOException error) throws IOException {
                // 他JVMが確定したpartだけは、列挙後に消失してよい。
                if(temporary(path)&&error instanceof NoSuchFileException)return FileVisitResult.CONTINUE;
                throw error;
            }
        });
        System.out.println(Json.MAPPER.writeValueAsString(Map.of("version",prepared.version().id(),"sourceSha256",FilesSupport.hash(prepared.client(),"SHA-256"),"mappingsSha256",FilesSupport.hash(prepared.mappings(),"SHA-256"),"natives",hashes)));
    }
    private static boolean temporary(Path path) {
        return path.getFileName().toString().matches("\\.mcicon-[0-9]+\\.part");
    }
}
