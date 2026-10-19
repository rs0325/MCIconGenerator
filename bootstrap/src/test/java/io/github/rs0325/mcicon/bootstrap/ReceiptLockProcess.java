package io.github.rs0325.mcicon.bootstrap;

import java.nio.channels.*;
import java.nio.file.*;

/** 本番と同じchannel読取後も別JVMからのlockが拒否されることを確認する。 */
public final class ReceiptLockProcess {
    public static void main(String[] args) throws Exception {
        try(FileChannel channel=FileChannel.open(Path.of(args[0]),StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock held=channel.lock()) {
            UpdatePreparation.readReceipt(channel);System.out.write(1);System.out.flush();System.in.read();
        }
    }
}
