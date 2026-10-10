import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Synthetic SIGKILL actor for the production atomic writer and exporter cleanup. */
public final class AtomicCrashProbe {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[1]),jar=output.resolve("fixture.jar");Files.createDirectories(output);
        if(!Files.exists(jar)) {
            BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0x80abcdef);
            ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(image,"PNG",png);
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))) {
                zip.putNextEntry(new ZipEntry("assets/minecraft/textures/item/sample.png"));zip.write(png.toByteArray());zip.closeEntry();
            }
        }
        CancellationToken token=new CancellationToken();if(args[0].equals("write"))token.cancel();
        var result=new RawExporter(token,ProgressListener.NONE).export("synthetic",jar,new ExtractRequest("synthetic",List.of(),true,output,false,false));
        if(args[0].equals("write")) {
            if(result.complete())throw new AssertionError("Expected reserved failed output");
            byte[] data=new byte[256*1024*1024];
            System.out.println("READY_FOR_ATOMIC_WRITE");System.out.flush();
            FilesSupport.atomicWrite(output.resolve("java/synthetic/raw/assets/minecraft/textures/item/sample.png"),data);
        } else if(!result.complete())throw new AssertionError("Recovery did not complete");
    }
}
