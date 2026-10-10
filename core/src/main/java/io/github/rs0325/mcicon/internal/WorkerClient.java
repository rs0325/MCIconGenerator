package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.jar.JarFile;
import java.util.*;
import java.util.concurrent.*;

public final class WorkerClient implements AutoCloseable,IconWorker {
    private final Process process;
    private final BufferedWriter output;
    private final BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>(256);
    private final CancellationToken token;
    private final WorkerOptions options;
    private final Path runDirectory;
    private final String renderer;
    private final String containerName="mcicon-worker-"+UUID.randomUUID();
    private final String agentSha256;
    private final String imageId;
    private final Thread shutdownHook;
    public WorkerClient(RuntimePreparer.Prepared runtime,Path cache,WorkerOptions options,CancellationToken token) throws Exception {
        this.token=token;this.options=options;
        if(!Files.isRegularFile(options.agentJar()))throw new McIconException("WORKER_NOT_FOUND","Worker JAR not found; run :worker:agentJar or specify --worker-jar",1);
        byte[] agentBytes=readAgent(options.agentJar());
        agentSha256=FilesSupport.hash(agentBytes);
        imageId=resolveImage(options,token);
        cache=cache.toAbsolutePath().normalize();
        runDirectory=Files.createTempDirectory(cache,"worker-");
        Path agentSnapshot=runDirectory.resolve("worker-agent.jar");
        FilesSupport.atomicWrite(agentSnapshot,agentBytes);
        validateAgentSnapshot(agentSnapshot);
        Map<String,Object> config=new TreeMap<>();config.put("version",runtime.version().id());config.put("profile",runtime.profile());
        if(runtime.mappings()!=null)config.put("mappings",inContainer(cache,runtime.mappings()));
        if(runtime.profile().startsWith("legacy-")) {config.put("hookClass","net.minecraft.client.render.GameRenderer");config.put("tickMethod","render");config.put("hookDescriptor","(FJZ)V");}
        FilesSupport.atomicWrite(runDirectory.resolve("agent.json"),Json.MAPPER.writeValueAsBytes(config));
        Path game=runDirectory.resolve("game");Files.createDirectories(game);
        Files.writeString(game.resolve("options.txt"),"soundCategory_master:0.0\nguiScale:1\nmaxFps:60\nfullscreen:false\nonboardAccessibility:false\n");
        List<String> cp=new ArrayList<>();cp.add(inContainer(cache,runtime.client()));for(Path lib:runtime.libraries())cp.add(inContainer(cache,lib));
        List<String> command=new ArrayList<>(List.of("docker","--context",options.dockerContext(),"run","--rm","--name",containerName,"-i","--platform","linux/amd64",
                "--network","none","--memory","3g","--cpus","4","-v",cache+":/mcicon/cache","-v",agentSnapshot+":/mcicon/worker.jar:ro",imageId));
        if(runtime.profile().equals("gui-26.3"))command.add(command.indexOf(imageId),"-e");
        if(runtime.profile().equals("gui-26.3"))command.add(command.indexOf(imageId),"SDL_VIDEO_FORCE_EGL=1");
        String java=runtime.javaMajor()==25?"/opt/java/openjdk/bin/java":"/opt/java/java"+runtime.javaMajor()+"/bin/java";
        String run=inContainer(cache,runDirectory);
        command.addAll(List.of(java,"-Xmx2G","-Djava.awt.headless=true","-Duser.timezone=UTC","-Djava.library.path="+inContainer(cache,runtime.natives()),
                "-javaagent:/mcicon/worker.jar="+run+"/agent.json","-cp",String.join(":",cp),runtime.metadata().path("mainClass").asText(),
                "--username","MCIconGenerator","--version",runtime.version().id(),"--gameDir",run+"/game",
                "--assetsDir",inContainer(cache,runtime.assets()),"--assetIndex",runtime.metadata().path("assetIndex").path("id").asText(),
                "--accessToken","0","--uuid","00000000000000000000000000000000","--userType","legacy","--width","320","--height","240","--demo","--disableMultiplayer","--disableChat"));
        ProcessBuilder builder=new ProcessBuilder(command).redirectError(runDirectory.resolve("worker.log").toFile());
        process=builder.start();output=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        shutdownHook=new Thread(this::terminate,"mcicon-cleanup-"+containerName);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        Thread reader=Thread.ofPlatform().daemon().name("mcicon-worker-reader").start(()-> {
            try(BufferedReader input=process.inputReader(StandardCharsets.UTF_8)) {
                String line;while((line=input.readLine())!=null) {
                    if(line.length()>16*1024*1024)throw new IOException("Worker message too large");
                    messages.put(Json.MAPPER.readTree(line));
                }
            }catch(Exception e){process.destroy();}
        });
        try {
            JsonNode hello=await(options.startupTimeout());
            if(!hello.path("type").asText().equals("ready")||hello.path("protocolVersion").asInt()!=1)throw new IOException("Worker protocol version mismatch");
            if(!hello.path("animationFrameZero").asBoolean()||!hello.path("contextProfile").asText().equals("fixed-v1"))
                throw new McIconException("WORKER_CONTEXT_MISMATCH","Worker did not install the required fixed-v1 context",1);
            renderer=hello.path("renderer").asText();
        }catch(Exception e){terminate();removeShutdownHook();throw e;}
    }
    static void validateAgentSnapshot(Path file) throws IOException {
        try(JarFile jar=new JarFile(file.toFile())) {
            var manifest=jar.getManifest();
            String premain=manifest==null?null:manifest.getMainAttributes().getValue("Premain-Class");
            if(premain==null||jar.getJarEntry(premain.replace('.','/')+".class")==null)
                throw new IOException("Worker JAR has no valid Premain-Class");
        } catch(IOException e) {throw new McIconException("WORKER_INVALID","Worker JAR is incomplete or invalid; finish the build before starting a job",1,e);}
    }
    static byte[] readAgent(Path file) throws IOException {
        try(InputStream input=Files.newInputStream(file)) {
            byte[] data=input.readNBytes(64*1024*1024+1);
            if(data.length>64*1024*1024)throw new IOException("Worker JAR exceeds 64 MiB");
            return data;
        }
    }
    public List<CatalogEntry> catalog() throws Exception {
        JsonNode result=request(Map.of("command","catalog"));List<CatalogEntry> entries=new ArrayList<>();
        for(JsonNode entry:result.path("entries"))entries.add(Json.MAPPER.treeToValue(entry,CatalogEntry.class));
        entries.sort(Comparator.comparing(CatalogEntry::id).thenComparing(CatalogEntry::status));return List.copyOf(entries);
    }
    public Path render(String id,int size) throws Exception {
        Path file=runDirectory.resolve("icon-"+UUID.randomUUID()+".png");
        JsonNode result=request(Map.of("command","render","id",id,"size",size,"path","/mcicon/cache/"+runDirectory.getParent().relativize(file).toString().replace('\\','/')));
        if(!Files.isRegularFile(file)||result.path("result").path("visiblePixels").asInt()==0)throw new IOException("Worker produced no image");
        return file;
    }
    private JsonNode request(Map<String,Object> values) throws Exception {
        Map<String,Object> request=new TreeMap<>(values);String id=UUID.randomUUID().toString();request.put("requestId",id);
        output.write(Json.MAPPER.writeValueAsString(request).replace("\n",""));output.newLine();output.flush();
        JsonNode result=await(options.itemTimeout());
        if(!result.path("requestId").asText().equals(id))throw new IOException("Mismatched worker request ID");
        if(result.path("type").asText().equals("error"))throw new McIconException(result.path("code").asText(),result.path("message").asText(),3);
        if(!result.path("type").asText().equals("result"))throw new IOException("Unexpected worker response type");
        return result;
    }
    private JsonNode await(Duration timeout) throws Exception {
        long end=System.nanoTime()+timeout.toNanos();
        while(System.nanoTime()<end) {
            token.check();JsonNode message=messages.poll(100,TimeUnit.MILLISECONDS);if(message!=null)return message;
            if(!process.isAlive())throw new McIconException("WORKER_EXITED","Worker exited before replying; inspect the cache worker log",1);
        }
        terminate();throw new McIconException("WORKER_TIMEOUT","Worker did not reply within its timeout",1);
    }
    private String inContainer(Path cache,Path file) {return "/mcicon/cache/"+cache.relativize(file.toAbsolutePath().normalize()).toString().replace('\\','/');}
    public String renderer(){return renderer;}
    public String agentSha256(){return agentSha256;}
    public String imageId(){return imageId;}
    private static String resolveImage(WorkerOptions options,CancellationToken token) throws Exception {
        token.check();
        Process inspect=new ProcessBuilder("docker","--context",options.dockerContext(),"image","inspect",options.dockerImage(),"--format","{{.Id}}")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if(!inspect.waitFor(15,TimeUnit.SECONDS))throw new McIconException("DOCKER_UNAVAILABLE","Docker image inspection timed out",1);
            token.check();String id=new String(inspect.getInputStream().readNBytes(1024),StandardCharsets.UTF_8).trim();
            if(inspect.exitValue()!=0||!id.matches("sha256:[0-9a-f]{64}"))throw new McIconException("DOCKER_IMAGE_UNAVAILABLE","Build the rendering image in the selected Docker context first",1);
            return id;
        }finally{if(inspect.isAlive())inspect.destroyForcibly();}
    }
    private void terminate() {
        process.destroyForcibly();
        boolean interrupted=Thread.interrupted();
        try {
            Process cleanup=new ProcessBuilder("docker","--context",options.dockerContext(),"rm","-f",containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!cleanup.waitFor(10,TimeUnit.SECONDS))cleanup.destroyForcibly();
        }catch(IOException ignored){}catch(InterruptedException e){interrupted=true;}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private void removeShutdownHook() {
        try {Runtime.getRuntime().removeShutdownHook(shutdownHook);}catch(IllegalStateException ignored){}
    }
    @Override public void close() {
        try {if(process.isAlive()) {request(Map.of("command","shutdown"));if(!process.waitFor(10,TimeUnit.SECONDS))terminate();}}
        catch(Exception e){terminate();}
        removeShutdownHook();
        try {output.close();}catch(IOException ignored){}
        try {Files.deleteIfExists(runDirectory.resolve("worker-agent.jar"));}catch(IOException ignored){}
        try(var files=Files.list(runDirectory)){for(Path file:files.filter(p->p.getFileName().toString().startsWith("icon-")).toList())Files.deleteIfExists(file);}
        catch(IOException ignored){}
    }
}
