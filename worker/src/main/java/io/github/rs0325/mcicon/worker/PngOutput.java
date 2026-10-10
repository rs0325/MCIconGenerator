package io.github.rs0325.mcicon.worker;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Converts bottom-up premultiplied RGBA8 readback to straight-alpha PNG. */
final class PngOutput {
    private PngOutput() {}
    static int write(ByteBuffer pixels,int size,Path output) throws IOException {
        if(pixels.remaining()!=size*size*4)throw new IOException("Unexpected GPU readback length");
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);int visible=0;
        int start=pixels.position();
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            int offset=start+((size-1-y)*size+x)*4;
            int r=pixels.get(offset)&255,g=pixels.get(offset+1)&255,b=pixels.get(offset+2)&255,a=pixels.get(offset+3)&255;
            if(CanonicalContext.opaqueProbe())a=255;
            if(a>0)visible++;
            if(a>0&&a<255){r=unpremultiply(r,a);g=unpremultiply(g,a);b=unpremultiply(b,a);}
            if(a==0)r=g=b=0;
            image.setRGB(x,y,(a<<24)|(r<<16)|(g<<8)|b);
        }
        if(visible==0)throw new IOException("Empty inventory icon");
        if(CanonicalContext.opaqueProbe()){CanonicalContext.probeImage(image);return visible;}
        Files.createDirectories(output.getParent());
        if(!ImageIO.write(image,"PNG",output.toFile()))throw new IOException("PNG encoder unavailable");
        return visible;
    }
    static int compose(Path black,Path white,Path output) throws IOException {
        BufferedImage dark=ImageIO.read(black.toFile()),light=ImageIO.read(white.toFile());
        return compose(dark,light,output);
    }
    static int compose(BufferedImage dark,BufferedImage light,Path output) throws IOException {
        if(dark==null||light==null||dark.getWidth()!=light.getWidth()||dark.getHeight()!=light.getHeight())throw new IOException("Invalid alpha probe pair");
        BufferedImage result=new BufferedImage(dark.getWidth(),dark.getHeight(),BufferedImage.TYPE_INT_ARGB);int visible=0;
        for(int y=0;y<result.getHeight();y++)for(int x=0;x<result.getWidth();x++) {
            int b=dark.getRGB(x,y),w=light.getRGB(x,y);
            int br=(b>>>16)&255,bg=(b>>>8)&255,bb=b&255;
            int dr=Math.max(0,((w>>>16)&255)-br),dg=Math.max(0,((w>>>8)&255)-bg),db=Math.max(0,(w&255)-bb);
            int median=dr+dg+db-Math.min(dr,Math.min(dg,db))-Math.max(dr,Math.max(dg,db));
            int alpha=255-median;
            int r=0,g=0,blue=0;
            if(alpha>0){visible++;r=unpremultiply(br,alpha);g=unpremultiply(bg,alpha);blue=unpremultiply(bb,alpha);}
            result.setRGB(x,y,(alpha<<24)|(r<<16)|(g<<8)|blue);
        }
        if(visible==0)throw new IOException("Empty inventory icon");
        Files.createDirectories(output.getParent());
        if(!ImageIO.write(result,"PNG",output.toFile()))throw new IOException("PNG encoder unavailable");
        return visible;
    }
    private static int unpremultiply(int component,int alpha) {return Math.min(255,(component*255+alpha/2)/alpha);}
}
