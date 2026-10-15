import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 同一bundleの既存FileLock回帰を実ホストで実行し、所有Javaだけを期限内に回収する。 */
public final class HostFileLockProbe {
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("hang-child")){new CountDownLatch(1).await();return;}
        if(Runtime.version().feature()!=21)throw new IllegalStateException("このホスト検証にはJava21が必要");
        Path report=Path.of(args[0]);long seconds=Long.parseLong(args[1]);
        String mode=args[2];String expectedInventory=args[3];
        if(!Set.of("normal","deadline","deadline-reap-error").contains(mode))throw new IllegalArgumentException("不明な検証mode");
        if(seconds<=0)throw new IllegalArgumentException("期限は正数が必要");
        var mapper=new ObjectMapper();var base=new LinkedHashMap<String,Object>();
        base.put("javaVersion",System.getProperty("java.version"));
        base.put("os",System.getProperty("os.name"));base.put("arch",System.getProperty("os.arch"));
        base.put("filesystem",Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).type());
        byte[] inventoryBytes=Files.readAllBytes(Path.of("file-hashes.json"));
        String inventoryHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inventoryBytes));
        if(!inventoryHash.equals(expectedInventory))throw new IllegalStateException("bundle hash不一致: inventory");
        var inventory=mapper.readTree(inventoryBytes);
        if(!inventory.isObject()||inventory.isEmpty())throw new IllegalStateException("bundle inventoryが空またはobjectではない");
        var actualFiles=new TreeSet<String>();
        for(String directory:List.of("classes","lib")) {
            try(var paths=Files.walk(Path.of(directory))) {
                paths.filter(Files::isRegularFile).forEach(p->actualFiles.add(p.toString().replace(File.separatorChar,'/')));
            }
        }
        if(!actualFiles.equals(inventory.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet())))throw new IllegalStateException("bundle classpath集合がinventoryと異なる");
        var expectedClasspath=new HashSet<Path>();expectedClasspath.add(Path.of("classes").toAbsolutePath().normalize());
        try(var paths=Files.list(Path.of("lib"))) {paths.filter(p->p.toString().endsWith(".jar")).forEach(p->expectedClasspath.add(p.toAbsolutePath().normalize()));}
        var actualClasspath=Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))).map(p->Path.of(p).toAbsolutePath().normalize()).collect(Collectors.toSet());
        if(!actualClasspath.equals(expectedClasspath))throw new IllegalStateException("未登録のclasspath");
        var hashes=new TreeMap<String,String>();
        var fields=inventory.properties().iterator();
        while(fields.hasNext()) {
            var entry=fields.next();Path file=Path.of(entry.getKey());
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            if(!actual.equals(entry.getValue().asText()))throw new IllegalStateException("bundle hash不一致: "+file);
            hashes.put(entry.getKey(),actual);
        }
        base.put("bundleHashes",hashes);base.put("inventorySha256",inventoryHash);base.put("pid",ProcessHandle.current().pid());
        var timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"qa-deadline");t.setDaemon(true);return t;});
        var deadline=timer.schedule(()-> {
            var children=ProcessHandle.current().descendants().toList();
            var failed=new LinkedHashMap<>(base);failed.put("passed",false);failed.put("failure","DEADLINE");
            failed.put("ownedChildren",children.stream().map(ProcessHandle::pid).toList());
            children.forEach(ProcessHandle::destroyForcibly);
            try {
                CompletableFuture.allOf(children.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);
                if(mode.equals("deadline-reap-error"))throw new IllegalStateException("QA reap例外注入");
            } catch(Exception e){failed.put("cleanupFailure",e.toString());}
            failed.put("remainingOwnedChildren",children.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList());
            try {mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),failed);}
            catch(Exception e){e.printStackTrace(System.err);}
            Runtime.getRuntime().halt(124);
        },seconds,TimeUnit.SECONDS);
        try {
            if(mode.startsWith("deadline")) {
                new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),HostFileLockProbe.class.getName(),"hang-child").inheritIO().start();
                new CountDownLatch(1).await();
            }
            var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass("io.github.rs0325.mcicon.internal.AssetStoreLockTest")).build());
            var summary=listener.getSummary();
            var remaining=ProcessHandle.current().descendants().toList();
            boolean passed=summary.getTestsFoundCount()==3&&summary.getTestsSucceededCount()==3&&summary.getTestsFailedCount()==0&&summary.getTestsSkippedCount()==0&&summary.getTestsAbortedCount()==0&&summary.getContainersFailedCount()==0&&summary.getContainersAbortedCount()==0&&remaining.isEmpty();
            var result=new LinkedHashMap<>(base);result.put("passed",passed);result.put("testsFound",summary.getTestsFoundCount());
            result.put("testsSucceeded",summary.getTestsSucceededCount());result.put("testsFailed",summary.getTestsFailedCount());
            result.put("testsSkipped",summary.getTestsSkippedCount());result.put("testsAborted",summary.getTestsAbortedCount());
            result.put("containersFailed",summary.getContainersFailedCount());result.put("containersAborted",summary.getContainersAbortedCount());
            result.put("failures",summary.getFailures().stream().map(f->Map.of("test",f.getTestIdentifier().getDisplayName(),"exception",f.getException().toString())).toList());
            result.put("remainingOwnedChildren",remaining.stream().map(ProcessHandle::pid).toList());
            remaining.forEach(ProcessHandle::destroyForcibly);
            try {CompletableFuture.allOf(remaining.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);}
            catch(Exception e){result.put("cleanupFailure",e.toString());result.put("passed",false);passed=false;}
            result.put("remainingOwnedChildrenAfterCleanup",remaining.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList());
            mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),result);
            if(!passed)System.exit(1);
        } finally {deadline.cancel(false);timer.shutdownNow();}
    }
}
