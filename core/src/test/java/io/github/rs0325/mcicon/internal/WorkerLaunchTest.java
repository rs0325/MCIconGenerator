package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerLaunchTest {
    @TempDir Path root;
    @Test void explicitDockerPrefixIsUnchangedAndManagedEnvironmentIsPerJob() {
        assertEquals(List.of("docker","--context","chosen","image","inspect","pinned"),WorkerLaunch.docker("chosen").command(List.of("image","inspect","pinned")).command());
        var launch=new WorkerLaunch(List.of(root.resolve("helper").toString(),"docker","--"),Map.of("MCICON_JOB","one"),"sha256:"+"a".repeat(64),"a".repeat(64),Map.of(),Map.of());
        var command=launch.command(List.of("run","literal ; $value"));assertEquals(Map.of("MCICON_JOB","one"),command.environment());assertEquals("literal ; $value",command.command().getLast());
        assertEquals(List.of("docker","--context","chosen"),WorkerLaunch.docker("chosen").prefix());
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void managedJavaProbeAndCleanupUseSamePrefixAndRejectHashMismatch() throws Exception {
        String hash="a".repeat(64);Path helper=root.resolve("helper"),log=root.resolve("commands");
        Files.writeString(helper,"#!/bin/sh\nprintf '%s\\n' \"$*\" >> '"+log+"'\ncase \"$*\" in *sha256sum*) printf '%s  /opt/java/java21/bin/java\\n' '"+hash+"';; *-version*) printf 'openjdk version \\\"21.0.9\\\"\\n' >&2;; esac\n");assertTrue(helper.toFile().setExecutable(true));
        WorkerOptions options=new WorkerOptions(root.resolve("agent.jar"),"unused","sha256:"+hash,Duration.ofSeconds(1),Duration.ofSeconds(1),"/opt/java/java21",3072,2048);
        WorkerLaunch launch=new WorkerLaunch(List.of(helper.toString(),"--lease","fixture-lease","docker","--"),Map.of(),options.dockerImage(),hash,Map.of(21,hash),Map.of());
        assertEquals(21,Diagnostics.workerJava(options,options.dockerImage(),21,new CancellationToken(),launch).major());
        String commands=Files.readString(log);assertTrue(commands.contains("--lease fixture-lease docker -- run"));assertTrue(commands.contains("--lease fixture-lease docker -- rm -f mcicon-doctor-"));assertTrue(commands.contains("sha256sum"));
        WorkerLaunch wrong=new WorkerLaunch(launch.prefix(),Map.of(),options.dockerImage(),hash,Map.of(21,"b".repeat(64)),Map.of());
        assertEquals("MANAGED_RUNTIME_INVALID",assertThrows(McIconException.class,()->Diagnostics.workerJava(options,options.dockerImage(),21,new CancellationToken(),wrong)).code());
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void checksumUsesOnlyOneExactStdoutFrameWhileVersionStaysOnStderr() throws Exception {
        String hash="a".repeat(64);Path helper=root.resolve("native-helper"),log=root.resolve("commands");
        String script="""
                #!/bin/sh
                printf '%s\\n' "$*" >> 'LOG'
                native(){ printf 'nativeJob phase=child-signalled childExit=0 active=0 queries=1\\nnativeJob phase=drain-empty childExit=0 active=0 queries=1\\n' >&2; }
                case "$*" in
                 *sha256sum*)
                  case "$MCICON_CASE" in
                   before) native; printf 'HASH  /opt/java/java21/bin/java\\n';;
                   stderr-only) printf 'HASH  /opt/java/java21/bin/java\\n' >&2;;
                   wrong-path) printf 'HASH  /other/java\\n';;
                   extra) printf 'HASH  /opt/java/java21/bin/java\\nextra\\n';;
                   leading-space) printf ' HASH  /opt/java/java21/bin/java\\n';;
                   crlf) printf 'HASH  /opt/java/java21/bin/java\\r\\n';;
                   no-newline) printf 'HASH  /opt/java/java21/bin/java';;
                   wrong-hash) printf 'bad  /opt/java/java21/bin/java\\n';;
                   overflow) printf 'HASH  /opt/java/java21/bin/java\\n'; printf '%065537d' 0 >&2;;
                   *) printf 'HASH  /opt/java/java21/bin/java\\n';;
                  esac
                  native
                  if [ "$MCICON_CASE" = nonzero ]; then exit 7; fi;;
                 *-version*) native; if [ "$MCICON_CASE" = wrong-major ]; then printf 'openjdk version "25.0.1"\\n' >&2; elif [ "$MCICON_CASE" = version-stdout ]; then printf 'openjdk version "21.0.9"\\n'; else printf 'openjdk version "21.0.9"\\n' >&2; fi; native;;
                esac
                """.replace("HASH",hash).replace("LOG",log.toString());
        Files.writeString(helper,script);assertTrue(helper.toFile().setExecutable(true));
        WorkerOptions options=new WorkerOptions(root.resolve("agent.jar"),"unused","sha256:"+hash,Duration.ofSeconds(1),Duration.ofSeconds(1),"/opt/java/java21",3072,2048);
        for(String mode:List.of("tail","before","stderr-only","wrong-path","extra","leading-space","crlf","no-newline","wrong-hash","overflow","nonzero","wrong-major","version-stdout")) {
            WorkerLaunch launch=new WorkerLaunch(List.of(helper.toString(),"docker","--"),Map.of("MCICON_CASE",mode),options.dockerImage(),hash,Map.of(21,hash),Map.of());
            if(Set.of("tail","before").contains(mode))assertEquals(21,Diagnostics.workerJava(options,options.dockerImage(),21,new CancellationToken(),launch).major());
            else assertEquals(mode.equals("version-stdout")?"WORKER_JAVA_UNAVAILABLE":"MANAGED_RUNTIME_INVALID",assertThrows(McIconException.class,()->Diagnostics.workerJava(options,options.dockerImage(),21,new CancellationToken(),launch),mode).code());
        }
        assertTrue(Files.readString(log).contains("rm -f mcicon-doctor-"));
    }
    @Test @EnabledOnOs({OS.MAC,OS.LINUX}) void diagnosticTimeoutAndCancellationStillCleanOnlyTheirNamedContainer() throws Exception {
        Path helper=root.resolve("slow-helper"),log=root.resolve("cleanup");Files.writeString(helper,"#!/bin/sh\ncase \"$*\" in *\" rm -f \"*) printf '%s\\n' \"$*\" >> '"+log+"';; *) /bin/sleep 1;; esac\n");assertTrue(helper.toFile().setExecutable(true));
        WorkerLaunch launch=new WorkerLaunch(List.of(helper.toString(),"docker","--"),Map.of(),"image","a".repeat(64),Map.of(),Map.of());
        var method=Diagnostics.class.getDeclaredMethod("command",String.class,WorkerLaunch.class,List.class,int.class,CancellationToken.class);method.setAccessible(true);
        Object result=method.invoke(null,"fixture",launch,List.of("run","image"),0,new CancellationToken());var check=result.getClass().getDeclaredMethod("check");check.setAccessible(true);DiagnosticReport.Check observed=(DiagnosticReport.Check)check.invoke(result);assertFalse(observed.passed());assertTrue(observed.detail().startsWith("Diagnostic timed out"));
        CancellationToken token=new CancellationToken();token.cancel();WorkerOptions options=new WorkerOptions(root.resolve("agent.jar"),"unused","image",Duration.ofSeconds(1),Duration.ofSeconds(1),"/opt/java/java21",3072,2048);
        assertEquals("CANCELLED",assertThrows(McIconException.class,()->Diagnostics.workerJava(options,"image",21,token,launch)).code());
        assertEquals(2,Files.readAllLines(log).size());assertTrue(Files.readAllLines(log).stream().allMatch(line->line.startsWith("docker -- rm -f mcicon-doctor-")));
    }
    @Test void workerJavaFailurePreservesExitTimeoutStartAndOutputLimitDetails() throws Exception {
        WorkerOptions options=new WorkerOptions(root.resolve("agent.jar"),"unused","image",Duration.ofSeconds(1),Duration.ofSeconds(1),"/opt/java/java21",3072,2048);
        for(String mode:List.of("exit","overflow")) {
            WorkerLaunch launch=fixture(mode);
            var failure=assertThrows(McIconException.class,()->Diagnostics.workerJava(options,"image",21,new CancellationToken(),launch),mode);
            assertEquals("WORKER_JAVA_UNAVAILABLE",failure.code());
            assertTrue(failure.getMessage().contains("stdoutBytes="));assertTrue(failure.getMessage().contains("stderrBytes="));
            assertFalse(failure.getMessage().contains("out detail"));assertFalse(failure.getMessage().contains("err detail"));
            if(mode.equals("exit")){assertTrue(failure.getMessage().contains("exited with code 7"),failure.getMessage());int lineBytes=System.lineSeparator().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;assertTrue(failure.getMessage().contains("stdoutBytes="+(10+lineBytes)));assertTrue(failure.getMessage().contains("stderrBytes="+(10+lineBytes)));}
            if(mode.equals("overflow")){assertTrue(failure.getMessage().contains("response limit exceeded"),failure.getMessage());assertFalse(failure.getMessage().contains("supersecret"));assertFalse(failure.getMessage().contains("environment-secret"));assertFalse(failure.getMessage().contains("alice"));assertFalse(failure.getMessage().contains("XXXXX"));assertTrue(failure.getMessage().length()<1400);}
        }
        WorkerLaunch slow=fixture("timeout");
        var command=Diagnostics.class.getDeclaredMethod("command",String.class,WorkerLaunch.class,List.class,int.class,CancellationToken.class);command.setAccessible(true);
        Object timed=command.invoke(null,"worker_java",slow,List.of("probe"),0,new CancellationToken());var check=timed.getClass().getDeclaredMethod("check");check.setAccessible(true);
        String timeout=((DiagnosticReport.Check)check.invoke(timed)).detail();assertTrue(timeout.contains("timed out"));assertTrue(timeout.contains("stdoutBytes="));assertFalse(timeout.contains("before timeout"));
        WorkerLaunch missing=new WorkerLaunch(List.of(root.resolve("missing-helper").toString(),"docker","--"),Map.of(),"image","a".repeat(64),Map.of(),Map.of());
        var failure=assertThrows(McIconException.class,()->Diagnostics.workerJava(options,"image",21,new CancellationToken(),missing));
        assertTrue(failure.getMessage().contains("Process start or diagnostic failed: IOException"));assertInstanceOf(IOException.class,failure.getCause());assertTrue(failure.getMessage().contains("stdoutBytes=0; stderrBytes=0"));
        var failed=Diagnostics.class.getDeclaredMethod("failed",String.class,String.class,Path.class,Path.class,Throwable.class);failed.setAccessible(true);
        Object before=failed.invoke(null,"fixture","Process start or diagnostic failed: IOException",null,null,new IOException());var getCheck=before.getClass().getDeclaredMethod("check");getCheck.setAccessible(true);
        DiagnosticReport.Check beforeCheck=(DiagnosticReport.Check)getCheck.invoke(before);assertFalse(beforeCheck.passed());assertTrue(beforeCheck.detail().contains("stdoutBytes=unknown; stderrBytes=unknown"));
        Path first=root.resolve("first-diagnostic.log");Files.writeString(first,"fixture");
        Object after=failed.invoke(null,"fixture","Process start or diagnostic failed: IOException",first,null,new IOException());DiagnosticReport.Check afterCheck=(DiagnosticReport.Check)getCheck.invoke(after);
        assertTrue(afterCheck.detail().contains("stdoutBytes=7; stderrBytes=unknown"));
        Object successful=command.invoke(null,"fixture",fixture("success"),List.of("probe"),45,new CancellationToken());DiagnosticReport.Check successCheck=(DiagnosticReport.Check)getCheck.invoke(successful);
        assertTrue(successCheck.passed());assertEquals("Authorization: Bearer secret-value"+System.lineSeparator()+"{\"credential\":\"secret-value\"}",successCheck.detail());
    }
    private static WorkerLaunch fixture(String mode) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Map<String,String> environment=new HashMap<>(System.getenv());environment.put("MCICON_TEST_MODE",mode);
        String classes=Path.of(DiagnosticProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new WorkerLaunch(List.of(executable,"-cp",classes,DiagnosticProcess.class.getName()),environment,"image","a".repeat(64),Map.of(),Map.of());
    }
    public static final class DiagnosticProcess {
        public static void main(String[] args) throws Exception {
            if(args.length>0&&args[0].equals("rm"))return;
            switch(System.getenv("MCICON_TEST_MODE")) {
                case "success" -> {System.out.println("Authorization: Bearer secret-value");System.err.println("{\"credential\":\"secret-value\"}");}
                case "exit" -> {System.out.println("out detail");System.err.println("err detail");System.exit(7);}
                case "overflow" -> System.out.print("token=supersecret PRIVATE_ENV=environment-secret C:\\Users\\alice\\private "+"X".repeat(70000));
                case "timeout" -> Thread.sleep(2000);
            }
        }
    }
}
