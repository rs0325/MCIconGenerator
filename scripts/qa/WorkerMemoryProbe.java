import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;

/** 合成Dockerで実際に渡されるcontainer/heap予算と終了を確認する。 */
public final class WorkerMemoryProbe {
    public static void main(String[] args) throws Exception {
        Path cache=Path.of(args[0]);Files.createDirectories(cache);
        var runtime=new RuntimePreparer.Prepared(new VersionInfo("synthetic","","","",""),
                Json.MAPPER.readTree("{\"mainClass\":\"Fixture\",\"assetIndex\":{\"id\":\"synthetic\"}}"),
                cache.resolve("client.jar"),null,List.of(),cache.resolve("natives"),cache.resolve("assets"),21,"gui-fixed");
        var options=args.length==2?new WorkerOptions(Path.of(args[1]),"synthetic","synthetic",Duration.ofSeconds(3),Duration.ofSeconds(3))
                :new WorkerOptions(Path.of(args[1]),"synthetic","synthetic",Duration.ofSeconds(3),Duration.ofSeconds(3),null,Integer.parseInt(args[2]),Integer.parseInt(args[3]));
        try(var worker=new WorkerClient(runtime,cache,options,new CancellationToken())) {
            if(!worker.catalog().isEmpty())throw new AssertionError("合成catalogが空ではありません");
        }
        try(var runs=Files.list(cache)) {
            if(runs.filter(p->p.getFileName().toString().startsWith("worker-")).anyMatch(p->Files.exists(p.resolve("worker-agent.jar"))))
                throw new AssertionError("agent snapshotが残っています");
        }
    }
}
