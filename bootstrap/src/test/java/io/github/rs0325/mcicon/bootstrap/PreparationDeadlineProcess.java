package io.github.rs0325.mcicon.bootstrap;

import java.time.Duration;

/** package pinのI/O停止を模し、本番と同じ自己期限監督が先に稼働する。 */
public final class PreparationDeadlineProcess {
    public static void main(String[] args) throws Exception {
        PreparationMain.guard(System.nanoTime()+Duration.ofMillis(50).toNanos());System.out.write(1);System.out.flush();System.in.read();
    }
}
