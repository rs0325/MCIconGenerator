package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class VersionService {
    public static final URI MANIFEST=URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private final AssetStore assets;
    private JsonNode manifest;
    private Map<String,String> support;
    public VersionService(AssetStore assets) {this.assets=assets;}
    private synchronized JsonNode manifest() throws IOException {
        if(manifest==null)manifest=Json.MAPPER.readTree(assets.obtain("metadata/version_manifest_v2.json",MANIFEST,null,-1,true).toFile());
        return manifest;
    }
    public List<VersionInfo> list() throws IOException {
        if(support==null) {
            support=new HashMap<>();
            try(InputStream input=VersionService.class.getResourceAsStream("/adapters/releases.json")) {
                if(input==null)throw new IOException("Missing releases lock");
                for(JsonNode version:Json.MAPPER.readTree(input).path("versions"))support.put(version.path("id").asText(),version.path("status").asText());
            }
        }
        JsonNode manifest=manifest();Instant cutoff=null;
        for(JsonNode v:manifest.path("versions"))if(v.path("id").asText().equals("1.13")&&v.path("type").asText().equals("release"))cutoff=Instant.parse(v.path("releaseTime").asText());
        if(cutoff==null)throw new IOException("Official manifest has no 1.13 release");
        List<VersionInfo> versions=new ArrayList<>();
        for(JsonNode v:manifest.path("versions")) {
            if(!v.path("type").asText().equals("release")||Instant.parse(v.path("releaseTime").asText()).isBefore(cutoff))continue;
            String id=v.path("id").asText();
            if(!id.matches("[a-zA-Z0-9_.-]+"))throw new IOException("Unsafe version identifier in manifest");
            versions.add(new VersionInfo(id,v.path("releaseTime").asText(),support.getOrDefault(id,"discovered"),v.path("url").asText(),v.path("sha1").asText()));
        }
        versions.sort(Comparator.comparing(VersionInfo::releaseTime).reversed());return List.copyOf(versions);
    }
    public VersionInfo resolve(String requested) throws IOException {
        String exact=requested.equals("latest")?manifest().path("latest").path("release").asText():requested;
        return list().stream().filter(v->v.id().equals(exact)).findFirst()
                .orElseThrow(()->new McIconException("UNSUPPORTED_VERSION","Not a Java Edition release at or after 1.13: "+exact,2));
    }
    public JsonNode metadata(VersionInfo version) throws IOException {
        Path file=assets.obtain("versions/"+version.id()+"/version.json",URI.create(version.metadataUrl()),version.metadataSha1(),-1,false);
        return Json.MAPPER.readTree(file.toFile());
    }
    public Path client(VersionInfo version) throws IOException {
        JsonNode client=metadata(version).path("downloads").path("client");
        if(client.isMissingNode())throw new IOException("No official client download");
        return assets.obtain("versions/"+version.id()+"/client.jar",URI.create(client.path("url").asText()),client.path("sha1").asText(),client.path("size").asLong(),false);
    }
}
