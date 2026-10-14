package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Extracted GUI with the distinct Blaze3D 26.2 and RenderPearl 26.3 APIs. */
public final class LatestRenderer extends ModernRenderer {
    private final boolean pearl;
    private final String gpuPackage;
    public LatestRenderer(Symbols symbols,Object minecraft) throws Exception {
        super(symbols,minecraft);
        pearl=RenderAgent.config.get("profile").getAsString().equals("gui-26.3");
        gpuPackage=pearl?"com.mojang.renderpearl.api.":"com.mojang.blaze3d.";
        Object lookup=s.call("net.minecraft.data.registries.VanillaRegistries",pearl?"createWorldLookup":"createLookup",null);
        Object initializers=s.field("net.minecraft.core.registries.BuiltInRegistries","DATA_COMPONENT_INITIALIZERS",null);
        Iterable<?> pending=(Iterable<?>)s.call("net.minecraft.core.component.DataComponentInitializers","build",initializers,lookup);
        for(Object components:pending)s.call("net.minecraft.core.component.DataComponentInitializers$PendingComponents","apply",components);
    }
    public JsonObject render(JsonObject request) throws Exception {
        String id=request.get("id").getAsString();int size=request.get("size").getAsInt();
        if(!Arrays.asList(16,32,64,128,256,512).contains(size))throw new IllegalArgumentException("Unsupported size");
        Object location=s.call("net.minecraft.resources.Identifier","parse",null,id);
        Object item=s.call("net.minecraft.core.Registry","getValue",items,location);
        if(item==null||key(items,item).equals("minecraft:air"))throw new IllegalArgumentException("Unknown item: "+id);
        Object stack=s.call("net.minecraft.world.item.Item","getDefaultInstance",item);
        Object gameRenderer=s.field("net.minecraft.client.Minecraft","gameRenderer",minecraft);
        Object gameState=s.call("net.minecraft.client.renderer.GameRenderer","gameRenderState",gameRenderer);
        Object state=s.field("net.minecraft.client.renderer.state.GameRenderState","guiRenderState",gameState);
        Object window=s.field("net.minecraft.client.renderer.state.GameRenderState","windowRenderState",gameState);
        Object guiRenderer=s.field("net.minecraft.client.renderer.GameRenderer","guiRenderer",gameRenderer);
        Object original=s.field("net.minecraft.client.renderer.GameRenderer","mainRenderTarget",gameRenderer);
        String windowClass="net.minecraft.client.renderer.state.WindowRenderState";
        Object width=s.field(windowClass,"width",window),height=s.field(windowClass,"height",window),scale=s.field(windowClass,"guiScale",window);
        Object colorFormat=s.field(gpuPackage+"GpuFormat","RGBA8_UNORM",null);
        IntBuffer targetState=captureTargetState();
        Object target;
        if(pearl) {
            Object depthFormat=s.field(gpuPackage+"GpuFormat","D32_FLOAT",null);
            target=s.construct("com.mojang.blaze3d.pipeline.TextureTarget","mcicon",size,size,colorFormat,depthFormat);
        } else target=s.construct("com.mojang.blaze3d.pipeline.TextureTarget","mcicon",size,size,true,colorFormat);
        Object gpu=s.call("com.mojang.blaze3d.systems.RenderSystem","getDevice",null);
        Object buffer=null,mapping=null,fence=null;
        try {
            s.setField("net.minecraft.client.renderer.GameRenderer","mainRenderTarget",gameRenderer,target);
            s.setField(windowClass,"width",window,size);s.setField(windowClass,"height",window,size);s.setField(windowClass,"guiScale",window,size/16);
            GuiAtlasReset.reset(s,guiRenderer);
            s.call("net.minecraft.client.renderer.state.gui.GuiRenderState","reset",state);
            Object graphics=s.construct("net.minecraft.client.gui.GuiGraphicsExtractor",minecraft,state,0,0);
            s.call("net.minecraft.client.gui.GuiGraphicsExtractor","item",graphics,stack,0,0);
            Object encoder=s.call(gpuPackage+(pearl?"device.GpuDevice":"systems.GpuDevice"),"createCommandEncoder",gpu);
            Object texture=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getColorTexture",target);
            Object depth=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getDepthTexture",target);
            Object transparent=s.construct("org.joml.Vector4f",CanonicalContext.backgroundColor(),CanonicalContext.backgroundColor(),CanonicalContext.backgroundColor(),CanonicalContext.backgroundAlpha());
            s.call(gpuPackage+(pearl?"commands.CommandEncoder":"systems.CommandEncoder"),"clearColorAndDepthTextures",encoder,texture,transparent,depth,0d);
            s.call("net.minecraft.client.gui.render.GuiRenderer","render",guiRenderer);
            int usage=((Integer)s.field(gpuPackage+"buffers.GpuBuffer","USAGE_MAP_READ",null))|((Integer)s.field(gpuPackage+"buffers.GpuBuffer","USAGE_COPY_DST",null));
            buffer=s.call(gpuPackage+(pearl?"device.GpuDevice":"systems.GpuDevice"),"createBuffer",gpu,new Supplier<String>(){public String get(){return "mcicon readback";}},usage,(long)size*size*4);
            s.call(gpuPackage+(pearl?"commands.CommandEncoder":"systems.CommandEncoder"),"copyTextureToBuffer",encoder,texture,buffer,0L,new Runnable(){public void run(){}},0);
            fence=s.call(gpuPackage+(pearl?"commands.CommandEncoder":"systems.CommandEncoder"),"createFence",encoder);
            s.call(gpuPackage+(pearl?"commands.CommandEncoder":"systems.CommandEncoder"),"submit",encoder);
            if(!Boolean.TRUE.equals(s.call(gpuPackage+(pearl?"commands.GpuFence":"buffers.GpuFence"),"awaitCompletion",fence,10000000000L)))throw new IllegalStateException("GPU readback timeout");
            mapping=s.call(gpuPackage+"buffers.GpuBuffer","map",buffer,true,false);
            ByteBuffer pixels=(ByteBuffer)s.call(gpuPackage+"buffers.GpuBufferSlice$MappedView","data",mapping);
            Path output=Paths.get(request.get("path").getAsString());
            int visible=PngOutput.write(pixels,size,output);
            JsonObject result=new JsonObject();result.addProperty("path",output.toString());result.addProperty("visiblePixels",visible);return result;
        } finally {
            if(mapping!=null)s.call(gpuPackage+"buffers.GpuBufferSlice$MappedView","close",mapping);
            if(fence!=null)s.call(gpuPackage+(pearl?"commands.GpuFence":"buffers.GpuFence"),"close",fence);
            if(buffer!=null)s.call(gpuPackage+"buffers.GpuBuffer","close",buffer);
            s.call("net.minecraft.client.gui.render.GuiRenderer","endFrame",guiRenderer);
            Object uniforms=s.call("com.mojang.blaze3d.systems.RenderSystem","getDynamicUniforms",null);
            s.call(pearl?"net.minecraft.client.renderer.DynamicGpuData":"net.minecraft.client.renderer.DynamicUniforms","reset",uniforms);
            s.setField("net.minecraft.client.renderer.GameRenderer","mainRenderTarget",gameRenderer,original);
            s.setField(windowClass,"width",window,width);s.setField(windowClass,"height",window,height);s.setField(windowClass,"guiScale",window,scale);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","destroyBuffers",target);
            restoreTargetState(targetState);
        }
    }
}
