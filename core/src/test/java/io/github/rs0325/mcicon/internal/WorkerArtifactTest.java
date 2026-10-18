package io.github.rs0325.mcicon.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.github.rs0325.mcicon.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerArtifactTest {
    @TempDir Path directory;
    private static final String PREFIX="META-INF/mcicon/worker/";

    @Test void resolvesTransitiveResourceAndRejectsCorruptedCache() throws Exception {
        var resolved=WorkerArtifact.resolve(directory,new CancellationToken());
        assertTrue(resolved.agentJar().isAbsolute());
        assertEquals(WorkerArtifactInfo.SHA256,FilesSupport.hash(resolved.agentJar(),"SHA-256"));
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(PREFIX+"agent.jar")) {
            assertArrayEquals(input.readAllBytes(),Files.readAllBytes(resolved.agentJar()));
        }
        assertEquals(resolved,WorkerArtifact.resolve(directory,new CancellationToken()));
        Files.write(resolved.agentJar(),new byte[]{1,2,3});
        assertCode("WORKER_INVALID",()->WorkerArtifact.resolve(directory,new CancellationToken()));
    }
    @Test void resolvesResourcesAfterFlatteningWithoutOriginalWorkerJar() throws Exception {
        Path combined=directory.resolve("combined.jar");
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(combined))) {
            for(String name:List.of("version.properties","agent.jar")) {
                jar.putNextEntry(new JarEntry(PREFIX+name));
                try(InputStream input=getClass().getClassLoader().getResourceAsStream(PREFIX+name)) {input.transferTo(jar);}
                jar.closeEntry();
            }
        }
        try(URLClassLoader loader=new URLClassLoader(new URL[]{combined.toUri().toURL()},null)) {
            var resolved=WorkerArtifact.resolve(directory.resolve("cache"),new CancellationToken(),loader);
            assertEquals(WorkerArtifactInfo.SHA256,FilesSupport.hash(resolved.agentJar(),"SHA-256"));
        }
    }
    @Test void rejectsMissingAndDuplicatedResources() throws Exception {
        assertCode("WORKER_NOT_FOUND",()->WorkerArtifact.resolve(directory,new CancellationToken(),new ClassLoader(null){}));
        for(String duplicate:List.of("version.properties","agent.jar")) {
            ClassLoader original=getClass().getClassLoader();
            ClassLoader loader=new ClassLoader(null) {
                @Override public Enumeration<URL> getResources(String name) throws IOException {
                    URL resource=original.getResource(name);
                    return Collections.enumeration(name.equals(PREFIX+duplicate)?List.of(resource,resource):List.of(resource));
                }
            };
            assertCode("WORKER_INVALID",()->WorkerArtifact.resolve(directory,new CancellationToken(),loader));
        }
    }
    @Test void rejectsGenerationMismatchAndNoncanonicalMetadata() throws Exception {
        String expected="toolVersion="+ToolVersion.VERSION+"\nagentSha256="+WorkerArtifactInfo.SHA256+"\n";
        for(String metadata:List.of(expected.replace(ToolVersion.VERSION,"9.9.9"),expected.replace(WorkerArtifactInfo.SHA256,"0".repeat(64)),
                expected+"extra=value\n",expected+"toolVersion="+ToolVersion.VERSION+"\n",expected.replace("\n","\r\n"))) {
            assertCode("WORKER_INVALID",()->WorkerArtifact.resolve(directory,new CancellationToken(),resources(metadata,null)));
        }
    }
    @Test void rejectsModifiedAgentAndCancellationBeforeWriting() throws Exception {
        String metadata="toolVersion="+ToolVersion.VERSION+"\nagentSha256="+WorkerArtifactInfo.SHA256+"\n";
        assertCode("WORKER_INVALID",()->WorkerArtifact.resolve(directory,new CancellationToken(),resources(metadata,new byte[]{1,2,3})));
        CancellationToken token=new CancellationToken();token.cancel();
        assertCode("CANCELLED",()->WorkerArtifact.resolve(directory.resolve("cancelled"),token));
        assertFalse(Files.exists(directory.resolve("cancelled")));
    }
    @Test void concurrentCallersUseTheSameVerifiedAgent() throws Exception {
        try(ExecutorService executor=Executors.newFixedThreadPool(4)) {
            List<Future<WorkerArtifact.Resolved>> futures=new ArrayList<>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->WorkerArtifact.resolve(directory,new CancellationToken())));
            var first=futures.getFirst().get(10,TimeUnit.SECONDS);
            for(var future:futures)assertEquals(first,future.get(10,TimeUnit.SECONDS));
        }
        try(var files=Files.walk(directory)) {assertFalse(files.anyMatch(file->file.toString().endsWith(".part")));}
    }
    @Test void cancellationStopsLockWaitAndAllowsNextResolver() throws Exception {
        Path lock=directory.resolve("workers/"+WorkerArtifactInfo.SHA256+"/agent.lock");
        Files.createDirectories(lock.getParent());
        try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var held=channel.lock();ExecutorService executor=Executors.newSingleThreadExecutor()) {
            CancellationToken token=new CancellationToken();
            var pending=executor.submit(()->WorkerArtifact.resolve(directory,token));
            assertThrows(TimeoutException.class,()->pending.get(100,TimeUnit.MILLISECONDS));
            token.cancel();
            var failure=assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
            assertEquals("CANCELLED",assertInstanceOf(McIconException.class,failure.getCause()).code());
        }
        assertEquals(WorkerArtifactInfo.SHA256,WorkerArtifact.resolve(directory,new CancellationToken()).sha256());
    }
    private ClassLoader resources(String metadata,byte[] agent) throws IOException {
        Path root=Files.createTempDirectory(directory,"resources-");
        Files.writeString(root.resolve("version.properties"),metadata,StandardCharsets.UTF_8);
        if(agent!=null)Files.write(root.resolve("agent.jar"),agent);
        ClassLoader original=getClass().getClassLoader();
        return new ClassLoader(null) {
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                String file=name.substring(PREFIX.length());
                URL resource=Files.exists(root.resolve(file))?root.resolve(file).toUri().toURL():original.getResource(name);
                return Collections.enumeration(List.of(resource));
            }
        };
    }
    private static void assertCode(String code,org.junit.jupiter.api.function.Executable action) {
        assertEquals(code,assertThrows(McIconException.class,action).code());
    }
}
