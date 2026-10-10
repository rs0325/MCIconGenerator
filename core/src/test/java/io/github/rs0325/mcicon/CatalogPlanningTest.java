package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.ExportPlanner;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPlanningTest {
    private final List<CatalogEntry> catalog=List.of(
        new CatalogEntry("minecraft:oak_sign","block","available",List.of("minecraft:oak_sign","minecraft:oak_wall_sign")),
        new CatalogEntry("minecraft:diamond","item","available",List.of()),
        new CatalogEntry("minecraft:water","block","no_item_form",List.of()));
    RenderRequest request(String kind,boolean all,String...ids) {
        return new RenderRequest("1.21.4",Arrays.stream(ids).map(ResourceId::parse).toList(),all,kind,64,Path.of("out"),false,false,true);
    }
    @Test void blockAliasesDeduplicateToCanonicalItemAndAllDoesNotInventWater() {
        assertEquals(Set.of("minecraft:oak_sign"),ExportPlanner.select(catalog,request("block",false,"oak_sign","oak_wall_sign")).keySet());
        assertEquals(Set.of("minecraft:diamond","minecraft:oak_sign"),ExportPlanner.select(catalog,request("all",true)).keySet());
    }
    @Test void noItemFormAndUnknownItemHaveDifferentDiagnostics() {
        assertEquals("NO_ITEM_FORM",assertThrows(McIconException.class,()->ExportPlanner.select(catalog,request("block",false,"water"))).code());
        assertEquals("ITEM_NOT_FOUND",assertThrows(McIconException.class,()->ExportPlanner.select(catalog,request("all",false,"missing"))).code());
    }
}
