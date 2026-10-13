package io.github.rs0325.mcicon.worker;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** Immediate GUI with the original Blaze3D GPU API introduced in 1.21.5. */
public class GpuImmediateRenderer extends ModernRenderer {
    public GpuImmediateRenderer(Symbols symbols,Object minecraft) throws Exception {
        super(symbols,minecraft);
        if(RenderAgent.config.get("profile").getAsString().equals("gui-1.21.5")) {
            Object renderer=s.field("net.minecraft.client.Minecraft","gameRenderer",minecraft);
            Object light=s.call("net.minecraft.client.renderer.GameRenderer","lightTexture",renderer);
            Object texture=s.call("net.minecraft.client.renderer.LightTexture","getTarget",light);
            Object gpu=s.call("com.mojang.blaze3d.systems.RenderSystem","getDevice",null);
            Object encoder=s.call("com.mojang.blaze3d.systems.GpuDevice","createCommandEncoder",gpu);
            s.call("com.mojang.blaze3d.systems.CommandEncoder","clearColorTexture",encoder,texture,0xfffcfcfc);
        }
    }
    protected Object createTarget(int size,boolean ignored) throws Exception {
        return s.construct("com.mojang.blaze3d.pipeline.TextureTarget","mcicon",size,size,true);
    }
    protected void clearTarget(Object target,boolean ignored) throws Exception {
        Object gpu=s.call("com.mojang.blaze3d.systems.RenderSystem","getDevice",null);
        Object encoder=s.call("com.mojang.blaze3d.systems.GpuDevice","createCommandEncoder",gpu);
        Object color=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getColorTexture",target);
        Object depth=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getDepthTexture",target);
        s.call("com.mojang.blaze3d.systems.CommandEncoder","clearColorAndDepthTextures",encoder,color,CanonicalContext.opaqueProbe()?(CanonicalContext.backgroundColor()==0f?0xff000000:0xffffffff):0,depth,1d);
    }
    protected void bindTarget(Object target) {}
    protected ByteBuffer readPixels(Object target,int size) throws Exception {
        Object gpu=s.call("com.mojang.blaze3d.systems.RenderSystem","getDevice",null);
        Object encoder=s.call("com.mojang.blaze3d.systems.GpuDevice","createCommandEncoder",gpu);
        Object type=s.field("com.mojang.blaze3d.buffers.BufferType","PIXEL_PACK",null);
        Object usage=s.field("com.mojang.blaze3d.buffers.BufferUsage","STREAM_READ",null);
        Object buffer=s.call("com.mojang.blaze3d.systems.GpuDevice","createBuffer",gpu,new Supplier<String>(){public String get(){return "mcicon readback";}},type,usage,size*size*4);
        Object view=null;
        try {
            Object texture=s.call("com.mojang.blaze3d.pipeline.RenderTarget","getColorTexture",target);
            s.call("com.mojang.blaze3d.systems.CommandEncoder","copyTextureToBuffer",encoder,texture,buffer,0,new Runnable(){public void run(){}},0);
            view=s.call("com.mojang.blaze3d.systems.CommandEncoder","readBuffer",encoder,buffer);
            ByteBuffer source=(ByteBuffer)s.call("com.mojang.blaze3d.buffers.GpuBuffer$ReadView","data",view);
            ByteBuffer result=ByteBuffer.allocateDirect(size*size*4);result.put(source);result.flip();return result;
        } finally {
            if(view!=null)s.call("com.mojang.blaze3d.buffers.GpuBuffer$ReadView","close",view);
            s.call("com.mojang.blaze3d.buffers.GpuBuffer","close",buffer);
        }
    }
}
