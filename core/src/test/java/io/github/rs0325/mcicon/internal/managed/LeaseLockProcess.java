package io.github.rs0325.mcicon.internal.managed;

import java.nio.channels.FileChannel;
import java.nio.file.*;

/** 別processがleaseの所有lockを保持する条件を検証する。 */
public final class LeaseLockProcess {
    public static void main(String[] args) throws Exception {
        try(var channel=FileChannel.open(Path.of(args[0]),StandardOpenOption.READ,StandardOpenOption.WRITE);
            var lock=channel.lock(64,1,false)) {
            if(Boolean.parseBoolean(args[1])){System.out.println("HELD");System.out.flush();}System.in.read();
        }
    }
}
