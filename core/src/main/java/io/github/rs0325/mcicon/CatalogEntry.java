package io.github.rs0325.mcicon;
import java.util.List;
public record CatalogEntry(String id,String kind,String status,List<String> blocks) {
    public CatalogEntry {blocks=blocks==null?List.of():List.copyOf(blocks);}
}
