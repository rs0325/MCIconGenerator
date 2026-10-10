package io.github.rs0325.mcicon.internal;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class IconExporter {
    private final CancellationToken token;
    private final ProgressListener progress;
    public IconExporter(CancellationToken token,ProgressListener progress){this.token=token;this.progress=progress;}
    public ExportResult export(RuntimePreparer.Prepared runtime,WorkerClient worker,List<CatalogEntry> catalog,RenderRequest request) throws Exception {
        SortedMap<String,CatalogEntry> selected=ExportPlanner.select(catalog,request);
        Path base=FilesSupport.safeResolve(request.output(),"java/"+runtime.version().id()+"/icons");Files.createDirectories(base);
        Path manifest=FilesSupport.safeResolve(base,"manifest.json");
        Map<String,Object> settings=Map.of("size",request.size(),"profile",runtime.profile(),"context","preview-v1","agentSha256",worker.agentSha256(),
                "mappingsSha256",runtime.mappings()==null?"named":FilesSupport.hash(runtime.mappings(),"SHA-256"),
                "metadataSha256",FilesSupport.hash(Json.MAPPER.writeValueAsBytes(runtime.metadata())),"dockerImageId",worker.imageId());
        String digest=FilesSupport.hash(Json.MAPPER.writeValueAsBytes(settings));String source=FilesSupport.hash(runtime.client(),"SHA-256");
        TreeMap<String,ExportResult.Entry> entries=new TreeMap<>();
        try(FileChannel channel=FileChannel.open(FilesSupport.safeResolve(base,".export.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock acquired;
            try {acquired=channel.tryLock();}catch(OverlappingFileLockException e){acquired=null;}
            if(acquired==null)throw new McIconException("OUTPUT_BUSY","Another export owns the output directory",1);
            try(FileLock lock=acquired) {
                if(Files.exists(manifest)) {
                    JsonNode previous=Json.MAPPER.readTree(manifest.toFile());
                    boolean matches=previous.path("settingsDigest").asText().equals(digest)&&previous.path("sourceSha256").asText().equals(source)
                            &&previous.path("renderer").asText().equals(worker.renderer());
                    if(!matches&&!request.overwrite())throw new McIconException("OUTPUT_MISMATCH","Output uses another input/renderer/settings; use another directory or --overwrite",2);
                    if(matches)for(JsonNode entry:previous.path("entries")){ExportResult.Entry e=Json.MAPPER.treeToValue(entry,ExportResult.Entry.class);entries.put(e.id(),e);}
                }
                int completed=0;boolean aborted=false;
                for(CatalogEntry entry:selected.values()) {
                    ResourceId id=ResourceId.parse(entry.id());String path="assets/"+id.namespace()+"/textures/"+entry.kind()+"/"+id.path()+".png";
                    try {
                        token.check();Path target=FilesSupport.safeResolve(base,path);ExportResult.Entry previous=entries.get(entry.id());
                        if(previous!=null&&previous.status().equals("success")&&Files.exists(target)&&FilesSupport.hash(target,"SHA-256").equals(previous.sha256())) {
                            progress.onProgress(new ProgressListener.ProgressEvent("render",entry.id(),++completed,selected.size()));continue;
                        }
                        if(Files.exists(target)&&previous==null&&!request.overwrite())throw new McIconException("OUTPUT_EXISTS","Unverified output exists; use --overwrite",2);
                        Path image=worker.render(entry.id(),request.size());
                        try {
                            var decoded=ImageIO.read(image.toFile());
                            if(decoded==null||decoded.getWidth()!=request.size()||decoded.getHeight()!=request.size()||!decoded.getColorModel().hasAlpha())throw new IOException("Invalid worker PNG");
                            byte[] data=Files.readAllBytes(image);FilesSupport.atomicWrite(target,data);
                            entries.put(entry.id(),new ExportResult.Entry(entry.id(),entry.kind(),"success",path,FilesSupport.hash(data),null,Map.of(),null,null));
                        }finally{Files.deleteIfExists(image);}
                    } catch(Exception e) {
                        entries.put(entry.id(),new ExportResult.Entry(entry.id(),entry.kind(),"failed",path,null,null,Map.of(),e instanceof McIconException m?m.code():"RENDER_FAILED","Inventory icon export failed"));
                        if(request.failFast()||token.isCancelled())aborted=true;
                    }
                    progress.onProgress(new ProgressListener.ProgressEvent("render",entry.id(),++completed,selected.size()));
                    save(manifest,runtime,worker,settings,digest,source,entries,false,catalog);
                    if(aborted)break;
                }
                int succeeded=(int)entries.values().stream().filter(e->e.status().equals("success")).count();int failed=entries.size()-succeeded;
                boolean complete=!aborted&&failed==0;save(manifest,runtime,worker,settings,digest,source,entries,complete,catalog);
                return new ExportResult(runtime.version().id(),"icons",manifest.toString(),complete,succeeded,failed,List.copyOf(entries.values()));
            }
        }
    }
    private void save(Path path,RuntimePreparer.Prepared runtime,WorkerClient worker,Map<String,Object> settings,String digest,String source,TreeMap<String,ExportResult.Entry> entries,boolean complete,List<CatalogEntry> catalog) throws IOException {
        Map<String,Object> manifest=new TreeMap<>();
        manifest.put("schemaVersion",1);manifest.put("edition","java");manifest.put("version",runtime.version().id());manifest.put("mode","icons");
        manifest.put("toolVersion","0.1.0-SNAPSHOT");manifest.put("adapterVersion",runtime.profile()+"/0.1");manifest.put("sourceSha256",source);
        manifest.put("settings",settings);manifest.put("settingsDigest",digest);manifest.put("renderer",worker.renderer());manifest.put("javaMajor",runtime.javaMajor());
        manifest.put("environment",Map.of("os","linux","arch","amd64","backend","docker","imageId",worker.imageId()));
        manifest.put("context",Map.of("profile","preview-v1","deterministic",false,"count",1,"overlays",false));
        manifest.put("entries",entries.values());manifest.put("complete",complete);
        manifest.put("catalog",catalog);long success=entries.values().stream().filter(e->e.status().equals("success")).count();
        manifest.put("counts",Map.of("succeeded",success,"failed",entries.size()-success,"noItemForm",catalog.stream().filter(e->e.status().equals("no_item_form")).count()));
        FilesSupport.atomicWrite(path,Json.MAPPER.writeValueAsBytes(manifest));
    }
}
