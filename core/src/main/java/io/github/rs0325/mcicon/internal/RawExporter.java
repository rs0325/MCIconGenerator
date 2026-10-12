package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class RawExporter {
    private static final long MAX_ENTRY=64L*1024*1024;
    private static final long MAX_TOTAL=1024L*1024*1024;
    private final CancellationToken cancellation;
    private final ProgressListener progress;
    public RawExporter(CancellationToken cancellation,ProgressListener progress) {this.cancellation=cancellation;this.progress=progress;}
    public ExportResult export(String version,Path client,ExtractRequest request) throws IOException {
        Path base=FilesSupport.safeResolve(request.output(),"java/"+version+"/raw");
        Files.createDirectories(base);
        Path manifestPath=FilesSupport.safeResolve(base,"manifest.json");
        Map<String,Object> settings=Map.of("mode","raw","preserveAnimation",true);
        String digest=FilesSupport.hash(Json.MAPPER.writeValueAsBytes(settings));
        String sourceHash=FilesSupport.hash(client,"SHA-256");
        try(FileChannel channel=FileChannel.open(FilesSupport.safeResolve(base,".export.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock acquired;
            try {acquired=channel.tryLock();}catch(OverlappingFileLockException e) {acquired=null;}
            if(acquired==null)throw new McIconException("OUTPUT_BUSY","Another export owns the output directory",1);
            try(FileLock lock=acquired;ZipFile zip=new ZipFile(client.toFile())) {
                FilesSupport.cleanupExportTemps(base);
                TreeMap<String,ExportResult.Entry> previous=new TreeMap<>();
                TreeMap<String,String> reservedAuxiliary=new TreeMap<>();
                if(Files.exists(manifestPath)) {
                    JsonNode prior=Json.MAPPER.readTree(manifestPath.toFile());
                    if(prior.path("schemaVersion").asInt()!=1||!prior.path("settingsDigest").asText().equals(digest)
                            ||!prior.path("sourceSha256").asText().equals(sourceHash)) {
                        if(!request.overwrite())throw new McIconException("OUTPUT_MISMATCH","Output belongs to different inputs/settings; use another directory or --overwrite",2);
                    } else {
                        for(JsonNode entry:prior.path("entries")) {
                            ExportResult.Entry value=Json.MAPPER.treeToValue(entry,ExportResult.Entry.class);
                            previous.put(value.id(),value);
                        }
                        prior.path("reservedAuxiliary").properties().forEach(entry->reservedAuxiliary.put(entry.getKey(),entry.getValue().asText()));
                    }
                }
                TreeMap<ResourceId,ZipEntry> selected=select(zip,request);
                TreeMap<String,ExportResult.Entry> entries=new TreeMap<>(previous);
                // Persist ownership of both files before any write. A PNG/mcmeta pair
                // is not one transaction; either may exist after a process crash.
                for(var selectedEntry:selected.entrySet()) {
                    String path=selectedEntry.getValue().getName();
                    boolean hasMetadata=zip.getEntry(path+".mcmeta")!=null;
                    ExportResult.Entry prior=entries.get(selectedEntry.getKey().toString());
                    if(prior!=null&&!(request.overwrite()&&"OUTPUT_EXISTS".equals(prior.code()))) {
                        if(request.overwrite()&&!prior.status().equals("success")&&hasMetadata)reservedAuxiliary.put(selectedEntry.getKey().toString(),path+".mcmeta");
                        continue;
                    }
                    boolean unowned=!request.overwrite()&&(Files.exists(FilesSupport.safeResolve(base,path))
                            ||hasMetadata&&Files.exists(FilesSupport.safeResolve(base,path+".mcmeta")));
                    if(hasMetadata&&!unowned)reservedAuxiliary.put(selectedEntry.getKey().toString(),path+".mcmeta");
                    entries.put(selectedEntry.getKey().toString(),new ExportResult.Entry(selectedEntry.getKey().toString(),"texture","failed",path,null,null,Map.of(),
                            unowned?"OUTPUT_EXISTS":"NOT_EXTRACTED",unowned?"Unverified output exists":"Extraction has not completed"));
                }
                saveManifest(manifestPath,version,sourceHash,settings,digest,entries,reservedAuxiliary,false);
                int completed=0;
                long total=0;
                boolean aborted=false;
                for(Map.Entry<ResourceId,ZipEntry> selectedEntry:selected.entrySet()) {
                    ResourceId id=selectedEntry.getKey();ZipEntry source=selectedEntry.getValue();
                    String path=source.getName();
                    try {
                        cancellation.check();
                        Path target=FilesSupport.safeResolve(base,path);
                        ExportResult.Entry prior=entries.get(id.toString());
                        if(prior!=null&&prior.status().equals("success")&&Files.exists(target)
                                &&FilesSupport.hash(target,"SHA-256").equals(prior.sha256())&&auxiliaryValid(base,prior.auxiliary())) {
                            completed++;progress.onProgress(new ProgressListener.ProgressEvent("extract",id.toString(),completed,selected.size()));continue;
                        }
                        byte[] bytes=read(zip,source);total+=bytes.length;
                        if(total>MAX_TOTAL)throw new IOException("Extraction exceeds total size limit");
                        PngValidation.validate(bytes);
                        String hash=FilesSupport.hash(bytes);
                        if(Files.exists(target)&&!request.overwrite()&&"OUTPUT_EXISTS".equals(prior.code()))throw new McIconException("OUTPUT_EXISTS","Unverified output exists; use --overwrite",2);
                        ZipEntry sidecar=zip.getEntry(path+".mcmeta");
                        Map<String,String> auxiliary=new TreeMap<>();
                        byte[] metadata=sidecar==null?null:read(zip,sidecar);
                        if(metadata!=null) {
                            total+=metadata.length;
                            if(total>MAX_TOTAL)throw new IOException("Extraction exceeds total size limit");
                            if(Json.MAPPER.readTree(metadata)==null)throw new IOException("Empty texture metadata");
                        }
                        if(metadata!=null) {
                            Path metadataTarget=FilesSupport.safeResolve(base,path+".mcmeta");
                            if(Files.exists(metadataTarget)&&!request.overwrite()&&("OUTPUT_EXISTS".equals(prior.code())
                                    ||!prior.auxiliary().containsKey(path+".mcmeta")&&!(path+".mcmeta").equals(reservedAuxiliary.get(id.toString()))))
                                throw new McIconException("OUTPUT_EXISTS","Unverified metadata exists; use --overwrite",2);
                        }
                        FilesSupport.atomicWrite(target,bytes);
                        if(metadata!=null) {
                            Path metadataTarget=FilesSupport.safeResolve(base,path+".mcmeta");
                            FilesSupport.atomicWrite(metadataTarget,metadata);
                            auxiliary.put(path+".mcmeta",FilesSupport.hash(metadata));
                        }
                        entries.put(id.toString(),new ExportResult.Entry(id.toString(),id.path().startsWith("block/")?"block":"item","success",path,hash,hash,auxiliary,null,null));
                        reservedAuxiliary.remove(id.toString());
                    } catch(Exception e) {
                        String code=e instanceof McIconException m?m.code():"EXTRACT_FAILED";
                        ExportResult.Entry owned=entries.get(id.toString());
                        if("OUTPUT_EXISTS".equals(code))reservedAuxiliary.remove(id.toString());
                        entries.put(id.toString(),new ExportResult.Entry(id.toString(),"texture","failed",path,null,null,
                                "OUTPUT_EXISTS".equals(code)?Map.of():owned.auxiliary(),code,"Texture export failed"));
                        if(request.failFast()||cancellation.isCancelled())aborted=true;
                    }
                    completed++;progress.onProgress(new ProgressListener.ProgressEvent("extract",id.toString(),completed,selected.size()));
                    if(completed%16==0||aborted||entries.get(id.toString()).status().equals("failed"))
                        saveManifest(manifestPath,version,sourceHash,settings,digest,entries,reservedAuxiliary,false);
                    if(aborted)break;
                }
                int finalSucceeded=(int)entries.values().stream().filter(e->e.status().equals("success")).count();
                int finalFailed=entries.size()-finalSucceeded;
                boolean complete=!aborted&&finalFailed==0;
                saveManifest(manifestPath,version,sourceHash,settings,digest,entries,reservedAuxiliary,complete);
                return new ExportResult(version,"raw",manifestPath.toString(),complete,finalSucceeded,finalFailed,List.copyOf(entries.values()));
            }
        }
    }
    private boolean auxiliaryValid(Path base,Map<String,String> auxiliary) throws IOException {
        for(var entry:auxiliary.entrySet()) {
            Path path=FilesSupport.safeResolve(base,entry.getKey());
            if(!Files.exists(path)||!FilesSupport.hash(path,"SHA-256").equals(entry.getValue()))return false;
        }
        return true;
    }
    private TreeMap<ResourceId,ZipEntry> select(ZipFile zip,ExtractRequest request) {
        TreeMap<ResourceId,ZipEntry> result=new TreeMap<>();
        if(request.all()) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(entry.isDirectory()||!name.endsWith(".png")||!name.startsWith("assets/"))continue;
                String[] parts=name.split("/",4);
                if(parts.length!=4||!parts[2].equals("textures")||!(parts[3].startsWith("item/")||parts[3].startsWith("block/")))continue;
                ResourceId id=new ResourceId(parts[1],parts[3].substring(0,parts[3].length()-4));
                if(result.put(id,entry)!=null)throw new McIconException("DUPLICATE_ASSET","Duplicate texture in archive",1);
            }
        } else for(ResourceId id:request.textures()) {
            String path="assets/"+id.namespace()+"/textures/"+id.path()+".png";
            ZipEntry entry=zip.getEntry(path);
            if(entry==null||entry.isDirectory())throw new McIconException("TEXTURE_NOT_FOUND","Texture not found: "+id,2);
            result.put(id,entry);
        }
        return result;
    }
    private byte[] read(ZipFile zip,ZipEntry entry) throws IOException {
        if(entry.getSize()>MAX_ENTRY||entry.getSize()<0)throw new IOException("Unsafe archive entry size");
        try(InputStream input=zip.getInputStream(entry);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[65536];long size=0;int count;
            while((count=input.read(buffer))>=0) {cancellation.check();size+=count;if(size>MAX_ENTRY)throw new IOException("Archive entry exceeds limit");output.write(buffer,0,count);}
            return output.toByteArray();
        }
    }
    private void saveManifest(Path path,String version,String source,Map<String,Object> settings,String digest,TreeMap<String,ExportResult.Entry> entries,Map<String,String> reservedAuxiliary,boolean complete) throws IOException {
        long successful=entries.values().stream().filter(e->e.status().equals("success")).count();
        Map<String,Object> manifest=new TreeMap<>();
        manifest.put("schemaVersion",1);manifest.put("edition","java");manifest.put("version",version);manifest.put("mode","raw");
        manifest.put("toolVersion",ToolVersion.VERSION);manifest.put("sourceSha256",source);manifest.put("settings",settings);manifest.put("settingsDigest",digest);
        manifest.put("entries",entries.values());manifest.put("complete",complete);
        manifest.put("reservedAuxiliary",reservedAuxiliary);
        manifest.put("counts",Map.of("succeeded",successful,"failed",entries.size()-successful));
        FilesSupport.atomicWrite(path,Json.MAPPER.writeValueAsBytes(manifest));
    }
}
