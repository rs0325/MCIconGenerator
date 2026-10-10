package io.github.rs0325.mcicon.worker;

/** Direct vanilla ItemRenderer GUI path before GuiGraphics was introduced. */
public class ClassicRenderer extends ModernRenderer {
    public ClassicRenderer(Symbols symbols,Object minecraft) throws Exception {super(symbols,minecraft);}
    protected void drawItem(Object stack) throws Exception {
        Object renderer=s.call("net.minecraft.client.Minecraft","getItemRenderer",minecraft);
        if(s.hasMethod("net.minecraft.client.renderer.entity.ItemRenderer","renderGuiItem","com.mojang.blaze3d.vertex.PoseStack","net.minecraft.world.item.ItemStack","int","int")) {
            Object pose=s.construct("com.mojang.blaze3d.vertex.PoseStack");
            s.call("net.minecraft.client.renderer.entity.ItemRenderer","renderGuiItem",renderer,pose,stack,0,0);
        } else s.call("net.minecraft.client.renderer.entity.ItemRenderer","renderGuiItem",renderer,stack,0,0);
    }
}
