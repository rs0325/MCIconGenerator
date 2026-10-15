import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import jdk.jfr.Recording;
import oshi.SystemInfo;

/** SystemReportが使うOSHI処理の子process開始を、ゲーム起動と分けて採取する。 */
public final class HardwareProcessProbe {
    private static void mark(Path directory, String phase) throws Exception {
        Files.writeString(directory.resolve("phases.tsv"), phase + "\t" + Instant.now() + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        try (var recording = new Recording()) {
            recording.enable("jdk.ProcessStart").withStackTrace();
            recording.start();
            try {
                mark(directory, "hardware.before");
                var hardware = new SystemInfo().getHardware();
                mark(directory, "processor.before");
                hardware.getProcessor().getProcessorIdentifier();
                mark(directory, "processor.after");
                hardware.getGraphicsCards();
                mark(directory, "graphics.after");
                hardware.getMemory().getPhysicalMemory();
                hardware.getMemory().getVirtualMemory().getVirtualMax();
                mark(directory, "memory.after");
            } finally {
                recording.stop();
                recording.dump(directory.resolve("processes.jfr"));
            }
        }
    }
}
