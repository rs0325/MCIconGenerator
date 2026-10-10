package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Minecraft 1.21.4 GUI backend. Other rendering generations have separate adapters. */
public class ModernRenderer implements WorkerRenderer {
    protected final Symbols s;
    protected final Object minecraft,items,blocks;
    public ModernRenderer(Symbols symbols,Object minecraft) throws Exception {
        this.s=symbols; this.minecraft=minecraft;
        items=s.field("net.minecraft.core.registries.BuiltInRegistries","ITEM",null);
        blocks=s.field("net.minecraft.core.registries.BuiltInRegistries","BLOCK",null);
    }
    public String environment() throws Exception {
        return s.call("org.lwjgl.opengl.GL11","glGetString",null,7937)+" / "+s.call("org.lwjgl.opengl.GL11","glGetString",null,7938);
    }
    protected String key(Object registry,Object value) throws Exception {
        return String.valueOf(s.call("net.minecraft.core.Registry","getKey",registry,value));
    }
    public JsonArray catalog() throws Exception {
        Map<String,List<String>> relations=new TreeMap<String,List<String>>();
        JsonArray exclusions=new JsonArray();
        for(Object block:(Iterable<?>)blocks) {
            String blockId=key(blocks,block);
            Object item=s.call("net.minecraft.world.level.block.Block","asItem",block);
            String itemId=key(items,item);
            if(itemId.equals("minecraft:air")) {
                JsonObject e=new JsonObject(); e.addProperty("id",blockId);e.addProperty("kind","block");e.addProperty("status","no_item_form");exclusions.add(e);
            } else {
                List<String> ids=relations.get(itemId);
                if(ids==null) {ids=new ArrayList<String>(); relations.put(itemId,ids);} ids.add(blockId);
            }
        }
        TreeMap<String,JsonObject> entries=new TreeMap<String,JsonObject>();
        for(Object item:(Iterable<?>)items) {
            String id=key(items,item); if(id.equals("minecraft:air"))continue;
            JsonObject e=new JsonObject();e.addProperty("id",id);e.addProperty("status","available");
            e.addProperty("kind",relations.containsKey(id)?"block":"item");
            JsonArray related=new JsonArray(); if(relations.containsKey(id))for(String block:relations.get(id))related.add(block);
            e.add("blocks",related);entries.put(id,e);
        }
        JsonArray result=new JsonArray(); for(JsonObject entry:entries.values())result.add(entry);
        for(JsonElement exclusion:exclusions)result.add(exclusion);
        return result;
    }
    public JsonObject render(JsonObject request) throws Exception {
        String id=request.get("id").getAsString(); int size=request.get("size").getAsInt();
        if(!Arrays.asList(16,32,64,128,256,512).contains(size))throw new IllegalArgumentException("Unsupported size");
        Object location=s.call("net.minecraft.resources.ResourceLocation","parse",null,id);
        Object item=s.call("net.minecraft.core.Registry","getValue",items,location);
        if(item==null||key(items,item).equals("minecraft:air"))throw new IllegalArgumentException("Unknown item: "+id);
        Object stack=s.call("net.minecraft.world.item.Item","getDefaultInstance",item);
        Object target=s.construct("com.mojang.blaze3d.pipeline.TextureTarget",size,size,true);
        Object original=s.field("net.minecraft.client.Minecraft","mainRenderTarget",minecraft);
        Object view=null;
        try {
            s.setField("net.minecraft.client.Minecraft","mainRenderTarget",minecraft,target);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","setClearColor",target,0f,0f,0f,0f);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","clear",target);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","bindWrite",target,true);
            Object projection=s.construct("org.joml.Matrix4f");
            s.call("org.joml.Matrix4f","setOrtho",projection,0f,16f,16f,0f,1000f,21000f);
            Object type=s.field("com.mojang.blaze3d.ProjectionType","ORTHOGRAPHIC",null);
            s.call("com.mojang.blaze3d.systems.RenderSystem","setProjectionMatrix",null,projection,type);
            view=s.call("com.mojang.blaze3d.systems.RenderSystem","getModelViewStack",null);
            s.call("org.joml.Matrix4fStack","pushMatrix",view);
            s.call("org.joml.Matrix4f","translation",view,0f,0f,-11000f);
            s.call("com.mojang.blaze3d.platform.Lighting","setupFor3DItems",null);
            Object buffers=s.call("net.minecraft.client.Minecraft","renderBuffers",minecraft);
            Object source=s.call("net.minecraft.client.renderer.RenderBuffers","bufferSource",buffers);
            Object graphics=s.construct("net.minecraft.client.gui.GuiGraphics",minecraft,source);
            s.call("net.minecraft.client.gui.GuiGraphics","renderItem",graphics,stack,0,0);
            s.call("net.minecraft.client.gui.GuiGraphics","flush",graphics);
            ByteBuffer pixels=ByteBuffer.allocateDirect(size*size*4);
            s.call("org.lwjgl.opengl.GL11","glReadPixels",null,0,0,size,size,6408,5121,pixels);
            BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
            int visible=0;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                int offset=((size-1-y)*size+x)*4;
                int r=pixels.get(offset)&255,g=pixels.get(offset+1)&255,b=pixels.get(offset+2)&255,a=pixels.get(offset+3)&255;
                if(a>0)visible++;
                // GUI blend output is premultiplied on a transparent framebuffer.
                if(a>0&&a<255){r=Math.min(255,(r*255+a/2)/a);g=Math.min(255,(g*255+a/2)/a);b=Math.min(255,(b*255+a/2)/a);}
                if(a==0)r=g=b=0;
                image.setRGB(x,y,(a<<24)|(r<<16)|(g<<8)|b);
            }
            if(visible==0)throw new IllegalStateException("Empty icon: "+id);
            Path output=Paths.get(request.get("path").getAsString());
            Files.createDirectories(output.getParent());
            if(!ImageIO.write(image,"PNG",output.toFile()))throw new IllegalStateException("PNG encoder unavailable");
            JsonObject result=new JsonObject();result.addProperty("path",output.toString());result.addProperty("visiblePixels",visible);return result;
        } finally {
            if(view!=null)s.call("org.joml.Matrix4fStack","popMatrix",view);
            s.setField("net.minecraft.client.Minecraft","mainRenderTarget",minecraft,original);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","bindWrite",original,true);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","destroyBuffers",target);
        }
    }
}
