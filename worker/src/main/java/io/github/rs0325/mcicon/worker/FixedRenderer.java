package io.github.rs0325.mcicon.worker;

/** Official-mapped fixed-function GUI generation beginning at 1.14.4. */
public final class FixedRenderer extends ClassicRenderer {
    private final String gl;
    public FixedRenderer(Symbols symbols,Object minecraft) throws Exception {
        super(symbols,minecraft);
        if(s.hasMethod("net.minecraft.client.renderer.blockentity.BlockEntityRenderer","bindTexture",1)) {
            // 1.14.4ではワールド開始時に設定される参照をGUIだけの起動でも補う。
            String dispatcherClass="net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher";
            Object dispatcher=s.field(dispatcherClass,"instance",null);
            s.setField(dispatcherClass,"textureManager",dispatcher,s.call("net.minecraft.client.Minecraft","getTextureManager",minecraft));
        }
        if(s.hasMethod("net.minecraft.client.renderer.entity.ItemRenderer","render",8)) {
            Object renderer=s.field("net.minecraft.client.Minecraft","gameRenderer",minecraft);
            initializeLightPixels(s.field("net.minecraft.client.renderer.GameRenderer","lightTexture",renderer));
        }
        gl=s.hasMethod("com.mojang.blaze3d.platform.GlStateManager","matrixMode",1)?"com.mojang.blaze3d.platform.GlStateManager":"com.mojang.blaze3d.systems.RenderSystem";
    }
    protected Object createTarget(int size,boolean ignored) throws Exception {
        return s.construct("com.mojang.blaze3d.pipeline.RenderTarget",size,size,true,false);
    }
    protected void setupLighting() throws Exception {s.call("com.mojang.blaze3d.platform.Lighting",s.hasMethod("com.mojang.blaze3d.platform.Lighting","turnOnGui",0)?"turnOnGui":"setupFor3DItems",null);}
    protected Object setupView(boolean ignored) throws Exception {
        s.call(gl,"matrixMode",null,5889);s.call(gl,"pushMatrix",null);s.call(gl,"loadIdentity",null);
        s.call(gl,"ortho",null,0d,16d,16d,0d,1000d,3000d);
        s.call(gl,"matrixMode",null,5888);s.call(gl,"pushMatrix",null);s.call(gl,"loadIdentity",null);
        s.call(gl,"translated",null,0d,0d,-2000d);
        return Boolean.TRUE;
    }
    protected void restoreView(Object ignored) throws Exception {
        s.call(gl,"matrixMode",null,5888);s.call(gl,"popMatrix",null);
        s.call(gl,"matrixMode",null,5889);s.call(gl,"popMatrix",null);s.call(gl,"matrixMode",null,5888);
    }
}
