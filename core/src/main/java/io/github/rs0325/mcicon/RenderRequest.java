package io.github.rs0325.mcicon;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
public record RenderRequest(String version,List<ResourceId> ids,boolean all,String kind,int size,Path output,
                            boolean overwrite,boolean failFast,boolean allowExperimental) {
    public RenderRequest {
        ids=List.copyOf(ids);
        if(version==null||output==null||all==!ids.isEmpty())throw new IllegalArgumentException("Specify IDs or --all, a version and an output directory");
        if(!Set.of("item","block","all").contains(kind))throw new IllegalArgumentException("Invalid item kind");
        if(!Set.of(16,32,64,128,256,512).contains(size))throw new IllegalArgumentException("Size must be 16, 32, 64, 128, 256, or 512");
    }
}
