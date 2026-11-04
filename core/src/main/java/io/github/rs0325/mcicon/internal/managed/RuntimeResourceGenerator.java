package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.internal.FilesSupport;
import io.github.rs0325.mcicon.internal.TrustSet;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 明示した発行入力を検証してcore用resourceを生成する、build専用入口。 */
final class RuntimeResourceGenerator {
    private static final String PREFIX="META-INF/mcicon/runtime/";
    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("inputDir outputDir toolVersion agentJar adapterRootが必要です");
        generate(Path.of(args[0]),Path.of(args[1]),args[2],Path.of(args[3]),Path.of(args[4]));
    }
    static void generate(Path input,Path output,String version,Path agent,Path adapters) throws Exception {
        input=input.toAbsolutePath().normalize();if(!Files.isDirectory(input,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(input))throw new IOException("明示input directoryが必要です");
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("生成先を上書きしません");
        JsonNode definition=RuntimeDescriptor.read(read(input,"input.json",1024*1024));RuntimeDescriptor.fields(definition,"schemaVersion","finalBytesConfirmed","runtimes");RuntimeDescriptor.constant(definition,"schemaVersion",1);
        if(!definition.path("finalBytesConfirmed").isBoolean()||!definition.path("finalBytesConfirmed").booleanValue())throw RuntimeDescriptor.invalid("最終bytes採用が必要です");
        String agentHash=FilesSupport.hash(agent,"SHA-256"),adapterHash;
        try(var loader=new URLClassLoader(new java.net.URL[]{adapters.toUri().toURL()},null)){adapterHash=ManagedRuntime.adapterDigest(loader);}
        byte[] trustBytes=null;Map<String,byte[]> resources=new TreeMap<>();Set<String> trustPaths=new HashSet<>();Set<RuntimeDescriptor.Host> hosts=new HashSet<>();ObjectNode pins=RuntimeDescriptor.JSON.createObjectNode().put("schemaVersion",1);var entries=pins.putArray("runtimes");
        Path scratch=Files.createTempDirectory(output.toAbsolutePath().getParent(),"runtime-validation-");Exception original=null;
        try {
            RuntimeStore store=new RuntimeStore(scratch.resolve("store"),true,new CancellationToken(),Duration.ofMinutes(10),(u,o,t)->{throw new AssertionError("生成時のnetworkは禁止です");});
            for(JsonNode entry:RuntimeDescriptor.array(definition,"runtimes",1,6)) {
                RuntimeDescriptor.fields(entry,"host","descriptorPath","descriptorSha256","trustKeyPath");JsonNode h=entry.get("host");RuntimeDescriptor.fields(h,"os","arch");
                var host=new RuntimeDescriptor.Host(RuntimeDescriptor.choice(h,"os","macos","windows","linux"),RuntimeDescriptor.choice(h,"arch","aarch64","x86_64"));if(!hosts.add(host))throw RuntimeDescriptor.invalid("hostが重複しています");
                String path=RuntimeDescriptor.relative(RuntimeDescriptor.text(entry,"descriptorPath",512)),trustPath=RuntimeDescriptor.relative(RuntimeDescriptor.text(entry,"trustKeyPath",512));
                byte[] raw=read(input,path,1024*1024),signature=read(input,path+".sig",64),der=read(input,trustPath,TrustSet.SIZE);
                TrustSet key=TrustSet.parse(der);if(trustBytes==null)trustBytes=der;else if(!Arrays.equals(trustBytes,der))throw RuntimeDescriptor.invalid("host間の信頼集合が不一致です");
                RuntimeDescriptor descriptor=RuntimeDescriptor.verify(raw,signature,key,new RuntimeDescriptor.Pin(RuntimeDescriptor.hash(entry,"descriptorSha256"),version,agentHash,adapterHash,host,Set.of(8,17,21,25)));
                add(resources,path,raw);add(resources,path+".sig",signature);
                if(trustPaths.add(trustPath))add(resources,trustPath,der);else if(!Arrays.equals(resources.get(trustPath),der))throw RuntimeDescriptor.invalid("共有trustが不一致です");
                for(var artifact:descriptor.artifacts()) {
                    byte[] inventory=read(input,artifact.inventoryPath(),4*1024*1024);descriptor.verifyInventory(artifact,inventory);ManagedRuntime.checkJobPath(artifact.inventoryPath());add(resources,artifact.inventoryPath(),inventory);
                    Path archive=FilesSupport.safeResolve(input,"artifacts/"+artifact.id()+"."+artifact.format());if(!store.valid(archive,artifact))throw RuntimeDescriptor.invalid("artifact実bytesが不一致です: "+artifact.id());
                    if(artifact.role().equals("host-helper")) {
                        Path extracted=scratch.resolve("helper-"+hosts.size());RuntimeArchive.extract(archive,extracted,artifact,store);
                        Path executable=FilesSupport.safeResolve(extracted,descriptor.helperExecutable());if(!Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS))throw RuntimeDescriptor.invalid("helper entryがありません");
                        if(!host.os().equals("windows")&&!Files.isExecutable(executable))throw RuntimeDescriptor.invalid("helper entryの実行属性がありません");
                    }
                }
                entries.add(entry.deepCopy());
            }
            String operating="mcicon-operating.pub.der",recovery="mcicon-recovery.pub.der";
            if(Files.exists(input.resolve(operating),LinkOption.NOFOLLOW_LINKS)||Files.exists(input.resolve(recovery),LinkOption.NOFOLLOW_LINKS)) {
                byte[] operatingBytes=read(input,operating,44),recoveryBytes=read(input,recovery,44),first=operatingBytes,second=recoveryBytes;
                if(Arrays.compareUnsigned(first,second)>0){byte[] swap=first;first=second;second=swap;}
                if(!Arrays.equals(first,Arrays.copyOfRange(trustBytes,8,52))||!Arrays.equals(second,Arrays.copyOfRange(trustBytes,52,96)))throw RuntimeDescriptor.invalid("公開DERと信頼集合が不一致です");
                add(resources,operating,operatingBytes);add(resources,recovery,recoveryBytes);
            }
            add(resources,"pins.json",RuntimeDescriptor.JSON.writeValueAsBytes(pins));Files.createDirectory(output);
            for(var entry:resources.entrySet()){Path target=output.resolve(PREFIX+entry.getKey());Files.createDirectories(target.getParent());Files.write(target,entry.getValue(),StandardOpenOption.CREATE_NEW);}
        }catch(Exception failure){original=failure;throw failure;}
        finally {try {remove(scratch);}catch(IOException failure){if(original!=null)original.addSuppressed(failure);else throw failure;}}
    }
    private static byte[] read(Path root,String path,int maximum) throws IOException {
        Path file=FilesSupport.safeResolve(root,RuntimeDescriptor.relative(path));if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>maximum)throw new IOException("input通常file/sizeが不正です: "+path);
        try(InputStream stream=Files.newInputStream(file)){byte[] bytes=stream.readNBytes(maximum+1);if(bytes.length>maximum)throw new IOException("input size超過: "+path);return bytes;}
    }
    private static void add(Map<String,byte[]> files,String path,byte[] bytes) {
        for(String existing:files.keySet())if(existing.equals(path)||existing.startsWith(path+"/")||path.startsWith(existing+"/"))throw RuntimeDescriptor.invalid("resource配置が重複/衝突しています: "+path);
        files.put(path,bytes);
    }
    private static void remove(Path root) throws IOException {try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
}
