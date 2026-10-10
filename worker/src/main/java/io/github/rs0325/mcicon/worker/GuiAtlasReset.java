package io.github.rs0325.mcicon.worker;

import java.util.Map;

/** Keeps every icon at slot zero without recreating native atlas textures. */
final class GuiAtlasReset {
    private GuiAtlasReset() {}
    static void reset(Symbols s,Object renderer) throws Exception {
        String gui="net.minecraft.client.gui.render.GuiRenderer";
        if(RenderAgent.config.get("version").getAsString().startsWith("26.")||s.hasField(gui,"itemAtlas")) {
            Object atlas=s.field(gui,"itemAtlas",renderer);
            if(atlas!=null) {
                String type="net.minecraft.client.gui.render.GuiItemAtlas";
                int extent=((Integer)s.field(type,"textureSize",atlas))/((Integer)s.field(type,"slotTextureSize",atlas));
                s.setField(type,"allocator",atlas,s.construct("net.minecraft.client.gui.render.DynamicAtlasAllocator",extent,extent));
                String profile=RenderAgent.config.get("profile").getAsString();
                boolean modern=profile.equals("gui-26.2")||profile.equals("gui-26.3");
                clear(s,s.field(type,"texture",atlas),s.field(type,"depthTexture",atlas),modern?s.field(gui,"CLEAR_COLOR",null):Integer.valueOf(0),modern?0d:1d,profile.equals("gui-26.3"));
            }
        } else {
            ((Map<?,?>)s.field(gui,"atlasPositions",renderer)).clear();
            s.setField(gui,"itemAtlasX",renderer,0);
            s.setField(gui,"itemAtlasY",renderer,0);
            Object texture=s.field(gui,"itemsAtlas",renderer);
            if(texture!=null)clear(s,texture,s.field(gui,"itemsAtlasDepth",renderer),Integer.valueOf(0),1d,false);
        }
    }
    private static void clear(Symbols s,Object color,Object depth,Object background,double depthValue,boolean pearl) throws Exception {
        Object gpu=s.call("com.mojang.blaze3d.systems.RenderSystem","getDevice",null);
        String device=pearl?"com.mojang.renderpearl.api.device.GpuDevice":"com.mojang.blaze3d.systems.GpuDevice";
        String commands=pearl?"com.mojang.renderpearl.api.commands.CommandEncoder":"com.mojang.blaze3d.systems.CommandEncoder";
        Object encoder=s.call(device,"createCommandEncoder",gpu);
        s.call(commands,"clearColorAndDepthTextures",encoder,color,background,depth,depthValue);
    }
}
