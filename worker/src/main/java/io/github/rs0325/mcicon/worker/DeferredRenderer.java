package io.github.rs0325.mcicon.worker;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;

/** Deferred GuiRenderer with Blaze3D GPU readback before RenderPearl. */
public final class DeferredRenderer extends GpuImmediateRenderer {
    private final Object gameRenderer,guiRenderer,state,window;
    private final String stateClass,windowClass,graphicsClass;
    private final boolean extracted;
    private final Map<String,Object> originalWindow=new LinkedHashMap<String,Object>();
    private int renderSize;
    public DeferredRenderer(Symbols symbols,Object minecraft) throws Exception {
        super(symbols,minecraft);
        extracted=RenderAgent.config.get("version").getAsString().startsWith("26.");
        gameRenderer=s.field("net.minecraft.client.Minecraft","gameRenderer",minecraft);
        guiRenderer=s.field("net.minecraft.client.renderer.GameRenderer","guiRenderer",gameRenderer);
        if(extracted) {
            Object gameState=s.call("net.minecraft.client.renderer.GameRenderer","getGameRenderState",gameRenderer);
            state=s.field("net.minecraft.client.renderer.state.GameRenderState","guiRenderState",gameState);
            window=s.field("net.minecraft.client.renderer.state.GameRenderState","windowRenderState",gameState);
            stateClass="net.minecraft.client.renderer.state.gui.GuiRenderState";
            windowClass="net.minecraft.client.renderer.state.WindowRenderState";
            graphicsClass="net.minecraft.client.gui.GuiGraphicsExtractor";
        } else {
            state=s.field("net.minecraft.client.renderer.GameRenderer","guiRenderState",gameRenderer);
            window=s.call("net.minecraft.client.Minecraft","getWindow",minecraft);
            stateClass="net.minecraft.client.gui.render.state.GuiRenderState";
            windowClass="com.mojang.blaze3d.platform.Window";
            graphicsClass="net.minecraft.client.gui.GuiGraphics";
        }
    }
    protected Object createTarget(int size,boolean ignored) throws Exception {renderSize=size;return super.createTarget(size,ignored);}
    protected void setupLighting() {}
    protected Object setupView(boolean ignored) throws Exception {
        originalWindow.clear();
        String[] fields=extracted?new String[]{"width","height","guiScale"}:new String[]{"width","height","framebufferWidth","framebufferHeight","guiScaledWidth","guiScaledHeight","guiScale"};
        for(String field:fields) {
            Object original=s.field(windowClass,field,window);originalWindow.put(field,original);
            int value=field.equals("guiScale")?renderSize/16:field.startsWith("guiScaled")?16:renderSize;
            Object replacement;
            if(original instanceof Double)replacement=(double)value;else replacement=value;
            s.setField(windowClass,field,window,replacement);
        }
        s.call(stateClass,"reset",state);
        return window;
    }
    protected void restoreView(Object ignored) throws Exception {
        for(Map.Entry<String,Object> entry:originalWindow.entrySet())s.setField(windowClass,entry.getKey(),window,entry.getValue());
    }
    protected void drawItem(Object stack) throws Exception {
        if(s.hasMethod("net.minecraft.client.gui.render.GuiRenderer","incrementFrameNumber",0))s.call("net.minecraft.client.gui.render.GuiRenderer","incrementFrameNumber",guiRenderer);
        Object graphics;
        try {graphics=s.construct(graphicsClass,minecraft,state,0,0);}
        catch(NoSuchMethodException olderSignature) {graphics=s.construct(graphicsClass,minecraft,state);}
        s.call(graphicsClass,extracted?"item":"renderItem",graphics,stack,0,0);
        Object fog=s.field("net.minecraft.client.renderer.GameRenderer","fogRenderer",gameRenderer);
        String fogClass="net.minecraft.client.renderer.fog.FogRenderer";
        Object none=s.field(fogClass+"$FogMode","NONE",null);
        Object fogBuffer=s.call(fogClass,"getBuffer",fog,none);
        s.call("net.minecraft.client.gui.render.GuiRenderer","render",guiRenderer,fogBuffer);
    }
    protected ByteBuffer readPixels(Object target,int size) throws Exception {
        Object gpu=s.call("com.mojang.blaze3d.systems.RenderSystem","getDevice",null);
        Object encoder=s.call("com.mojang.blaze3d.systems.GpuDevice","createCommandEncoder",gpu);
        int usage=((Integer)s.field("com.mojang.blaze3d.buffers.GpuBuffer","USAGE_MAP_READ",null))|((Integer)s.field("com.mojang.blaze3d.buffers.GpuBuffer","USAGE_COPY_DST",null));
        Object bufferSize;
        if(s.hasMethod("com.mojang.blaze3d.systems.GpuDevice","createBuffer","java.util.function.Supplier","int","int"))bufferSize=size*size*4;
        else bufferSize=(long)size*size*4;
        Object buffer=s.call("com.mojang.blaze3d.systems.GpuDevice","createBuffer",gpu,new Supplier<String>(){public String get(){return "mcicon readback";}},usage,bufferSize);
        Object view=null,fence=null;
        try {
            Object texture=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getColorTexture",target);
            Object offset;
            if(s.hasMethod("com.mojang.blaze3d.systems.CommandEncoder","copyTextureToBuffer","com.mojang.blaze3d.textures.GpuTexture","com.mojang.blaze3d.buffers.GpuBuffer","int","java.lang.Runnable","int"))offset=0;
            else offset=0L;
            s.call("com.mojang.blaze3d.systems.CommandEncoder","copyTextureToBuffer",encoder,texture,buffer,offset,new Runnable(){public void run(){}},0);
            fence=s.call("com.mojang.blaze3d.systems.CommandEncoder","createFence",encoder);
            if(!Boolean.TRUE.equals(s.call("com.mojang.blaze3d.buffers.GpuFence","awaitCompletion",fence,10000000000L)))throw new IllegalStateException("GPU readback timeout");
            view=s.call("com.mojang.blaze3d.systems.CommandEncoder","mapBuffer",encoder,buffer,true,false);
            ByteBuffer source=(ByteBuffer)s.call("com.mojang.blaze3d.buffers.GpuBuffer$MappedView","data",view);
            ByteBuffer result=ByteBuffer.allocateDirect(size*size*4);result.put(source);result.flip();return result;
        } finally {
            if(view!=null)s.call("com.mojang.blaze3d.buffers.GpuBuffer$MappedView","close",view);
            if(fence!=null)s.call("com.mojang.blaze3d.buffers.GpuFence","close",fence);
            s.call("com.mojang.blaze3d.buffers.GpuBuffer","close",buffer);
        }
    }
}
