package io.github.rs0325.mcicon.helper.windows;

import java.io.IOException;

/** WSL全体のguest実容量と、他descriptorを含むactive container予算を照合する。 */
final class GuestBudget {
    private GuestBudget() {}
    static long requiredBytes(long currentMiB,long activeMiB) throws IOException {
        try {return Math.multiplyExact(Math.max(8192,Math.addExact(Math.addExact(currentMiB,activeMiB),1024)),1024L*1024);}
        catch(ArithmeticException failure){throw new IOException("guest並行予算が範囲外です",failure);}
    }
    static void require(long actualBytes,long requiredBytes) throws IOException {if(actualBytes<requiredBytes)throw new IOException("guest実容量が全active leaseとoverheadの予算を満たしません");}
}
