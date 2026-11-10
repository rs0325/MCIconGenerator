package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkerClientImageDiagnosticsTest {
    private static final String IMAGE="sha256:"+"a".repeat(64);
    @TempDir Path root;

    @Test void imageInspectionFailuresKeepSafeClassificationAndExitCode() throws Exception {
        WorkerOptions options=options();

        McIconException nonzero=inspect(options,launch("nonzero"));
        assertEquals("DOCKER_IMAGE_UNAVAILABLE",nonzero.code());
        assertTrue(nonzero.getMessage().startsWith("Build the rendering image in the selected Docker context first ("));
        assertTrue(nonzero.getMessage().contains("exited unsuccessfully"));
        assertTrue(nonzero.getMessage().contains("exitCode=17"));
        assertTrue(nonzero.getMessage().contains("stdoutBytes="+lineBytes("private-stdout")));
        assertNoOutput(nonzero,"private-stdout","private-stderr","stderrBytes");

        McIconException malformed=inspect(options,launch("malformed"));
        assertEquals("DOCKER_IMAGE_UNAVAILABLE",malformed.code());
        assertTrue(malformed.getMessage().startsWith("Build the rendering image in the selected Docker context first ("));
        assertTrue(malformed.getMessage().contains("image ID format did not match"));
        assertTrue(malformed.getMessage().contains("exitCode=0"));
        assertTrue(malformed.getMessage().contains("stdoutBytes="+lineBytes("not-an-image-id")));
        assertNoOutput(malformed,"not-an-image-id","private-stderr","stderrBytes");

        McIconException capped=inspect(options,launch("oversized"));
        assertEquals("DOCKER_IMAGE_UNAVAILABLE",capped.code());
        assertTrue(capped.getMessage().startsWith("Build the rendering image in the selected Docker context first ("));
        assertTrue(capped.getMessage().contains("response limit reached"));
        assertTrue(capped.getMessage().contains("exitCode=0"));
        assertTrue(capped.getMessage().contains("stdoutBytes=1024"));
        assertNoOutput(capped,"XXXXX","stderrBytes");

        McIconException timeout=assertThrows(McIconException.class,()->WorkerClient.resolveImage(options,new CancellationToken(),launch("slow"),50,TimeUnit.MILLISECONDS));
        assertEquals("DOCKER_UNAVAILABLE",timeout.code());
        assertTrue(timeout.getMessage().startsWith("Docker image inspection timed out ("));
        assertTrue(timeout.getCause().getMessage().contains("exitCode=unknown; stdoutBytes=unknown; exceptionClass=TimeoutException"));
        assertNoOutput(timeout,"stderrBytes");
    }

    @Test void startupAndWaitExceptionsKeepIoFailedPublicCode() throws Exception {
        WorkerOptions options=options();
        WorkerLaunch missing=new WorkerLaunch(List.of(root.resolve("missing-docker").toString()),Map.of(),IMAGE,"unused",Map.of(),Map.of());
        IOException start=assertThrows(IOException.class,()->resolve(options,missing));
        assertTrue(start.getMessage().contains("failed to start"));
        assertTrue(start.getMessage().contains("exitCode=unknown; stdoutBytes=0; exceptionClass=IOException"));
        assertFalse(start.getMessage().contains(root.toString()));

        McIconException publicFailure=throughSubmit(options,missing);
        assertEquals("IO_FAILED",publicFailure.code());
        assertTrue(publicFailure.getCause() instanceof IOException);
        assertTrue(publicFailure.getCause().getMessage().contains("failed to start"));
        assertNull(publicFailure.getCause().getCause());

        McIconException interrupted=throughSubmitInterrupted(options,launch("slow"));
        assertEquals("IO_FAILED",interrupted.code());
        assertInstanceOf(InterruptedException.class,interrupted.getCause());
    }

    @Test void successfulImageInspectionAndManagedImagePinRemainUnchanged() throws Exception {
        WorkerOptions options=options();
        assertEquals(IMAGE,resolve(options,launch("success")));
        WorkerLaunch success=launch("success");
        WorkerLaunch wrongPin=new WorkerLaunch(success.prefix(),success.environment(),"sha256:"+"b".repeat(64),"unused",Map.of(),Map.of());
        McIconException invalid=assertThrows(McIconException.class,()->resolve(options,wrongPin));
        assertEquals("MANAGED_RUNTIME_INVALID",invalid.code());
    }

    private WorkerOptions options() {
        return new WorkerOptions(root.resolve("agent.jar"),"fixture","fixture",Duration.ofSeconds(1),Duration.ofSeconds(1));
    }
    private McIconException inspect(WorkerOptions options,WorkerLaunch launch) {
        return assertThrows(McIconException.class,()->resolve(options,launch));
    }
    private String resolve(WorkerOptions options,WorkerLaunch launch) throws Exception {
        return WorkerClient.resolveImage(options,new CancellationToken(),launch);
    }
    private McIconException throughSubmit(WorkerOptions options,WorkerLaunch launch) throws Exception {
        var submit=MCIconGenerator.class.getDeclaredMethod("submit",Callable.class);submit.setAccessible(true);
        try(MCIconGenerator generator=new MCIconGenerator(root,true,ProgressListener.NONE,new CancellationToken())) {
            Callable<String> task=()->WorkerClient.resolveImage(options,new CancellationToken(),launch);
            CompletableFuture<?> future=(CompletableFuture<?>)submit.invoke(generator,task);
            CompletionException failure=assertThrows(CompletionException.class,future::join);
            return assertInstanceOf(McIconException.class,failure.getCause());
        }catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    private McIconException throughSubmitInterrupted(WorkerOptions options,WorkerLaunch launch) throws Exception {
        var submit=MCIconGenerator.class.getDeclaredMethod("submit",Callable.class);submit.setAccessible(true);
        try(MCIconGenerator generator=new MCIconGenerator(root,true,ProgressListener.NONE,new CancellationToken())) {
            Callable<String> task=()->{
                Thread waiting=Thread.currentThread();
                new Thread(()->{try{Thread.sleep(500);}catch(InterruptedException ignored){}waiting.interrupt();}).start();
                return WorkerClient.resolveImage(options,new CancellationToken(),launch);
            };
            CompletableFuture<?> future=(CompletableFuture<?>)submit.invoke(generator,task);
            CompletionException failure=assertThrows(CompletionException.class,future::join);
            return assertInstanceOf(McIconException.class,failure.getCause());
        }catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    private WorkerLaunch launch(String mode) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Path.of(ImageProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Map<String,String> environment=new HashMap<>(System.getenv());environment.put("MCICON_IMAGE_TEST_MODE",mode);
        return new WorkerLaunch(List.of(java,"-cp",classes,ImageProcess.class.getName()),environment,IMAGE,"unused",Map.of(),Map.of());
    }
    private static int lineBytes(String value) {return (value+System.lineSeparator()).getBytes(StandardCharsets.UTF_8).length;}
    private static void assertNoOutput(McIconException failure,String... output) {
        assertNotNull(failure.getCause());
        for(String value:output){assertFalse(failure.getMessage().contains(value),value);assertFalse(failure.getCause().getMessage().contains(value),value);}
    }
    public static final class ImageProcess {
        public static void main(String[] args) throws Exception {
            switch(System.getenv("MCICON_IMAGE_TEST_MODE")) {
                case "nonzero" -> {System.out.println("private-stdout");System.err.println("private-stderr");System.exit(17);}
                case "malformed" -> {System.out.println("not-an-image-id");System.err.println("private-stderr");}
                case "oversized" -> {System.out.print("X".repeat(1025));System.err.println("private-stderr");}
                case "success" -> System.out.println(IMAGE);
                case "slow" -> {Thread.sleep(3000);System.out.println(IMAGE);}
            }
        }
    }
}
