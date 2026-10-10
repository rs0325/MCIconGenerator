package io.github.rs0325.mcicon.worker;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Synthetic GPU acceptance: no Minecraft assets or game implementation are used. */
public final class NativeAlphaProbe {
    private static final int[] ALPHA={0,1,16,64,128,192,254,255};
    private static final int[] COLOR={200,50,150};
    private static BufferedImage draw(int background) throws Exception {
        GL11.glClearColor(background/255f,background/255f,background/255f,1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
        for(int x=0;x<8;x++) {
            GL11.glColor4f(COLOR[0]/255f,COLOR[1]/255f,COLOR[2]/255f,ALPHA[x]/255f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1+x/4f,-1);GL11.glVertex2f(-1+(x+1)/4f,-1);
            GL11.glVertex2f(-1+(x+1)/4f,1);GL11.glVertex2f(-1+x/4f,1);
            GL11.glEnd();
        }
        ByteBuffer pixels=ByteBuffer.allocateDirect(8*8*4);
        GL11.glReadPixels(0,0,8,8,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new AssertionError("GPU probe failed");
        CanonicalContext.beginProbe(background/255f);
        try {PngOutput.write(pixels,8,Path.of("unused.png"));return CanonicalContext.probeImage();}
        finally {CanonicalContext.end();}
    }
    public static void main(String[] args) throws Exception {
        if(!GLFW.glfwInit())throw new AssertionError("GLFW unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(8,8,"MCIcon synthetic alpha QA",0,0);
        if(window==0)throw new AssertionError("GL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,8,8,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            int fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO incomplete");
            GL11.glViewport(0,0,8,8);GL11.glDisable(GL11.GL_DITHER);GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            BufferedImage black=draw(0),white=draw(255),gray=draw(127);
            Path output=Path.of(args[0]);PngOutput.compose(black,white,output);
            BufferedImage recovered=ImageIO.read(output.toFile());
            int alphaError=0,colorError=0,recompositeError=0;
            for(int x=0;x<8;x++) {
                int rgba=recovered.getRGB(x,0),alpha=rgba>>>24;
                alphaError=Math.max(alphaError,Math.abs(alpha-ALPHA[x]));
                if(alpha==0&&rgba!=0)throw new AssertionError("Hidden RGB in transparent pixel");
                for(int c=0;c<3;c++) {
                    int component=(rgba>>>(16-8*c))&255;
                    if(ALPHA[x]>=64)colorError=Math.max(colorError,Math.abs(component-COLOR[c]));
                    int composed=(component*alpha+127*(255-alpha)+127)/255;
                    int nativeGray=(gray.getRGB(x,0)>>>(16-8*c))&255;
                    recompositeError=Math.max(recompositeError,Math.abs(composed-nativeGray));
                }
            }
            System.out.println("{\"renderer\":\""+GL11.glGetString(GL11.GL_RENDERER)+"\",\"format\":\"RGBA8\",\"framebufferSrgb\":false,\"alphaMaxError\":"+alphaError+",\"rgbMaxErrorForAlphaAtLeast64\":"+colorError+",\"grayRecompositeMaxError\":"+recompositeError+"}");
            if(alphaError>1||colorError>2||recompositeError>1)throw new AssertionError("Alpha reconstruction exceeds quantization tolerance");
            GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(texture);
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
