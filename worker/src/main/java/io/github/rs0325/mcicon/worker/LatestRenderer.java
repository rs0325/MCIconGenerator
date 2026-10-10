package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/** Deferred GUI extraction and RenderPearl readback in Java Edition 26.3. */
public final class LatestRenderer extends ModernRenderer {
    public LatestRenderer(Symbols symbols,Object minecraft) throws Exception {
        super(symbols,minecraft);
        Object lookup=s.call("net.minecraft.data.registries.VanillaRegistries","createWorldLookup",null);
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
        Object colorFormat=s.field("com.mojang.renderpearl.api.GpuFormat","RGBA8_UNORM",null);
        Object depthFormat=s.field("com.mojang.renderpearl.api.GpuFormat","D32_FLOAT",null);
        Object target=s.construct("com.mojang.blaze3d.pipeline.TextureTarget","mcicon",size,size,colorFormat,depthFormat);
        Object gpu=s.call("com.mojang.blaze3d.systems.RenderSystem","getDevice",null);
        Object buffer=null,mapping=null,fence=null;
        try {
            s.setField("net.minecraft.client.renderer.GameRenderer","mainRenderTarget",gameRenderer,target);
            s.setField(windowClass,"width",window,size);s.setField(windowClass,"height",window,size);s.setField(windowClass,"guiScale",window,size/16);
            s.call("net.minecraft.client.renderer.state.gui.GuiRenderState","reset",state);
            Object graphics=s.construct("net.minecraft.client.gui.GuiGraphicsExtractor",minecraft,state,0,0);
            s.call("net.minecraft.client.gui.GuiGraphicsExtractor","item",graphics,stack,0,0);
            Object encoder=s.call("com.mojang.renderpearl.api.device.GpuDevice","createCommandEncoder",gpu);
            Object texture=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getColorTexture",target);
            Object depth=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getDepthTexture",target);
            Object transparent=s.construct("org.joml.Vector4f",0f,0f,0f,0f);
            s.call("com.mojang.renderpearl.api.commands.CommandEncoder","clearColorAndDepthTextures",encoder,texture,transparent,depth,0d);
            s.call("net.minecraft.client.gui.render.GuiRenderer","render",guiRenderer);
            int usage=((Integer)s.field("com.mojang.renderpearl.api.buffers.GpuBuffer","USAGE_MAP_READ",null))|((Integer)s.field("com.mojang.renderpearl.api.buffers.GpuBuffer","USAGE_COPY_DST",null));
            buffer=s.call("com.mojang.renderpearl.api.device.GpuDevice","createBuffer",gpu,new Supplier<String>(){public String get(){return "mcicon readback";}},usage,(long)size*size*4);
            s.call("com.mojang.renderpearl.api.commands.CommandEncoder","copyTextureToBuffer",encoder,texture,buffer,0L,new Runnable(){public void run(){}},0);
            fence=s.call("com.mojang.renderpearl.api.commands.CommandEncoder","createFence",encoder);
            s.call("com.mojang.renderpearl.api.commands.CommandEncoder","submit",encoder);
            if(!Boolean.TRUE.equals(s.call("com.mojang.renderpearl.api.commands.GpuFence","awaitCompletion",fence,10000000000L)))throw new IllegalStateException("GPU readback timeout");
            mapping=s.call("com.mojang.renderpearl.api.buffers.GpuBuffer","map",buffer,true,false);
            ByteBuffer pixels=(ByteBuffer)s.call("com.mojang.renderpearl.api.buffers.GpuBufferSlice$MappedView","data",mapping);
            BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);int visible=0;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                int offset=((size-1-y)*size+x)*4;
                int r=pixels.get(offset)&255,g=pixels.get(offset+1)&255,b=pixels.get(offset+2)&255,a=pixels.get(offset+3)&255;
                if(a>0)visible++;
                if(a>0&&a<255){r=Math.min(255,(r*255+a/2)/a);g=Math.min(255,(g*255+a/2)/a);b=Math.min(255,(b*255+a/2)/a);}
                if(a==0)r=g=b=0;image.setRGB(x,y,(a<<24)|(r<<16)|(g<<8)|b);
            }
            if(visible==0)throw new IllegalStateException("Empty icon: "+id);
            Path output=Paths.get(request.get("path").getAsString());Files.createDirectories(output.getParent());ImageIO.write(image,"PNG",output.toFile());
            JsonObject result=new JsonObject();result.addProperty("path",output.toString());result.addProperty("visiblePixels",visible);return result;
        } finally {
            if(mapping!=null)s.call("com.mojang.renderpearl.api.buffers.GpuBufferSlice$MappedView","close",mapping);
            if(fence!=null)s.call("com.mojang.renderpearl.api.commands.GpuFence","close",fence);
            if(buffer!=null)s.call("com.mojang.renderpearl.api.buffers.GpuBuffer","close",buffer);
            s.setField("net.minecraft.client.renderer.GameRenderer","mainRenderTarget",gameRenderer,original);
            s.setField(windowClass,"width",window,width);s.setField(windowClass,"height",window,height);s.setField(windowClass,"guiScale",window,scale);
            s.call("com.mojang.blaze3d.pipeline.RenderTarget","destroyBuffers",target);
        }
    }
}
