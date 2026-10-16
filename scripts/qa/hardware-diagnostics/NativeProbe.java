import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

public final class NativeProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("JNA=" + Native.class.getProtectionDomain().getCodeSource().getLocation());
        for (String library : new String[]{"udev", "X11"})
            System.out.println(library + "=" + NativeLibrary.getInstance(library).getFile().getCanonicalPath());
    }
}
