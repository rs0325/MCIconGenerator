import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.Json;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** 一版のnative全件描画を実行し、所有workerのkernel高水位メモリを採取する。 */
public final class NativeBenchmarkProbe {
    public static void main(String[] args) throws Exception {
        String version=args[0],context=args[5];long started=System.nanoTime();
        AtomicReference<Map<String,Long>> memory=new AtomicReference<>();
        long[] firstIconNanos={0};
        ProgressListener progress=event->{
            if(!event.phase().equals("render"))return;
            if(firstIconNanos[0]==0)firstIconNanos[0]=System.nanoTime();
            if(event.completed()!=event.total())return;
            for(ProcessHandle child:ProcessHandle.current().descendants().toList()) {
                String[] arguments=child.info().arguments().orElse(new String[0]);
                for(int i=0;i+1<arguments.length;i++)if(arguments[i].equals("--name")&&arguments[i+1].startsWith("mcicon-worker-")) {
                    try {
                        Process probe=new ProcessBuilder("docker","--context",context,"exec",arguments[i+1],"sh","-c",
                                "cat /proc/1/status; cat /sys/fs/cgroup/memory.peak").start();
                        if(!probe.waitFor(15,TimeUnit.SECONDS)){probe.destroyForcibly();throw new AssertionError("Memory sampling timed out");}
                        String status=new String(probe.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                        var rss=Pattern.compile("(?m)^VmHWM:\\s+(\\d+) kB$").matcher(status);
                        var peak=Pattern.compile("(?m)^(\\d+)$").matcher(status);
                        if(probe.exitValue()!=0||!rss.find()||!peak.find())throw new AssertionError("Memory high-water marks unavailable");
                        memory.set(Map.of("workerVmHwmBytesBeforeShutdown",Long.parseLong(rss.group(1))*1024,
                                "containerMemoryPeakBytesBeforeShutdown",Long.parseLong(peak.group(1))));return;
                    } catch(Exception e){throw new RuntimeException(e);}
                }
            }
            throw new AssertionError("Owned worker not found for memory sampling");
        };
        var options=new WorkerOptions(Path.of(args[4]),context,args[6],Duration.ofSeconds(180),Duration.ofSeconds(30));
        ExportResult result;
        try(var generator=new MCIconGenerator(Path.of(args[1]),Boolean.parseBoolean(args[3]),progress,new CancellationToken())) {
            result=generator.render(new RenderRequest(version,List.of(),true,"all",64,Path.of(args[2]),false,false,true),options).join();
        }
        if(!result.complete()||memory.get()==null)throw new AssertionError("Benchmark export incomplete");
        Map<String,Object> report=new TreeMap<>(memory.get());
        report.put("version",version);report.put("size",64);report.put("succeeded",result.succeeded());
        report.put("elapsedSeconds",(System.nanoTime()-started)/1e9);
        report.put("startToFirstIconSeconds",(firstIconNanos[0]-started)/1e9);
        System.out.println(Json.MAPPER.writeValueAsString(report));
    }
}
