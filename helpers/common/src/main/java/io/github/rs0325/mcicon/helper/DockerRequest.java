package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** OS helper内部の固定argv解釈結果。host bindはjob snapshotへ置換し、公開libraryのSPIにはしない。 */
public record DockerRequest(String name,List<String> create,List<String> process,Path cache,Path agent,boolean worker) {
    /** descriptor/lease予算外のargvを拒否し、guestCacheと世代labelを付けたcreate argvを返す。 */
    public static DockerRequest parse(List<String> argv,JsonNode descriptor,JsonNode lease,String guestCache,String generation) throws IOException {
        if(argv.isEmpty()||!argv.getFirst().equals("run"))throw new IOException("固定run引数が必要です");
        List<String> create=new ArrayList<>(List.of("create"));Set<String> once=new HashSet<>();String name=null,entry=null;Path cache=null,agent=null;boolean interactive=false,rm=false;int index=1;String image=descriptor.path("rendering").path("imageId").asText();
        while(index<argv.size()&&argv.get(index).startsWith("-")) {
            String flag=argv.get(index++);
            if(flag.equals("--rm")||flag.equals("-i")) {
                if(!once.add(flag))throw new IOException("run flagが重複しています");
                if(flag.equals("--rm"))rm=true;else {interactive=true;create.add("-i");}continue;
            }
            if(!Set.of("--name","--platform","--network","--memory","--cpus","--entrypoint","-v","-e").contains(flag)||index>=argv.size())throw new IOException("未対応run flagです");
            String value=argv.get(index++);if(!Set.of("-v","-e").contains(flag)&&!once.add(flag))throw new IOException("run flagが重複しています");
            switch(flag) {
                case "--name" -> {name=value;if(!name.matches("mcicon-(?:worker|doctor)-[0-9a-f-]{36}"))throw new IOException("container名が不正です");try {UUID.fromString(name.substring(14));}catch(IllegalArgumentException failure){throw new IOException("container UUIDが不正です",failure);}}
                case "--platform" -> {if(!value.equals("linux/amd64"))throw new IOException("renderer platformが一致しません");}
                case "--network" -> {if(!value.equals("none"))throw new IOException("renderer networkはnoneが必要です");}
                case "--memory" -> {if(!value.equals(lease.path("requirements").path("containerMemoryMiB").asLong()+"m"))throw new IOException("container予算が一致しません");}
                case "--cpus" -> {if(!value.equals("4"))throw new IOException("container CPU予算が一致しません");}
                case "--entrypoint" -> entry=value;
                case "-e" -> {
                    int equals=value.indexOf('=');if(equals<1)throw new IOException("renderer環境変数が不正です");String key=value.substring(0,equals),expected=descriptor.path("rendering").path("environment").path(key).textValue();
                    if(!value.substring(equals+1).equals(expected)&&!value.equals("SDL_VIDEO_FORCE_EGL=1"))throw new IOException("renderer環境変数がdescriptor外です");
                    if(!once.add("env:"+key))throw new IOException("renderer環境変数が重複しています");
                }
                case "-v" -> {
                    String suffix;
                    if(value.endsWith(":/mcicon/cache")) {suffix=":/mcicon/cache";if(cache!=null)throw new IOException("cache bindが重複しています");cache=absolute(value.substring(0,value.length()-suffix.length()));value=guestCache+suffix;}
                    else if(value.endsWith(":/mcicon/worker.jar:ro")) {suffix=":/mcicon/worker.jar:ro";if(agent!=null)throw new IOException("worker bindが重複しています");agent=absolute(value.substring(0,value.length()-suffix.length()));value=guestCache+"/"+agent.getParent().getFileName()+"/worker-agent.jar"+suffix;}
                    else throw new IOException("任意host bindは使用できません");
                }
                default -> throw new AssertionError();
            }
            create.add(flag);create.add(value);
        }
        if(!rm||name==null||!once.containsAll(Set.of("--platform","--network"))||index>=argv.size()||!argv.get(index++).equals(image))throw new IOException("renderer runの必須条件が一致しません");
        List<String> process=List.copyOf(argv.subList(index,argv.size()));if(process.isEmpty())throw new IOException("renderer commandがありません");
        boolean worker=interactive;
        if(worker) {
            if(!name.startsWith("mcicon-worker-")||cache==null||agent==null||entry!=null||!once.containsAll(Set.of("--memory","--cpus"))||!javaPaths(descriptor).contains(process.getFirst()))throw new IOException("worker起動条件が一致しません");
        }else {
            if(!name.startsWith("mcicon-doctor-")||cache!=null||agent!=null)throw new IOException("診断runにhost bindは使用できません");
            boolean version=javaPaths(descriptor).contains(entry)&&process.equals(List.of("-version"));
            boolean hash="sha256sum".equals(entry)&&process.size()==1&&javaPaths(descriptor).contains(process.getFirst());
            boolean mesa=entry==null&&process.equals(List.of("glxinfo","-B"));if(!version&&!hash&&!mesa)throw new IOException("未対応のrenderer診断commandです");
            create.addAll(List.of("--memory",lease.path("requirements").path("containerMemoryMiB").asLong()+"m","--cpus","4"));
        }
        for(String key:List.of("jobId","ownerId","descriptorSha256"))create.addAll(List.of("--label","io.github.rs0325.mcicon."+key+"="+lease.path(key).asText()));
        create.addAll(List.of("--label","io.github.rs0325.mcicon.generation="+generation,image));create.addAll(process);
        return new DockerRequest(name,List.copyOf(create),process,cache,agent,worker);
    }
    private static Set<String> javaPaths(JsonNode descriptor) {Set<String> paths=new HashSet<>();for(JsonNode value:descriptor.path("rendering").path("javaExecutables"))paths.add(value.path("executable").asText());return paths;}
    private static Path absolute(String value) throws IOException {Path path=Path.of(value);if(!path.isAbsolute()||!path.normalize().equals(path))throw new IOException("bind sourceは正規絶対pathが必要です");return path;}
}
