package io.github.rs0325.mcicon.internal;

import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Java側の重複lock管理を共有しない別JVMからactive区間を観測する。 */
public final class ReceiptProbeProcess {
    public static void main(String[] arguments) throws Exception {
        try(FileChannel channel=FileChannel.open(Path.of(arguments[0]),StandardOpenOption.READ,StandardOpenOption.WRITE);
            var lock=channel.tryLock(64,1,false)) {
            System.out.println(lock==null?"BUSY":"FREE");
        }
    }
}
