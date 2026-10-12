package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Immediate GuiGraphics backend, selected only for explicitly validated releases. */
public class ModernRenderer implements WorkerRenderer {
    protected final Symbols s;
    protected final Object minecraft,items,blocks;
    private final boolean inventoryView;
    public ModernRenderer(Symbols symbols,Object minecraft) throws Exception {
        this.s=symbols; this.minecraft=minecraft;
        String registries=(RenderAgent.config.get("version").getAsString().startsWith("26.")||s.hasField("net.minecraft.core.registries.BuiltInRegistries","ITEM"))?"net.minecraft.core.registries.BuiltInRegistries":"net.minecraft.core.Registry";
        items=s.field(registries,"ITEM",null);
        blocks=s.field(registries,"BLOCK",null);
        String version=RenderAgent.config.get("version").getAsString();
        inventoryView=version.equals("1.20.6");
        if(version.equals("1.20.6")||version.equals("1.21.4")) {
            Object gameRenderer=s.field("net.minecraft.client.Minecraft","gameRenderer",minecraft);
            Object light=s.call("net.minecraft.client.renderer.GameRenderer","lightTexture",gameRenderer);
            // 実ワールドのfull-bright lightmapと同じ252/255へ初期化する。
            if(version.equals("1.20.6")) {
                Object pixels=s.field("net.minecraft.client.renderer.LightTexture","lightPixels",light);
                for(int y=0;y<16;y++)for(int x=0;x<16;x++)s.call("com.mojang.blaze3d.platform.NativeImage","setPixelRGBA",pixels,x,y,0xfffcfcfc);
                s.call("net.minecraft.client.renderer.texture.DynamicTexture","upload",s.field("net.minecraft.client.renderer.LightTexture","lightTexture",light));
            } else {
                Object target=s.field("net.minecraft.client.renderer.LightTexture","target",light);
                s.call("com.mojang.blaze3d.pipeline.RenderTarget","setClearColor",target,0.99f,0.99f,0.99f,1f);
                s.call("com.mojang.blaze3d.pipeline.RenderTarget","clear",target);
                bindTarget(s.call("net.minecraft.client.Minecraft","getMainRenderTarget",minecraft));
            }
        }
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
        String locationClass=(RenderAgent.config.get("version").getAsString().startsWith("26.")||s.hasClass("net.minecraft.resources.Identifier"))?"net.minecraft.resources.Identifier":"net.minecraft.resources.ResourceLocation";
        Object location=locationClass.endsWith("Identifier")||s.hasMethod(locationClass,"parse",1)?s.call(locationClass,"parse",null,id):s.construct(locationClass,id);
        Object item=s.call("net.minecraft.core.Registry",(RenderAgent.config.get("version").getAsString().startsWith("26.")||s.hasMethod("net.minecraft.core.Registry","getValue",1))?"getValue":"get",items,location);
        if(item==null||key(items,item).equals("minecraft:air"))throw new IllegalArgumentException("Unknown item: "+id);
        Object stack=s.call("net.minecraft.world.item.Item","getDefaultInstance",item);
        boolean oldTarget=s.hasMethod("com.mojang.blaze3d.pipeline.RenderTarget","clear",1);
        Object target=createTarget(size,oldTarget);
        Object original=s.field("net.minecraft.client.Minecraft","mainRenderTarget",minecraft);
        Object view=null;
        try {
            s.setField("net.minecraft.client.Minecraft","mainRenderTarget",minecraft,target);
            clearTarget(target,oldTarget);
            bindTarget(target);
            if(inventoryView) {
                int scale=size/16;
                // 通常インベントリの投影・スロット位置を、小さな描画先へ切り出す。
                s.call("org.lwjgl.opengl.GL11","glViewport",null,-80*scale,-103*scale,320*scale,240*scale);
            }
            view=setupView(oldTarget);
            setupLighting();
            drawItem(stack);
            ByteBuffer pixels=readPixels(target,size);
            Path output=Paths.get(request.get("path").getAsString());
            int visible=PngOutput.write(pixels,size,output);
            JsonObject result=new JsonObject();result.addProperty("path",output.toString());result.addProperty("visiblePixels",visible);return result;
        } finally {
            if(view!=null)restoreView(view);
            s.setField("net.minecraft.client.Minecraft","mainRenderTarget",minecraft,original);
            bindTarget(original);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","destroyBuffers",target);
        }
    }
    protected void setupLighting() throws Exception {s.call("com.mojang.blaze3d.platform.Lighting","setupFor3DItems",null);}
    protected Object setupView(boolean oldTarget) throws Exception {
        Object projection;
        if(s.hasClass("com.mojang.math.Matrix4f"))projection=s.call("com.mojang.math.Matrix4f","orthographic",null,16f,-16f,1000f,21000f);
        else {
            projection=s.construct("org.joml.Matrix4f");
            s.call("org.joml.Matrix4f","setOrtho",projection,0f,inventoryView?320f:16f,inventoryView?240f:16f,0f,1000f,21000f);
        }
        if(s.hasMethod("com.mojang.blaze3d.systems.RenderSystem","setProjectionMatrix",1))s.call("com.mojang.blaze3d.systems.RenderSystem","setProjectionMatrix",null,projection);
        else {
            Object type=oldTarget?s.field("com.mojang.blaze3d.vertex.VertexSorting","ORTHOGRAPHIC_Z",null):s.field("com.mojang.blaze3d.ProjectionType","ORTHOGRAPHIC",null);
            s.call("com.mojang.blaze3d.systems.RenderSystem","setProjectionMatrix",null,projection,type);
        }
        Object view=s.call("com.mojang.blaze3d.systems.RenderSystem","getModelViewStack",null);
        if(view.getClass().getName().equals(s.actualClass("com.mojang.blaze3d.vertex.PoseStack"))) {
            s.call("com.mojang.blaze3d.vertex.PoseStack","pushPose",view);
            s.call("com.mojang.blaze3d.vertex.PoseStack","setIdentity",view);
            s.call("com.mojang.blaze3d.vertex.PoseStack","translate",view,0d,0d,-11000d);
            s.call("com.mojang.blaze3d.systems.RenderSystem","applyModelViewMatrix",null);
        } else {
            s.call("org.joml.Matrix4fStack","pushMatrix",view);
            s.call("org.joml.Matrix4f","translation",view,0f,0f,-11000f);
            if(s.hasMethod("com.mojang.blaze3d.systems.RenderSystem","applyModelViewMatrix",0))s.call("com.mojang.blaze3d.systems.RenderSystem","applyModelViewMatrix",null);
        }
        return view;
    }
    protected void restoreView(Object view) throws Exception {
        if(view.getClass().getName().equals(s.actualClass("com.mojang.blaze3d.vertex.PoseStack"))) {
            s.call("com.mojang.blaze3d.vertex.PoseStack","popPose",view);
            s.call("com.mojang.blaze3d.systems.RenderSystem","applyModelViewMatrix",null);
        } else {
            s.call("org.joml.Matrix4fStack","popMatrix",view);
            if(s.hasMethod("com.mojang.blaze3d.systems.RenderSystem","applyModelViewMatrix",0))s.call("com.mojang.blaze3d.systems.RenderSystem","applyModelViewMatrix",null);
        }
    }
    protected void drawItem(Object stack) throws Exception {
        Object buffers=s.call("net.minecraft.client.Minecraft","renderBuffers",minecraft);
        Object source=s.call("net.minecraft.client.renderer.RenderBuffers","bufferSource",buffers);
        Object graphics=s.construct("net.minecraft.client.gui.GuiGraphics",minecraft,source);
        s.call("net.minecraft.client.gui.GuiGraphics","renderItem",graphics,stack,inventoryView?80:0,inventoryView?121:0);
        s.call("net.minecraft.client.gui.GuiGraphics","flush",graphics);
    }
    protected Object createTarget(int size,boolean oldTarget) throws Exception {
        return oldTarget?s.construct("com.mojang.blaze3d.pipeline.TextureTarget",size,size,true,false):s.construct("com.mojang.blaze3d.pipeline.TextureTarget",size,size,true);
    }
    protected void clearTarget(Object target,boolean oldTarget) throws Exception {
        s.call("com.mojang.blaze3d.pipeline.RenderTarget","setClearColor",target,CanonicalContext.backgroundColor(),CanonicalContext.backgroundColor(),CanonicalContext.backgroundColor(),CanonicalContext.backgroundAlpha());
        if(oldTarget)s.call("com.mojang.blaze3d.pipeline.RenderTarget","clear",target,false);
        else s.call("com.mojang.blaze3d.pipeline.RenderTarget","clear",target);
    }
    protected void bindTarget(Object target) throws Exception {s.call("com.mojang.blaze3d.pipeline.RenderTarget","bindWrite",target,true);}
    protected ByteBuffer readPixels(Object target,int size) throws Exception {
        ByteBuffer pixels=ByteBuffer.allocateDirect(size*size*4);
        s.call("org.lwjgl.opengl.GL11","glReadPixels",null,0,0,size,size,6408,5121,pixels);
        return pixels;
    }
}
