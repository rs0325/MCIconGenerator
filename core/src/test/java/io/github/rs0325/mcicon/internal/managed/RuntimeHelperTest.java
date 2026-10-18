package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.CancellationToken;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeHelperTest {
    @TempDir Path root;
    @Test void commonControlExamplesMatchCoreConsumer() throws Exception {
        var examples=RuntimeDescriptor.read(Files.readAllBytes(Path.of("..","schemas","managed-helper-v1.examples.json")));
        for(var item:examples.get("control")) {
            var response=item.get("response");var result=RuntimeHelper.parse(RuntimeDescriptor.JSON.writeValueAsBytes(response),item.path("exitCode").intValue());
            assertEquals(response.path("status").asText().equals("ok"),result.ok());
        }
    }
    @Test void controlProtocolRejectsStatusExitAndUnknownFields() throws Exception {
        byte[] setup="{\"protocolVersion\":1,\"status\":\"setup-required\",\"diagnostics\":[{\"code\":\"MISSING\",\"detail\":\"前提が不足しています\",\"requiresPrivilege\":true,\"requiresRestart\":false}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(RuntimeHelper.parse(setup,2).ok());RuntimeDescriptorTest.assertInvalid(()->RuntimeHelper.parse(setup,0));
        RuntimeDescriptorTest.assertInvalid(()->RuntimeHelper.parse(new String(setup,java.nio.charset.StandardCharsets.UTF_8).replace("\"protocolVersion\":1","\"protocolVersion\":2").getBytes(java.nio.charset.StandardCharsets.UTF_8),2));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void controlExecutesFixtureAndReleaseMarksEvidence() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        Path executable=root.resolve("helper");
        Files.writeString(executable,"#!/bin/sh\nprintf '%s' '{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}'\n");
        assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());
            assertTrue(helper.control("preflight",Duration.ofSeconds(1),new CancellationToken()).ok());
            assertTrue(helper.control("release",Duration.ofSeconds(1),new CancellationToken()).ok());
            assertEquals("released",RuntimeDescriptor.JSON.readTree(Files.readAllBytes(lease.file())).path("state").asText());
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void timedOutHelperKeepsUncertainLease() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        var descriptor=new ManagedFixture().descriptor();Path executable=root.resolve("helper");
        Files.writeString(executable,"#!/bin/sh\nexec /bin/sleep 5\n");assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,descriptor)) {
            RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());
            assertThrows(java.io.IOException.class,()->helper.control("release",Duration.ofMillis(50),new CancellationToken()));
            assertEquals("active",lease.read().path("state").asText());
            var evidence=evidence(lease);assertTrue(evidence.path("directProcessTerminated").asBoolean());assertTrue(evidence.path("exitCode").isInt());
            assertFalse(ProcessHandle.of(evidence.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));
        }
        assertFalse(RuntimeLease.idle(store,descriptor));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void runningHelperCancellationWaitsAndKeepsRawEvidence() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        Path executable=root.resolve("helper");Files.writeString(executable,"#!/bin/sh\nprintf started > \"$6.started\"\nprintf partial\nexec /bin/sleep 5\n");assertTrue(executable.toFile().setExecutable(true));
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor());var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            CancellationToken token=new CancellationToken();RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());
            var future=executor.submit(()->helper.control("prepare",Duration.ofSeconds(4),token));
            Path marker=Path.of(lease.file()+".started");long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(5);assertTrue(Files.exists(marker));token.cancel();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED",((io.github.rs0325.mcicon.McIconException)failure.getCause()).code());
            var evidence=evidence(lease);assertTrue(evidence.path("directProcessTerminated").asBoolean());
            assertEquals("partial",Files.readString(lease.file().getParent().resolve(evidence.path("stdout").asText())));
            assertFalse(ProcessHandle.of(evidence.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));assertEquals("active",lease.read().path("state").asText());
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,org.junit.jupiter.api.condition.OS.LINUX})
    void malformedAndInconsistentRepliesKeepStdoutAndExitCode() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        for(String reply:List.of("invalid-json","{\"protocolVersion\":1,\"status\":\"ok\",\"diagnostics\":[]}")) {
            Path executable=root.resolve("helper");Files.writeString(executable,"#!/bin/sh\nprintf '%s' '"+reply+"'\nexit 2\n");assertTrue(executable.toFile().setExecutable(true));
            try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
                RuntimeHelper helper=new RuntimeHelper(executable,store.root(),lease,Map.of());assertThrows(Exception.class,()->helper.control("prepare",Duration.ofSeconds(1),new CancellationToken()));
                var evidence=evidence(lease);assertEquals(2,evidence.path("exitCode").intValue());assertTrue(evidence.path("directProcessTerminated").asBoolean());
                assertEquals(reply,Files.readString(lease.file().getParent().resolve(evidence.path("stdout").asText())));assertEquals("active",lease.read().path("state").asText());
            }
        }
    }
    @Test void nonTerminatingDirectProcessIsRecordedAsFailure() throws Exception {
        Process process=new Process() {
            public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
            public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
            public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit) throws InterruptedException {Thread.sleep(unit.toMillis(timeout));return false;}
            public int exitValue(){throw new IllegalThreadStateException();}
            public boolean isAlive(){return true;}
            public void destroy(){}
            public Process destroyForcibly(){return this;}
            public long pid(){return ProcessHandle.current().pid();}
            public ProcessHandle.Info info(){return ProcessHandle.current().info();}
        };
        Path evidence=root.resolve("cleanup.json");assertThrows(java.io.IOException.class,()->RuntimeHelper.terminate(process,evidence));assertFalse(RuntimeDescriptor.read(Files.readAllBytes(evidence)).path("terminated").asBoolean());
    }
    private com.fasterxml.jackson.databind.JsonNode evidence(RuntimeLease lease) throws Exception {
        try(var files=Files.list(lease.file().getParent())) {return RuntimeDescriptor.read(Files.readAllBytes(files.filter(p->p.getFileName().toString().endsWith(".result.json")).findFirst().orElseThrow()));}
    }
    @Test void alreadyCancelledJobDoesNotStartHelper() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(root.resolve("missing-helper"),store.root(),lease,Map.of());
            CancellationToken token=new CancellationToken();token.cancel();
            assertEquals("CANCELLED",assertThrows(io.github.rs0325.mcicon.McIconException.class,()->helper.control("prepare",Duration.ofSeconds(1),token)).code());
            assertEquals("active",lease.read().path("state").asText());
        }
    }
    @Test void dockerKeepsAbsolutePrefixAndArgumentsSeparate() throws Exception {
        var store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(5),(u,o,t)->{throw new AssertionError();});
        try(var lease=ManagedFixture.lease(store,new ManagedFixture().descriptor())) {
            RuntimeHelper helper=new RuntimeHelper(root.resolve("helper"),root.toRealPath(),lease,Map.of("MCICON_JOB","fixture"));
            ProcessBuilder builder=helper.docker(List.of("run","--name","contains spaces; $(literal)"));
            assertEquals(List.of(root.resolve("helper").toString(),"--protocol","1","--root",root.toRealPath().toString(),"--lease",lease.file().toString(),"docker","--","run","--name","contains spaces; $(literal)"),builder.command());
            assertEquals("fixture",builder.environment().get("MCICON_JOB"));
        }
    }
}
