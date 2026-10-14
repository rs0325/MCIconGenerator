import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;

/** 合成Docker peerに対する現行coreのhandshake・要求不一致の処理を確認する。 */
public final class WorkerContractProbe {
    public static void main(String[] args) throws Exception {
        Path cache=Path.of(args[0]);Files.createDirectories(cache);
        var runtime=new RuntimePreparer.Prepared(new VersionInfo("synthetic","","","",""),
                Json.MAPPER.readTree("{\"mainClass\":\"Fixture\",\"assetIndex\":{\"id\":\"synthetic\"}}"),
                cache.resolve("client.jar"),null,List.of(),cache.resolve("natives"),cache.resolve("assets"),21,"gui-fixed");
        var options=new WorkerOptions(Path.of(args[1]),"synthetic","synthetic",Duration.ofSeconds(3),Duration.ofSeconds(3));
        String outcome="success";
        try(var worker=new WorkerClient(runtime,cache,options,new CancellationToken())) {
            if(!worker.catalog().isEmpty())throw new AssertionError("合成catalogが空ではありません");
        } catch(McIconException e) {outcome=e.code()+":"+e.exitCode();}
        catch(IOException e) {outcome="IOException";}
        if(!outcome.equals(args[2]))throw new AssertionError("期待="+args[2]+", 実際="+outcome);
        try(var runs=Files.list(cache)) {
            if(runs.filter(p->p.getFileName().toString().startsWith("worker-")).anyMatch(p->Files.exists(p.resolve("worker-agent.jar"))))
                throw new AssertionError("agent snapshotが残っています");
        }
        System.out.println(outcome);
    }
}
