package io.github.rs0325.mcicon.helper.windows;

import java.io.IOException;

/** guest実容量と自jobのcontainer予算を照合する。 */
final class GuestBudget {
    private GuestBudget() {}
    static long requiredBytes(long memoryMiB) throws IOException {
        try {return Math.multiplyExact(Math.max(8192,Math.addExact(memoryMiB,1024)),1024L*1024);}
        catch(ArithmeticException failure){throw new IOException("guest予算が範囲外です",failure);}
    }
    static void require(long actualBytes,long requiredBytes) throws IOException {if(actualBytes<requiredBytes)throw new IOException("guest実容量が自jobとoverheadの予算を満たしません");}
}
