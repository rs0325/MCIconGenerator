package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Fixed-function vanilla GUI pipeline for the 1.13 release family. */
public final class LegacyRenderer implements WorkerRenderer {
    private final Symbols s;
    private final Object minecraft,items,blocks;
    private final String registryOwner;
    private static final String GL="net.minecraft.client.render.platform.GlStateManager";
    private static final String TARGET="net.minecraft.client.render.pipeline.RenderTarget";
    public LegacyRenderer(Symbols symbols,Object minecraft) throws Exception {
        s=symbols;this.minecraft=minecraft;
        boolean unified=!RenderAgent.config.get("version").getAsString().equals("1.13");
        registryOwner=unified?"net.minecraft.util.registry.Registry":"net.minecraft.util.registry.IdRegistry";
        items=s.field(unified?registryOwner:"net.minecraft.item.Item",unified?"ITEM":"REGISTRY",null);
        blocks=s.field(unified?registryOwner:"net.minecraft.block.Block",unified?"BLOCK":"REGISTRY",null);
    }
    public String environment() throws Exception {return s.call("org.lwjgl.opengl.GL11","glGetString",null,7937)+" / "+s.call("org.lwjgl.opengl.GL11","glGetString",null,7938);}
    private String key(Object registry,Object value) throws Exception {return String.valueOf(s.call(registryOwner,"getKey",registry,value));}
    public JsonArray catalog() throws Exception {
        Map<String,List<String>> relations=new TreeMap<String,List<String>>();JsonArray exclusions=new JsonArray();
        for(Object block:(Iterable<?>)blocks) {
            String blockId=key(blocks,block);
            String itemId=key(items,s.call("net.minecraft.item.Item","byBlock",null,block));
            if(itemId.equals("minecraft:air")) {
                JsonObject entry=new JsonObject();entry.addProperty("id",blockId);entry.addProperty("kind","block");entry.addProperty("status","no_item_form");exclusions.add(entry);
            } else {
                List<String> list=relations.get(itemId);if(list==null){list=new ArrayList<String>();relations.put(itemId,list);}list.add(blockId);
            }
        }
        TreeMap<String,JsonObject> entries=new TreeMap<String,JsonObject>();
        for(Object item:(Iterable<?>)items) {
            String id=key(items,item);if(id.equals("minecraft:air"))continue;
            JsonObject entry=new JsonObject();entry.addProperty("id",id);entry.addProperty("status","available");entry.addProperty("kind",relations.containsKey(id)?"block":"item");
            JsonArray related=new JsonArray();if(relations.containsKey(id))for(String block:relations.get(id))related.add(block);
            entry.add("blocks",related);entries.put(id,entry);
        }
        JsonArray result=new JsonArray();for(JsonObject entry:entries.values())result.add(entry);for(JsonElement entry:exclusions)result.add(entry);return result;
    }
    public JsonObject render(JsonObject request) throws Exception {
        String id=request.get("id").getAsString();int size=request.get("size").getAsInt();
        if(!Arrays.asList(16,32,64,128,256,512).contains(size))throw new IllegalArgumentException("Unsupported size");
        Object item=null;for(Object value:(Iterable<?>)items)if(key(items,value).equals(id)){item=value;break;}
        if(item==null||id.equals("minecraft:air"))throw new IllegalArgumentException("Unknown item: "+id);
        Object stack=s.call("net.minecraft.item.Item","defaultItem",item);
        Object target=s.construct(TARGET,size,size,true);
        Object original=s.call("net.minecraft.client.Minecraft","getRenderTarget",minecraft);
        try {
            s.setField("net.minecraft.client.Minecraft","renderTarget",minecraft,target);
            s.call(TARGET,"setClearColor",target,0f,0f,0f,0f);s.call(TARGET,"clear",target);s.call(TARGET,"bindWrite",target,true);
            s.call(GL,"matrixMode",null,5889);s.call(GL,"pushMatrix",null);s.call(GL,"loadIdentity",null);
            s.call(GL,"ortho",null,0d,16d,16d,0d,1000d,3000d);
            s.call(GL,"matrixMode",null,5888);s.call(GL,"pushMatrix",null);s.call(GL,"loadIdentity",null);
            s.call(GL,"translated",null,0d,0d,-2000d);
            s.call("net.minecraft.client.render.platform.Lighting","turnOnGui",null);
            Object renderer=s.call("net.minecraft.client.Minecraft","getItemRenderer",minecraft);
            s.call("net.minecraft.client.render.entity.ItemRenderer","renderGuiItem",renderer,stack,0,0);
            ByteBuffer pixels=ByteBuffer.allocateDirect(size*size*4);
            s.call("org.lwjgl.opengl.GL11","glReadPixels",null,0,0,size,size,6408,5121,pixels);
            BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);int visible=0;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                int index=((size-1-y)*size+x)*4;
                int r=pixels.get(index)&255,g=pixels.get(index+1)&255,b=pixels.get(index+2)&255,a=pixels.get(index+3)&255;
                if(a>0)visible++;
                if(a>0&&a<255){r=Math.min(255,(r*255+a/2)/a);g=Math.min(255,(g*255+a/2)/a);b=Math.min(255,(b*255+a/2)/a);}
                if(a==0)r=g=b=0;image.setRGB(x,y,(a<<24)|(r<<16)|(g<<8)|b);
            }
            if(visible==0)throw new IllegalStateException("Empty icon: "+id);
            Path output=Paths.get(request.get("path").getAsString());Files.createDirectories(output.getParent());ImageIO.write(image,"png",output.toFile());
            JsonObject result=new JsonObject();result.addProperty("path",output.toString());result.addProperty("visiblePixels",visible);return result;
        } finally {
            s.call(GL,"matrixMode",null,5888);s.call(GL,"popMatrix",null);s.call(GL,"matrixMode",null,5889);s.call(GL,"popMatrix",null);s.call(GL,"matrixMode",null,5888);
            s.setField("net.minecraft.client.Minecraft","renderTarget",minecraft,original);s.call(TARGET,"bindWrite",original,true);s.call(TARGET,"destroyBuffers",target);
        }
    }
}
