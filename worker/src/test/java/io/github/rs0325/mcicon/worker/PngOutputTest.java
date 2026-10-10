package io.github.rs0325.mcicon.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PngOutputTest {
    @TempDir Path directory;
    @Test void flipsRowsAndConvertsPremultipliedSamplesAndClearsHiddenRgb() throws Exception {
        ByteBuffer pixels=ByteBuffer.wrap(new byte[]{(byte)255,0,0,(byte)255,1,2,3,0,26,51,77,(byte)128,0,(byte)255,0,(byte)255});
        Path output=directory.resolve("icon.png");assertEquals(3,PngOutput.write(pixels,2,output));
        BufferedImage image=ImageIO.read(output.toFile());
        assertEquals(0x80346699,image.getRGB(0,0));assertEquals(0xff00ff00,image.getRGB(1,0));
        assertEquals(0xffff0000,image.getRGB(0,1));assertEquals(0,image.getRGB(1,1));
    }
    @Test void rejectsEmptyReadbackAndWrongLength() {
        assertThrows(java.io.IOException.class,()->PngOutput.write(ByteBuffer.allocate(16),2,directory.resolve("empty.png")));
        assertThrows(java.io.IOException.class,()->PngOutput.write(ByteBuffer.allocate(15),2,directory.resolve("short.png")));
    }
    @Test void reconstructsNativeCompositingWithoutTrustingFramebufferAlpha() throws Exception {
        BufferedImage dark=new BufferedImage(2,1,BufferedImage.TYPE_INT_ARGB),light=new BufferedImage(2,1,BufferedImage.TYPE_INT_ARGB);
        dark.setRGB(0,0,0x40204060);light.setRGB(0,0,0x99a0c0e0);
        dark.setRGB(1,0,0x11000000);light.setRGB(1,0,0x77ffffff);
        Path black=directory.resolve("black.png"),white=directory.resolve("white.png"),output=directory.resolve("alpha.png");
        ImageIO.write(dark,"PNG",black.toFile());ImageIO.write(light,"PNG",white.toFile());
        assertEquals(1,PngOutput.compose(black,white,output));
        BufferedImage recovered=ImageIO.read(output.toFile());
        assertEquals(0x7f4081c1,recovered.getRGB(0,0));assertEquals(0,recovered.getRGB(1,0));
    }
}
