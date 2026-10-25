package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 固定同梱の信頼設定から準備し、同じleaseと起動指定をジョブ終了まで保持する。 */
public final class ManagedRuntime implements AutoCloseable {
    private static final String RESOURCE="META-INF/mcicon/runtime/";
    private final ManagedOptions options;
    private final RuntimeDescriptor descriptor;
    private final WorkerArtifact.Resolved worker;
    private final RuntimeLease lease;
    private final RuntimeHelper helper;
    private final WorkerLaunch launch;
    private ManagedRuntime(ManagedOptions options,RuntimeDescriptor descriptor,WorkerArtifact.Resolved worker,RuntimeLease lease,RuntimeHelper helper,WorkerLaunch launch) {
        this.options=options;this.descriptor=descriptor;this.worker=worker;this.lease=lease;this.helper=helper;this.launch=launch;
    }
    public static ManagedRuntime prepare(ManagedOptions options,boolean offline,CancellationToken token) throws IOException {
        return prepare(options,offline,token,ManagedRuntime.class.getClassLoader(),host(),RuntimeStore.https(token));
    }
    static ManagedRuntime prepare(ManagedOptions options,boolean offline,CancellationToken token,ClassLoader loader,RuntimeDescriptor.Host host,RuntimeStore.Transport transport) throws IOException {
        token.check();long deadline=System.nanoTime()+options.preparationTimeout().toNanos();
        Instant preparationDeadline=Instant.now().plus(options.preparationTimeout());
        JsonNode pins;try {pins=RuntimeDescriptor.read(resource(loader,"pins.json",1024*1024));}catch(IOException failure){throw new McIconException("MANAGED_RUNTIME_INVALID","同梱pinの形式が不正です",1,failure);}RuntimeDescriptor.fields(pins,"schemaVersion","runtimes");RuntimeDescriptor.constant(pins,"schemaVersion",1);
        JsonNode selected=null;Set<RuntimeDescriptor.Host> hosts=new HashSet<>();
        for(JsonNode entry:RuntimeDescriptor.array(pins,"runtimes",1,6)) {
            RuntimeDescriptor.fields(entry,"host","descriptorPath","descriptorSha256","trustKeyPath");
            JsonNode platform=entry.get("host");RuntimeDescriptor.fields(platform,"os","arch");
            var candidate=new RuntimeDescriptor.Host(RuntimeDescriptor.choice(platform,"os","macos","windows","linux"),RuntimeDescriptor.choice(platform,"arch","aarch64","x86_64"));
            if(!hosts.add(candidate))throw RuntimeDescriptor.invalid("同梱pinのhostが重複しています");
            RuntimeDescriptor.relative(RuntimeDescriptor.text(entry,"descriptorPath",512));RuntimeDescriptor.relative(RuntimeDescriptor.text(entry,"trustKeyPath",512));RuntimeDescriptor.hash(entry,"descriptorSha256");
            if(candidate.equals(host))selected=entry;
        }
        if(selected==null)throw new McIconException("MANAGED_SETUP_REQUIRED","このOS/arch用の管理runtimeは発行されていません: "+host.os()+"/"+host.arch(),1);
        long remaining=deadline-System.nanoTime();if(remaining<=0)throw new java.net.http.HttpTimeoutException("管理環境の準備期限を超えました");
        RuntimeStore store=new RuntimeStore(options.runtimeRoot(),offline,token,Duration.ofNanos(remaining),transport);
        store.check();WorkerArtifact.Resolved worker=WorkerArtifact.resolve(store.root(),token);store.check();
        String descriptorPath=selected.get("descriptorPath").asText();byte[] original=resource(loader,descriptorPath,1024*1024),signature=resource(loader,descriptorPath+".sig",64);
        PublicKey key;
        try {key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(resource(loader,selected.get("trustKeyPath").asText(),1024)));}
        catch(GeneralSecurityException failure){throw new McIconException("MANAGED_RUNTIME_INVALID","同梱信頼鍵が不正です",1,failure);}
        RuntimeDescriptor descriptor=RuntimeDescriptor.verify(original,signature,key,new RuntimeDescriptor.Pin(selected.get("descriptorSha256").asText(),ToolVersion.VERSION,worker.sha256(),adapterDigest(loader),host,Set.of(8,17,21,25)));
        Map<String,byte[]> inventories=new TreeMap<>();
        for(var artifact:descriptor.artifacts()) {
            String path=artifact.inventoryPath();checkJobPath(path);
            for(String existing:inventories.keySet())if(path.startsWith(existing+"/")||existing.startsWith(path+"/"))throw RuntimeDescriptor.invalid("inventory同士の配置が衝突しています");
            byte[] bytes=resource(loader,path,4*1024*1024);descriptor.verifyInventory(artifact,bytes);inventories.put(path,bytes);
        }
        RuntimeGeneration generation=new RuntimeGeneration(store);Path executable=generation.helper(descriptor);
        recover(store,descriptor,executable,token);store.check();
        RuntimeLease lease=RuntimeLease.create(store,descriptor,options,preparationDeadline);RuntimeHelper helper=null;
        try {
            FilesSupport.atomicWrite(lease.file().getParent().resolve("descriptor.json"),original);
            for(var entry:inventories.entrySet()) {
                Path target=FilesSupport.safeResolve(lease.file().getParent(),entry.getKey());if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw RuntimeDescriptor.invalid("inventoryのjob内配置が衝突しています");FilesSupport.atomicWrite(target,entry.getValue());
            }
            Path temp=lease.file().getParent().resolve("tmp");Files.createDirectory(temp);Map<String,String> environment=new HashMap<>();
            environment.put("TMPDIR",temp.toString());environment.put("TMP",temp.toString());environment.put("TEMP",temp.toString());
            if(host.os().equals("windows"))for(String name:List.of("SystemRoot","WINDIR")){String value=System.getenv(name);if(value!=null)environment.put(name,value);}
            helper=new RuntimeHelper(executable,store.root(),lease,environment,descriptor.host().os().equals("windows"));
            requireOk(helper.control("preflight",store.remaining(),token));
            generation.prepare(descriptor,inventories);if(host.os().equals("windows"))helper.keep(store.remaining(),token,false);requireOk(helper.control("prepare",store.remaining(),token));store.check();
            ProcessBuilder prefix=helper.docker(List.of());Map<Integer,String> hashes=new TreeMap<>();for(var java:descriptor.javaExecutables())hashes.put(java.major(),java.sha256());
            WorkerLaunch launch=new WorkerLaunch(prefix.command(),prefix.environment(),descriptor.imageId(),descriptor.agentSha256(),hashes,descriptor.environment());
            return new ManagedRuntime(options,descriptor,worker,lease,helper,launch);
        }catch(IOException|RuntimeException|Error failure) {
            try {release(helper,lease);}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    private static void recover(RuntimeStore store,RuntimeDescriptor descriptor,Path executable,CancellationToken token) throws IOException {
        if(!Set.of("macos","windows").contains(descriptor.host().os()))return;
        List<Path> jobs;
        try(var ignored=store.lock("generations")) {
            Path directory=store.path("jobs");if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw RuntimeLease.mismatch("jobsがdirectoryではありません");
            try(var paths=Files.list(directory)){jobs=paths.toList();}
        }
        for(Path job:jobs) {
            store.check();try(RuntimeLease lease=RuntimeLease.tryRecovery(store,descriptor,job)) {
                if(lease==null)continue;
                Map<String,String> environment=new HashMap<>(Map.of("TMPDIR",job.resolve("tmp").toString(),"TMP",job.resolve("tmp").toString(),"TEMP",job.resolve("tmp").toString()));
                if(descriptor.host().os().equals("windows"))for(String key:List.of("SystemRoot","WINDIR")){String value=System.getenv(key);if(value!=null)environment.put(key,value);}
                requireOk(new RuntimeHelper(executable,store.root(),lease,environment,descriptor.host().os().equals("windows")).control("release",store.remaining(),token));
            }
        }
    }
    public WorkerLaunch launch(){return launch;}
    public WorkerOptions worker(int major) {
        var java=descriptor.javaExecutables().stream().filter(j->j.major()==major).findFirst().orElseThrow(()->RuntimeDescriptor.invalid("要求Java majorが管理世代にありません"));
        return new WorkerOptions(worker.agentJar(),"managed",descriptor.imageId(),options.startupTimeout(),options.itemTimeout(),java.executable().substring(0,java.executable().length()-9),options.containerMemoryMiB(),options.heapMemoryMiB());
    }
    @Override public void close() throws IOException {release(helper,lease);}
    private static void release(RuntimeHelper helper,RuntimeLease lease) throws IOException {
        boolean interrupted=Thread.interrupted();
        try {if(helper!=null)requireOk(helper.control("release",Duration.ofSeconds(10),new CancellationToken()));}
        finally {try {lease.close();}finally{if(interrupted)Thread.currentThread().interrupt();}}
    }
    private static void requireOk(RuntimeHelper.Result result) {
        if(result.ok())return;
        String detail=String.join("; ",result.diagnostics().stream().map(d->d.code()+": "+d.detail()+(d.requiresPrivilege()?"（管理者操作が必要）":"")+(d.requiresRestart()?"（再起動が必要）":"")).toList());
        throw new McIconException(result.status().equals("setup-required")?"MANAGED_SETUP_REQUIRED":"IO_FAILED","管理helperの準備に失敗しました: "+detail,1);
    }
    static RuntimeDescriptor.Host host() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform=os.contains("mac")?"macos":os.contains("win")?"windows":os.contains("linux")?"linux":null;
        String cpu=Set.of("aarch64","arm64").contains(arch)?"aarch64":Set.of("amd64","x86_64").contains(arch)?"x86_64":null;
        if(platform==null||cpu==null)throw new McIconException("MANAGED_SETUP_REQUIRED","未対応のOS/archです: "+os+"/"+arch,1);return new RuntimeDescriptor.Host(platform,cpu);
    }
    static String adapterDigest(ClassLoader loader) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(String name:List.of("adapters/legacy-mappings.json","adapters/releases.json")) {
            var resources=Collections.list(loader.getResources(name));if(resources.size()!=1)throw RuntimeDescriptor.invalid("coreのadapter resourceが不足または重複しています");
            byte[] data;try(InputStream input=resources.getFirst().openStream()){data=input.readNBytes(4*1024*1024+1);}if(data.length>4*1024*1024)throw RuntimeDescriptor.invalid("adapter resourceが上限を超えています");
            bytes.write((name+"\0"+data.length+"\0").getBytes(StandardCharsets.UTF_8));bytes.write(data);
        }
        return FilesSupport.hash(bytes.toByteArray());
    }
    private static byte[] resource(ClassLoader loader,String path,int maximum) throws IOException {
        RuntimeDescriptor.relative(path);List<URL> urls=Collections.list(loader.getResources(RESOURCE+path));if(urls.size()!=1)throw RuntimeDescriptor.invalid("固定同梱のruntime信頼設定/resourceが不足または重複しています: "+path);
        try(InputStream input=urls.getFirst().openStream()){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw RuntimeDescriptor.invalid("同梱runtime resourceが上限を超えています");return bytes;}
    }
    static void checkJobPath(String path) {
        RuntimeDescriptor.relative(path);String first=path.split("/")[0];if(Set.of("lease.json","lease.lock","descriptor.json","tmp").contains(first)||first.startsWith("helper-"))throw RuntimeDescriptor.invalid("inventoryがjobの所有記録またはlogに衝突しています");
    }
}
