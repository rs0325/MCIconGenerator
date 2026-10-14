package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class Diagnostics {
    public static DiagnosticReport doctor(VersionService service, String version, WorkerOptions worker,CancellationToken token) throws IOException {
        VersionInfo exact = service.resolve(version);
        int javaMajor = RuntimePreparer.runtimeJavaMajor(service.metadata(exact).path("javaVersion").path("majorVersion").asInt(exact.id().startsWith("1.13") || exact.id().startsWith("1.14") ? 8 : 21));
        List<DiagnosticReport.Check> checks = new ArrayList<>();
        checks.add(new DiagnosticReport.Check("core_java", Runtime.version().feature() >= 21, "Java " + Runtime.version().feature()));
        boolean adapter;
        try {RuntimePreparer.profileFor(exact.id());adapter=true;}catch(McIconException unavailable){adapter=false;}
        checks.add(new DiagnosticReport.Check("adapter", adapter, adapter ? "experimental; canonical context is pending" : "ADAPTER_UNAVAILABLE"));
        checks.add(new DiagnosticReport.Check("agent", Files.isRegularFile(worker.agentJar()), "Rendering agent must exist"));
        List<String> docker = List.of("docker", "--context", worker.dockerContext());
        checks.add(command("docker_image", docker, List.of("image", "inspect", worker.dockerImage(), "--format", "{{.Os}}/{{.Architecture}} {{.Id}}"), 15,token));
        try {
            WorkerJava java=workerJava(worker,worker.dockerImage(),javaMajor,token);
            checks.add(new DiagnosticReport.Check("worker_java",true,"Java "+java.version()));
        }catch(McIconException failure){checks.add(new DiagnosticReport.Check("worker_java",false,failure.code()));}
        token.check();checks.add(command("mesa", docker, List.of("run", "--rm", "--platform", "linux/amd64", "--network", "none", worker.dockerImage(), "glxinfo", "-B"), 45,token));
        token.check();
        return new DiagnosticReport(exact.id(), checks.stream().allMatch(DiagnosticReport.Check::passed), checks);
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
        String executable=worker.javaHome()==null?(minimum==25?"/opt/java/openjdk/bin/java":"/opt/java/java"+minimum+"/bin/java")
                :worker.javaHome().replaceAll("/+$","")+"/bin/java";
        var check=command("worker_java",List.of("docker","--context",worker.dockerContext()),
                List.of("run","--rm","--platform","linux/amd64","--network","none","--entrypoint",executable,image,"-version"),45,token);
        token.check();
        if(!check.passed())throw new McIconException("WORKER_JAVA_UNAVAILABLE","The selected Java is unavailable inside the Docker image",1);
        return parseJava(executable,check.detail(),minimum);
    }
    private static DiagnosticReport.Check command(String name, List<String> prefix, List<String> args, int seconds,CancellationToken token) {
        List<String> command = new ArrayList<>(prefix); command.addAll(args);
        String container=null;
        if(args.getFirst().equals("run")) {
            container="mcicon-doctor-"+UUID.randomUUID();
            command.add(prefix.size()+1,"--name");command.add(prefix.size()+2,container);
        }
        Path log = null;
        Process process = null;
        try {
            log = Files.createTempFile("mcicon-diagnostic-", ".log");
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)) {
                token.check();
                if(System.nanoTime()>=deadline){process.destroyForcibly();return new DiagnosticReport.Check(name,false,"Diagnostic timed out");}
            }
            String detail;
            try (InputStream input = Files.newInputStream(log)) { detail = new String(input.readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8).trim(); }
            return new DiagnosticReport.Check(name, process.exitValue() == 0, detail);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DiagnosticReport.Check(name, false, e.getClass().getSimpleName());
        } finally {
            if(process != null && process.isAlive()) process.destroyForcibly();
            if(container!=null) {
                boolean interrupted=Thread.interrupted();
                try {
                    List<String> cleanup=new ArrayList<>(prefix);cleanup.addAll(List.of("rm","-f",container));
                    Process removal=new ProcessBuilder(cleanup).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if(!removal.waitFor(10,TimeUnit.SECONDS))removal.destroyForcibly();
                }catch(IOException ignored){}catch(InterruptedException e){interrupted=true;}
                finally{if(interrupted)Thread.currentThread().interrupt();}
            }
            if(log != null)try { Files.deleteIfExists(log); } catch(IOException ignored) {}
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
