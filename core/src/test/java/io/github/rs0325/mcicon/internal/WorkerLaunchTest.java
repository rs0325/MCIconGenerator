package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
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
        Object result=method.invoke(null,"fixture",launch,List.of("run","image"),0,new CancellationToken());var check=result.getClass().getDeclaredMethod("check");check.setAccessible(true);DiagnosticReport.Check observed=(DiagnosticReport.Check)check.invoke(result);assertFalse(observed.passed());assertEquals("Diagnostic timed out",observed.detail());
        CancellationToken token=new CancellationToken();token.cancel();WorkerOptions options=new WorkerOptions(root.resolve("agent.jar"),"unused","image",Duration.ofSeconds(1),Duration.ofSeconds(1),"/opt/java/java21",3072,2048);
        assertEquals("CANCELLED",assertThrows(McIconException.class,()->Diagnostics.workerJava(options,"image",21,token,launch)).code());
        assertEquals(2,Files.readAllLines(log).size());assertTrue(Files.readAllLines(log).stream().allMatch(line->line.startsWith("docker -- rm -f mcicon-doctor-")));
    }
}
