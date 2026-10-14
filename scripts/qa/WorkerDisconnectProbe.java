import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;

/** 終了済みDocker CLIでも専用containerの削除要求を行う合成受入。 */
public final class WorkerDisconnectProbe {
    public static void main(String[] args) throws Exception {
        Path cache=Path.of(args[0]);Files.createDirectories(cache);
        var runtime=new RuntimePreparer.Prepared(new VersionInfo("synthetic","","","",""),
                Json.MAPPER.readTree("{\"mainClass\":\"Fixture\",\"assetIndex\":{\"id\":\"synthetic\"}}"),
                cache.resolve("client.jar"),null,List.of(),cache.resolve("natives"),cache.resolve("assets"),21,"gui-fixed");
        var options=new WorkerOptions(Path.of(args[1]),"synthetic","synthetic",Duration.ofSeconds(5),Duration.ofSeconds(5));
        try(var worker=new WorkerClient(runtime,cache,options,new CancellationToken())) {
            try {worker.catalog();throw new AssertionError("切断を検出しませんでした");}
            catch(McIconException e) {if(!e.code().equals("WORKER_EXITED"))throw e;}
        }
        if(!Files.exists(cache.resolve("removed"))||!Files.readString(cache.resolve("removed")).equals(Files.readString(cache.resolve("owned"))))throw new AssertionError("終了済みCLIの専用container削除を要求していません");
        try(var runs=Files.list(cache)) {
            if(runs.filter(p->p.getFileName().toString().startsWith("worker-")).anyMatch(p->Files.exists(p.resolve("worker-agent.jar"))))
                throw new AssertionError("agent snapshotが残っています");
        }
    }
}
