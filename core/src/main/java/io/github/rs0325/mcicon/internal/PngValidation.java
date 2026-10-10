package io.github.rs0325.mcicon.internal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Validates bounded PNG chunks and decoded pixels without changing source bytes. */
public final class PngValidation {
    public static void validate(byte[] bytes) throws IOException {
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        if(bytes.length<33||!Arrays.equals(Arrays.copyOf(bytes,8),signature))throw new IOException("Invalid PNG signature");
        ByteBuffer data=ByteBuffer.wrap(bytes);data.position(8);
        boolean header=false,pixels=false,end=false;
        while(data.remaining()>=12) {
            int length=data.getInt();
            if(length<0||length>data.remaining()-8)throw new IOException("Truncated PNG chunk");
            int start=data.position();byte[] type=new byte[4];data.get(type);
            String name=new String(type,StandardCharsets.US_ASCII);
            if(!header&&!name.equals("IHDR"))throw new IOException("PNG header must be first");
            if(name.equals("IHDR")) {
                if(header||length!=13)throw new IOException("Invalid PNG header");
                int width=data.getInt(start+4),height=data.getInt(start+8);
                if(width<=0||height<=0||(long)width*height>16L*1024*1024)throw new IOException("PNG pixel limit exceeded");
                header=true;
            }
            if(name.equals("IDAT"))pixels=true;
            CRC32 crc=new CRC32();crc.update(bytes,start,length+4);
            data.position(start+4+length);
            if(Integer.toUnsignedLong(data.getInt())!=crc.getValue())throw new IOException("PNG chunk CRC mismatch");
            if(name.equals("IEND")) {
                if(length!=0||data.hasRemaining())throw new IOException("Invalid PNG end");
                end=true;break;
            }
        }
        if(!header||!pixels||!end||ImageIO.read(new ByteArrayInputStream(bytes))==null)throw new IOException("Invalid PNG data");
    }
    private PngValidation() {}
}
