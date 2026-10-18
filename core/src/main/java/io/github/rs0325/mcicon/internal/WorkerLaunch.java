package io.github.rs0325.mcicon.internal;

import java.util.*;

/** ジョブごとの固定command prefix・環境変数と、管理世代の期待値。公開Optionsへ輸送詳細を混ぜない。 */
public record WorkerLaunch(List<String> prefix,Map<String,String> environment,String expectedImageId,String expectedAgentSha256,
        Map<Integer,String> javaHashes,Map<String,String> renderingEnvironment) {
    public WorkerLaunch {prefix=List.copyOf(prefix);environment=Map.copyOf(environment);javaHashes=Map.copyOf(javaHashes);renderingEnvironment=Map.copyOf(renderingEnvironment);}
    public static WorkerLaunch docker(String context) {return new WorkerLaunch(List.of("docker","--context",context),Map.of(),null,null,Map.of(),Map.of());}
    public boolean managed(){return expectedImageId!=null;}
    public ProcessBuilder command(List<String> args) {
        List<String> command=new ArrayList<>(prefix);command.addAll(args);ProcessBuilder builder=new ProcessBuilder(command);
        if(managed()){builder.environment().clear();builder.environment().putAll(environment);}
        return builder;
    }
}
