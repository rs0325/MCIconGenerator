package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.internal.ToolVersion;
import io.github.rs0325.mcicon.internal.TrustSet;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 新配布CLIの署名済みhelperから、既知旧Mac jobの停止終端だけを明示確定する。 */
public final class RuntimeRepair {
    private static final String RESOURCE="META-INF/mcicon/runtime/";
    private RuntimeRepair() {}
    /** 同host・同bootの旧jobを指定する。旧VMの起動・停止・削除やbootstrap trial変更は行わない。検証不足はIOExceptionで拒否する。 */
    public static void repair(Path root,UUID job) throws IOException {
        if(!System.getProperty("os.name").startsWith("Mac"))throw new IOException("runtime repairはMacだけに対応しています");
        if(!root.isAbsolute()||!root.toRealPath().equals(root))throw new IOException("--runtime-rootはlinkを含まない絶対pathが必要です");
        ClassLoader loader=RuntimeRepair.class.getClassLoader();JsonNode pins=RuntimeDescriptor.read(resource(loader,"pins.json",1024*1024)),selected=null;
        for(JsonNode pin:pins.path("runtimes"))if(pin.path("host").path("os").asText().equals("macos")&&pin.path("host").path("arch").asText().equals("aarch64")){if(selected!=null)throw new IOException("Mac pinが重複しています");selected=pin;}
        if(selected==null)throw new IOException("同梱Mac runtime pinがありません");
        String path=RuntimeDescriptor.relative(selected.path("descriptorPath").asText());byte[] original=resource(loader,path,1024*1024),signature=resource(loader,path+".sig",64);
        TrustSet trust=TrustSet.parse(resource(loader,RuntimeDescriptor.relative(selected.path("trustKeyPath").asText()),TrustSet.SIZE));JsonNode definition=RuntimeDescriptor.read(original);
        RuntimeDescriptor descriptor=RuntimeDescriptor.verify(original,signature,trust,new RuntimeDescriptor.Pin(selected.path("descriptorSha256").asText(),ToolVersion.VERSION,definition.path("worker").path("agentSha256").asText(),definition.path("worker").path("adapterSetSha256").asText(),new RuntimeDescriptor.Host("macos","aarch64"),Set.of(8,17,21,25)));
        var token=new CancellationToken();RuntimeStore store=new RuntimeStore(root,false,token,Duration.ofMinutes(2),RuntimeStore.https(token));
        for(var artifact:descriptor.artifacts())descriptor.verifyInventory(artifact,resource(loader,artifact.inventoryPath(),4*1024*1024));
        Path executable=new RuntimeGeneration(store).helper(descriptor);
        ProcessBuilder builder=new ProcessBuilder(executable.toString(),"--repair","--runtime-root",root.toString(),"--job",job.toString()).inheritIO();builder.environment().clear();
        Process process=builder.start();
        try {
            if(!process.waitFor(30,TimeUnit.SECONDS))throw new IOException("runtime repairの期限を超えました");
            if(process.exitValue()!=0)throw new IOException("runtime repairが拒否されました");
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("runtime repairが中断されました");}
        finally {if(process.isAlive()){process.destroy();try{if(!process.waitFor(3,TimeUnit.SECONDS))throw new IOException("保守helperの終了を確認できません");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("保守helper回収が中断されました");}}}
    }
    private static byte[] resource(ClassLoader loader,String path,int limit) throws IOException {
        try(InputStream input=loader.getResourceAsStream(RESOURCE+path)){if(input==null)throw new IOException("同梱保守資材がありません: "+path);byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("同梱保守資材が大きすぎます");return bytes;}
    }
}
