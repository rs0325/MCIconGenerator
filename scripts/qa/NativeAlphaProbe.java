package io.github.rs0325.mcicon.worker;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** ゲーム資産・shaderを使わず、RGBA8の量子化とbyte/linear合成を測る。 */
public final class NativeAlphaProbe {
    private static final int[] ALPHA={0,1,127,128,254,255};
    private static final int[][] COLORS={{0,0,0},{1,2,3},{17,83,219},{128,128,128},{254,253,252},{255,255,255}};
    private static final int[][] BACKGROUNDS={{0,0,0},{255,255,255},{127,127,127},{32,96,128},{192,32,64}};
    private static double decode(double value) {return value<=0.04045?value/12.92:Math.pow((value+0.055)/1.055,2.4);}
    private static double encode(double value) {return value<=0.0031308?value*12.92:1.055*Math.pow(value,1/2.4)-0.055;}
    private static JsonArray array(int[] values) {JsonArray a=new JsonArray();for(int v:values)a.add(v);return a;}
    private static double color(int value,boolean linear) {return linear?decode(value/255.0):value/255.0;}
    private static void renderGrid(int[] background,boolean linear) {
        GL11.glClearColor((float)color(background[0],linear),(float)color(background[1],linear),(float)color(background[2],linear),1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
        for(int y=0;y<COLORS.length;y++)for(int x=0;x<ALPHA.length;x++) {
            GL11.glColor4f((float)color(COLORS[y][0],linear),(float)color(COLORS[y][1],linear),(float)color(COLORS[y][2],linear),ALPHA[x]/255f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1+x/3f,-1+y/3f);GL11.glVertex2f(-1+(x+1)/3f,-1+y/3f);
            GL11.glVertex2f(-1+(x+1)/3f,-1+(y+1)/3f);GL11.glVertex2f(-1+x/3f,-1+(y+1)/3f);GL11.glEnd();
        }
    }
    private static BufferedImage draw(int[] background,boolean linear) {
        renderGrid(background,linear);
        ByteBuffer pixels=ByteBuffer.allocateDirect(6*6*4);GL11.glReadPixels(0,0,6,6,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new AssertionError("GPU probe failed");
        BufferedImage image=new BufferedImage(6,6,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<6;y++)for(int x=0;x<6;x++) {int offset=(y*6+x)*4;image.setRGB(x,y,0xff000000|((pixels.get(offset)&255)<<16)|((pixels.get(offset+1)&255)<<8)|(pixels.get(offset+2)&255));}
        return image;
    }
    private static JsonObject measure(boolean linear,Path output) throws Exception {
        int format=linear?GL21.GL_SRGB8_ALPHA8:GL11.GL_RGBA8;
        int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,6,6,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
        int fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
        try {
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO incomplete");
            GL11.glViewport(0,0,6,6);GL11.glDisable(GL11.GL_DITHER);
            if(linear)GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            BufferedImage[] nativeImages=new BufferedImage[BACKGROUNDS.length];
            for(int i=0;i<BACKGROUNDS.length;i++) {nativeImages[i]=draw(BACKGROUNDS[i],linear);ImageIO.write(nativeImages[i],"PNG",output.resolveSibling((linear?"linear":"byte")+"-background-"+i+".png").toFile());}
            PngOutput.compose(nativeImages[0],nativeImages[1],output);BufferedImage recovered=ImageIO.read(output.toFile());
            JsonObject result=new JsonObject();result.addProperty("mode",linear?"srgb-linear-blend":"rgba8-byte-blend");
            result.addProperty("internalFormat",GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT));
            result.addProperty("colorEncoding",GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING));
            result.addProperty("framebufferSrgb",GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB));result.addProperty("dither",GL11.glIsEnabled(GL11.GL_DITHER));
            result.addProperty("blendSrcRgb",GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));result.addProperty("blendDstRgb",GL11.glGetInteger(GL14.GL_BLEND_DST_RGB));
            JsonArray samples=new JsonArray();double oracleError=0;int alphaError=0,recompositeError=0,otherEncodingError=0,rgbError=0,rgbErrorAtLeast127=0;
            for(int y=0;y<6;y++)for(int x=0;x<6;x++) {
                int rgba=recovered.getRGB(x,y),alpha=rgba>>>24;int[] rgb={(rgba>>>16)&255,(rgba>>>8)&255,rgba&255};
                if(alpha==0&&rgba!=0)throw new AssertionError("Hidden transparent RGB");
                JsonObject cell=new JsonObject();cell.add("sourceRgb",array(COLORS[y]));cell.addProperty("sourceAlpha",ALPHA[x]);cell.add("recoveredRgb",array(rgb));cell.addProperty("recoveredAlpha",alpha);
                alphaError=Math.max(alphaError,Math.abs(alpha-ALPHA[x]));
                for(int c=0;c<3;c++)if(ALPHA[x]>0) {rgbError=Math.max(rgbError,Math.abs(rgb[c]-COLORS[y][c]));if(ALPHA[x]>=127)rgbErrorAtLeast127=Math.max(rgbErrorAtLeast127,Math.abs(rgb[c]-COLORS[y][c]));}
                JsonArray backgrounds=new JsonArray();
                for(int i=0;i<BACKGROUNDS.length;i++) {
                    JsonObject sample=new JsonObject();sample.add("background",array(BACKGROUNDS[i]));JsonArray values=new JsonArray();
                    for(int c=0;c<3;c++) {
                        int actual=(nativeImages[i].getRGB(x,y)>>>(16-c*8))&255;
                        double a=ALPHA[x]/255.0,byteIdeal=COLORS[y][c]*a+BACKGROUNDS[i][c]*(1-a);
                        double linearIdeal=encode(decode(COLORS[y][c]/255.0)*a+decode(BACKGROUNDS[i][c]/255.0)*(1-a))*255;
                        int byteRecomposite=(rgb[c]*alpha+BACKGROUNDS[i][c]*(255-alpha)+127)/255;
                        JsonObject channel=new JsonObject();channel.addProperty("native",actual);channel.addProperty("byteIdeal",byteIdeal);channel.addProperty("linearIdeal",linearIdeal);channel.addProperty("byteRecomposite",byteRecomposite);values.add(channel);
                        oracleError=Math.max(oracleError,Math.abs(actual-(linear?linearIdeal:byteIdeal)));
                        otherEncodingError=Math.max(otherEncodingError,Math.abs(actual-(int)Math.round(linear?byteIdeal:linearIdeal)));
                        recompositeError=Math.max(recompositeError,Math.abs(actual-byteRecomposite));
                    }
                    sample.add("channels",values);backgrounds.add(sample);
                }
                cell.add("backgrounds",backgrounds);samples.add(cell);
            }
            result.add("samples",samples);result.addProperty("maxMatchingOracleError",oracleError);result.addProperty("maxOtherEncodingError",otherEncodingError);
            result.addProperty("maxRecoveredAlphaError",alphaError);result.addProperty("maxByteRecompositeError",recompositeError);
            result.addProperty("maxRecoveredRgbError",rgbError);result.addProperty("maxRecoveredRgbErrorForAlphaAtLeast127",rgbErrorAtLeast127);
            result.addProperty("productionRecoveryApplicable",!linear);
            result.addProperty("nativeOraclePassed",oracleError<=1.01);
            if(linear)result.add("byteRecoveryPassed",JsonNull.INSTANCE);else result.addProperty("byteRecoveryPassed",alphaError<=1&&recompositeError<=2);
            result.addProperty("misappliedByteRecoveryMatches",alphaError<=1&&recompositeError<=2);
            result.addProperty("passed",oracleError<=1.01&&(linear||alphaError<=1&&recompositeError<=2));
            return result;
        } finally {GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(texture);}
    }
    private static JsonObject floatingControl() {
        int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,6,6,0,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);
        int fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
        try {
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Float FBO incomplete");
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);GL11.glViewport(0,0,6,6);double maxError=0;
            JsonArray samples=new JsonArray();
            for(int[] background:BACKGROUNDS) {
                renderGrid(background,false);java.nio.FloatBuffer pixels=ByteBuffer.allocateDirect(6*6*4*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                GL11.glReadPixels(0,0,6,6,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);
                for(int y=0;y<6;y++)for(int x=0;x<6;x++)for(int c=0;c<3;c++) {
                    double actual=pixels.get((y*6+x)*4+c)*255,expected=COLORS[y][c]*ALPHA[x]/255.0+background[c]*(1-ALPHA[x]/255.0);
                    maxError=Math.max(maxError,Math.abs(actual-expected));
                    JsonObject sample=new JsonObject();sample.add("sourceRgb",array(COLORS[y]));sample.addProperty("sourceAlpha",ALPHA[x]);sample.add("background",array(background));sample.addProperty("channel",c);sample.addProperty("nativeByteUnits",actual);sample.addProperty("byteIdeal",expected);samples.add(sample);
                }
            }
            JsonObject result=new JsonObject();result.addProperty("internalFormat",GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT));result.addProperty("maxByteUnitOracleError",maxError);result.add("samples",samples);
            result.addProperty("glError",GL11.glGetError());result.addProperty("passed",maxError<0.001&&result.get("glError").getAsInt()==0);return result;
        } finally {GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(texture);}
    }
    public static void main(String[] args) throws Exception {
        if(!GLFW.glfwInit())throw new AssertionError("GLFW unavailable");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(6,6,"MCIcon color precision QA",0,0);if(window==0)throw new AssertionError("GL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();Path directory=Path.of(args[0]).getParent();
            JsonObject report=new JsonObject();report.addProperty("renderer",GL11.glGetString(GL11.GL_RENDERER));report.add("sourceAlpha",array(ALPHA));
            JsonArray modes=new JsonArray();JsonObject byteMode=measure(false,directory.resolve("byte-recovered.png")),linearMode=measure(true,directory.resolve("linear-recovered.png"));modes.add(byteMode);modes.add(linearMode);report.add("modes",modes);
            JsonObject floating=floatingControl();report.add("floatingControl",floating);
            report.addProperty("passed",byteMode.get("passed").getAsBoolean()&&linearMode.get("passed").getAsBoolean()&&floating.get("passed").getAsBoolean());System.out.println(new Gson().toJson(report));
            if(!report.get("passed").getAsBoolean())throw new AssertionError("Native color oracle mismatch");
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
