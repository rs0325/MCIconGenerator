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
        Files.writeString(helper,"#!/bin/sh\nprintf '%s\\n' \"$*\" >> '"+log+"'\ncase \"$*\" in *sha256sum*) printf '%s  /opt/java/java21/bin/java\\n' '"+hash+"';; *-version*) printf 'openjdk version \\\"21.0.9\\\"\\n';; esac\n");assertTrue(helper.toFile().setExecutable(true));
        WorkerOptions options=new WorkerOptions(root.resolve("agent.jar"),"unused","sha256:"+hash,Duration.ofSeconds(1),Duration.ofSeconds(1),"/opt/java/java21",3072,2048);
        WorkerLaunch launch=new WorkerLaunch(List.of(helper.toString(),"--lease","fixture-lease","docker","--"),Map.of(),options.dockerImage(),hash,Map.of(21,hash),Map.of());
        assertEquals(21,Diagnostics.workerJava(options,options.dockerImage(),21,new CancellationToken(),launch).major());
        String commands=Files.readString(log);assertTrue(commands.contains("--lease fixture-lease docker -- run"));assertTrue(commands.contains("--lease fixture-lease docker -- rm -f mcicon-doctor-"));assertTrue(commands.contains("sha256sum"));
        WorkerLaunch wrong=new WorkerLaunch(launch.prefix(),Map.of(),options.dockerImage(),hash,Map.of(21,"b".repeat(64)),Map.of());
        assertEquals("MANAGED_RUNTIME_INVALID",assertThrows(McIconException.class,()->Diagnostics.workerJava(options,options.dockerImage(),21,new CancellationToken(),wrong)).code());
    }
}
