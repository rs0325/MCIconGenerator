package io.github.rs0325.mcicon.qa;

import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.nio.file.*;
import java.util.*;

/** Separate-JVM preparation stress probe, without starting Minecraft or Docker. */
public final class RuntimePrepareProbe {
    public static void main(String[] args) throws Exception {
        var token=new CancellationToken();var store=new AssetStore(Path.of(args[0]),true,token);
        var service=new VersionService(store);var prepared=new RuntimePreparer(store,service,token).prepare(service.resolve(args[1]));
        var hashes=new TreeMap<String,String>();
        try(var files=Files.walk(prepared.natives())) {
            for(Path path:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&!p.getFileName().toString().matches("\\.mcicon-[0-9]+\\.part")).toList())
                hashes.put(prepared.natives().relativize(path).toString(),FilesSupport.hash(path,"SHA-256"));
        }
        System.out.println(Json.MAPPER.writeValueAsString(Map.of("version",prepared.version().id(),"sourceSha256",FilesSupport.hash(prepared.client(),"SHA-256"),"mappingsSha256",FilesSupport.hash(prepared.mappings(),"SHA-256"),"natives",hashes)));
    }
}
