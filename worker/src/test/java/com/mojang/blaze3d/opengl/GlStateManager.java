package com.mojang.blaze3d.opengl;

/** 26.1名前付きFBO APIの試験用クラス。 */
public final class GlStateManager {
    public static int draw,read;

    public static void reset(){draw=0;read=0;}
    public static void _glBindFramebuffer(int target,int framebuffer){
        boolean changed=false;
        if(target!=36008){changed|=draw!=framebuffer;draw=framebuffer;}
        if(target!=36009){changed|=read!=framebuffer;read=framebuffer;}
        if(changed)io.github.rs0325.mcicon.worker.RendererCleanupTest.Gl.glBindFramebuffer(target,framebuffer);
    }
    public static void _viewport(int x,int y,int width,int height){io.github.rs0325.mcicon.worker.RendererCleanupTest.Gl.glViewport(x,y,width,height);}
}
