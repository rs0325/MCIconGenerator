package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 署名前候補の実bytesを検査するbuild専用入口。署名・採用・公開可否は証明しない。 */
final class RuntimeCandidateVerifier {
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("inputDir toolVersion agentJar adapterRootが必要です");
        verify(Path.of(args[0]),args[1],Path.of(args[2]),Path.of(args[3]));
        System.out.println("PASS unsigned-candidate-bytes; signature/trust/adoption NOT verified");
    }
    static void verify(Path input,String version,Path agent,Path adapters) throws Exception {
        JsonNode definition=RuntimeDescriptor.read(read(input,"input.json",1024*1024));
        RuntimeDescriptor.fields(definition,"schemaVersion","finalBytesConfirmed","runtimes");
        RuntimeDescriptor.constant(definition,"schemaVersion",1);
        if(!definition.path("finalBytesConfirmed").isBoolean())throw RuntimeDescriptor.invalid("採用flagがbooleanではありません");
        String agentHash=FilesSupport.hash(agent,"SHA-256"),adapterHash;
        try(var loader=new URLClassLoader(new java.net.URL[]{adapters.toUri().toURL()},null)){adapterHash=ManagedRuntime.adapterDigest(loader);}
        Set<RuntimeDescriptor.Host> hosts=new HashSet<>();Set<String> resourcePaths=new HashSet<>(),trustPaths=new HashSet<>();
        Path scratch=Files.createTempDirectory("mcicon-unsigned-candidate-");
        try {
            RuntimeStore store=new RuntimeStore(scratch.resolve("store"),true,new CancellationToken(),Duration.ofMinutes(10),(u,o,t)->{throw new AssertionError("候補検査のnetworkは禁止です");});
            for(JsonNode entry:RuntimeDescriptor.array(definition,"runtimes",1,6)) {
                RuntimeDescriptor.fields(entry,"host","descriptorPath","descriptorSha256","trustKeyPath");
                JsonNode host=entry.get("host");RuntimeDescriptor.fields(host,"os","arch");
                var h=new RuntimeDescriptor.Host(RuntimeDescriptor.choice(host,"os","windows","macos","linux"),RuntimeDescriptor.choice(host,"arch","x86_64","aarch64"));
                if(!hosts.add(h))throw RuntimeDescriptor.invalid("hostが重複しています");
                String trustPath=RuntimeDescriptor.relative(RuntimeDescriptor.text(entry,"trustKeyPath",512));
                String descriptorPath=RuntimeDescriptor.relative(RuntimeDescriptor.text(entry,"descriptorPath",512));
                place(resourcePaths,descriptorPath);place(resourcePaths,descriptorPath+".sig");
                if(trustPaths.add(trustPath))place(resourcePaths,trustPath);
                byte[] raw=read(input,descriptorPath,1024*1024);
                var pin=new RuntimeDescriptor.Pin(RuntimeDescriptor.hash(entry,"descriptorSha256"),version,agentHash,adapterHash,h,Set.of(8,17,21,25));
                var descriptor=RuntimeDescriptor.parse(raw,pin);
                for(var artifact:descriptor.artifacts()) {
                    ManagedRuntime.checkJobPath(artifact.inventoryPath());place(resourcePaths,artifact.inventoryPath());
                    descriptor.verifyInventory(artifact,read(input,artifact.inventoryPath(),4*1024*1024));
                    Path archive=FilesSupport.safeResolve(input,"artifacts/"+artifact.id()+"."+artifact.format());
                    if(!store.valid(archive,artifact))throw RuntimeDescriptor.invalid("artifact実bytesが不一致です: "+artifact.id());
                    if(artifact.role().equals("host-helper")) {
                        Path extracted=scratch.resolve("helper-"+hosts.size());RuntimeArchive.extract(archive,extracted,artifact,store);
                        Path executable=FilesSupport.safeResolve(extracted,descriptor.helperExecutable());
                        if(!Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS)||!h.os().equals("windows")&&!Files.isExecutable(executable))throw RuntimeDescriptor.invalid("helper entryが不正です");
                    }
                }
            }
        } finally {
            try(var paths=Files.walk(scratch)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private static void place(Set<String> paths,String path) {
        for(String previous:paths)if(previous.equals(path)||previous.startsWith(path+"/")||path.startsWith(previous+"/"))throw RuntimeDescriptor.invalid("resource配置が重複/衝突しています: "+path);
        paths.add(path);
    }
    private static byte[] read(Path root,String name,int maximum) throws Exception {
        Path path=FilesSupport.safeResolve(root,RuntimeDescriptor.relative(name));
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>maximum)throw RuntimeDescriptor.invalid("候補fileの種類/sizeが不正です");
        try(var stream=Files.newInputStream(path)){byte[] raw=stream.readNBytes(maximum+1);if(raw.length>maximum)throw RuntimeDescriptor.invalid("候補fileが大きすぎます");return raw;}
    }
}
