import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

/** 実atomicWriteの途中終了とraw再開を、同じbundleの所有actorだけで検査する。 */
public final class HostAtomicRecoveryProbe {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<Process> OWNED=new CopyOnWriteArrayList<>();
    private static final Map<String,Object> RESULT=new ConcurrentHashMap<>();
    private static Path report;
    private static boolean terminal;

    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("hang-child")) {System.out.println("READY_FOR_ATOMIC_WRITE");System.out.flush();new CountDownLatch(1).await();return;}
        if(args.length==1&&args[0].equals("exit-child"))return;
        Path output=Path.of(args[0]).toAbsolutePath().normalize();
        Path evidence=Path.of(args[1]).toAbsolutePath().normalize();
        long seconds=Long.parseLong(args[2]);String mode=args[3],inventoryHash=args[4];
        if(seconds<=0||!Set.of("normal","deadline","deadline-reap-error","natural-exit","no-part","terminal-success-first","terminal-deadline-first").contains(mode))throw new IllegalArgumentException("期限またはmodeが不正");
        if(Files.exists(output)||Files.exists(evidence)||output.startsWith(evidence)||evidence.startsWith(output))throw new IllegalArgumentException("outputとevidenceには重ならない新規directoryが必要");
        Files.createDirectories(evidence);report=evidence.resolve("report.json");
        RESULT.put("passed",false);RESULT.put("mode",mode);RESULT.put("javaVersion",System.getProperty("java.version"));
        RESULT.put("os",System.getProperty("os.name"));RESULT.put("osVersion",System.getProperty("os.version"));RESULT.put("arch",System.getProperty("os.arch"));
        var timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"atomic-qa-deadline");t.setDaemon(true);return t;});
        var deadlineCommitted=new CountDownLatch(1);var lateSuccessAttempted=new CountDownLatch(1);
        var deadline=timer.schedule(()-> {
            if(finish(false,"DEADLINE",mode.equals("deadline-reap-error"))) {
                if(mode.equals("terminal-deadline-first")) {
                    deadlineCommitted.countDown();
                    try {lateSuccessAttempted.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                }
                Runtime.getRuntime().halt(124);
            }
        },seconds,TimeUnit.SECONDS);
        boolean success=false;String failure=null;
        try {
            if(Runtime.version().feature()!=21)throw new IllegalStateException("Java21が必要");
            verifyBundle(inventoryHash);
            if(mode.startsWith("terminal-")) {
                RESULT.put("terminalFixture",true);
                if(mode.equals("terminal-success-first")) {
                    finish(true,null,false);
                    deadline.get(5,TimeUnit.SECONDS);
                    System.out.println("LATE_DEADLINE_REJECTED");
                } else {
                    if(!deadlineCommitted.await(10,TimeUnit.SECONDS))throw new IllegalStateException("期限確定fixtureの待機超過");
                    if(finish(true,null,false))throw new IllegalStateException("期限後に成功を再確定した");
                    System.out.println("LATE_SUCCESS_REJECTED");System.out.flush();lateSuccessAttempted.countDown();
                }
                return;
            }
            Files.createDirectories(output);
            RESULT.put("filesystem",Files.getFileStore(output).type());
            Path root=output.resolve("java/synthetic/raw");Files.createDirectories(root);
            byte[] userBytes="利用者ファイルを保持する\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            List<Path> userFiles=List.of(root.resolve("user.bin"),root.resolve(".mcicon-user.part"));
            for(Path file:userFiles)Files.write(file,userBytes,StandardOpenOption.CREATE_NEW);
            if(mode.startsWith("deadline")) {
                start(evidence,"deadline",HostAtomicRecoveryProbe.class.getName(),"hang-child");
                new CountDownLatch(1).await();
            }
            Process actor;
            if(mode.equals("natural-exit"))actor=start(evidence,"write",HostAtomicRecoveryProbe.class.getName(),"exit-child");
            else if(mode.equals("no-part"))actor=start(evidence,"write",HostAtomicRecoveryProbe.class.getName(),"hang-child");
            else actor=start(evidence,"write","AtomicCrashProbe","write",output.toString());
            Path stdout=evidence.resolve("write.stdout.log");
            long readyUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(mode.equals("no-part")?1:30);
            while(!Files.readString(stdout).contains("READY_FOR_ATOMIC_WRITE")) {
                if(!actor.isAlive())throw new IllegalStateException("READY前にactorが終了: "+actor.exitValue());
                if(System.nanoTime()>=readyUntil)throw new IllegalStateException("READY未観測");
                Thread.sleep(1);
            }
            RESULT.put("readyObserved",true);
            Path png=root.resolve("assets/minecraft/textures/item/sample.png");
            Path parent=png.getParent();long partUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(mode.equals("no-part")?1:10);
            Path observed=null;long observedSize=0;
            while(observed==null) {
                if(!actor.isAlive())throw new IllegalStateException("part観測前にactorが終了");
                if(Files.isDirectory(parent)) {
                    try(var files=Files.list(parent)) {
                        for(Path file:files.filter(HostAtomicRecoveryProbe::reservedPart).toList()) {
                            try {long size=Files.size(file);if(size>0){observed=file;observedSize=size;break;}}
                            catch(NoSuchFileException e) {throw new IllegalStateException("観測前にpartが消失",e);}
                        }
                    }
                }
                if(observed==null) {
                    if(System.nanoTime()>=partUntil)throw new IllegalStateException("実part未観測");
                    Thread.sleep(1);
                }
            }
            RESULT.put("observedPart",root.relativize(observed).toString().replace(File.separatorChar,'/'));
            RESULT.put("observedPartBytes",observedSize);
            if(!actor.isAlive())throw new IllegalStateException("強制終了前にactorが自然終了");
            actor.destroyForcibly();
            if(!actor.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("actor回収期限超過");
            RESULT.put("writeExit",actor.exitValue());
            if(actor.exitValue()==0||Files.exists(png))throw new IllegalStateException("atomicWriteが完了したため中断不成立");
            List<Path> orphaned=parts(root);
            if(!orphaned.contains(observed))throw new IllegalStateException("観測partが残らないため中断不成立");
            RESULT.put("orphanedPartCount",orphaned.size());RESULT.put("orphanedPartBytes",Files.size(observed));
            Path manifest=root.resolve("manifest.json");
            Files.copy(manifest,evidence.resolve("interrupted-manifest.json"));
            JsonNode interrupted=JSON.readTree(manifest.toFile());
            if(interrupted.path("complete").asBoolean(true)||interrupted.path("counts").path("failed").asInt()!=1
                    ||interrupted.path("entries").size()!=1||!interrupted.path("entries").get(0).path("path").asText().equals("assets/minecraft/textures/item/sample.png"))
                throw new IllegalStateException("予約manifestが未完了ではない");
            RESULT.put("interruptedCounts",interrupted.get("counts"));
            Process resume=start(evidence,"resume","AtomicCrashProbe","resume",output.toString());
            if(!resume.waitFor(30,TimeUnit.SECONDS))throw new IllegalStateException("resume期限超過");
            RESULT.put("resumeExit",resume.exitValue());
            if(resume.exitValue()!=0)throw new IllegalStateException("resume失敗");
            JsonNode recovered=JSON.readTree(manifest.toFile());
            if(!recovered.path("complete").asBoolean()||recovered.path("counts").path("failed").asInt(-1)!=0
                    ||recovered.path("counts").path("succeeded").asInt()!=1||recovered.path("entries").size()!=1)
                throw new IllegalStateException("復旧manifest不一致");
            byte[] original;
            try(var zip=new ZipFile(output.resolve("fixture.jar").toFile());var input=zip.getInputStream(zip.getEntry("assets/minecraft/textures/item/sample.png"))) {original=input.readAllBytes();}
            byte[] actual=Files.readAllBytes(png);String pngHash=hash(actual);
            JsonNode entry=recovered.path("entries").get(0);
            if(!Arrays.equals(original,actual)||!entry.path("sha256").asText().equals(pngHash)||!entry.path("status").asText().equals("success"))
                throw new IllegalStateException("fixture PNGのbytes/hash不一致");
            RESULT.put("pngSha256",pngHash);RESULT.put("pngBytes",actual.length);RESULT.put("fixtureSha256",hash(Files.readAllBytes(output.resolve("fixture.jar"))));
            RESULT.put("complete",true);RESULT.put("recoveredCounts",recovered.get("counts"));
            RESULT.put("reservedPartsAfterResume",parts(root).size());
            if(!parts(root).isEmpty())throw new IllegalStateException("復旧後に予約partが残存");
            for(Path file:userFiles)if(!Arrays.equals(userBytes,Files.readAllBytes(file)))throw new IllegalStateException("利用者ファイルが変更された");
            RESULT.put("preservedUserFiles",userFiles.size());RESULT.put("userFileSha256",hash(userBytes));
            try(var channel=FileChannel.open(root.resolve(".export.lock"),StandardOpenOption.WRITE);var lock=channel.tryLock()) {
                if(lock==null)throw new IllegalStateException("同じ出力lockを再取得できない");
                RESULT.put("outputLockReacquired",true);
            }
            Files.copy(manifest,evidence.resolve("recovered-manifest.json"));
            success=true;
        } catch(Exception e) {failure=e.toString();e.printStackTrace(System.err);}
        finally {
            finish(success,failure,false);
            deadline.cancel(false);timer.shutdownNow();
            System.exit(Boolean.TRUE.equals(RESULT.get("passed"))?0:"DEADLINE".equals(RESULT.get("failure"))?124:1);
        }
    }

    private static Process start(Path evidence,String label,String main,String... arguments) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx512m","-cp",System.getProperty("java.class.path"),main));
        command.addAll(List.of(arguments));
        synchronized(RESULT) {
            if(terminal)throw new IllegalStateException("検証終端後のactor起動");
            Process process=new ProcessBuilder(command).redirectOutput(evidence.resolve(label+".stdout.log").toFile()).redirectError(evidence.resolve(label+".stderr.log").toFile()).start();
            OWNED.add(process);return process;
        }
    }

    private static boolean finish(boolean passed,String failure,boolean injectCleanupFailure) {
        synchronized(RESULT) {
            if(terminal)return false;
            terminal=true;RESULT.put("passed",passed);
            if(failure!=null)RESULT.put("failure",failure);
            RESULT.put("terminalWinner",passed?"SUCCESS":"DEADLINE".equals(failure)?"DEADLINE":"FAILURE");
            cleanup(injectCleanupFailure);writeReport();return true;
        }
    }

    private static boolean reservedPart(Path file) {return file.getFileName().toString().matches("\\.mcicon-[0-9]+\\.part");}
    private static List<Path> parts(Path root) throws Exception {
        try(var paths=Files.walk(root)) {return paths.filter(Files::isRegularFile).filter(HostAtomicRecoveryProbe::reservedPart).toList();}
    }
    private static String hash(byte[] data) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}

    private static void verifyBundle(String expected) throws Exception {
        byte[] inventory=Files.readAllBytes(Path.of("file-hashes.json"));String actual=hash(inventory);
        if(!actual.equals(expected))throw new IllegalStateException("inventory SHA256不一致");
        JsonNode entries=JSON.readTree(inventory);
        if(!entries.isObject()||entries.isEmpty())throw new IllegalStateException("inventoryが空またはobjectではない");
        var files=new TreeSet<String>();
        for(String directory:List.of("classes","lib","source")) {
            try(var paths=Files.walk(Path.of(directory))) {paths.filter(Files::isRegularFile).forEach(p->files.add(p.toString().replace(File.separatorChar,'/')));}
        }
        files.add("run-windows.ps1");
        if(!files.equals(entries.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet())))throw new IllegalStateException("bundleのファイル集合不一致");
        var classpath=new HashSet<Path>();classpath.add(Path.of("classes").toAbsolutePath().normalize());
        try(var jars=Files.list(Path.of("lib"))) {jars.filter(p->p.toString().endsWith(".jar")).forEach(p->classpath.add(p.toAbsolutePath().normalize()));}
        var actualClasspath=Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))).map(p->Path.of(p).toAbsolutePath().normalize()).collect(Collectors.toSet());
        if(!actualClasspath.equals(classpath))throw new IllegalStateException("未登録classpath");
        var hashes=new TreeMap<String,String>();
        for(var entry:entries.properties()) {
            String digest=hash(Files.readAllBytes(Path.of(entry.getKey())));
            if(!digest.equals(entry.getValue().asText()))throw new IllegalStateException("bundle SHA256不一致: "+entry.getKey());
            hashes.put(entry.getKey(),digest);
        }
        RESULT.put("inventorySha256",actual);RESULT.put("bundleHashes",hashes);
    }

    private static void cleanup(boolean injectFailure) {
        var alive=OWNED.stream().filter(Process::isAlive).toList();
        RESULT.put("cleanupKilledOwnedActors",alive.stream().map(Process::pid).toList());
        alive.forEach(Process::destroyForcibly);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {
            for(Process process:OWNED)if(!process.waitFor(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS))throw new IllegalStateException("所有actor回収期限超過");
            if(injectFailure)throw new IllegalStateException("QA回収失敗注入");
        } catch(Exception e) {RESULT.put("cleanupFailure",e.toString());RESULT.put("passed",false);}
        var remaining=OWNED.stream().filter(Process::isAlive).map(Process::pid).toList();
        RESULT.put("ownedActorPids",OWNED.stream().map(Process::pid).toList());RESULT.put("remainingOwnedActors",remaining);
        if(!remaining.isEmpty())RESULT.put("passed",false);
    }

    private static void writeReport() {
        try {JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),RESULT);}
        catch(Exception e) {e.printStackTrace(System.err);Runtime.getRuntime().halt(2);}
    }
}
