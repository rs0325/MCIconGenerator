import io.github.rs0325.mcicon.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Stops at a native-render checkpoint and identifies only its own Docker child. */
public final class IconCrashProbe {
    public static void main(String[] args) throws Exception {
        ProgressListener progress=event->{
            if(event.phase().equals("render")&&event.completed()==3) {
                for(ProcessHandle child:ProcessHandle.current().descendants().toList()) {
                    var arguments=child.info().arguments().orElse(new String[0]);
                    for(int i=0;i+1<arguments.length;i++)if(arguments[i].equals("--name")&&arguments[i+1].startsWith("mcicon-worker-")) {
                        System.out.println("READY_FOR_KILL "+arguments[i+1]);System.out.flush();
                        try {new CountDownLatch(1).await();}
                        catch(InterruptedException e){Thread.currentThread().interrupt();}
                        return;
                    }
                }
                throw new AssertionError("Owned worker was not found");
            }
        };
        var options=new WorkerOptions(Path.of(args[2]),args[3],args[4],Duration.ofSeconds(180),Duration.ofSeconds(30));
        try(var generator=new MCIconGenerator(Path.of(args[0]),true,progress,new CancellationToken())) {
            generator.render(new RenderRequest("1.13",List.of(),true,"all",64,Path.of(args[1]),false,false,true),options).join();
            throw new AssertionError("Expected interruption");
        }
    }
}
