package io.github.rs0325.mcicon.internal;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.*;

public final class RuntimePreparer {
    public record Prepared(VersionInfo version,JsonNode metadata,Path client,Path mappings,List<Path> libraries,Path natives,Path assets,int javaMajor,String profile) {}
    private final AssetStore assets;
    private final VersionService versions;
    private final CancellationToken token;
    public RuntimePreparer(AssetStore assets,VersionService versions,CancellationToken token){this.assets=assets;this.versions=versions;this.token=token;}
    public Prepared prepare(VersionInfo version) throws Exception {
        String profile=profileFor(version.id());
        JsonNode meta=versions.metadata(version);Path client=versions.client(version);
        Path mappings=null;
        if(meta.path("downloads").has("client_mappings"))mappings=download(meta.path("downloads").path("client_mappings"),"versions/"+version.id()+"/mappings.txt");
        if(profile.startsWith("legacy-")) {
            mappings=LegacyMappings.prepare(assets,version.id());
        }
        List<JsonNode> libraries=new ArrayList<>();
        for(JsonNode lib:meta.path("libraries"))if(allowed(lib.path("rules"),"linux","amd64"))libraries.add(lib);
        List<Path> classpath=new ArrayList<>();List<Path> nativeJars=new ArrayList<>();
        for(JsonNode lib:libraries) {
            JsonNode downloads=lib.path("downloads");
            if(downloads.has("artifact"))classpath.add(download(downloads.path("artifact"),"libraries/"+downloads.path("artifact").path("path").asText()));
            if(lib.path("natives").has("linux")) {
                String classifier=lib.path("natives").path("linux").asText().replace("${arch}","64");
                JsonNode ref=downloads.path("classifiers").path(classifier);
                nativeJars.add(download(ref,"libraries/"+ref.path("path").asText()));
            }
        }
        Path natives=FilesSupport.safeResolve(assets.root(),"versions/"+version.id()+"/natives-linux");Files.createDirectories(natives);
        for(Path jar:nativeJars)try(ZipFile zip=new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();if(entry.isDirectory()||entry.getName().startsWith("META-INF/"))continue;
                if(entry.getSize()<0||entry.getSize()>64L*1024*1024)throw new IOException("Unsafe native archive entry");
                Path target=FilesSupport.safeResolve(natives,entry.getName());
                try(InputStream input=zip.getInputStream(entry)){byte[] data=input.readNBytes(64*1024*1024+1);if(data.length>64*1024*1024)throw new IOException("Native exceeds size limit");FilesSupport.atomicWrite(target,data);}
            }
        }
        JsonNode indexRef=meta.path("assetIndex");
        Path index=download(indexRef,"assets/indexes/"+indexRef.path("id").asText()+".json");
        JsonNode objects=Json.MAPPER.readTree(index.toFile()).path("objects");
        List<Map.Entry<String,JsonNode>> inputs=new ArrayList<>();objects.fields().forEachRemaining(inputs::add);
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures=new ArrayList<>();
            for(var entry:inputs)if(!entry.getKey().endsWith(".ogg"))futures.add(pool.submit(()-> {
                token.check();JsonNode object=entry.getValue();String hash=object.path("hash").asText();
                if(!hash.matches("[0-9a-f]{40}"))throw new McIconException("INVALID_ASSET_HASH","Invalid official asset hash",1);
                try {assets.obtain("assets/objects/"+hash.substring(0,2)+"/"+hash,URI.create("https://resources.download.minecraft.net/"+hash.substring(0,2)+"/"+hash),hash,object.path("size").asLong(),false);}
                catch(IOException e){throw new UncheckedIOException(e);}
            }));
            for(Future<?> future:futures)future.get();
        }
        int javaMajor=runtimeJavaMajor(meta.path("javaVersion").path("majorVersion").asInt(profile.startsWith("legacy-")?8:21));
        return new Prepared(version,meta,client,mappings,List.copyOf(classpath),natives,assets.root().resolve("assets"),javaMajor,profile);
    }
    static int runtimeJavaMajor(int minimum) {
        // Java 16 is no longer an image runtime; 1.17 runs on the Java 17 LTS.
        return minimum==16?17:minimum;
    }
    static String profileFor(String version) {
        return switch(version) {
            case "1.13","1.13.1","1.13.2" -> "legacy-1.13";
            case "1.14","1.14.1","1.14.2","1.14.3" -> "legacy-1.14";
            case "1.14.4","1.15","1.15.1","1.15.2","1.16","1.16.1","1.16.2","1.16.3","1.16.4","1.16.5" -> "gui-fixed";
            case "1.17","1.17.1","1.18","1.18.1","1.18.2","1.19","1.19.1","1.19.2","1.19.3","1.19.4" -> "gui-classic";
            case "1.20","1.20.1","1.20.2","1.20.3","1.20.4","1.20.5","1.20.6","1.21","1.21.1","1.21.2","1.21.3","1.21.4" -> "gui-1.21.4";
            case "1.21.5" -> "gui-1.21.5";
            case "1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11","26.1","26.1.1","26.1.2" -> "gui-deferred";
            case "26.2" -> "gui-26.2";
            case "26.3" -> "gui-26.3";
            default -> throw new McIconException("ADAPTER_UNAVAILABLE","Rendering adapter is not implemented for "+version,2);
        };
    }
    private Path download(JsonNode ref,String path) throws IOException {
        if(!ref.has("url")||!ref.has("sha1"))throw new IOException("Missing download metadata");
        return assets.obtain(path,URI.create(ref.path("url").asText()),ref.path("sha1").asText(),ref.has("size")?ref.path("size").asLong():-1,false);
    }
    public static boolean allowed(JsonNode rules,String os,String arch) {
        boolean allowed=rules.isMissingNode()||rules.isEmpty();
        for(JsonNode rule:rules) {
            JsonNode target=rule.path("os");
            if(target.has("name")&&!target.path("name").asText().equals(os))continue;
            if(target.has("arch")&&!Pattern.matches(target.path("arch").asText(),arch))continue;
            if(rule.has("features"))continue;
            allowed=rule.path("action").asText().equals("allow");
        }
        return allowed;
    }
}
