package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class Diagnostics {
    public static DiagnosticReport doctor(VersionService service, String version, WorkerOptions worker,CancellationToken token) throws IOException {
        return doctor(service,version,worker,token,WorkerLaunch.docker(worker.dockerContext()));
    }
    public static DiagnosticReport doctor(VersionService service,String version,WorkerOptions worker,CancellationToken token,WorkerLaunch launch) throws IOException {
        VersionInfo exact = service.resolve(version);
        int javaMajor=javaMajor(service,exact);
        List<DiagnosticReport.Check> checks = new ArrayList<>();
        checks.add(new DiagnosticReport.Check("core_java", Runtime.version().feature() >= 21, "Java " + Runtime.version().feature()));
        boolean adapter;
        try {RuntimePreparer.profileFor(exact.id());adapter=true;}catch(McIconException unavailable){adapter=false;}
        checks.add(new DiagnosticReport.Check("adapter", adapter, adapter ? exact.support() : "ADAPTER_UNAVAILABLE"));
        checks.add(new DiagnosticReport.Check("agent", Files.isRegularFile(worker.agentJar()), "Rendering agent must exist"));
        if(launch.managed()&&!FilesSupport.hash(WorkerClient.readAgent(worker.agentJar())).equals(launch.expectedAgentSha256()))throw new McIconException("MANAGED_RUNTIME_INVALID","管理agentのhashが一致しません",1);
        var image=command("docker_image", launch, List.of("image", "inspect", worker.dockerImage(), "--format", "{{.Os}}/{{.Architecture}} {{.Id}}"), 15,token);var imageCheck=image.check();
        if(launch.managed()&&imageCheck.passed()&&!image.stdout().trim().equals("linux/amd64 "+launch.expectedImageId()))throw new McIconException("MANAGED_RUNTIME_INVALID","管理imageのID/platformが一致しません",1);
        checks.add(imageCheck);
        try {
            WorkerJava java=workerJava(worker,worker.dockerImage(),javaMajor,token,launch);
            checks.add(new DiagnosticReport.Check("worker_java",true,"Java "+java.version()));
        }catch(McIconException failure){if(failure.code().equals("MANAGED_RUNTIME_INVALID"))throw failure;checks.add(new DiagnosticReport.Check("worker_java",false,failure.code()));}
        token.check();checks.add(command("mesa", launch, List.of("run", "--rm", "--platform", "linux/amd64", "--network", "none", worker.dockerImage(), "glxinfo", "-B"), 45,token).check());
        token.check();
        return new DiagnosticReport(exact.id(), checks.stream().allMatch(DiagnosticReport.Check::passed), checks);
    }
    public static int javaMajor(VersionService service,VersionInfo version) throws IOException {
        return RuntimePreparer.runtimeJavaMajor(service.metadata(version).path("javaVersion").path("majorVersion").asInt(version.id().startsWith("1.13") || version.id().startsWith("1.14") ? 8 : 21));
    }
    record WorkerJava(String executable,int major,String version) {
        Map<String,Object> settings() {return Map.of("javaMajor",major,"javaVersion",version,"javaSelectionSha256",FilesSupport.hash(executable.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    }
    static WorkerJava parseJava(String executable,String detail,int minimum) {
        var match=Pattern.compile("(?:java|openjdk) version \"([0-9][0-9A-Za-z.+_-]{0,79})\"").matcher(detail);
        if(!match.find())throw new McIconException("WORKER_JAVA_UNAVAILABLE","The selected image Java did not report its version",1);
        String version=match.group(1);var number=Pattern.compile("^(?:1[.])?([0-9]+)").matcher(version);number.find();
        int major;
        try {major=Integer.parseInt(number.group(1));}
        catch(NumberFormatException invalid){throw new McIconException("WORKER_JAVA_UNAVAILABLE","The selected image Java reported an invalid major",1);}
        if(major<minimum)throw new McIconException("WORKER_JAVA_INCOMPATIBLE","The selected image Java is older than the required major "+minimum,2);
        return new WorkerJava(executable,major,version);
    }
    static WorkerJava workerJava(WorkerOptions worker,String image,int minimum,CancellationToken token) {
        return workerJava(worker,image,minimum,token,WorkerLaunch.docker(worker.dockerContext()));
    }
    static WorkerJava workerJava(WorkerOptions worker,String image,int minimum,CancellationToken token,WorkerLaunch launch) {
        String executable=worker.javaHome()==null?(minimum==25?"/opt/java/openjdk/bin/java":"/opt/java/java"+minimum+"/bin/java")
                :worker.javaHome().replaceAll("/+$","")+"/bin/java";
        var check=command("worker_java",launch,
                List.of("run","--rm","--platform","linux/amd64","--network","none","--entrypoint",executable,image,"-version"),45,token);
        token.check();
        if(!check.check().passed())throw new McIconException("WORKER_JAVA_UNAVAILABLE","The selected Java is unavailable inside the Docker image",1);
        WorkerJava java=parseJava(executable,check.stderr(),minimum);
        if(launch.managed()) {
            String expected=launch.javaHashes().get(minimum);
            var hash=command("worker_java_hash",launch,List.of("run","--rm","--platform","linux/amd64","--network","none","--entrypoint","sha256sum",image,executable),45,token);
            token.check();
            if(java.major()!=minimum||expected==null||!hash.check().passed()||!hash.stdout().equals(expected+"  "+executable+"\n"))throw new McIconException("MANAGED_RUNTIME_INVALID","管理Javaのmajor/hashがdescriptorと一致しません",1);
        }
        return java;
    }
    private record CommandResult(DiagnosticReport.Check check,String stdout,String stderr) {}
    private static CommandResult command(String name, WorkerLaunch launch, List<String> args, int seconds,CancellationToken token) {
        List<String> command = new ArrayList<>(args);
        String container=null;
        if(args.getFirst().equals("run")) {
            container="mcicon-doctor-"+UUID.randomUUID();
            command.add(1,"--name");command.add(2,container);
        }
        Path log = null,errorLog = null;
        Process process = null;
        try {
            log = Files.createTempFile("mcicon-diagnostic-", ".log");errorLog=Files.createTempFile("mcicon-diagnostic-error-", ".log");
            process = launch.command(command).redirectOutput(log.toFile()).redirectError(errorLog.toFile()).start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)) {
                token.check();
                if(System.nanoTime()>=deadline){process.destroyForcibly();return new CommandResult(new DiagnosticReport.Check(name,false,"Diagnostic timed out"),"","");}
            }
            long size=Files.size(log),errorSize=Files.size(errorLog);
            if(size>65536||errorSize>65536-size)return new CommandResult(new DiagnosticReport.Check(name,false,"Diagnostic応答が上限を超えました"),"","");
            String stdout,stderr;
            try(InputStream output=Files.newInputStream(log);InputStream error=Files.newInputStream(errorLog)) {
                byte[] out=output.readNBytes((int)size),err=error.readNBytes((int)errorSize);
                if(out.length!=size||err.length!=errorSize||Files.size(log)!=size||Files.size(errorLog)!=errorSize)return new CommandResult(new DiagnosticReport.Check(name,false,"Diagnostic応答が変化しました"),"","");
                stdout=new String(out,java.nio.charset.StandardCharsets.UTF_8);stderr=new String(err,java.nio.charset.StandardCharsets.UTF_8);
            }
            return new CommandResult(new DiagnosticReport.Check(name,process.exitValue()==0,(stdout+stderr).trim()),stdout,stderr);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new CommandResult(new DiagnosticReport.Check(name,false,e.getClass().getSimpleName()),"","");
        } finally {
            if(process != null && process.isAlive()) process.destroyForcibly();
            if(container!=null) {
                boolean interrupted=Thread.interrupted();
                try {
                    Process removal=launch.command(List.of("rm","-f",container)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if(!removal.waitFor(10,TimeUnit.SECONDS))removal.destroyForcibly();
                }catch(IOException ignored){}catch(InterruptedException e){interrupted=true;}
                finally{if(interrupted)Thread.currentThread().interrupt();}
            }
            if(log != null)try { Files.deleteIfExists(log); } catch(IOException ignored) {}
            if(errorLog != null)try { Files.deleteIfExists(errorLog); } catch(IOException ignored) {}
        }
    }
    public static CacheReport cache(Path root, boolean verify, CancellationToken token) throws IOException {
        root = root.toAbsolutePath().normalize();
        if(!Files.exists(root)) return new CacheReport(0,0,0,List.of());
        long bytes=0; int files=0,verified=0;
        List<String> failures=new ArrayList<>();
        try(var walk=Files.walk(root)) {
            for(Path file:walk.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                token.check();
                try {
                bytes+=Files.size(file);files++;
                if(verify && file.getFileName().toString().endsWith(".sha256")) {
                    String relative=root.relativize(file).toString();
                    Path source=FilesSupport.safeResolve(root,relative.substring(0,relative.length()-7));
                    if(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) && Files.readString(file).trim().equals(FilesSupport.hash(source,"SHA-256")))verified++;
                    else failures.add(relative.substring(0,relative.length()-7));
                }
                }catch(NoSuchFileException transientWorkerFile){ /* A concurrent worker completed and removed its temporary file. */ }
            }
        }
        return new CacheReport(bytes,files,verified,failures);
    }
    private Diagnostics() {}
}
