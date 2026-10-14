import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.Json;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 専用代理接続の切断地点とFuture完了を観測する。 */
public final class DockerDisconnectProbe {
    public static void main(String[] args) throws Exception {
        boolean fault=args[0].equals("fault");
        ProgressListener progress=event->{
            if(!fault||!event.phase().equals("render")||event.completed()!=1)return;
            for(ProcessHandle child:ProcessHandle.current().descendants().toList()) {
                String[] values=child.info().arguments().orElse(new String[0]);
                for(int i=0;i+1<values.length;i++)if(values[i].equals("--name")&&values[i+1].startsWith("mcicon-worker-")) {
                    System.out.println("READY_FOR_DISCONNECT "+values[i+1]);System.out.flush();
                    try {if(System.in.read()!='\n')throw new AssertionError("切断開始通知がありません");}
                    catch(java.io.IOException e){throw new RuntimeException(e);}
                    return;
                }
            }
            throw new AssertionError("専用workerが見つかりません");
        };
        var options=new WorkerOptions(Path.of(args[3]),args[4],args[5],Duration.ofSeconds(180),Duration.ofSeconds(30));
        var ids=List.of("chest","diamond","glass","stone").stream().map(ResourceId::parse).toList();
        try(var generator=new MCIconGenerator(Path.of(args[1]),true,progress,new CancellationToken())) {
            var future=generator.render(new RenderRequest("1.13",ids,false,"all",64,Path.of(args[2]),false,fault,true),options);
            var result=future.get(210,TimeUnit.SECONDS);
            System.out.println("RESULT "+Json.MAPPER.writeValueAsString(result).replace("\n",""));
            if(fault?(result.complete()||result.succeeded()!=1||result.failed()!=3):!result.complete())
                throw new AssertionError("切断または再開の結果が不正です");
        }
        if(fault)System.exit(3);
    }
}
