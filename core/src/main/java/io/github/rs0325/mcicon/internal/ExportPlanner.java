package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import java.util.*;

/** Selects canonical items from registry data, preserving block aliases. */
public final class ExportPlanner {
    public static SortedMap<String,CatalogEntry> select(List<CatalogEntry> catalog,RenderRequest request) {
        TreeMap<String,CatalogEntry> available=new TreeMap<>();
        Map<String,String> aliases=new HashMap<>();
        Set<String> noItem=new HashSet<>();
        for(CatalogEntry entry:catalog) {
            ResourceId.parse(entry.id());
            if(entry.status().equals("no_item_form"))noItem.add(entry.id());
            else if(entry.status().equals("available")) {
                if(available.put(entry.id(),entry)!=null)throw new McIconException("INVALID_CATALOG","Duplicate registered item",1);
                for(String block:entry.blocks()) {
                    ResourceId.parse(block);
                    String previous=aliases.put(block,entry.id());
                    if(previous!=null&&!previous.equals(entry.id()))throw new McIconException("INVALID_CATALOG","Ambiguous block-item relation",1);
                }
            } else throw new McIconException("INVALID_CATALOG","Unknown catalog status",1);
        }
        TreeMap<String,CatalogEntry> selected=new TreeMap<>();
        if(request.all())for(CatalogEntry entry:available.values()) {
            if(request.kind().equals("all")||request.kind().equals(entry.kind()))selected.put(entry.id(),entry);
        } else for(ResourceId id:request.ids()) {
            String value=id.toString();
            if(request.kind().equals("block")) {
                if(noItem.contains(value))throw new McIconException("NO_ITEM_FORM","Block has no inventory item: "+id,2);
                value=aliases.getOrDefault(value,value);
            }
            CatalogEntry entry=available.get(value);
            if(entry==null)throw new McIconException("ITEM_NOT_FOUND","No registered item: "+id,2);
            if(!request.kind().equals("all")&&!request.kind().equals(entry.kind()))throw new McIconException("KIND_MISMATCH","Requested item has a different kind: "+id,2);
            selected.put(entry.id(),entry);
        }
        return Collections.unmodifiableSortedMap(selected);
    }
    private ExportPlanner() {}
}
